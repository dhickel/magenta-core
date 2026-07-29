package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Structural query contract for assignment views.
 * <p>
 * Agent and project queues are filtered views of the same canonical assignment
 * rows. Scoped queries use server-created sealed {@link AgentScope} /
 * {@link ProjectScope} types. Cursors are signed/authenticated snapshot
 * references (not encoded SQL). DTOs exclude raw input/output/evidence,
 * internal paths, lease owners/fences/checkpoints, and secrets.
 */
@Service
public class AssignmentQueryService {
    private static final Logger log = LoggerFactory.getLogger(AssignmentQueryService.class);

    /** Maximum page size enforced regardless of caller input. */
    static final int MAX_PAGE_SIZE = 100;
    /** Default page size. */
    static final int DEFAULT_PAGE_SIZE = 25;
    /** Cursor lifetime. */
    static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    private final OrchestrationRuntimeRepository repository;
    private final ProjectRepository projectRepository;
    private final byte[] hmacKey;

    public AssignmentQueryService(OrchestrationRuntimeRepository repository,
                                  ProjectRepository projectRepository) {
        this.repository = repository;
        this.projectRepository = projectRepository;
        // Stable server-local signing key rotated on restart only.
        this.hmacKey = ("aqs-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════
    //  Sealed scope types
    // ════════════════════════════════════════════════════════════════

    public sealed interface AssignmentScope permits AgentScope, ProjectScope {
        /** A stable, human-readable label for audit/logging. */
        String label();
    }

    /**
     * An agent-scoped view of assignments. All queries filter by agent identity
     * and the scope cannot be widened post-construction.
     */
    public record AgentScope(String agentId) implements AssignmentScope {
        public AgentScope {
            if (!StringUtils.hasText(agentId) || agentId.contains("/") || agentId.contains("\\")) {
                throw new IllegalArgumentException("Invalid agent scope identity");
            }
        }

        @Override
        public String label() {
            return "agent:" + agentId;
        }
    }

    /**
     * A project-scoped view of assignments. All queries filter by project
     * identity and the scope cannot be widened post-construction.
     */
    public record ProjectScope(String projectId) implements AssignmentScope {
        public ProjectScope {
            if (!StringUtils.hasText(projectId)) {
                throw new IllegalArgumentException("Invalid project scope identity");
            }
        }

        @Override
        public String label() {
            return "project:" + projectId;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Active / History queries
    // ════════════════════════════════════════════════════════════════

    /**
     * List active (non-terminal) assignments for a scope with deterministic
     * ordering (priority desc, then created_at asc).
     */
    public List<WorkAssignment> listActive(AssignmentScope scope, int limit) {
        int clamped = clampLimit(limit);
        return switch (scope) {
            case AgentScope s -> repository.findActiveAssignmentsForAgent(s.agentId(), clamped);
            case ProjectScope s -> {
                requireProject(s.projectId(), false);
                yield repository.findActiveAssignmentsForProject(s.projectId(), clamped);
            }
        };
    }

    /**
     * List terminal (history) assignments for a scope with deterministic
     * ordering (completed_at desc).
     */
    public List<WorkAssignment> listHistory(AssignmentScope scope, int limit) {
        int clamped = clampLimit(limit);
        return switch (scope) {
            case AgentScope s -> repository.findTerminalAssignmentsForAgent(s.agentId(), clamped);
            case ProjectScope s -> {
                requireProject(s.projectId(), true);
                yield repository.findTerminalAssignmentsForProject(s.projectId(), clamped);
            }
        };
    }

    /** Re-resolve one assignment under the supplied sealed scope. */
    public AssignmentListEntry resolveEntry(AssignmentScope scope, String assignmentId) {
        if (!StringUtils.hasText(assignmentId)) {
            throw new IllegalArgumentException("Assignment id is required");
        }
        WorkAssignment assignment = repository.findAssignment(assignmentId)
            .orElseThrow(() -> new IllegalArgumentException("Assignment not found"));
        if (!matchesScope(assignment, scope)) {
            throw new IllegalArgumentException("Assignment not found");
        }
        if (scope instanceof ProjectScope projectScope) {
            boolean terminal = assignment.status() != null && assignment.status().isTerminal();
            requireProject(projectScope.projectId(), terminal);
        }
        return AssignmentListEntry.from(assignment, computeAllowedActions(assignment, scope));
    }

    // ════════════════════════════════════════════════════════════════
    //  Cursor-based pagination
    // ════════════════════════════════════════════════════════════════

    /**
     * Create a bounded snapshot cursor for the initial list request.
     * The cursor encodes scope identity, filter, ordering digest, expiry,
     * and the ordered member identities seen on this page so the server can
     * validate and re-resolve on the next page.
     */
    public CursorSnapshot snapshotCursor(AssignmentScope scope, String filter,
                                          List<WorkAssignment> members) {
        String scopeDigest = sha256(scope.label());
        String requestDigest = sha256("filter=" + (filter != null ? filter : "active")
            + "&scope=" + scope.label());
        Instant expiresAt = Instant.now().plus(CURSOR_TTL);
        List<String> memberIds = members.stream().limit(MAX_PAGE_SIZE).map(WorkAssignment::id).toList();
        String snapshotId = UUID.randomUUID().toString();
        String memberList = String.join("\u001f", memberIds);
        repository.deleteExpiredAssignmentQuerySnapshots(Instant.now());
        repository.insertAssignmentQuerySnapshot(snapshotId, scopeDigest, requestDigest, memberList, expiresAt);

        String payload = String.join("\u001f", snapshotId, scopeDigest, requestDigest, expiresAt.toString(), "0");
        String signature = sign(payload);

        return new CursorSnapshot(snapshotId, scopeDigest, requestDigest, expiresAt, memberIds,
            signature, filter);
    }

    /**
     * Validate and resolve the next page from a cursor. Rejects tampered,
     * expired, cross-scope, or unknown cursors. Re-reads every displayed
     * member canonically under the original scope.
     */
    public CursorPage resolveCursor(AssignmentScope scope, String filter, CursorSnapshot cursor) {
        if (scope instanceof ProjectScope projectScope) {
            requireProject(projectScope.projectId(), "history".equals(filter));
        }
        if (cursor == null) {
            throw new InvalidCursorException("Cursor is required");
        }
        if (cursor.expiresAt().isBefore(Instant.now())) {
            throw new InvalidCursorException("Cursor has expired");
        }
        if (!StringUtils.hasText(cursor.snapshotId())) {
            throw new InvalidCursorException("Cursor snapshot is required");
        }
        String scopeDigest = sha256(scope.label());
        String requestDigest = sha256("filter=" + (filter != null ? filter : "active")
            + "&scope=" + scope.label());
        if (!scopeDigest.equals(cursor.scopeDigest())
                || !requestDigest.equals(cursor.requestDigest())) {
            throw new InvalidCursorException("Cursor scope, request, or expiry is invalid");
        }
        String payload = String.join("\u001f", cursor.snapshotId(), cursor.scopeDigest(),
            cursor.requestDigest(), cursor.expiresAt().toString(), String.valueOf(cursor.offset()));
        String expectedSig = sign(payload);
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8),
                cursor.signature().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidCursorException("Cursor signature validation failed");
        }
        var stored = repository.findAssignmentQuerySnapshot(cursor.snapshotId())
            .orElseThrow(() -> new InvalidCursorException("Cursor snapshot is unavailable"));
        if (stored.expiresAt().isBefore(Instant.now()) || !stored.scopeDigest().equals(scopeDigest)
                || !stored.requestDigest().equals(requestDigest)) {
            throw new InvalidCursorException("Cursor snapshot binding is invalid");
        }
        List<String> memberIds = stored.memberIds().isEmpty() ? List.of()
            : List.of(stored.memberIds().split("\u001f", -1));
        if (memberIds.size() > MAX_PAGE_SIZE || !memberIds.equals(cursor.memberIds())) {
            throw new InvalidCursorException("Cursor membership is invalid");
        }
        boolean active = !"history".equals(filter);
        if (cursor.offset() < 0 || cursor.offset() > memberIds.size()) {
            throw new InvalidCursorException("Cursor position is invalid");
        }
        int end = Math.min(cursor.offset() + DEFAULT_PAGE_SIZE, memberIds.size());
        List<AssignmentListEntry> members = memberIds.subList(cursor.offset(), end).stream()
            .map(id -> repository.findAssignment(id).orElse(null))
            .filter(Objects::nonNull)
            .filter(a -> matchesScope(a, scope))
            .filter(a -> active != a.status().isTerminal())
            .map(a -> AssignmentListEntry.from(a, computeAllowedActions(a, scope)))
            .toList();
        CursorSnapshot next = end < memberIds.size()
            ? cursor.withOffset(end, sign(String.join("\u001f", cursor.snapshotId(), cursor.scopeDigest(),
                cursor.requestDigest(), cursor.expiresAt().toString(), String.valueOf(end))))
            : null;
        return new CursorPage(members, cursor, next);
    }

    // ════════════════════════════════════════════════════════════════
    //  Secret-minimized summary DTO
    // ════════════════════════════════════════════════════════════════

    /**
     * A safe redacted view of an assignment for list displays and API responses.
     * Excludes raw input/output/evidence, internal paths, lease owners/fences,
     * checkpoints, and secrets.
     */
    public record AssignmentListEntry(
        String id,
        String agentId,
        String jobId,
        AssignmentType assignmentType,
        String runDisplayName,
        int priority,
        OrchestrationStatus status,
        String projectId,
        int rowVersion,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        /** Server-computed allowed actions for the caller's scope. */
        List<String> allowedActions
    ) {
        public static AssignmentListEntry from(WorkAssignment a, List<String> allowedActions) {
            return new AssignmentListEntry(
                a.id(), a.agentId(), a.jobId(), a.assignmentType(), a.runDisplayName(),
                a.priority(), a.status(), a.projectId(), a.rowVersion(),
                a.createdAt(), a.updatedAt(), a.startedAt(), a.completedAt(),
                allowedActions);
        }
    }

    /**
     * Compute the allowed actions for this assignment given the caller's scope.
     */
    public List<String> computeAllowedActions(WorkAssignment assignment, AssignmentScope scope) {
        boolean terminal = assignment.status() != null && assignment.status().isTerminal();
        boolean queued = assignment.status() == OrchestrationStatus.QUEUED;
        boolean running = assignment.status() == OrchestrationStatus.RUNNING
            || assignment.status() == OrchestrationStatus.CANCEL_REQUESTED;

        List<String> actions = new java.util.ArrayList<>();
        if (!terminal) {
            if (queued) {
                actions.add("SET_PRIORITY");
                actions.add("CANCEL");
            }
            if (running) {
                actions.add("CANCEL");
            }
            if (assignment.status() == OrchestrationStatus.PAUSED
                    || assignment.status() == OrchestrationStatus.INTERRUPTED
                    || assignment.status() == OrchestrationStatus.WAITING) {
                actions.add("RESUME");
                actions.add("CANCEL");
            }
        }
        return List.copyOf(actions);
    }

    // ════════════════════════════════════════════════════════════════
    //  DTOs
    // ════════════════════════════════════════════════════════════════

    public record CursorSnapshot(
        String snapshotId,
        String scopeDigest,
        String requestDigest,
        Instant expiresAt,
        List<String> memberIds,
        String signature,
        String filter,
        int offset
    ) {
        public CursorSnapshot(String snapshotId, String scopeDigest, String requestDigest,
                              Instant expiresAt, List<String> memberIds, String signature,
                              String filter) {
            this(snapshotId, scopeDigest, requestDigest, expiresAt, memberIds, signature, filter, 0);
        }

        public CursorSnapshot(String scopeDigest, String requestDigest, Instant expiresAt,
                              List<String> memberIds, String signature, String filter) {
            this(null, scopeDigest, requestDigest, expiresAt, memberIds, signature, filter, 0);
        }

        CursorSnapshot withOffset(int nextOffset, String nextSignature) {
            return new CursorSnapshot(snapshotId, scopeDigest, requestDigest, expiresAt, memberIds,
                nextSignature, filter, nextOffset);
        }

        public String encode() {
            String raw = String.join("\u001e",
                snapshotId, scopeDigest, requestDigest, expiresAt.toString(),
                String.join("\u001d", memberIds), signature,
                filter != null ? filter : "active", String.valueOf(offset));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                raw.getBytes(StandardCharsets.UTF_8));
        }

        public static CursorSnapshot decode(String encoded) {
            if (!StringUtils.hasText(encoded)) {
                throw new InvalidCursorException("Empty cursor");
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
                String[] parts = raw.split("\u001e", 8);
                if (parts.length != 8) {
                    throw new InvalidCursorException("Malformed cursor payload");
                }
                List<String> ids = parts[4].isEmpty()
                    ? List.of()
                    : List.of(parts[4].split("\u001d"));
                return new CursorSnapshot(parts[0], parts[1], parts[2], Instant.parse(parts[3]),
                    ids, parts[5], parts[6], Integer.parseInt(parts[7]));
            } catch (Exception e) {
                throw new InvalidCursorException("Malformed cursor");
            }
        }
    }

    public record CursorPage(
        List<AssignmentListEntry> members,
        CursorSnapshot cursor,
        CursorSnapshot nextCursor
    ) {}

    // ════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ════════════════════════════════════════════════════════════════

    private boolean matchesScope(WorkAssignment assignment, AssignmentScope scope) {
        return switch (scope) {
            case AgentScope s -> s.agentId().equals(assignment.agentId());
            case ProjectScope s -> s.projectId().equals(assignment.projectId());
        };
    }

    private int clampLimit(int limit) {
        return Math.clamp(limit > 0 ? limit : DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
    }

    private void requireProject(String projectId, boolean allowArchivedHistory) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found"));
        if (project.archived() && !allowArchivedHistory) {
            throw new ProjectOperationsAccessService.ProjectAccessDeniedException("Project is archived");
        }
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing unavailable", e);
        }
    }

    static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Exception ──

    public static final class InvalidCursorException extends RuntimeException {
        public InvalidCursorException(String message) { super(message); }
    }
}
