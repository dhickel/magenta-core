package io.mindspice.magenta2.ai.orchestration.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobServiceTest {
    @TempDir Path tempDir;
    private JobService service;
    private JobRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(
            Files.createDirectories(tempDir.resolve("data")));
        repository = new JobRepository(
            new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)),
            new ObjectMapper().registerModule(new JavaTimeModule()));
        service = new JobService(repository, directories, null, null);
    }

    @Test
    void savesDefinitionWithTypedSelection() {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of(item("one"))));
        assertThat(saved.id()).isNotBlank();
        assertThat(saved.workspaceIdentity()).isEqualTo("agent/agent-1");
    }

    @Test
    void rejectsIncompleteSelection() {
        assertThatThrownBy(() -> new JobDefinition(
            null, "agent-1", null, "agent", null, null, "DRAFT", "Bad", null,
            List.of(), null, null, null, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("supplied together");
    }

    @Test
    void directRunRequiresAssignment() {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of()));
        assertThatThrownBy(() -> service.startRun(saved.id()))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runSnapshotsTypedSelectionWithoutCreatingOutputTree() throws Exception {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of(item("one"))));
        JobRun run = service.startRun(saved.id(), "agent-1", null, "assignment-1");
        assertThat(run.workspaceType()).isEqualTo("agent");
        assertThat(run.workspaceKey()).isEqualTo("agent-1");
        assertThat(run.workspaceIdentity()).isEqualTo("agent/agent-1");
        assertThat(run.workItemRuns()).hasSize(1);
        try (var paths = Files.walk(tempDir.resolve("data"))) {
            assertThat(paths.map(Path::toString)).noneMatch(path -> path.endsWith("outputs"));
        }
    }

    @Test
    void runUsesExactContextSelection() {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of()));
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "Agent",
            saved.id(),
            null,
            null,
            null,
            "user",
            "shared",
            "user/shared",
            "JOB_RUN",
            null,
            null,
            null, null, null, null, null));
        try {
            JobRun run = service.startRun(saved.id(), "agent-1", null, "assignment-user");
            assertThat(run.workspaceIdentity()).isEqualTo("user/shared");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void reusesRunForAssignment() {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of()));
        JobRun first = service.startRun(saved.id(), "agent-1", null, "assignment-1");
        assertThat(service.startRun(saved.id(), "agent-1", null, "assignment-1").id()).isEqualTo(first.id());
    }

    @Test
    void workItemsDriveProgressAndCompletion() {
        JobDefinition saved = service.saveDefinition(def(null, "Job", List.of(item("one"), item("two"))));
        JobRun run = service.markRunning(service.startRun(saved.id(), "agent-1", null, "assignment-1").id());
        run = service.updateWorkItemRun(run.id(), "one", "COMPLETED", "run-1", Map.of(), null);
        assertThat(run.progress()).isEqualTo(0.5);
        run = service.updateWorkItemRun(run.id(), "two", "COMPLETED", "run-2", Map.of(), null);
        assertThat(run.status()).isEqualTo(JobRunStatus.COMPLETED);
    }

    @Test
    void emptyRunCanComplete() {
        JobDefinition saved = service.saveDefinition(def(null, "Empty", List.of()));
        JobRun run = service.markRunning(service.startRun(saved.id(), "agent-1", null, "assignment-1").id());
        assertThat(service.completeRun(run.id(), "done").status()).isEqualTo(JobRunStatus.COMPLETED);
    }

    @Test
    void cancellationIsTerminal() {
        JobDefinition saved = service.saveDefinition(def(null, "Cancel", List.of()));
        JobRun run = service.markRunning(service.startRun(saved.id(), "agent-1", null, "assignment-1").id());
        assertThat(service.cancelRun(run.id()).status()).isEqualTo(JobRunStatus.CANCELLED);
    }

    @Test
    void recurrencePersists() {
        JobDefinition saved = service.saveDefinition(def(null, "Recurring", List.of()));
        Instant due = Instant.now().minusSeconds(10);
        service.setRecurrence(saved.id(), "0 9 * * *", "UTC", due);
        assertThat(service.getRecurrence(saved.id())).isPresent();
    }

    private JobDefinition def(String id, String title, List<JobWorkItem> items) {
        return new JobDefinition(id, "agent-1", null, "agent", "agent-1", null, "DRAFT",
            title, "Summary", items, null, null, null, 1, null, null);
    }

    private JobWorkItem item(String key) {
        return new JobWorkItem(key, JobWorkItemType.PLAN, "plan-" + key, null, Map.of(), 0, null, null);
    }
}
