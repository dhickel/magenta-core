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
 * Persistence for materialization lifecycle state, operation idempotency,
 * and immutable generation rows. Contains no constructor DDL; migrations
 * 010, 011, 018 and fresh schema own the tables.
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

    // ── Immutable generation rows (migration 018) ──

    /** The generation root kind separates V2 roots from legacy-compatible locations. */
    public enum RootKind {
        GENERATION_V2,
        LEGACY_V1
    }

    /** Lifecycle state for a single immutable generation. */
    public enum GenerationLifecycleState {
        PROVISIONING, READY, FAILED, REVOKED
    }

    /**
     * Immutable generation row. Identity is {@code (agentId, projectKey, generation)}.
     * Only {@code lifecycleState} and {@code lifecycleVersion} may change via CAS;
     * all other fields are immutable after insert.
     */
    public record GenerationRecord(
            String agentId,
            String projectKey,
            int generation,
            RootKind rootKind,
            String rootLocation,
            GenerationLifecycleState lifecycleState,
            int lifecycleVersion,
            Integer templateRevision,
            String templateDirectoriesSnapshot,
            String failureDetail,
            Instant createdAt,
            Instant updatedAt) {

        public GenerationRecord {
            Objects.requireNonNull(agentId, "agentId");
            Objects.requireNonNull(projectKey, "projectKey");
            if (agentId.isBlank() || projectKey.isBlank()) {
                throw new IllegalArgumentException("agentId and projectKey are required");
            }
            if (generation < 1) {
                throw new IllegalArgumentException("generation must be >= 1");
            }
            Objects.requireNonNull(rootKind, "rootKind");
            Objects.requireNonNull(lifecycleState, "lifecycleState");
            if (lifecycleVersion < 1) {
                throw new IllegalArgumentException("lifecycleVersion must be >= 1");
            }
            Objects.requireNonNull(templateDirectoriesSnapshot, "templateDirectoriesSnapshot");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }

        /** The logical head identity {@code (agentId, projectKey)} selects unique generations. */
        public String headKey() {
            return agentId + "/" + projectKey;
        }
    }

    /**
     * Insert a new immutable generation row. Returns true iff inserted.
     * The compound primary key is {@code (agent_id, project_key, generation)}.
     */
    public boolean insertGeneration(GenerationRecord record) {
        int inserted = jdbcTemplate.update("""
                insert or ignore into agent_materialization_generations (
                    agent_id, project_key, generation,
                    root_kind, root_location, lifecycle_state, lifecycle_version,
                    template_revision, template_directories_snapshot,
                    failure_detail, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.agentId(), record.projectKey(), record.generation(),
                record.rootKind().name(), record.rootLocation(),
                record.lifecycleState().name(), record.lifecycleVersion(),
                record.templateRevision(), record.templateDirectoriesSnapshot(),
                record.failureDetail(),
                record.createdAt().toString(), record.updatedAt().toString());
        return inserted == 1;
    }

    /** Find one generation by its exact logical head {@code (agentId, projectKey, generation)}. */
    public Optional<GenerationRecord> findGeneration(String agentId, String projectKey, int generation) {
        return jdbcTemplate.query(
                "select * from agent_materialization_generations"
                        + " where agent_id = ? and project_key = ? and generation = ?",
                rs -> rs.next() ? Optional.of(generationFromRow(rs)) : Optional.empty(),
                agentId, projectKey, generation);
    }

    /** List all generations for a logical head ordered by generation descending. */
    public List<GenerationRecord> findGenerationsByHead(String agentId, String projectKey) {
        return jdbcTemplate.query(
                "select * from agent_materialization_generations"
                        + " where agent_id = ? and project_key = ? order by generation desc",
                (rs, rowNum) -> generationFromRow(rs), agentId, projectKey);
    }

    /** List all generations for an agent ordered by updated_at descending. */
    public List<GenerationRecord> findGenerationsByAgent(String agentId) {
        return jdbcTemplate.query(
                "select * from agent_materialization_generations"
                        + " where agent_id = ? order by updated_at desc",
                (rs, rowNum) -> generationFromRow(rs), agentId);
    }

    /** Return the latest generation number for a logical head, or 0 if none exist. */
    public int findLatestGenerationNumber(String agentId, String projectKey) {
        Integer max = jdbcTemplate.queryForObject(
                "select max(generation) from agent_materialization_generations"
                        + " where agent_id = ? and project_key = ?",
                Integer.class, agentId, projectKey);
        return max == null ? 0 : max;
    }

    /**
     * CAS lifecycle transition on a generation row. Only {@code lifecycleState},
     * {@code lifecycleVersion}, {@code rootLocation}, and {@code failureDetail}
     * are mutable. Generation identity fields ({@code agentId}, {@code projectKey},
     * {@code generation}, {@code rootKind}, {@code templateRevision},
     * {@code templateDirectoriesSnapshot}) never change.
     *
     * @return true if exactly one row was updated
     */
    public boolean casTransitionGenerationLifecycle(
            String agentId, String projectKey, int generation,
            int expectedLifecycleVersion,
            GenerationLifecycleState fromState,
            GenerationLifecycleState toState,
            String rootLocation,
            String failureDetail) {
        int updated = jdbcTemplate.update("""
                update agent_materialization_generations
                set lifecycle_state = ?, lifecycle_version = ?,
                    root_location = ?, failure_detail = ?, updated_at = ?
                where agent_id = ? and project_key = ? and generation = ?
                  and lifecycle_version = ? and lifecycle_state = ?
                """,
                toState.name(), expectedLifecycleVersion + 1,
                rootLocation, failureDetail,
                Instant.now().toString(),
                agentId, projectKey, generation,
                expectedLifecycleVersion, fromState.name());
        return updated == 1;
    }

    // ── Generation row mapping ──

    private GenerationRecord generationFromRow(ResultSet rs) throws SQLException {
        return new GenerationRecord(
                rs.getString("agent_id"),
                rs.getString("project_key"),
                rs.getInt("generation"),
                RootKind.valueOf(rs.getString("root_kind")),
                rs.getString("root_location"),
                GenerationLifecycleState.valueOf(rs.getString("lifecycle_state")),
                rs.getInt("lifecycle_version"),
                readNullableInteger(rs, "template_revision"),
                rs.getString("template_directories_snapshot"),
                rs.getString("failure_detail"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private Integer readNullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
