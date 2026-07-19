package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Owns all {@code run_output_artifacts} schema, queries, writes, deletion,
 * and row mapping independently of generic workspace persistence.
 */
@Repository
public class OutputArtifactRepository {
    private final JdbcTemplate jdbcTemplate;

    public OutputArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public RunOutputArtifact saveArtifact(RunOutputArtifact artifact) {
        Instant now = Instant.now();
        Instant createdAt = artifact.createdAt() == null ? now : artifact.createdAt();
        jdbcTemplate.update(
            """
                insert into run_output_artifacts (
                    id, run_id, plan_id, agent_id, job_id, job_assignment_id, job_run_id,
                    project_id, workspace_type, workspace_key, workspace_identity, run_type,
                    output_name, artifact_type,
                    file_name, file_path, content_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    agent_id = excluded.agent_id,
                    job_id = excluded.job_id,
                    job_assignment_id = excluded.job_assignment_id,
                    job_run_id = excluded.job_run_id,
                    project_id = excluded.project_id,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    run_type = excluded.run_type,
                    output_name = excluded.output_name,
                    artifact_type = excluded.artifact_type,
                    file_name = excluded.file_name,
                    file_path = excluded.file_path,
                    content_json = excluded.content_json
                """,
            artifact.id(),
            artifact.runId(),
            artifact.planId(),
            artifact.agentId(),
            artifact.jobId(),
            artifact.jobAssignmentId(),
            artifact.jobRunId(),
            artifact.projectId(),
            artifact.workspaceType(),
            artifact.workspaceKey(),
            artifact.workspaceIdentity(),
            artifact.runType(),
            artifact.outputName(),
            artifact.artifactType(),
            artifact.fileName(),
            artifact.filePath(),
            artifact.contentJson(),
            createdAt.toString()
        );
        return artifact;
    }

    public List<RunOutputArtifact> findArtifactsByRunId(String runId) {
        return jdbcTemplate.query(
            "select * from run_output_artifacts where run_id = ? order by output_name",
            (rs, rowNum) -> toArtifact(rs),
            runId
        );
    }

    public Optional<RunOutputArtifact> findArtifactById(String artifactId) {
        if (!StringUtils.hasText(artifactId)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from run_output_artifacts where id = ?",
            rs -> rs.next() ? Optional.of(toArtifact(rs)) : Optional.empty(),
            artifactId
        );
    }

    public List<RunOutputArtifact> findArtifactsByPlanId(String planId) {
        return jdbcTemplate.query(
            "select * from run_output_artifacts where plan_id = ? order by created_at desc",
            (rs, rowNum) -> toArtifact(rs),
            planId
        );
    }

    public List<RunOutputArtifact> findArtifacts(OutputArtifactQuery query) {
        OutputArtifactQuery effectiveQuery = query == null
            ? OutputArtifactQuery.of(null, null, null, null, null, null, null, null, null, null, null, null, 50)
            : query;
        StringBuilder sql = new StringBuilder("select * from run_output_artifacts where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(effectiveQuery.agentId())) {
            sql.append(" and agent_id = ?");
            args.add(effectiveQuery.agentId());
        }
        if (StringUtils.hasText(effectiveQuery.jobId())) {
            sql.append(" and job_id = ?");
            args.add(effectiveQuery.jobId());
        }
        if (StringUtils.hasText(effectiveQuery.jobAssignmentId())) {
            sql.append(" and job_assignment_id = ?");
            args.add(effectiveQuery.jobAssignmentId());
        }
        if (StringUtils.hasText(effectiveQuery.jobRunId())) {
            sql.append(" and job_run_id = ?");
            args.add(effectiveQuery.jobRunId());
        }
        if (StringUtils.hasText(effectiveQuery.projectId())) {
            sql.append(" and project_id = ?");
            args.add(effectiveQuery.projectId());
        }
        if (StringUtils.hasText(effectiveQuery.workspaceType())) {
            sql.append(" and workspace_type = ?");
            args.add(effectiveQuery.workspaceType());
        }
        if (StringUtils.hasText(effectiveQuery.workspaceKey())) {
            sql.append(" and workspace_key = ?");
            args.add(effectiveQuery.workspaceKey());
        }
        if (StringUtils.hasText(effectiveQuery.workspaceIdentity())) {
            sql.append(" and workspace_identity = ?");
            args.add(effectiveQuery.workspaceIdentity());
        }
        if (StringUtils.hasText(effectiveQuery.runId())) {
            sql.append(" and run_id = ?");
            args.add(effectiveQuery.runId());
        }
        if (StringUtils.hasText(effectiveQuery.planId())) {
            sql.append(" and plan_id = ?");
            args.add(effectiveQuery.planId());
        }
        if (StringUtils.hasText(effectiveQuery.runType())) {
            sql.append(" and run_type = ?");
            args.add(effectiveQuery.runType());
        }
        if (StringUtils.hasText(effectiveQuery.artifactType())) {
            sql.append(" and artifact_type = ?");
            args.add(effectiveQuery.artifactType());
        }
        sql.append(" order by created_at desc limit ?");
        args.add(effectiveQuery.limit());
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toArtifact(rs), args.toArray());
    }

    public int backfillArtifactAttribution(String runId, OutputArtifactContext context) {
        if (!StringUtils.hasText(runId) || context == null || context.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
            """
                update run_output_artifacts
                set
                    agent_id = coalesce(agent_id, ?),
                    job_id = coalesce(job_id, ?),
                    job_assignment_id = coalesce(job_assignment_id, ?),
                    job_run_id = coalesce(job_run_id, ?),
                    project_id = coalesce(project_id, ?),
                    workspace_type = coalesce(workspace_type, ?),
                    workspace_key = coalesce(workspace_key, ?),
                    workspace_identity = coalesce(workspace_identity, ?),
                    run_type = coalesce(run_type, ?)
                where run_id = ?
                """,
            context.agentId(),
            context.jobId(),
            context.jobAssignmentId(),
            context.jobRunId(),
            context.projectId(),
            context.workspaceType(),
            context.workspaceKey(),
            context.workspaceIdentity(),
            context.runType(),
            runId
        );
    }

    public void deleteArtifactsByWorkspaceIdentity(String workspaceIdentity) {
        if (!StringUtils.hasText(workspaceIdentity)) return;
        jdbcTemplate.update(
            "delete from run_output_artifacts where workspace_identity = ?",
            workspaceIdentity
        );
    }

    // ── Row mapping ──

    private RunOutputArtifact toArtifact(ResultSet rs) throws SQLException {
        return new RunOutputArtifact(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("plan_id"),
            rs.getString("agent_id"),
            rs.getString("job_id"),
            getNullable(rs, "job_assignment_id"),
            getNullable(rs, "job_run_id"),
            rs.getString("project_id"),
            getNullable(rs, "workspace_type"),
            getNullable(rs, "workspace_key"),
            getNullable(rs, "workspace_identity"),
            rs.getString("run_type"),
            rs.getString("output_name"),
            rs.getString("artifact_type"),
            rs.getString("file_name"),
            rs.getString("file_path"),
            rs.getString("content_json"),
            instant(rs.getString("created_at"))
        );
    }

    private String getNullable(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // ── Schema ──

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists run_output_artifacts (
                id text primary key,
                run_id text not null,
                plan_id text not null,
                agent_id text,
                job_id text,
                job_assignment_id text,
                job_run_id text,
                project_id text,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                run_type text,
                output_name text not null,
                artifact_type text not null,
                file_name text not null,
                file_path text not null,
                content_json text,
                created_at text not null
            )
            """);
        addColumnIfMissing("run_output_artifacts", "workspace_type",
            "alter table run_output_artifacts add column workspace_type text");
        addColumnIfMissing("run_output_artifacts", "workspace_key",
            "alter table run_output_artifacts add column workspace_key text");
        addColumnIfMissing("run_output_artifacts", "workspace_identity",
            "alter table run_output_artifacts add column workspace_identity text");
        addColumnIfMissing("run_output_artifacts", "agent_id",
            "alter table run_output_artifacts add column agent_id text");
        addColumnIfMissing("run_output_artifacts", "job_id",
            "alter table run_output_artifacts add column job_id text");
        addColumnIfMissing("run_output_artifacts", "job_assignment_id",
            "alter table run_output_artifacts add column job_assignment_id text");
        addColumnIfMissing("run_output_artifacts", "job_run_id",
            "alter table run_output_artifacts add column job_run_id text");
        addColumnIfMissing("run_output_artifacts", "project_id",
            "alter table run_output_artifacts add column project_id text");
        addColumnIfMissing("run_output_artifacts", "run_type",
            "alter table run_output_artifacts add column run_type text");

        jdbcTemplate.execute("""
            create index if not exists idx_run_output_artifacts_run
                on run_output_artifacts(run_id)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_run_output_artifacts_agent
                on run_output_artifacts(agent_id)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_run_output_artifacts_job
                on run_output_artifacts(job_id)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_run_output_artifacts_project
                on run_output_artifacts(project_id)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_run_output_artifacts_workspace_identity
                on run_output_artifacts(workspace_identity)
            """);
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (!table.matches("[a-zA-Z0-9_]+") || !column.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table/column identifier");
        }
        if (!columnExists(table, column)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private boolean columnExists(String table, String column) {
        if (!table.matches("[a-zA-Z0-9_]+") || !column.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table/column identifier");
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from pragma_table_info(?) where name = ?",
            Integer.class,
            table,
            column
        );
        return count != null && count > 0;
    }
}
