package io.mindspice.magenta2.ai.chat.system;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persists non-executing lifecycle proposals and confirms them through AssignmentService. */
@Component
public class SystemChatActionProposalService {
    static final Set<String> VALID_ACTIONS = Set.of("PAUSE", "RESUME", "RETRY", "REPLAY", "CANCEL");

    private final JdbcTemplate jdbc;
    private final AssignmentService assignmentService;
    private final WorkspaceDirectoryService directoryService;
    private final AgentContextService contextService;
    private final ObjectMapper objectMapper;

    public SystemChatActionProposalService(JdbcTemplate jdbc, AssignmentService assignmentService,
                                           WorkspaceDirectoryService directoryService,
                                           AgentContextService contextService, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.assignmentService = Objects.requireNonNull(assignmentService);
        this.directoryService = Objects.requireNonNull(directoryService);
        this.contextService = Objects.requireNonNull(contextService);
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    ToolCallback proposalCreateCallback() {
        return new ToolCallback() {
            @Override public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("system_proposal_create")
                        .description("Create, but never execute, one lifecycle proposal for the bound agent.")
                        .inputSchema("""
                                {"type":"object","properties":{"action":{"enum":["PAUSE","RESUME","RETRY","REPLAY","CANCEL"]},
                                "target_id":{"type":"string"},"operation_key":{"type":"string"}},
                                "required":["action","target_id"]}
                                """).build();
            }
            @Override public String call(String input) {
                ChatInvocationProfile.SystemChatProfile profile = validatedCurrentProfile();
                if (profile == null) return unavailable();
                Map<String, Object> values = parse(input);
                try {
                    CreateResult created = createProposal(profile, text(values, "action"), text(values, "target_id"),
                            text(values, "operation_key"));
                    return json(Map.of("proposal_id", created.proposalId(), "action", created.action(),
                            "target_id", created.targetId(), "state", created.state()));
                } catch (ProposalConflictException exception) {
                    return json(Map.of("conflict", true));
                } catch (RuntimeException exception) {
                    return unavailable();
                }
            }
        };
    }

    /** Creation only validates and persists; it never invokes AssignmentService mutation methods. */
    @Transactional
    public CreateResult createProposal(ChatInvocationProfile.SystemChatProfile profile, String action,
                                       String targetId, String requestedOperationKey) {
        requireValidatedProfile(profile);
        String normalizedAction = normalizeAction(action);
        WorkAssignment assignment = assignmentService.get(targetId);
        if (!profile.agentId().equals(assignment.agentId()) || !meaningful(normalizedAction, assignment.status())) {
            throw new IllegalArgumentException("Proposal target is unavailable");
        }
        String digest = canonicalPayloadDigest(normalizedAction, targetId, assignment.status().name(), profile.agentId());
        String operationKey = operationKey(requestedOperationKey, digest);
        ProposalRow existingByKey = findByOperationKey(operationKey);
        if (existingByKey != null) {
            if (!digest.equals(existingByKey.digest())) throw new ProposalConflictException();
            return existingByKey.asCreateResult();
        }
        ProposalRow equivalent = findByDigest(normalizedAction, targetId, digest);
        if (equivalent != null) return equivalent.asCreateResult();

        String proposalId = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try {
            jdbc.update("""
                    insert into system_chat_action_proposals(
                        id, conversation_id, agent_id, target_kind, target_id, action, expected_state_or_revision,
                        canonical_payload_digest, operation_key, proposal_state, created_at, updated_at)
                    values (?, ?, ?, 'ASSIGNMENT', ?, ?, ?, ?, ?, 'PENDING', ?, ?)
                    """, proposalId, "system-chat:" + profile.agentId(), profile.agentId(), targetId,
                    normalizedAction, assignment.status().name(), digest, operationKey, now, now);
        } catch (RuntimeException exception) {
            ProposalRow concurrent = findByOperationKey(operationKey);
            if (concurrent != null && digest.equals(concurrent.digest())) return concurrent.asCreateResult();
            ProposalRow sameRequest = findByDigest(normalizedAction, targetId, digest);
            if (sameRequest != null) return sameRequest.asCreateResult();
            throw exception;
        }
        return new CreateResult(proposalId, normalizedAction, targetId, "PENDING");
    }

    /**
     * Confirms one exact, user-visible proposal identity. The client must echo the
     * proposal's canonical digest; the service derives the lifecycle operation key
     * from that immutable identity instead of accepting a caller-generated key.
     */
    @Transactional
    public ConfirmResult confirmProposal(String proposalId, String agentId, String canonicalDigest,
                                         boolean explicitUserConfirmation) {
        if (!explicitUserConfirmation || blank(proposalId) || blank(agentId) || blank(canonicalDigest)) {
            return ConfirmResult.unavailable(proposalId);
        }
        ProposalRow proposal = findById(proposalId);
        if (proposal == null || !agentId.equals(proposal.agentId()) || !canonicalDigest.equals(proposal.digest())) {
            return ConfirmResult.unavailable(proposalId);
        }
        String confirmationOperationKey = confirmationOperationKey(proposal);
        if ("CONFIRMED".equals(proposal.state())) {
            if (!confirmationOperationKey.equals(proposal.confirmationOperationKey())) throw new ProposalConflictException();
            return new ConfirmResult(proposal.id(), "CONFIRMED", proposal.linkedOperationId());
        }
        if (!"PENDING".equals(proposal.state())) return new ConfirmResult(proposal.id(), proposal.state(), null);

        // A CAS claim makes concurrent confirmations fail closed before any lifecycle mutation.
        int claimed = jdbc.update("""
                update system_chat_action_proposals
                set proposal_state = 'CONFIRMING', confirmation_operation_key = ?, updated_at = ?
                where id = ? and canonical_payload_digest = ? and proposal_state = 'PENDING'
                """, confirmationOperationKey, Instant.now().toString(), proposalId, canonicalDigest);
        if (claimed != 1) return ConfirmResult.unavailable(proposalId);

        try {
            WorkAssignment assignment = assignmentService.get(proposal.targetId());
            if (!agentId.equals(assignment.agentId())
                    || !proposal.expectedState().equals(assignment.status().name())
                    || !meaningful(proposal.action(), assignment.status())) {
                mark(proposalId, "STALE", confirmationOperationKey, null, "stale");
                return new ConfirmResult(proposalId, "STALE", null);
            }
            // This is the existing agent-scoped lifecycle control plane and retains its own checks.
            executeLifecycleAction(agentId, proposal.targetId(), proposal.action(), confirmationOperationKey);
            mark(proposalId, "CONFIRMED", confirmationOperationKey, confirmationOperationKey, "confirmed");
            return new ConfirmResult(proposalId, "CONFIRMED", confirmationOperationKey);
        } catch (RuntimeException exception) {
            mark(proposalId, "UNAVAILABLE", confirmationOperationKey, null, "unavailable");
            return new ConfirmResult(proposalId, "UNAVAILABLE", null);
        }
    }

    private ChatInvocationProfile.SystemChatProfile validatedCurrentProfile() {
        ChatInvocationProfile.SystemChatProfile profile = SystemChatProfileHolder.current();
        try {
            requireValidatedProfile(profile);
            return profile;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void requireValidatedProfile(ChatInvocationProfile.SystemChatProfile profile) {
        if (profile == null) throw new IllegalArgumentException("System profile is required");
        WorkspaceDescriptor descriptor = directoryService.resolveForRead(WorkspaceKind.AGENT, profile.agentId());
        if (!descriptor.identity().equals(profile.agentDescriptor().identity())
                || contextService.resolveBinding(descriptor, profile.bindingKey()).outcome() != ContextOutcome.AVAILABLE) {
            throw new IllegalArgumentException("System profile scope is unavailable");
        }
    }

    private void executeLifecycleAction(String agentId, String assignmentId, String action, String operationKey) {
        switch (action) {
            case "PAUSE" -> assignmentService.pause(agentId, assignmentId);
            case "RESUME" -> assignmentService.resume(agentId, assignmentId);
            case "CANCEL" -> assignmentService.cancel(agentId, assignmentId);
            case "RETRY" -> assignmentService.retry(agentId, assignmentId, operationKey);
            case "REPLAY" -> assignmentService.replay(agentId, assignmentId, operationKey);
            default -> throw new IllegalArgumentException("Unsupported proposal action");
        }
    }

    private static boolean meaningful(String action, OrchestrationStatus status) {
        if (status == null) return false;
        return switch (action) {
            case "PAUSE" -> !status.isTerminal() && status != OrchestrationStatus.PAUSED;
            case "RESUME" -> status == OrchestrationStatus.PAUSED || status == OrchestrationStatus.INTERRUPTED
                    || status == OrchestrationStatus.WAITING;
            case "CANCEL" -> !status.isTerminal() && status != OrchestrationStatus.CANCEL_REQUESTED;
            case "RETRY", "REPLAY" -> status.isTerminal() || status == OrchestrationStatus.INTERRUPTED;
            default -> false;
        };
    }

    /** List proposals for an agent's system-chat conversation (read-only). */
    public List<ProposalListRow> listProposals(String agentId, String conversationId) {
        return queryMany("""
                select * from system_chat_action_proposals
                where agent_id = ? and conversation_id = ?
                order by created_at desc
                """, agentId, conversationId).stream()
                .map(row -> new ProposalListRow(row.id(), row.action(), row.targetId(), row.digest(), row.state(),
                        row.createdAt() != null ? Instant.parse(row.createdAt()) : Instant.EPOCH))
                .toList();
    }

    private ProposalRow findByOperationKey(String operationKey) {
        return queryOne("select * from system_chat_action_proposals where operation_key = ?", operationKey);
    }
    private ProposalRow findByDigest(String action, String targetId, String digest) {
        return queryOne("""
                select * from system_chat_action_proposals
                where target_kind = 'ASSIGNMENT' and target_id = ? and action = ? and canonical_payload_digest = ?
                """, 
                targetId, action, digest);
    }
    private ProposalRow findById(String id) {
        return queryOne("select * from system_chat_action_proposals where id = ?", id);
    }
    private ProposalRow queryOne(String sql, Object... args) {
        var rows = queryMany(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private List<ProposalRow> queryMany(String sql, Object... args) {
        return jdbc.query(sql, (rs, ignored) -> new ProposalRow(rs.getString("id"), rs.getString("agent_id"),
                rs.getString("target_id"), rs.getString("action"), rs.getString("expected_state_or_revision"),
                rs.getString("canonical_payload_digest"), rs.getString("proposal_state"),
                rs.getString("confirmation_operation_key"), rs.getString("linked_operation_id"),
                rs.getString("created_at")), args);
    }
    private void mark(String id, String state, String confirmationKey, String linkedOperationId, String outcome) {
        jdbc.update("""
                update system_chat_action_proposals set proposal_state = ?, confirmation_operation_key = ?,
                linked_operation_id = ?, confirmed_at = ?, audit_facts_json = ?, updated_at = ? where id = ?
                """, state, confirmationKey, linkedOperationId, Instant.now().toString(),
                json(Map.of("outcome", outcome)), Instant.now().toString(), id);
    }

    static String canonicalPayloadDigest(String action, String targetId, String expectedState, String agentId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : new String[]{action, targetId, expectedState, agentId}) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0xffff) throw new IllegalArgumentException("Proposal field is too large");
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static String confirmationOperationKey(ProposalRow proposal) {
        return "system-proposal-confirm:" + proposal.id() + ":" + proposal.digest();
    }

    private static String normalizeAction(String action) {
        if (action == null || !VALID_ACTIONS.contains(action.toUpperCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("Unsupported proposal action");
        }
        return action.toUpperCase(java.util.Locale.ROOT);
    }
    private static String operationKey(String requested, String digest) {
        if (requested == null || requested.isBlank()) return "system-proposal:" + digest;
        if (requested.length() > 255) throw new IllegalArgumentException("Operation key is invalid");
        return requested;
    }
    private Map<String, Object> parse(String input) {
        try { return objectMapper.readValue(input, Map.class); } catch (Exception exception) { return Map.of(); }
    }
    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); } catch (Exception exception) { return "{}"; }
    }
    private String unavailable() { return "{\"unavailable\":true}"; }
    private static String text(Map<String, Object> input, String key) { return input.get(key) instanceof String value ? value : null; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record CreateResult(String proposalId, String action, String targetId, String state) { }
    public record ConfirmResult(String proposalId, String state, String linkedOperationId) {
        static ConfirmResult unavailable(String proposalId) { return new ConfirmResult(proposalId, "UNAVAILABLE", null); }
    }
    private record ProposalRow(String id, String agentId, String targetId, String action, String expectedState,
                               String digest, String state, String confirmationOperationKey, String linkedOperationId,
                               String createdAt) {
        CreateResult asCreateResult() { return new CreateResult(id, action, targetId, state); }
    }
    public record ProposalListRow(String proposalId, String action, String targetId, String canonicalDigest,
                                  String state, java.time.Instant createdAt) {}
    private static final class ProposalConflictException extends IllegalStateException { }
}
