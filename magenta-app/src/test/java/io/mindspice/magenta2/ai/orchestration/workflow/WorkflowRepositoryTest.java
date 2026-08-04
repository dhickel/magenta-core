package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowRepositoryTest {

    @Test
    void migratesLegacyStepWorkflowDefinitionsToGraphColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
        );
        jdbcTemplate.execute("""
            create table workflow_definitions (
                id text primary key,
                title text not null,
                summary text,
                steps_json text not null,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.update("""
                insert into workflow_definitions (id, title, summary, steps_json, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                """,
            "legacy-workflow", "Legacy Workflow", "old shape", "[]",
            "2026-05-14T00:00:00Z", "2026-05-14T00:00:00Z");

        WorkflowRepository repository = new WorkflowRepository(jdbcTemplate, new ObjectMapper());

        WorkflowDefinition migrated = repository.findDefinition("legacy-workflow").orElseThrow();
        assertThat(migrated.title()).isEqualTo("Legacy Workflow");
        assertThat(migrated.nodes()).isEmpty();
        assertThat(migrated.routes()).isEmpty();
    }

    @Test
    void migratesLegacyWorkflowRunsToGraphRunColumns() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
        );
        jdbcTemplate.execute("""
            create table workflow_definitions (
                id text primary key,
                schema_version integer not null default 2,
                title text not null,
                summary text,
                max_concurrency integer not null default 4,
                nodes_json text not null,
                routes_json text not null default '[]',
                ui_layout_json text not null default '{}',
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create table workflow_runs (
                id text primary key,
                workflow_id text not null,
                status text not null,
                workflow_snapshot_json text,
                step_runs_json text,
                final_output_values_json text,
                final_message text,
                error_text text,
                created_at text not null,
                started_at text,
                completed_at text
            )
            """);
        jdbcTemplate.update("""
                insert into workflow_definitions (
                    id, title, summary, nodes_json, routes_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
            "legacy-workflow", "Legacy Workflow", "old shape", "[]", "[]",
            "2026-05-14T00:00:00Z", "2026-05-14T00:00:00Z");
        jdbcTemplate.update("""
                insert into workflow_runs (
                    id, workflow_id, status, workflow_snapshot_json, step_runs_json,
                    final_output_values_json, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
            "legacy-run", "legacy-workflow", "COMPLETED", null, "[]", "{}",
            "2026-05-14T00:00:00Z");

        WorkflowRepository repository = new WorkflowRepository(jdbcTemplate, new ObjectMapper());

        WorkflowRun migrated = repository.findRunsByWorkflowId("legacy-workflow").getFirst();
        assertThat(migrated.id()).isEqualTo("legacy-run");
        assertThat(migrated.currentNodeIndex()).isZero();
        assertThat(migrated.nodeRuns()).isEmpty();
        assertThat(migrated.updatedAt()).isEqualTo(migrated.createdAt());
        assertThat(columns(jdbcTemplate, "workflow_runs")).contains(
            "agent_id", "job_id", "job_assignment_id", "job_run_id", "project_id",
            "workspace_type", "workspace_key", "workspace_identity", "run_type");
        assertThat(columns(jdbcTemplate, "workflow_runs")).doesNotContain("workspace_id");
    }

    @Test
    void persistsWorkflowRunAttribution() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
            new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
        );
        WorkflowRepository repository = new WorkflowRepository(jdbcTemplate, new ObjectMapper());
        WorkflowDefinition definition = repository.saveDefinition(new WorkflowDefinition(
            "workflow-1", 2, "Workflow", "", 1, java.util.List.of(), java.util.List.of(), java.util.Map.of(), null, null
        ));

        repository.saveRun(new WorkflowRun(
            "run-1",
            definition.id(),
            "Workflow display run",
            WorkflowRunStatus.COMPLETED,
            0,
            java.util.List.of(),
            "agent-1",
            "job-1",
            "assignment-1",
            "job-run-1",
            "project-1",
            "agent",
            "agent-1",
            null,
            "JOB_WORKFLOW_ITEM",
            definition,
            java.util.Map.of("result", "ok"),
            java.util.List.of("artifact-1"),
            "done",
            null,
            null,
            null,
            null,
            null
        ));

        WorkflowRun run = repository.findRun("run-1").orElseThrow();
        assertThat(run.runDisplayName()).isEqualTo("Workflow display run");
        assertThat(run.agentId()).isEqualTo("agent-1");
        assertThat(run.jobId()).isEqualTo("job-1");
        assertThat(run.jobAssignmentId()).isEqualTo("assignment-1");
        assertThat(run.jobRunId()).isEqualTo("job-run-1");
        assertThat(run.projectId()).isEqualTo("project-1");
        assertThat(run.workspaceType()).isEqualTo("agent");
        assertThat(run.workspaceKey()).isEqualTo("agent-1");
        assertThat(run.workspaceIdentity()).isEqualTo("agent/agent-1");
        assertThat(run.runType()).isEqualTo("JOB_WORKFLOW_ITEM");
    }

    private java.util.List<String> columns(JdbcTemplate jdbcTemplate, String table) {
        return jdbcTemplate.queryForList("select name from pragma_table_info('" + table + "')", String.class);
    }
}
