package io.mindspice.magenta2.ai.orchestration.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Persistence for materialization lifecycle state and operation idempotency.
 * Contains no constructor DDL; migration 010 and fresh schema own the tables.
 */
@Repository
public class AgentMaterializationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentMaterializationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Materialization CRUD ──

    public Optional<MaterializationRecord> findByAgentAndKey(String agentId,
                                                              MaterializationKey key) {
        return jdbcTemplate.query(
                "select * from agent_materializations where agent_id = ? and materialization_key = ?",
                rs -> rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(),
                agentId, key.serialized());
    }

    public List<MaterializationRecord> findByAgent(String agentId) {
        return jdbcTemplate.query(
                "select * from agent_materializations where agent_id = ? order by updated_at desc",
                (rs, rowNum) -> fromRow(rs), agentId);
    }

    public List<MaterializationRecord> findByAgentAndState(String agentId,
                                                            MaterializationState state) {
        return jdbcTemplate.query(
                "select * from agent_materializations where agent_id = ? and state = ? order by updated_at desc",
                (rs, rowNum) -> fromRow(rs), agentId, state.name());
    }

    /**
     * Insert a materialization row. Returns true iff the row was new.
     * Detect a duplicate key with a preceding count query.
     */
    public boolean insertIfAbsent(MaterializationRecord record) {
        int inserted = jdbcTemplate.update("""
                insert or ignore into agent_materializations (
                    agent_id, materialization_key, materialization_kind,
                    source_kind, source_id, generation, state,
                    root_relative_path, failure_detail, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.agentId(), record.materializationKey().serialized(),
                record.materializationKey().kindToken(),
                record.sourceKind(), record.sourceId(),
                record.generation(), record.state().name(),
                record.rootRelativePath(), record.failureDetail(),
                record.createdAt().toString(), record.updatedAt().toString());
        return inserted == 1;
    }

    /**
     * CAS lifecycle transition: update state and generation only when
     * the current generation matches the expected value and the current
     * state is the permitted source.
     *
     * @return true if exactly one row was updated
     */
    public boolean transitionState(String agentId, MaterializationKey key,
                                    int expectedGeneration,
                                    MaterializationState fromState,
                                    MaterializationState toState,
                                    String rootRelativePath,
                                    String failureDetail) {
        int updated = jdbcTemplate.update("""
                update agent_materializations
                set state = ?, generation = ?, root_relative_path = ?,
                    failure_detail = ?, updated_at = ?
                where agent_id = ? and materialization_key = ?
                  and generation = ? and state = ?
                """,
                toState.name(), expectedGeneration + 1,
                rootRelativePath, failureDetail,
                Instant.now().toString(),
                agentId, key.serialized(),
                expectedGeneration, fromState.name());
        return updated == 1;
    }

    // ── Operation idempotency ──

    /**
     * Lookup an existing operation result. Returns the stored result
     * state and JSON iff the digest matches. Returns empty if the
     * operation has never been recorded. Returns {@code CONFLICT}
     * if the same type/key exists with a different digest.
     */
    public Optional<MaterializationOperationResult> findOperation(
            String operationType, String operationKey, String requestDigest) {
        List<MaterializationOperationResult> results = jdbcTemplate.query(
                "select result_state, materialization_key, result_json, request_digest"
                        + " from materialization_operation_results"
                        + " where operation_type = ? and operation_key = ?",
                (rs, rowNum) -> new MaterializationOperationResult(
                        rs.getString("result_state"),
                        rs.getString("materialization_key"),
                        rs.getString("result_json"),
                        rs.getString("request_digest")),
                operationType, operationKey);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        MaterializationOperationResult result = results.get(0);
        if (requestDigest.equals(result.storedDigest())) {
            return Optional.of(result);
        }
        return Optional.of(MaterializationOperationResult.conflict());
    }

    /** Insert a new operation result if absent. Returns true iff inserted. */
    public boolean insertOperationIfAbsent(String operationType, String operationKey,
                                            String requestDigest, String resultState,
                                            String materializationKey, String resultJson) {
        int inserted = jdbcTemplate.update("""
                insert or ignore into materialization_operation_results (
                    operation_type, operation_key, request_digest,
                    result_state, materialization_key, result_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
                operationType, operationKey, requestDigest,
                resultState, materializationKey, resultJson,
                Instant.now().toString());
        return inserted == 1;
    }

    // ── Row mapping ──

    private MaterializationRecord fromRow(ResultSet rs) throws SQLException {
        String agentId = rs.getString("agent_id");
        String sourceId = rs.getString("source_id");
        String serialized = rs.getString("materialization_key");
        MaterializationKey key = MaterializationKey.fromTrustedParts(
                agentId, sourceId, serialized);
        return new MaterializationRecord(
                agentId, key,
                rs.getString("source_kind"),
                sourceId,
                rs.getInt("generation"),
                MaterializationState.valueOf(rs.getString("state")),
                rs.getString("root_relative_path"),
                rs.getString("failure_detail"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    public record MaterializationOperationResult(
            String resultState,
            String materializationKey,
            String resultJson,
            String storedDigest) {

        public static MaterializationOperationResult conflict() {
            return new MaterializationOperationResult("CONFLICT", null, null, null);
        }

        public boolean isConflict() {
            return "CONFLICT".equals(resultState);
        }

        public boolean isCompleted() {
            return "COMPLETED".equals(resultState);
        }
    }

    public enum MaterializationState {
        PROVISIONING, READY, FAILED, REVOKED
    }

    public record MaterializationRecord(
            String agentId, MaterializationKey materializationKey, String sourceKind, String sourceId,
            int generation, MaterializationState state, String rootRelativePath, String failureDetail,
            Instant createdAt, Instant updatedAt) {
        public MaterializationRecord {
            if (agentId == null || agentId.isBlank() || materializationKey == null
                    || sourceKind == null || sourceKind.isBlank() || sourceId == null || sourceId.isBlank()
                    || generation < 1 || state == null || createdAt == null || updatedAt == null) {
                throw new IllegalArgumentException("Materialization record is incomplete or invalid");
            }
        }
    }

    public static final class MaterializationKey {
        private static final byte ENCODING_VERSION = 1;
        public static final String KIND_PROJECT = "PROJECT";
        private static final String SOURCE_KIND_PROJECT = "project";

        private final String kindToken;
        private final String sourceKind;
        private final String agentId;
        private final String sourceId;
        private final String serialized;

        private MaterializationKey(String kindToken, String sourceKind,
                                   String agentId, String sourceId, String digest) {
            this.kindToken = kindToken;
            this.sourceKind = sourceKind;
            this.agentId = agentId;
            this.sourceId = sourceId;
            this.serialized = "v1-" + kindToken + "-" + digest;
        }

        public static MaterializationKey forProject(String agentId, String projectId) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(projectId, "projectId");
            if (agentId.isBlank() || projectId.isBlank()) {
                throw new IllegalArgumentException("agentId and projectId are required");
            }
            return canonicalProjectKey(agentId, projectId);
        }

        public static MaterializationKey fromTrustedParts(String agentId, String sourceId,
                                                           String serialized) {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(sourceId, "sourceId");
            validateSerialized(serialized);
            MaterializationKey canonical = canonicalProjectKey(agentId, sourceId);
            if (!canonical.serialized.equals(serialized)) {
                throw new IllegalArgumentException("Stored materialization key does not match its identity");
            }
            return canonical;
        }

        public static MaterializationKey fromSerialized(String agentId, String serialized) {
            Objects.requireNonNull(agentId, "agentId");
            validateSerialized(serialized);
            String digest = serialized.substring("v1-PROJECT-".length());
            return new MaterializationKey(KIND_PROJECT, SOURCE_KIND_PROJECT, agentId, serialized, digest);
        }

        private static void validateSerialized(String serialized) {
            Objects.requireNonNull(serialized, "serialized");
            if (serialized.length() != 75 || !serialized.startsWith("v1-PROJECT-")
                    || !serialized.substring("v1-PROJECT-".length()).chars().allMatch(c ->
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("Malformed materialization key: " + serialized);
            }
        }

        static byte[] encodeProjectFrame(String agentId, String projectId) {
            return projectFrame(agentId, projectId);
        }

        private static MaterializationKey canonicalProjectKey(String agentId, String projectId) {
            return new MaterializationKey(KIND_PROJECT, SOURCE_KIND_PROJECT, agentId, projectId,
                    sha256Hex(projectFrame(agentId, projectId)));
        }

        private static byte[] projectFrame(String agentId, String projectId) {
            byte[] agent = agentId.getBytes(StandardCharsets.UTF_8);
            byte[] project = projectId.getBytes(StandardCharsets.UTF_8);
            if (agent.length > 0xffff || project.length > 0xffff) {
                throw new IllegalArgumentException("Materialization identity exceeds canonical two-byte length");
            }
            byte[] kind = KIND_PROJECT.getBytes(StandardCharsets.UTF_8);
            byte[] sourceKind = SOURCE_KIND_PROJECT.getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[1 + kind.length + 2 + agent.length + 2 + sourceKind.length + 2 + project.length];
            int pos = 0;
            frame[pos++] = ENCODING_VERSION;
            System.arraycopy(kind, 0, frame, pos, kind.length); pos += kind.length;
            pos = copyFramed(frame, pos, agent);
            pos = copyFramed(frame, pos, sourceKind);
            copyFramed(frame, pos, project);
            return frame;
        }

        private static int copyFramed(byte[] target, int pos, byte[] value) {
            target[pos++] = (byte) (value.length >>> 8);
            target[pos++] = (byte) value.length;
            System.arraycopy(value, 0, target, pos, value.length);
            return pos + value.length;
        }

        private static String sha256Hex(byte[] payload) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }

        public String kindToken() { return kindToken; }
        public String sourceKind() { return sourceKind; }
        public String agentId() { return agentId; }
        public String sourceId() { return sourceId; }
        public String serialized() { return serialized; }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof MaterializationKey that
                    && serialized.equals(that.serialized);
        }
        @Override public int hashCode() { return serialized.hashCode(); }
        @Override public String toString() { return serialized; }
    }
}
