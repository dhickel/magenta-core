package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns all {@code output_publications} and legacy {@code run_output_artifacts}
 * schema, queries, writes, guarded transitions, and row mapping.
 */
@Repository
public class OutputArtifactRepository {
    private final JdbcTemplate jdbcTemplate;

    public OutputArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── Publication record persistence ──

    public PublicationRecord insertReservation(PublicationRecord record) {
        Instant now = Instant.now();
        jdbcTemplate.update("""
                insert into output_publications (
                    artifact_id, publication_origin, workspace_kind, workspace_key,
                    workspace_identity, task_run_id, task_path_key, native_run_id,
                    attempt_number, root_relative_path, staging_path,
                    operation_key, request_digest, content_sha256,
                    publication_state, reconciliation_state, failure_detail,
                    expected_sha256, verified_sha256, verified_size, media_type,
                    writer_owner_id, fence_generation,
                    logical_name, file_extension,
                    created_at, updated_at, staged_at, published_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                record.artifactId(), record.publicationOrigin(), record.workspaceKind(), record.workspaceKey(),
                record.workspaceIdentity(), record.taskRunId(), record.taskPathKey(), record.nativeRunId(),
                record.attemptNumber(), record.rootRelativePath(), record.stagingPath(),
                record.operationKey(), record.requestDigest(), record.contentSha256(),
                record.publicationState(), record.reconciliationState(), record.failureDetail(),
                record.expectedSha256(), record.verifiedSha256(), record.verifiedSize(), record.mediaType(),
                record.writerOwnerId(), record.fenceGeneration(),
                record.logicalName(), record.fileExtension(),
                record.createdAt(), record.updatedAt(), record.stagedAt(), record.publishedAt());
        return record;
    }

    /** Atomically reserves the first available deterministic final path. */
    @Transactional
    public PublicationRecord reserveFirstAvailable(List<PublicationRecord> candidates) {
        for (PublicationRecord candidate : candidates) {
            try {
                return insertReservation(candidate);
            } catch (DataIntegrityViolationException collision) {
                Optional<PublicationRecord> existing = findByOperationKey(
                        candidate.publicationOrigin(), candidate.operationKey());
                if (existing.isPresent()) {
                    if (!existing.get().requestDigest().equals(candidate.requestDigest())) {
                        throw new IllegalArgumentException("Operation key reused with different input", collision);
                    }
                    return existing.get();
                }
                // A final-path uniqueness collision selects the next deterministic suffix.
            }
        }
        throw new IllegalStateException("Cannot reserve an artifact final path");
    }

    /**
     * Compare-and-set publication state transition. For TASK_RUN publications,
     * the live writer owner and fence must match when required by the transition.
     */
    public boolean compareAndSetState(String artifactId, String expectedState, String newState,
                                       String writerOwnerId, Long fenceGeneration,
                                       String failureDetail, String verifiedSha256,
                                       Long verifiedSize, String mediaType,
                                       String reconciliationState) {
        StringBuilder sql = new StringBuilder("""
                update output_publications set publication_state = ?, updated_at = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(newState);
        args.add(Instant.now().toString());

        if (failureDetail != null) {
            sql.append(", failure_detail = ?");
            args.add(failureDetail);
        }
        if (verifiedSha256 != null) {
            sql.append(", verified_sha256 = ?");
            args.add(verifiedSha256);
        }
        if (verifiedSize != null) {
            sql.append(", verified_size = ?");
            args.add(verifiedSize);
        }
        if (mediaType != null) {
            sql.append(", media_type = ?");
            args.add(mediaType);
        }
        if (reconciliationState != null) {
            sql.append(", reconciliation_state = ?");
            args.add(reconciliationState);
        }

        if ("STAGED".equals(newState)) {
            sql.append(", staged_at = ?");
            args.add(Instant.now().toString());
        }
        if ("PUBLISHED".equals(newState)) {
            sql.append(", published_at = ?");
            args.add(Instant.now().toString());
        }

        sql.append(" where artifact_id = ? and publication_state = ?");
        args.add(artifactId);
        args.add(expectedState);

        sql.append(" and (publication_origin <> 'TASK_RUN' or (writer_owner_id = ? and fence_generation = ?))");
        args.add(writerOwnerId);
        args.add(fenceGeneration);

        return jdbcTemplate.update(sql.toString(), args.toArray()) == 1;
    }

    public boolean updateStagedFacts(String artifactId, String writerOwnerId, Long fenceGeneration,
                                      String stagingPath, String verifiedSha256,
                                      long verifiedSize, String mediaType) {
        return jdbcTemplate.update("""
                update output_publications set
                    publication_state = 'STAGED', staging_path = ?,
                    verified_sha256 = ?, verified_size = ?, media_type = ?,
                    staged_at = ?, updated_at = ?
                where artifact_id = ? and publication_state = 'RESERVED'
                  and (publication_origin <> 'TASK_RUN'
                       or (writer_owner_id = ? and fence_generation = ?))
                """, stagingPath, verifiedSha256, verifiedSize, mediaType,
                Instant.now().toString(), Instant.now().toString(),
                artifactId, writerOwnerId, fenceGeneration) == 1;
    }

    public boolean updateStagingPath(String artifactId, String writerOwnerId, Long fenceGeneration,
                                      String stagingPath) {
        return jdbcTemplate.update("""
                update output_publications set staging_path = ?, updated_at = ?
                where artifact_id = ? and publication_state = 'RESERVED'
                  and (publication_origin <> 'TASK_RUN'
                       or (writer_owner_id = ? and fence_generation = ?))
                """, stagingPath, Instant.now().toString(), artifactId,
                writerOwnerId, fenceGeneration) == 1;
    }

    public boolean updatePublishedFacts(String artifactId, String verifiedSha256,
                                         long verifiedSize, String mediaType) {
        return jdbcTemplate.update("""
                update output_publications set
                    publication_state = 'PUBLISHED', verified_sha256 = ?,
                    verified_size = ?, media_type = ?, published_at = ?, updated_at = ?
                where artifact_id = ? and publication_state = 'STAGED'
                """, verifiedSha256, verifiedSize, mediaType,
                Instant.now().toString(), Instant.now().toString(), artifactId) == 1;
    }

    public boolean markIntegrityFailed(String artifactId, String failureDetail,
                                        String reconciliationState) {
        return jdbcTemplate.update("""
                update output_publications set
                    publication_state = 'INTEGRITY_FAILED', failure_detail = ?,
                    reconciliation_state = ?, updated_at = ?
                where artifact_id = ? and publication_state = 'PUBLISHED'
                """, failureDetail, reconciliationState,
                Instant.now().toString(), artifactId) == 1;
    }

    public boolean markFailed(String artifactId, String failureDetail,
                               String reconciliationState) {
        return jdbcTemplate.update("""
                update output_publications set
                    publication_state = 'FAILED', failure_detail = ?,
                    reconciliation_state = ?, updated_at = ?
                where artifact_id = ? and publication_state in ('RESERVED', 'STAGED')
                """, failureDetail, reconciliationState,
                Instant.now().toString(), artifactId) == 1;
    }

    public boolean setReconciliationState(String artifactId, String state) {
        return jdbcTemplate.update("""
                update output_publications set reconciliation_state = ?, updated_at = ?
                where artifact_id = ?
                """, state, Instant.now().toString(), artifactId) == 1;
    }

    // ── Query methods ──

    public Optional<PublicationRecord> findById(String artifactId) {
        if (!StringUtils.hasText(artifactId)) return Optional.empty();
        return jdbcTemplate.query(
                "select * from output_publications where artifact_id = ?",
                rs -> rs.next() ? Optional.of(toPublicationRecord(rs)) : Optional.empty(),
                artifactId);
    }

    public Optional<PublicationRecord> findByOperationKey(
            PublicationOrigin origin, String operationKey) {
        if (!StringUtils.hasText(operationKey)) return Optional.empty();
        return jdbcTemplate.query(
                "select * from output_publications where publication_origin = ? and operation_key = ?",
                rs -> rs.next() ? Optional.of(toPublicationRecord(rs)) : Optional.empty(),
                origin.name(), operationKey);
    }

    public Optional<PublicationRecord> findByTaskRunIdAndOperationKey(
            String taskRunId, String operationKey) {
        if (!StringUtils.hasText(taskRunId) || !StringUtils.hasText(operationKey))
            return Optional.empty();
        return jdbcTemplate.query(
                "select * from output_publications where task_run_id = ? and operation_key = ?",
                rs -> rs.next() ? Optional.of(toPublicationRecord(rs)) : Optional.empty(),
                taskRunId, operationKey);
    }

    public List<PublicationRecord> findByTaskRunId(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbcTemplate.query(
                "select * from output_publications where task_run_id = ? order by created_at desc",
                (rs, rowNum) -> toPublicationRecord(rs),
                taskRunId);
    }

    public List<PublicationRecord> findByWorkspace(String workspaceKind, String workspaceKey) {
        return jdbcTemplate.query(
                "select * from output_publications where workspace_kind = ? and workspace_key = ? order by created_at desc",
                (rs, rowNum) -> toPublicationRecord(rs),
                workspaceKind, workspaceKey);
    }

    public List<PublicationRecord> findByState(String publicationState, int limit) {
        return jdbcTemplate.query(
                "select * from output_publications where publication_state = ? order by updated_at limit ?",
                (rs, rowNum) -> toPublicationRecord(rs),
                publicationState, Math.max(1, Math.min(limit, 500)));
    }

    public List<PublicationRecord> findReconciliationCandidates(int limit) {
        return jdbcTemplate.query("""
                select * from output_publications
                where publication_state in ('RESERVED', 'STAGED', 'PUBLISHED', 'INTEGRITY_FAILED')
                order by updated_at
                limit ?
                """, (rs, rowNum) -> toPublicationRecord(rs),
                Math.max(1, Math.min(limit, 500)));
    }

    public List<PublicationRecord> findPublishedByWorkspace(
            String workspaceKind, String workspaceKey) {
        return jdbcTemplate.query(
                "select * from output_publications where workspace_kind = ? and workspace_key = ? and publication_state = 'PUBLISHED' order by created_at desc",
                (rs, rowNum) -> toPublicationRecord(rs),
                workspaceKind, workspaceKey);
    }

    public boolean existsByFinalPath(String workspaceKind, String workspaceKey,
                                      String rootRelativePath) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from output_publications where workspace_kind = ? and workspace_key = ? and root_relative_path = ?",
                Integer.class, workspaceKind, workspaceKey, rootRelativePath);
        return count != null && count > 0;
    }

    // ── Legacy run_output_artifacts compatibility ──

    public List<RunOutputArtifact> findArtifactsByRunId(String runId) {
        return jdbcTemplate.query(
                "select * from run_output_artifacts where run_id = ? order by output_name",
                (rs, rowNum) -> toArtifact(rs),
                runId);
    }

    public Optional<RunOutputArtifact> findArtifactById(String artifactId) {
        if (!StringUtils.hasText(artifactId)) return Optional.empty();
        return jdbcTemplate.query(
                "select * from run_output_artifacts where id = ?",
                rs -> rs.next() ? Optional.of(toArtifact(rs)) : Optional.empty(),
                artifactId);
    }

    public List<RunOutputArtifact> findArtifactsByPlanId(String planId) {
        return jdbcTemplate.query(
                "select * from run_output_artifacts where plan_id = ? order by created_at desc",
                (rs, rowNum) -> toArtifact(rs),
                planId);
    }

    public List<RunOutputArtifact> findArtifactsByProjectId(String projectId, int limit) {
        return jdbcTemplate.query(
                "select * from run_output_artifacts where project_id = ? order by created_at desc limit ?",
                (rs, rowNum) -> toArtifact(rs),
                projectId, Math.max(1, Math.min(limit, 500)));
    }

    public List<RunOutputArtifact> findArtifacts(OutputArtifactQuery query) {
        OutputArtifactQuery effectiveQuery = query == null
                ? OutputArtifactQuery.of(null, null, null, null, null, null, null, null, null, null, null, null, 50)
                : query;
        StringBuilder sql = new StringBuilder("select * from run_output_artifacts where 1 = 1");
        List<Object> args = new ArrayList<>();
        appendIfText(sql, args, "agent_id", effectiveQuery.agentId());
        appendIfText(sql, args, "job_id", effectiveQuery.jobId());
        appendIfText(sql, args, "job_assignment_id", effectiveQuery.jobAssignmentId());
        appendIfText(sql, args, "job_run_id", effectiveQuery.jobRunId());
        appendIfText(sql, args, "project_id", effectiveQuery.projectId());
        appendIfText(sql, args, "workspace_type", effectiveQuery.workspaceType());
        appendIfText(sql, args, "workspace_key", effectiveQuery.workspaceKey());
        appendIfText(sql, args, "workspace_identity", effectiveQuery.workspaceIdentity());
        appendIfText(sql, args, "run_id", effectiveQuery.runId());
        appendIfText(sql, args, "plan_id", effectiveQuery.planId());
        appendIfText(sql, args, "run_type", effectiveQuery.runType());
        appendIfText(sql, args, "artifact_type", effectiveQuery.artifactType());
        sql.append(" order by created_at desc limit ?");
        args.add(effectiveQuery.limit());
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toArtifact(rs), args.toArray());
    }

    public int backfillArtifactAttribution(String runId, OutputArtifactContext context) {
        if (!StringUtils.hasText(runId) || context == null || context.isEmpty()) return 0;
        return jdbcTemplate.update("""
                update run_output_artifacts
                set agent_id = coalesce(agent_id, ?), job_id = coalesce(job_id, ?),
                    job_assignment_id = coalesce(job_assignment_id, ?), job_run_id = coalesce(job_run_id, ?),
                    project_id = coalesce(project_id, ?), workspace_type = coalesce(workspace_type, ?),
                    workspace_key = coalesce(workspace_key, ?), workspace_identity = coalesce(workspace_identity, ?),
                    run_type = coalesce(run_type, ?)
                where run_id = ?
                """, context.agentId(), context.jobId(), context.jobAssignmentId(), context.jobRunId(),
                context.projectId(), context.workspaceType(), context.workspaceKey(),
                context.workspaceIdentity(), context.runType(), runId);
    }

    public void deleteArtifactsByWorkspaceIdentity(String workspaceIdentity) {
        if (!StringUtils.hasText(workspaceIdentity)) return;
        jdbcTemplate.update(
                "delete from run_output_artifacts where workspace_identity = ?", workspaceIdentity);
    }

    /** Legacy-compatible save (used by callers still using the old record). */
    public RunOutputArtifact saveArtifact(RunOutputArtifact artifact) {
        Instant now = Instant.now();
        Instant createdAt = artifact.createdAt() == null ? now : artifact.createdAt();
        jdbcTemplate.update("""
                insert into run_output_artifacts (
                    id, run_id, plan_id, agent_id, job_id, job_assignment_id, job_run_id,
                    project_id, workspace_type, workspace_key, workspace_identity, run_type,
                    output_name, artifact_type, file_name, file_path, content_json, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    agent_id = excluded.agent_id, job_id = excluded.job_id,
                    job_assignment_id = excluded.job_assignment_id, job_run_id = excluded.job_run_id,
                    project_id = excluded.project_id, workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key, workspace_identity = excluded.workspace_identity,
                    run_type = excluded.run_type, output_name = excluded.output_name,
                    artifact_type = excluded.artifact_type, file_name = excluded.file_name,
                    file_path = excluded.file_path, content_json = excluded.content_json
                """,
                artifact.id(), artifact.runId(), artifact.planId(), artifact.agentId(),
                artifact.jobId(), artifact.jobAssignmentId(), artifact.jobRunId(),
                artifact.projectId(), artifact.workspaceType(), artifact.workspaceKey(),
                artifact.workspaceIdentity(), artifact.runType(), artifact.outputName(),
                artifact.artifactType(), artifact.fileName(), artifact.filePath(),
                artifact.contentJson(), createdAt.toString());
        return artifact;
    }

    // ── Row mapping ──

    private PublicationRecord toPublicationRecord(ResultSet rs) throws SQLException {
        return new PublicationRecord(
                rs.getString("artifact_id"),
                PublicationOrigin.valueOf(rs.getString("publication_origin")),
                rs.getString("workspace_kind"),
                rs.getString("workspace_key"),
                rs.getString("workspace_identity"),
                getNullable(rs, "task_run_id"),
                getNullable(rs, "task_path_key"),
                getNullable(rs, "native_run_id"),
                rs.getObject("attempt_number") != null ? rs.getInt("attempt_number") : null,
                rs.getString("root_relative_path"),
                getNullable(rs, "staging_path"),
                rs.getString("operation_key"),
                rs.getString("request_digest"),
                rs.getString("content_sha256"),
                PublicationState.valueOf(rs.getString("publication_state")),
                getNullable(rs, "reconciliation_state"),
                getNullable(rs, "failure_detail"),
                rs.getString("expected_sha256"),
                getNullable(rs, "verified_sha256"),
                rs.getObject("verified_size") != null ? rs.getLong("verified_size") : null,
                getNullable(rs, "media_type"),
                getNullable(rs, "writer_owner_id"),
                rs.getObject("fence_generation") != null ? rs.getLong("fence_generation") : null,
                rs.getString("logical_name"),
                getNullable(rs, "file_extension"),
                instant(rs.getString("created_at")),
                instant(rs.getString("updated_at")),
                instant(getNullable(rs, "staged_at")),
                instant(getNullable(rs, "published_at")));
    }

    private RunOutputArtifact toArtifact(ResultSet rs) throws SQLException {
        return new RunOutputArtifact(
                rs.getString("id"), rs.getString("run_id"), rs.getString("plan_id"),
                getNullable(rs, "agent_id"), getNullable(rs, "job_id"),
                getNullable(rs, "job_assignment_id"), getNullable(rs, "job_run_id"),
                rs.getString("project_id"), getNullable(rs, "workspace_type"),
                getNullable(rs, "workspace_key"), getNullable(rs, "workspace_identity"),
                rs.getString("run_type"), rs.getString("output_name"),
                rs.getString("artifact_type"), rs.getString("file_name"),
                rs.getString("file_path"), rs.getString("content_json"),
                instant(rs.getString("created_at")));
    }

    // ── Helpers ──

    private static void appendIfText(StringBuilder sql, List<Object> args,
                                      String column, String value) {
        if (StringUtils.hasText(value)) {
            sql.append(" and ").append(column).append(" = ?");
            args.add(value);
        }
    }

    private static String getNullable(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // Schema ownership belongs exclusively to SchemaMigrationRunner.
}
