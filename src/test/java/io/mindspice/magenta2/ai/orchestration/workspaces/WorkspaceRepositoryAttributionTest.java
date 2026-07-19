package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRepositoryAttributionTest {

    @Test
    void ensureSchemaAddsAttributionColumnsForLegacyTable() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("""
            create table run_output_artifacts (
                id text primary key,
                run_id text not null,
                plan_id text not null,
                output_name text not null,
                artifact_type text not null,
                file_name text not null,
                file_path text not null,
                content_json text,
                created_at text not null
            )
            """);

        new OutputArtifactRepository(jdbc);

        Set<String> columns = jdbc.queryForList("select name from pragma_table_info('run_output_artifacts')", String.class)
            .stream()
            .collect(Collectors.toSet());
        assertThat(columns).contains(
            "agent_id", "job_id", "job_assignment_id", "job_run_id", "project_id", "workspace_type", "workspace_key", "workspace_identity", "run_type");
    }

    @Test
    void findArtifactsFiltersByAttributionFields() {
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc());
        RunOutputArtifact artifact = repository.saveArtifact(new RunOutputArtifact(
            "artifact-1",
            "run-1",
            "plan-1",
            "agent-1",
            "job-1",
            "assignment-1",
            "job-run-1",
            "project-1",
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            "summary",
            "text",
            "summary.txt",
            "/tmp/summary.txt",
            null,
            Instant.now()
        ));

        assertThat(repository.findArtifacts(OutputArtifactQuery.of("agent-1", null, null, null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, "job-1", null, null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, "assignment-1", null, null, null, null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, null, "job-run-1", null, null, null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, "project-1", null, null, null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, null, null, null, "agent", "agent-1", "agent/agent-1", null, null, null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, null, null, null, null, null, null, null, "plan-1", null, null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of(null, null, null, null, null, null, null, null, null, null, "TASK_RUN", null, 10)))
            .extracting(RunOutputArtifact::id)
            .containsExactly(artifact.id());
        assertThat(repository.findArtifacts(OutputArtifactQuery.of("missing", null, null, null, null, null, null, null, null, 10)))
            .isEmpty();
    }

    @Test
    void backfillAttributionSetsOnlyMissingValues() {
        OutputArtifactRepository repository = new OutputArtifactRepository(jdbc());
        repository.saveArtifact(new RunOutputArtifact(
            "artifact-1",
            "run-1",
            "plan-1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "summary",
            "text",
            "summary.txt",
            "/tmp/summary.txt",
            null,
            Instant.now()
        ));

        int updated = repository.backfillArtifactAttribution(
            "run-1",
            new OutputArtifactContext("agent-1", "job-1", "assignment-1", "job-run-1", "project-1", null, null, "workspace-1", "TASK_RUN")
        );
        assertThat(updated).isEqualTo(1);

        RunOutputArtifact artifact = repository.findArtifactsByRunId("run-1").get(0);
        assertThat(artifact.agentId()).isEqualTo("agent-1");
        assertThat(artifact.jobId()).isEqualTo("job-1");
        assertThat(artifact.jobAssignmentId()).isEqualTo("assignment-1");
        assertThat(artifact.jobRunId()).isEqualTo("job-run-1");
        assertThat(artifact.projectId()).isEqualTo("project-1");
        assertThat(artifact.workspaceIdentity()).isEqualTo("workspace-1");
        assertThat(artifact.runType()).isEqualTo("TASK_RUN");
    }

    @Test
    void findAllFiltersByOwnerTypeAndOwnerId() {
        WorkspaceRepository repository = new WorkspaceRepository(jdbc());
        repository.save(new Workspace(
            "ws-1",
            WorkspaceOwnerType.AGENT,
            "agent-1",
            "agents/agent-1",
            "Agent 1",
            "{}",
            Instant.now().minus(5, ChronoUnit.MINUTES),
            Instant.now().minus(4, ChronoUnit.MINUTES)
        ));
        repository.save(new Workspace(
            "ws-2",
            WorkspaceOwnerType.AGENT,
            "agent-2",
            "agents/agent-2",
            "Agent 2",
            "{}",
            Instant.now().minus(3, ChronoUnit.MINUTES),
            Instant.now().minus(2, ChronoUnit.MINUTES)
        ));
        repository.save(new Workspace(
            "ws-3",
            WorkspaceOwnerType.JOB,
            "job-1",
            "jobs/job-1",
            "Job 1",
            "{}",
            Instant.now().minus(1, ChronoUnit.MINUTES),
            Instant.now()
        ));

        assertThat(repository.findAll(WorkspaceOwnerType.AGENT, null, 10))
            .extracting(Workspace::id)
            .containsExactly("ws-2", "ws-1");
        assertThat(repository.findAll(WorkspaceOwnerType.AGENT, "agent-1", 10))
            .extracting(Workspace::id)
            .containsExactly("ws-1");
        assertThat(repository.findAll(null, null, 2)).hasSize(2);
    }

    @Test
    void findActiveLeasesByWorkspaceReturnsOnlyUnreleasedLeases() {
        WorkspaceRepository repository = new WorkspaceRepository(jdbc());
        Instant now = Instant.now();

        repository.save(new Workspace(
            "ws-1",
            WorkspaceOwnerType.AGENT,
            "agent-1",
            "agents/agent-1",
            "Agent 1",
            "{}",
            now.minusSeconds(40),
            now.minusSeconds(40)
        ));
        repository.save(new Workspace(
            "ws-2",
            WorkspaceOwnerType.AGENT,
            "agent-2",
            "agents/agent-2",
            "Agent 2",
            "{}",
            now.minusSeconds(40),
            now.minusSeconds(40)
        ));
        repository.saveLease(new WorkspaceLease(
            "lease-active",
            "ws-1",
            "TASK_RUN",
            "run-1",
            LeaseMode.READ,
            now.plusSeconds(60),
            false,
            null,
            now.minusSeconds(10),
            now.minusSeconds(10)
        ));
        repository.saveLease(new WorkspaceLease(
            "lease-released",
            "ws-1",
            "TASK_RUN",
            "run-2",
            LeaseMode.WRITE,
            now.plusSeconds(120),
            false,
            now.minusSeconds(5),
            now.minusSeconds(20),
            now.minusSeconds(5)
        ));
        repository.saveLease(new WorkspaceLease(
            "lease-other-workspace",
            "ws-2",
            "TASK_RUN",
            "run-3",
            LeaseMode.READ,
            now.plusSeconds(60),
            false,
            null,
            now.minusSeconds(30),
            now.minusSeconds(30)
        ));

        assertThat(repository.findActiveLeases("ws-1"))
            .extracting(WorkspaceLease::id)
            .containsExactly("lease-active");
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }
}
