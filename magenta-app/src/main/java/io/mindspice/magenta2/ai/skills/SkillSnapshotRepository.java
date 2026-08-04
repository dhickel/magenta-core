package io.mindspice.magenta2.ai.skills;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/** Immutable snapshot and resource persistence. */
@Repository
public class SkillSnapshotRepository {
    private static final int MAX_RESOURCES_PER_SNAPSHOT = 256;
    private static final long MAX_RESOURCE_BYTES = 10L * 1024 * 1024;

    private final JdbcTemplate jdbcTemplate;

    public SkillSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Snapshots ──

    public SkillSnapshot insertSnapshot(SkillSnapshot snapshot) {
        requireValid(snapshot);
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                insert or ignore into skill_snapshots (
                    candidate_version_id, logical_key, provenance, format_version,
                    snapshot_digest, skill_name, description, instructions,
                    hints, allowed_tools, resource_manifest_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            snapshot.candidateVersionId(),
            snapshot.logicalKey(),
            snapshot.provenance().name(),
            snapshot.formatVersion(),
            snapshot.snapshotDigest(),
            snapshot.skillName(),
            snapshot.description(),
            snapshot.instructions(),
            snapshot.hints(),
            snapshot.allowedTools(),
            snapshot.resourceManifestJson(),
            now.toString()
        );
        return findByDigest(snapshot.snapshotDigest())
            .orElseThrow(() -> new IllegalStateException("Failed to persist snapshot"));
    }

    public Optional<SkillSnapshot> findById(Long id) {
        if (id == null || id < 1) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_snapshots where id = ?",
            rs -> rs.next() ? Optional.of(fromSnapshotRow(rs)) : Optional.empty(),
            id
        );
    }

    public Optional<SkillSnapshot> findByDigest(String digest) {
        if (!StringUtils.hasText(digest) || digest.length() != 64) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_snapshots where snapshot_digest = ?",
            rs -> rs.next() ? Optional.of(fromSnapshotRow(rs)) : Optional.empty(),
            digest
        );
    }

    public Optional<SkillSnapshot> findByCandidateVersionId(Long candidateVersionId) {
        if (candidateVersionId == null || candidateVersionId < 1) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_snapshots where candidate_version_id = ?",
            rs -> rs.next() ? Optional.of(fromSnapshotRow(rs)) : Optional.empty(),
            candidateVersionId
        );
    }

    public List<SkillSnapshot> findAllSnapshots() {
        return jdbcTemplate.query(
            "select * from skill_snapshots order by logical_key, created_at desc, id desc",
            (rs, rowNum) -> fromSnapshotRow(rs)
        );
    }

    public List<SkillSnapshot> findByLogicalKey(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return List.of();
        return jdbcTemplate.query(
            "select * from skill_snapshots where logical_key = ? order by created_at desc, id desc",
            (rs, rowNum) -> fromSnapshotRow(rs),
            logicalKey
        );
    }

    public List<SkillSnapshot> findCurrentlyApprovedSnapshots() {
        return jdbcTemplate.query(
            """
                select s.* from skill_snapshots s
                join skill_trust_decisions d on d.snapshot_id = s.id
                where d.scope = 'INSTRUCTION' and d.decision = 'APPROVE'
                  and d.id = (
                    select latest.id from skill_trust_decisions latest
                    where latest.logical_key = s.logical_key and latest.scope = 'INSTRUCTION'
                    order by latest.decided_at desc, latest.id desc limit 1
                  )
                order by s.logical_key
                """,
            (rs, rowNum) -> fromSnapshotRow(rs)
        );
    }

    public Optional<SkillSnapshot> findLatestApproved(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return Optional.empty();
        return jdbcTemplate.query(
            """
                select s.* from skill_snapshots s
                join skill_trust_decisions d on d.snapshot_id = s.id
                where s.logical_key = ? and d.decision = 'APPROVE' and d.scope = 'INSTRUCTION'
                order by s.created_at desc limit 1
                """,
            rs -> rs.next() ? Optional.of(fromSnapshotRow(rs)) : Optional.empty(),
            logicalKey
        );
    }

    public boolean snapshotExistsForCandidate(Long candidateVersionId) {
        if (candidateVersionId == null || candidateVersionId < 1) return false;
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from skill_snapshots where candidate_version_id = ?",
            Integer.class,
            candidateVersionId
        );
        return count != null && count > 0;
    }

    private SkillSnapshot fromSnapshotRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number cvId = (Number) rs.getObject("candidate_version_id");
        return new SkillSnapshot(
            id == null ? null : id.longValue(),
            cvId == null ? null : cvId.longValue(),
            rs.getString("logical_key"),
            SkillProvenance.valueOf(rs.getString("provenance")),
            rs.getInt("format_version"),
            rs.getString("snapshot_digest"),
            rs.getString("skill_name"),
            rs.getString("description"),
            rs.getString("instructions"),
            rs.getString("hints"),
            rs.getString("allowed_tools"),
            rs.getString("resource_manifest_json"),
            instant(rs.getString("created_at"))
        );
    }

    // ── Snapshot resources ──

    public SkillSnapshotResource insertResource(SkillSnapshotResource resource) {
        requireValid(resource);
        jdbcTemplate.update(
            """
                insert or ignore into skill_snapshot_resources (
                    snapshot_id, relative_path, resource_type,
                    content_length, content_bytes, content_digest
                ) values (?, ?, ?, ?, ?, ?)
                """,
            resource.snapshotId(),
            resource.relativePath(),
            resource.resourceType(),
            resource.contentLength(),
            resource.contentBytes(),
            resource.contentDigest()
        );
        return findBySnapshotAndPath(resource.snapshotId(), resource.relativePath())
            .orElseThrow(() -> new IllegalStateException("Failed to persist snapshot resource"));
    }

    public Optional<SkillSnapshotResource> findBySnapshotAndPath(Long snapshotId, String relativePath) {
        if (snapshotId == null || snapshotId < 1 || !StringUtils.hasText(relativePath)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_snapshot_resources where snapshot_id = ? and relative_path = ?",
            rs -> rs.next() ? Optional.of(fromResourceRow(rs)) : Optional.empty(),
            snapshotId, relativePath
        );
    }

    public Optional<SkillSnapshotResource> findResourceById(Long id) {
        if (id == null || id < 1) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_snapshot_resources where id = ?",
            rs -> rs.next() ? Optional.of(fromResourceRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<SkillSnapshotResource> findResourcesBySnapshotId(Long snapshotId) {
        if (snapshotId == null || snapshotId < 1) return List.of();
        return jdbcTemplate.query(
            "select * from skill_snapshot_resources where snapshot_id = ? order by relative_path",
            (rs, rowNum) -> fromResourceRow(rs),
            snapshotId
        );
    }

    private SkillSnapshotResource fromResourceRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number snapshotId = (Number) rs.getObject("snapshot_id");
        byte[] bytes = rs.getBytes("content_bytes");
        return new SkillSnapshotResource(
            id == null ? null : id.longValue(),
            snapshotId == null ? null : snapshotId.longValue(),
            rs.getString("relative_path"),
            rs.getString("resource_type"),
            rs.getInt("content_length"),
            bytes == null ? new byte[0] : bytes,
            rs.getString("content_digest")
        );
    }

    // ── Trust decisions ──

    public TrustDecisionRecord insertDecision(TrustDecisionRecord record) {
        requireValid(record);
        jdbcTemplate.update(
            """
                insert into skill_trust_decisions (
                    candidate_version_id, logical_key, decision, snapshot_id,
                    actor, reason, scope, operation_key, request_digest, decided_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            record.candidateVersionId(),
            record.logicalKey(),
            record.decision(),
            record.snapshotId(),
            record.actor(),
            record.reason(),
            record.scope(),
            record.operationKey(),
            record.requestDigest(),
            record.decidedAt().toString()
        );
        return findDecisionByOperationKey(record.operationKey())
            .orElseThrow(() -> new IllegalStateException("Failed to persist trust decision"));
    }

    public Optional<TrustDecisionRecord> findDecisionByOperationKey(String operationKey) {
        if (!StringUtils.hasText(operationKey)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_trust_decisions where operation_key = ?",
            rs -> rs.next() ? Optional.of(fromDecisionRow(rs)) : Optional.empty(),
            operationKey
        );
    }

    public List<TrustDecisionRecord> findDecisionsByCandidate(Long candidateVersionId) {
        if (candidateVersionId == null || candidateVersionId < 1) return List.of();
        return jdbcTemplate.query(
            "select * from skill_trust_decisions where candidate_version_id = ? order by decided_at desc",
            (rs, rowNum) -> fromDecisionRow(rs),
            candidateVersionId
        );
    }

    public Optional<TrustDecisionRecord> findLatestDecision(String logicalKey, String scope) {
        if (!StringUtils.hasText(logicalKey) || !StringUtils.hasText(scope)) return Optional.empty();
        return jdbcTemplate.query(
            """
                select * from skill_trust_decisions
                where logical_key = ? and scope = ?
                order by decided_at desc limit 1
                """,
            rs -> rs.next() ? Optional.of(fromDecisionRow(rs)) : Optional.empty(),
            logicalKey, scope
        );
    }

    public boolean isCurrentlyApproved(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return false;
        return findLatestDecision(logicalKey, "INSTRUCTION")
            .map(d -> "APPROVE".equals(d.decision()))
            .orElse(false);
    }

    public boolean isRevoked(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return false;
        return findLatestDecision(logicalKey, "INSTRUCTION")
            .map(d -> "REVOKE".equals(d.decision()))
            .orElse(false);
    }

    private TrustDecisionRecord fromDecisionRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number cvId = (Number) rs.getObject("candidate_version_id");
        Number snapshotId = (Number) rs.getObject("snapshot_id");
        return new TrustDecisionRecord(
            id == null ? null : id.longValue(),
            cvId == null ? null : cvId.longValue(),
            rs.getString("logical_key"),
            rs.getString("decision"),
            snapshotId == null ? null : snapshotId.longValue(),
            rs.getString("actor"),
            rs.getString("reason"),
            rs.getString("scope"),
            rs.getString("operation_key"),
            rs.getString("request_digest"),
            instant(rs.getString("decided_at"))
        );
    }

    // ── Activations ──

    public SkillActivationRecord insertActivation(SkillActivationRecord record) {
        requireValid(record);
        jdbcTemplate.update(
            """
                insert or ignore into skill_activations (
                    snapshot_id, logical_key, task_run_id, conversation_id,
                    agent_id, activation_context, activated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
            record.snapshotId(),
            record.logicalKey(),
            record.taskRunId(),
            record.conversationId(),
            record.agentId(),
            record.activationContext(),
            record.activatedAt().toString()
        );
        return findByActivationContext(record.snapshotId(), record.activationContext())
            .orElseThrow(() -> new IllegalStateException("Failed to persist activation"));
    }

    public Optional<SkillActivationRecord> findByActivationContext(Long snapshotId, String activationContext) {
        if (snapshotId == null || snapshotId < 1 || !StringUtils.hasText(activationContext)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_activations where snapshot_id = ? and activation_context = ?",
            rs -> rs.next() ? Optional.of(fromActivationRow(rs)) : Optional.empty(),
            snapshotId, activationContext
        );
    }

    public Optional<SkillActivationRecord> findActivationForTaskRun(String taskRunId, String logicalKey) {
        if (!StringUtils.hasText(taskRunId) || !StringUtils.hasText(logicalKey)) return Optional.empty();
        return jdbcTemplate.query(
            """
                select * from skill_activations
                where task_run_id = ? and logical_key = ?
                order by activated_at desc limit 1
                """,
            rs -> rs.next() ? Optional.of(fromActivationRow(rs)) : Optional.empty(),
            taskRunId, logicalKey
        );
    }

    public List<SkillActivationRecord> findActivationsByTaskRun(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbcTemplate.query(
            "select * from skill_activations where task_run_id = ? order by activated_at desc, id desc",
            (rs, rowNum) -> fromActivationRow(rs),
            taskRunId
        );
    }

    private SkillActivationRecord fromActivationRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number snapshotId = (Number) rs.getObject("snapshot_id");
        return new SkillActivationRecord(
            id == null ? null : id.longValue(),
            snapshotId == null ? null : snapshotId.longValue(),
            rs.getString("logical_key"),
            rs.getString("task_run_id"),
            rs.getString("conversation_id"),
            rs.getString("agent_id"),
            rs.getString("activation_context"),
            instant(rs.getString("activated_at"))
        );
    }

    // ── Executable authorizations ──

    public ExecutableAuthorizationRecord insertExecutableAuthorization(ExecutableAuthorizationRecord record) {
        requireValid(record);
        jdbcTemplate.update(
            """
                insert into skill_executable_authorizations (
                    snapshot_id, snapshot_resource_id, logical_key, decision,
                    actor, reason, confinement_capability, operation_key,
                    request_digest, decided_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            record.snapshotId(),
            record.snapshotResourceId(),
            record.logicalKey(),
            record.decision(),
            record.actor(),
            record.reason(),
            record.confinementCapability(),
            record.operationKey(),
            record.requestDigest(),
            record.decidedAt().toString()
        );
        return findExecAuthByOperationKey(record.operationKey())
            .orElseThrow(() -> new IllegalStateException("Failed to persist executable authorization"));
    }

    public Optional<ExecutableAuthorizationRecord> findExecAuthByOperationKey(String operationKey) {
        if (!StringUtils.hasText(operationKey)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_executable_authorizations where operation_key = ?",
            rs -> rs.next() ? Optional.of(fromExecAuthRow(rs)) : Optional.empty(),
            operationKey
        );
    }

    public Optional<ExecutableAuthorizationRecord> findExecAuthForResource(Long snapshotId, Long resourceId) {
        if (snapshotId == null || snapshotId < 1 || resourceId == null || resourceId < 1) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_executable_authorizations where snapshot_id = ? and snapshot_resource_id = ?",
            rs -> rs.next() ? Optional.of(fromExecAuthRow(rs)) : Optional.empty(),
            snapshotId, resourceId
        );
    }

    public boolean isExecutableAuthorized(Long snapshotId, Long resourceId) {
        return findExecAuthForResource(snapshotId, resourceId)
            .map(a -> "AUTHORIZED".equals(a.decision()))
            .orElse(false);
    }

    private ExecutableAuthorizationRecord fromExecAuthRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number snapshotId = (Number) rs.getObject("snapshot_id");
        Number resourceId = (Number) rs.getObject("snapshot_resource_id");
        return new ExecutableAuthorizationRecord(
            id == null ? null : id.longValue(),
            snapshotId == null ? null : snapshotId.longValue(),
            resourceId == null ? null : resourceId.longValue(),
            rs.getString("logical_key"),
            rs.getString("decision"),
            rs.getString("actor"),
            rs.getString("reason"),
            rs.getString("confinement_capability"),
            rs.getString("operation_key"),
            rs.getString("request_digest"),
            instant(rs.getString("decided_at"))
        );
    }

    // ── Package operations ──

    public PackageOperationRecord insertPackageOperation(PackageOperationRecord record) {
        requireValid(record);
        jdbcTemplate.update(
            """
                insert into skill_package_operations (
                    operation_type, operation_key, request_digest,
                    operation_state, staging_path, package_identity,
                    entry_count, total_bytes, validation_evidence,
                    failure_detail, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            record.operationType(),
            record.operationKey(),
            record.requestDigest(),
            record.operationState(),
            record.stagingPath(),
            record.packageIdentity(),
            record.entryCount(),
            record.totalBytes(),
            record.validationEvidence(),
            record.failureDetail(),
            record.createdAt().toString(),
            record.updatedAt().toString()
        );
        return findPackageOpByKey(record.operationType(), record.operationKey())
            .orElseThrow(() -> new IllegalStateException("Failed to persist package operation"));
    }

    public Optional<PackageOperationRecord> findPackageOpByKey(String operationType, String operationKey) {
        if (!StringUtils.hasText(operationType) || !StringUtils.hasText(operationKey)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from skill_package_operations where operation_type = ? and operation_key = ?",
            rs -> rs.next() ? Optional.of(fromPackageOpRow(rs)) : Optional.empty(),
            operationType, operationKey
        );
    }

    public int updatePackageOperationState(Long id, String state, String failureDetail) {
        return jdbcTemplate.update(
            """
                update skill_package_operations
                set operation_state = ?, failure_detail = ?, updated_at = ?
                where id = ?
                """,
            state, failureDetail, Instant.now().toString(), id
        );
    }

    private PackageOperationRecord fromPackageOpRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number entryCount = (Number) rs.getObject("entry_count");
        Number totalBytes = (Number) rs.getObject("total_bytes");
        return new PackageOperationRecord(
            id == null ? null : id.longValue(),
            rs.getString("operation_type"),
            rs.getString("operation_key"),
            rs.getString("request_digest"),
            rs.getString("operation_state"),
            rs.getString("staging_path"),
            rs.getString("package_identity"),
            entryCount == null ? null : entryCount.intValue(),
            totalBytes == null ? null : totalBytes.longValue(),
            rs.getString("validation_evidence"),
            rs.getString("failure_detail"),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    // ── Resource executions ──

    public ResourceExecutionRecord insertResourceExecution(ResourceExecutionRecord record) {
        requireValid(record);
        jdbcTemplate.update(
            """
                insert into skill_resource_executions (
                    snapshot_id, snapshot_resource_id, task_run_id, agent_id,
                    writer_owner_id, fence_generation, attempt_number,
                    launch_state, process_policy, args_json, env_json,
                    start_result_state, exit_code, exit_facts_json,
                    failure_detail, started_at, completed_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            record.snapshotId(),
            record.snapshotResourceId(),
            record.taskRunId(),
            record.agentId(),
            record.writerOwnerId(),
            record.fenceGeneration(),
            record.attemptNumber(),
            record.launchState(),
            record.processPolicy(),
            record.argsJson(),
            record.envJson(),
            record.startResultState(),
            record.exitCode(),
            record.exitFactsJson(),
            record.failureDetail(),
            record.startedAt() == null ? null : record.startedAt().toString(),
            record.completedAt() == null ? null : record.completedAt().toString(),
            record.createdAt().toString()
        );
        return jdbcTemplate.query(
            "select * from skill_resource_executions where id = last_insert_rowid()",
            rs -> rs.next() ? fromResourceExecRow(rs) : null
        );
    }

    private ResourceExecutionRecord fromResourceExecRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number snapshotId = (Number) rs.getObject("snapshot_id");
        Number resourceId = (Number) rs.getObject("snapshot_resource_id");
        Number fence = (Number) rs.getObject("fence_generation");
        Number attempt = (Number) rs.getObject("attempt_number");
        Number exitCode = (Number) rs.getObject("exit_code");
        return new ResourceExecutionRecord(
            id == null ? null : id.longValue(),
            snapshotId == null ? null : snapshotId.longValue(),
            resourceId == null ? null : resourceId.longValue(),
            rs.getString("task_run_id"),
            rs.getString("agent_id"),
            rs.getString("writer_owner_id"),
            fence == null ? null : fence.intValue(),
            attempt == null ? null : attempt.intValue(),
            rs.getString("launch_state"),
            rs.getString("process_policy"),
            rs.getString("args_json"),
            rs.getString("env_json"),
            rs.getString("start_result_state"),
            exitCode == null ? null : exitCode.intValue(),
            rs.getString("exit_facts_json"),
            rs.getString("failure_detail"),
            instantOrNull(rs.getString("started_at")),
            instantOrNull(rs.getString("completed_at")),
            instant(rs.getString("created_at"))
        );
    }

    // ── Helpers ──

    private void requireValid(SkillSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
    }

    private void requireValid(SkillSnapshotResource resource) {
        if (resource == null) throw new IllegalArgumentException("resource is required");
    }

    private void requireValid(TrustDecisionRecord record) {
        if (record == null) throw new IllegalArgumentException("decision record is required");
    }

    private void requireValid(SkillActivationRecord record) {
        if (record == null) throw new IllegalArgumentException("activation record is required");
    }

    private void requireValid(ExecutableAuthorizationRecord record) {
        if (record == null) throw new IllegalArgumentException("executable authorization record is required");
    }

    private void requireValid(PackageOperationRecord record) {
        if (record == null) throw new IllegalArgumentException("package operation record is required");
    }

    private void requireValid(ResourceExecutionRecord record) {
        if (record == null) throw new IllegalArgumentException("resource execution record is required");
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private Instant instantOrNull(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // ── Domain records ──

    public record SkillSnapshot(Long id, Long candidateVersionId, String logicalKey,
                                 SkillProvenance provenance, int formatVersion,
                                 String snapshotDigest, String skillName,
                                 String description, String instructions,
                                 String hints, String allowedTools,
                                 String resourceManifestJson, Instant createdAt) {
        public String activationIdentifier() {
            return logicalKey + "@" + snapshotDigest;
        }
    }

    public record SkillSnapshotResource(Long id, Long snapshotId, String relativePath,
                                         String resourceType, int contentLength,
                                         byte[] contentBytes, String contentDigest) {
        public boolean isExecutable() {
            return "SCRIPT".equals(resourceType);
        }
    }

    public record TrustDecisionRecord(Long id, Long candidateVersionId, String logicalKey,
                                       String decision, Long snapshotId,
                                       String actor, String reason, String scope,
                                       String operationKey, String requestDigest,
                                       Instant decidedAt) { }

    public record SkillActivationRecord(Long id, Long snapshotId, String logicalKey,
                                         String taskRunId, String conversationId,
                                         String agentId, String activationContext,
                                         Instant activatedAt) { }

    public record ExecutableAuthorizationRecord(Long id, Long snapshotId, Long snapshotResourceId,
                                                  String logicalKey, String decision,
                                                  String actor, String reason,
                                                  String confinementCapability,
                                                  String operationKey, String requestDigest,
                                                  Instant decidedAt) { }

    public record PackageOperationRecord(Long id, String operationType, String operationKey,
                                          String requestDigest, String operationState,
                                          String stagingPath, String packageIdentity,
                                          Integer entryCount, Long totalBytes,
                                          String validationEvidence, String failureDetail,
                                          Instant createdAt, Instant updatedAt) { }

    public record ResourceExecutionRecord(Long id, Long snapshotId, Long snapshotResourceId,
                                            String taskRunId, String agentId,
                                            String writerOwnerId, Integer fenceGeneration,
                                            Integer attemptNumber, String launchState,
                                            String processPolicy, String argsJson,
                                            String envJson, String startResultState,
                                            Integer exitCode, String exitFactsJson,
                                            String failureDetail, Instant startedAt,
                                            Instant completedAt, Instant createdAt) { }
}
