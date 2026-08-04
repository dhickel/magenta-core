package io.mindspice.magenta2.ai.orchestration.context;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Persistence for versioned context defaults, insert-only context bindings,
 * owner links, and durable context-operation idempotency. Constructor DDL is
 * forbidden: migrations 010 and 011 plus the fresh schema own these tables.
 */
@Repository
public class AgentContextRepository {

    private static final String BINDING_CAPTURE = "BINDING_CAPTURE";
    private static final String OWNER_LINK = "OWNER_LINK";

    private final JdbcTemplate jdbcTemplate;

    public AgentContextRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Context defaults ──

    public Optional<ContextDefault> findDefaultByAgent(String agentId) {
        return jdbcTemplate.query(
                "select * from context_defaults where agent_id = ?",
                rs -> rs.next() ? Optional.of(defaultFromRow(rs)) : Optional.empty(),
                agentId);
    }

    public void insertDefault(ContextDefault defaultRecord) {
        jdbcTemplate.update("""
                insert into context_defaults (
                    agent_id, version, selection_kind,
                    materialization_key, materialization_generation,
                    personal_selection, reason, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                defaultRecord.agentId(), defaultRecord.version(),
                defaultRecord.selectionKind().name(),
                serialized(defaultRecord.materializationKey()),
                defaultRecord.materializationGeneration(),
                enumName(defaultRecord.personalSelection()),
                defaultRecord.reason(),
                defaultRecord.createdAt().toString(), defaultRecord.updatedAt().toString());
    }

    public boolean updateDefault(String agentId, int expectedVersion, ContextDefault newDefault) {
        int updated = jdbcTemplate.update("""
                update context_defaults
                set version = ?, selection_kind = ?,
                    materialization_key = ?, materialization_generation = ?,
                    personal_selection = ?, reason = ?, updated_at = ?
                where agent_id = ? and version = ?
                """,
                expectedVersion + 1, newDefault.selectionKind().name(),
                serialized(newDefault.materializationKey()), newDefault.materializationGeneration(),
                enumName(newDefault.personalSelection()), newDefault.reason(),
                newDefault.updatedAt().toString(), agentId, expectedVersion);
        return updated == 1;
    }

    // ── Context bindings (insert-only) ──

    public Optional<ContextBinding> findByBindingKey(String bindingKey) {
        return jdbcTemplate.query(
                "select * from context_bindings where binding_key = ?",
                rs -> rs.next() ? Optional.of(bindingFromRow(rs)) : Optional.empty(), bindingKey);
    }

    public List<ContextBinding> findBindingsByAgent(String agentId) {
        return jdbcTemplate.query(
                "select * from context_bindings where agent_id = ? order by captured_at desc",
                (rs, rowNum) -> bindingFromRow(rs), agentId);
    }

    /** Direct insert for fixtures and non-retriable internal maintenance. */
    public ContextBinding insertBinding(ContextBinding binding) {
        insertBindingRow(binding);
        return findByBindingKey(binding.bindingKey()).orElseThrow();
    }

    /**
     * Atomically records a capture operation before inserting its immutable
     * binding. A same operation type/key and digest replays the recorded
     * binding; a changed digest returns {@link ContextOperationStatus#CONFLICT}.
     */
    /** Returns a durable replay or conflict result when this capture key already exists. */
    public Optional<BindingCaptureOperation> findBindingCaptureOperation(
            String operationKey, String requestDigest) {
        ContextOperation existing = findOperation(BINDING_CAPTURE, operationKey);
        return existing == null ? Optional.empty() : Optional.of(replayBinding(existing, requestDigest));
    }

    @Transactional
    public BindingCaptureOperation captureBindingIdempotently(
            String operationKey, String requestDigest, ContextBinding binding) {
        ContextOperation existing = findOperation(BINDING_CAPTURE, operationKey);
        if (existing != null) {
            return replayBinding(existing, requestDigest);
        }
        if (!insertOperationIfAbsent(BINDING_CAPTURE, operationKey, requestDigest,
                binding.bindingKey(), null, null)) {
            return replayBinding(requireOperation(BINDING_CAPTURE, operationKey), requestDigest);
        }

        try {
            insertBindingRow(binding);
        } catch (RuntimeException failure) {
            ContextBinding existingBinding = findByBindingKey(binding.bindingKey()).orElse(null);
            if (existingBinding == null || !sameBindingSelection(existingBinding, binding)) {
                throw failure;
            }
        }
        return new BindingCaptureOperation(ContextOperationStatus.CREATED,
                findByBindingKey(binding.bindingKey()).orElseThrow());
    }

    // ── Binding owner links ──

    public Optional<BindingOwnerLink> findOwnerLink(String ownerType, String ownerId) {
        return jdbcTemplate.query(
                "select * from binding_owner_links where owner_type = ? and owner_id = ?",
                rs -> rs.next() ? Optional.of(ownerLinkFromRow(rs)) : Optional.empty(), ownerType, ownerId);
    }

    /** Direct insert for fixtures and non-retriable internal maintenance. */
    public boolean insertOwnerLinkIfAbsent(BindingOwnerLink link) {
        int inserted = jdbcTemplate.update("""
                insert or ignore into binding_owner_links (owner_type, owner_id, binding_key, created_at)
                values (?, ?, ?, ?)
                """, link.ownerType(), link.ownerId(), link.bindingKey(), link.createdAt().toString());
        return inserted == 1;
    }

    /**
     * Atomically records an owner-link operation before inserting its immutable
     * owner pair. A replay never retargets an existing owner link.
     */
    /** Returns a durable replay or conflict result when this owner-link key already exists. */
    public Optional<OwnerLinkOperation> findOwnerLinkOperation(
            String operationKey, String requestDigest) {
        ContextOperation existing = findOperation(OWNER_LINK, operationKey);
        return existing == null ? Optional.empty() : Optional.of(replayOwnerLink(existing, requestDigest));
    }

    @Transactional
    public OwnerLinkOperation linkOwnerIdempotently(
            String operationKey, String requestDigest, BindingOwnerLink link) {
        ContextOperation existing = findOperation(OWNER_LINK, operationKey);
        if (existing != null) {
            return replayOwnerLink(existing, requestDigest);
        }
        if (!insertOperationIfAbsent(OWNER_LINK, operationKey, requestDigest,
                link.bindingKey(), link.ownerType(), link.ownerId())) {
            return replayOwnerLink(requireOperation(OWNER_LINK, operationKey), requestDigest);
        }

        BindingOwnerLink current = findOwnerLink(link.ownerType(), link.ownerId()).orElse(null);
        if (current == null) {
            insertOwnerLinkIfAbsent(link);
            current = findOwnerLink(link.ownerType(), link.ownerId()).orElseThrow();
        }
        if (!current.bindingKey().equals(link.bindingKey())) {
            throw new IllegalStateException("Owner is already linked to a different binding");
        }
        return new OwnerLinkOperation(ContextOperationStatus.CREATED, current);
    }

    // ── Assignment submission idempotency ──

    /**
     * Reserves the one transactional decision for an assignment submission.
     * The caller must capture a binding only after receiving {@code CREATED}.
     */
    public AssignmentSubmissionOperation beginAssignmentSubmission(String operationKey, String requestDigest) {
        Instant now = Instant.now();
        int inserted = updateWithBusyRetry("""
                insert or ignore into assignment_binding_operations(
                    operation_key, request_digest, operation_state, created_at, updated_at)
                values (?, ?, 'RESERVED', ?, ?)
                """, operationKey, requestDigest, now.toString(), now.toString());
        AssignmentSubmissionRow operation = findAssignmentSubmission(operationKey);
        if (operation == null) {
            throw new IllegalStateException("Assignment submission operation was not recorded");
        }
        if (!operation.requestDigest().equals(requestDigest)) {
            return new AssignmentSubmissionOperation(ContextOperationStatus.CONFLICT,
                    operation.bindingKey(), operation.assignmentId(), operation.taskRunId());
        }
        if (inserted == 1) {
            return new AssignmentSubmissionOperation(ContextOperationStatus.CREATED, null, null, null);
        }
        if (!"COMPLETED".equals(operation.state())) {
            throw new IllegalStateException("Assignment submission operation has no completed result");
        }
        return new AssignmentSubmissionOperation(ContextOperationStatus.REPLAYED,
                operation.bindingKey(), operation.assignmentId(), operation.taskRunId());
    }

    public void completeAssignmentSubmission(String operationKey, String requestDigest,
                                             String bindingKey, String assignmentId, String taskRunId) {
        int updated = jdbcTemplate.update("""
                update assignment_binding_operations
                set operation_state = 'COMPLETED', binding_key = ?, assignment_id = ?, task_run_id = ?, updated_at = ?
                where operation_key = ? and request_digest = ? and operation_state = 'RESERVED'
                """, bindingKey, assignmentId, taskRunId, Instant.now().toString(), operationKey, requestDigest);
        if (updated != 1) {
            throw new IllegalStateException("Assignment submission operation reservation was lost");
        }
    }

    private int updateWithBusyRetry(String sql, Object... arguments) {
        DataAccessException lastFailure = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                return jdbcTemplate.update(sql, arguments);
            } catch (DataAccessException failure) {
                if (!String.valueOf(failure.getMessage()).contains("SQLITE_BUSY") || attempt == 39) {
                    throw failure;
                }
                lastFailure = failure;
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    private AssignmentSubmissionRow findAssignmentSubmission(String operationKey) {
        return jdbcTemplate.query("""
                select request_digest, operation_state, binding_key, assignment_id, task_run_id
                from assignment_binding_operations where operation_key = ?
                """, rs -> rs.next() ? new AssignmentSubmissionRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) : null,
                operationKey);
    }

    // ── Durable context-operation idempotency ──

    private BindingCaptureOperation replayBinding(ContextOperation operation, String requestDigest) {
        if (!operation.requestDigest().equals(requestDigest)) {
            return new BindingCaptureOperation(ContextOperationStatus.CONFLICT, null);
        }
        return new BindingCaptureOperation(ContextOperationStatus.REPLAYED,
                findByBindingKey(operation.bindingKey()).orElseThrow(() ->
                        new IllegalStateException("Recorded binding operation has no binding")));
    }

    private OwnerLinkOperation replayOwnerLink(ContextOperation operation, String requestDigest) {
        if (!operation.requestDigest().equals(requestDigest)) {
            return new OwnerLinkOperation(ContextOperationStatus.CONFLICT, null);
        }
        return new OwnerLinkOperation(ContextOperationStatus.REPLAYED,
                findOwnerLink(operation.ownerType(), operation.ownerId()).orElseThrow(() ->
                        new IllegalStateException("Recorded owner-link operation has no owner link")));
    }

    private ContextOperation findOperation(String operationType, String operationKey) {
        return jdbcTemplate.query("""
                select operation_type, operation_key, request_digest, binding_key, owner_type, owner_id
                from operation_idempotency
                where operation_type = ? and operation_key = ?
                """, rs -> rs.next() ? new ContextOperation(
                        rs.getString("operation_type"), rs.getString("operation_key"),
                        rs.getString("request_digest"), rs.getString("binding_key"),
                        rs.getString("owner_type"), rs.getString("owner_id")) : null,
                operationType, operationKey);
    }

    private ContextOperation requireOperation(String operationType, String operationKey) {
        ContextOperation operation = findOperation(operationType, operationKey);
        if (operation == null) {
            throw new IllegalStateException("Context operation was not recorded");
        }
        return operation;
    }

    private boolean insertOperationIfAbsent(String operationType, String operationKey,
                                            String requestDigest, String bindingKey,
                                            String ownerType, String ownerId) {
        return jdbcTemplate.update("""
                insert or ignore into operation_idempotency (
                    operation_type, operation_key, request_digest, result_state,
                    binding_key, owner_type, owner_id, created_at
                ) values (?, ?, ?, 'COMPLETED', ?, ?, ?, ?)
                """, operationType, operationKey, requestDigest, bindingKey, ownerType, ownerId,
                Instant.now().toString()) == 1;
    }

    private void insertBindingRow(ContextBinding binding) {
        jdbcTemplate.update("""
                insert into context_bindings (
                    agent_id, binding_key, selection_kind,
                    materialization_key, materialization_generation,
                    personal_selection, captured_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """, binding.agentId(), binding.bindingKey(), binding.selectionKind().name(),
                serialized(binding.materializationKey()), binding.materializationGeneration(),
                enumName(binding.personalSelection()), binding.capturedAt().toString());
    }

    // ── Row mapping ──

    private ContextDefault defaultFromRow(ResultSet rs) throws SQLException {
        String materializationKey = rs.getString("materialization_key");
        String personalSelection = rs.getString("personal_selection");
        return new ContextDefault(rs.getString("agent_id"), rs.getInt("version"),
                SelectionKind.valueOf(rs.getString("selection_kind")),
                materializationKey == null ? null : parseKey(rs.getString("agent_id"), materializationKey),
                (Integer) rs.getObject("materialization_generation"),
                personalSelection == null ? null : PersonalSelection.valueOf(personalSelection),
                rs.getString("reason"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private ContextBinding bindingFromRow(ResultSet rs) throws SQLException {
        String materializationKey = rs.getString("materialization_key");
        String personalSelection = rs.getString("personal_selection");
        return new ContextBinding(rs.getLong("id"), rs.getString("agent_id"),
                rs.getString("binding_key"), SelectionKind.valueOf(rs.getString("selection_kind")),
                materializationKey == null ? null : parseKey(rs.getString("agent_id"), materializationKey),
                (Integer) rs.getObject("materialization_generation"),
                personalSelection == null ? null : PersonalSelection.valueOf(personalSelection),
                Instant.parse(rs.getString("captured_at")));
    }

    private BindingOwnerLink ownerLinkFromRow(ResultSet rs) throws SQLException {
        return new BindingOwnerLink(rs.getString("owner_type"), rs.getString("owner_id"),
                rs.getString("binding_key"), Instant.parse(rs.getString("created_at")));
    }

    private MaterializationKey parseKey(String agentId, String serialized) {
        if (!StringUtils.hasText(serialized) || !serialized.startsWith("v1-")) {
            throw new IllegalStateException("Stored materialization_key is not canonical: " + serialized);
        }
        return MaterializationKey.fromSerialized(agentId, serialized);
    }

    private static String serialized(MaterializationKey key) {
        return key == null ? null : key.serialized();
    }

    private static String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static boolean sameBindingSelection(ContextBinding actual, ContextBinding expected) {
        return actual.agentId().equals(expected.agentId())
                && actual.selectionKind() == expected.selectionKind()
                && java.util.Objects.equals(actual.materializationKey(), expected.materializationKey())
                && java.util.Objects.equals(actual.materializationGeneration(), expected.materializationGeneration())
                && actual.personalSelection() == expected.personalSelection();
    }

    public enum SelectionKind { MATERIALIZATION, PERSONAL }
    public enum PersonalSelection { HOME, SCRIPTS, APPS }
    public enum ContextOperationStatus { CREATED, REPLAYED, CONFLICT }

    public record BindingCaptureOperation(ContextOperationStatus status, ContextBinding binding) { }
    public record OwnerLinkOperation(ContextOperationStatus status, BindingOwnerLink ownerLink) { }
    public record AssignmentSubmissionOperation(ContextOperationStatus status, String bindingKey,
                                                String assignmentId, String taskRunId) { }
    private record AssignmentSubmissionRow(String requestDigest, String state, String bindingKey,
                                           String assignmentId, String taskRunId) { }
    private record ContextOperation(String operationType, String operationKey, String requestDigest,
                                    String bindingKey, String ownerType, String ownerId) { }

    public record ContextDefault(
            String agentId, int version, SelectionKind selectionKind,
            MaterializationKey materializationKey, Integer materializationGeneration,
            PersonalSelection personalSelection, String reason, Instant createdAt, Instant updatedAt) {
        public ContextDefault {
            if (agentId == null || agentId.isBlank() || version < 1 || selectionKind == null
                    || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("Context default is incomplete or invalid");
            }
            validateSelection(selectionKind, materializationKey, materializationGeneration, personalSelection);
        }
    }

    public record ContextBinding(
            Long id, String agentId, String bindingKey, SelectionKind selectionKind,
            MaterializationKey materializationKey, Integer materializationGeneration,
            PersonalSelection personalSelection, Instant capturedAt) {
        public ContextBinding {
            if (agentId == null || agentId.isBlank() || bindingKey == null || bindingKey.isBlank()
                    || selectionKind == null || capturedAt == null) {
                throw new IllegalArgumentException("Context binding is incomplete or invalid");
            }
            validateSelection(selectionKind, materializationKey, materializationGeneration, personalSelection);
        }

        public static ContextBinding forInsert(String agentId, String bindingKey,
                                               SelectionKind selectionKind,
                                               MaterializationKey materializationKey,
                                               Integer materializationGeneration,
                                               PersonalSelection personalSelection,
                                               Instant capturedAt) {
            return new ContextBinding(null, agentId, bindingKey, selectionKind, materializationKey,
                    materializationGeneration, personalSelection, capturedAt);
        }
    }

    public record BindingOwnerLink(String ownerType, String ownerId, String bindingKey, Instant createdAt) {
        public BindingOwnerLink {
            if (ownerType == null || ownerType.isBlank() || ownerId == null || ownerId.isBlank()
                    || bindingKey == null || bindingKey.isBlank() || createdAt == null) {
                throw new IllegalArgumentException("Binding owner link is incomplete or invalid");
            }
        }
    }

    private static void validateSelection(SelectionKind selectionKind,
                                          MaterializationKey materializationKey,
                                          Integer materializationGeneration,
                                          PersonalSelection personalSelection) {
        if (selectionKind == SelectionKind.MATERIALIZATION) {
            if (materializationKey == null || materializationGeneration == null
                    || materializationGeneration < 1 || personalSelection != null) {
                throw new IllegalArgumentException("Materialization selection is invalid");
            }
        } else if (materializationKey != null || materializationGeneration != null
                || personalSelection == null) {
            throw new IllegalArgumentException("Personal selection is invalid");
        }
    }
}
