package io.mindspice.magenta2.ai.orchestration.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest {

    @Test
    void savesTypedWorkspaceDefinition() {
        JobRepository repo = repository();
        repo.saveDefinition(new JobDefinition(
            "job-1", "agent-1", "project-1", "agent", "agent-1", null, "ACTIVE",
            "Test Job", "Summary", List.of(planItem("step1", "plan-1", 0)),
            null, null, null, 1, Instant.now(), Instant.now()));

        JobDefinition found = repo.findDefinition("job-1").orElseThrow();
        assertThat(found.workspaceType()).isEqualTo("agent");
        assertThat(found.workspaceKey()).isEqualTo("agent-1");
        assertThat(found.workspaceIdentity()).isEqualTo("agent/agent-1");
        assertThat(found.items()).hasSize(1);
    }

    @Test
    void derivesAgentSelectionForExistingDefinitionWithoutTypedColumns() {
        JdbcTemplate jdbc = jdbc();
        jdbc.execute("""
            create table job_definitions (
              id text primary key, owner_agent_id text, project_id text, status text, title text not null,
              summary text, items_json text not null, prompt_profile text, model text,
              settings_override_json text, created_at text not null, updated_at text not null)
            """);
        jdbc.update("insert into job_definitions values (?, ?, null, 'ACTIVE', 'Legacy', null, '[]', null, null, null, ?, ?)",
            "legacy", "agent-legacy", Instant.now().toString(), Instant.now().toString());

        JobDefinition found = new JobRepository(jdbc, mapper()).findDefinition("legacy").orElseThrow();
        assertThat(found.workspaceIdentity()).isEqualTo("agent/agent-legacy");
        assertThat(columns(jdbc, "job_definitions")).doesNotContain("workspace_id", "persistent_workspace_enabled");
    }

    @Test
    void savesTypedWorkspaceRunWithoutHostPaths() {
        JobRepository repo = repository();
        repo.saveDefinition(jobDef("job-2", "Run Job"));
        JobRun run = new JobRun(
            "run-1", "job-2", "assignment-1", "user", "shared", "user/shared",
            JobRunStatus.QUEUED, List.of(), null, null, Instant.now(), Instant.now(), null, null);
        repo.saveRun(run);

        JobRun found = repo.findRun("run-1").orElseThrow();
        assertThat(found.workspaceType()).isEqualTo("user");
        assertThat(found.workspaceKey()).isEqualTo("shared");
        assertThat(found.workspaceIdentity()).isEqualTo("user/shared");
        assertThat(repo.findRunByAssignmentId("assignment-1")).contains(found);
    }

    @Test
    void definitionCasRejectsStaleWriter() {
        JobRepository repo = repository();
        JobDefinition saved = repo.saveDefinition(jobDef("job-cas", "Initial"));
        JobDefinition candidate = new JobDefinition(saved.id(), "agent-2", saved.projectId(),
            saved.workspaceType(), saved.workspaceKey(), null, saved.status(), saved.title(), saved.summary(),
            saved.items(), saved.promptProfile(), saved.model(), saved.settingsOverrideJson(), 0,
            saved.createdAt(), saved.updatedAt());

        JobDefinition updated = repo.saveDefinitionCAS(candidate, saved.definitionVersion()).orElseThrow();
        assertThat(updated.ownerAgentId()).isEqualTo("agent-2");
        assertThat(updated.definitionVersion()).isEqualTo(saved.definitionVersion() + 1);
        assertThat(repo.saveDefinitionCAS(candidate, saved.definitionVersion())).isEmpty();
    }

    @Test
    void oneJobRunPerAssignmentConvergesOnExistingRun() {
        JobRepository repo = repository();
        repo.saveDefinition(jobDef("job-link", "Linked"));
        JobRun first = repo.saveRun(new JobRun("run-first", "job-link", "assignment-link", "agent", "agent-1", null,
            JobRunStatus.QUEUED, List.of(), null, null, Instant.now(), Instant.now(), null, null));
        JobRun replay = repo.saveRun(new JobRun("run-second", "job-link", "assignment-link", "agent", "agent-1", null,
            JobRunStatus.QUEUED, List.of(), null, null, Instant.now(), Instant.now(), null, null));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(repo.findRunsByJobId("job-link")).hasSize(1);
    }

    @Test
    void deletesDefinitionAndRuns() {
        JobRepository repo = repository();
        repo.saveDefinition(jobDef("job-3", "Delete"));
        repo.saveRun(new JobRun("run-3", "job-3", null, "agent", "agent-1", null,
            JobRunStatus.QUEUED, List.of(), null, null, Instant.now(), Instant.now(), null, null));
        repo.deleteDefinition("job-3");
        assertThat(repo.findDefinition("job-3")).isEmpty();
        assertThat(repo.findRun("run-3")).isEmpty();
    }

    @Test
    void savesAndFindsRecurrence() {
        JobRepository repo = repository();
        repo.saveDefinition(jobDef("job-4", "Recurring"));
        Instant due = Instant.now().minusSeconds(60);
        repo.saveRecurrence(new JobRecurrence("rec-1", "job-4", "0 9 * * *", "UTC", due, true,
            Instant.now(), Instant.now()));
        assertThat(repo.findDueRecurrences(Instant.now())).extracting(JobRecurrence::jobId).contains("job-4");
    }

    private JobDefinition jobDef(String id, String title) {
        return new JobDefinition(id, "agent-1", null, "agent", "agent-1", null, "ACTIVE",
            title, "Summary", List.of(planItem("step", "plan", 0)), null, null, null, 1,
            Instant.now(), Instant.now());
    }

    private JobWorkItem planItem(String key, String planId, int order) {
        return new JobWorkItem(key, JobWorkItemType.PLAN, planId, null, Map.of(), order, null, null);
    }

    private JobRepository repository() {
        return new JobRepository(jdbc(), mapper());
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private List<String> columns(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("select name from pragma_table_info('" + table + "')", String.class);
    }
}
