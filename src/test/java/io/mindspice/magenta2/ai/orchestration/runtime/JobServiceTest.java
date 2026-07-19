package io.mindspice.magenta2.ai.orchestration.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobServiceTest {

    @TempDir
    Path tempDir;

    private JobService jobService;
    private JobRepository jobRepository;
    private WorkspaceDirectoryService workspaceDirectoryService;

    @BeforeEach
    void setUp() throws IOException {
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        AiConfig aiConfig = new AiConfig(null, null, null, 10, dataRoot, Map.of(), Map.of());
        workspaceDirectoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);

        jobRepository = repository();
        jobService = new JobService(jobRepository, workspaceDirectoryService, null, null);
    }

    @Test
    void createAndRetrieveJob() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "My Job", List.of(planItem("step1", "plan-1", 0)))
        );
        assertThat(def.id()).isNotNull();
        JobDefinition found = jobService.getDefinition(def.id());
        assertThat(found.title()).isEqualTo("My Job");
    }

    @Test
    void draftJobCanStartWithoutWorkItems() {
        JobDefinition definition = jobService.saveDefinition(jobDef("j1", "Empty", List.of()));

        assertThat(definition.items()).isEmpty();
        assertThat(definition.status()).isEqualTo("DRAFT");
    }

    @Test
    void planItemRequiresPlanId() {
        JobWorkItem badItem = new JobWorkItem("k", JobWorkItemType.PLAN, null, null,
            Map.of(), 0, null, null);
        assertThatThrownBy(() -> jobService.saveDefinition(
            jobDef("j2", "Bad", List.of(badItem))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no planId");
    }

    @Test
    void directStartRunRequiresAssignmentContext() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Guarded Runner", List.of(planItem("step", "plan-1", 0)))
        );

        assertThatThrownBy(() -> jobService.startRun(def.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("assignment context");
        assertThatThrownBy(() -> jobService.startRun(def.id(), "agent-1", null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("assignment context");
    }

    @Test
    void startRunCreatesRunWithWorkItems() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Runner", List.of(
                planItem("s1", "plan-1", 0),
                planItem("s2", "plan-2", 1)
            ))
        );

        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-run");
        assertThat(run.status()).isEqualTo(JobRunStatus.QUEUED);
        assertThat(run.workItemRuns()).hasSize(2);
        assertThat(run.workItemRuns().get(0).key()).isEqualTo("s1");
        assertThat(run.workItemRuns().get(1).key()).isEqualTo("s2");
    }

    @Test
    void startRunReusesExistingRunForAssignment() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Reuse Runner", List.of(planItem("step", "plan-1", 0)))
        );

        JobRun first = jobService.startRun(def.id(), "agent-1", null, "assignment-reuse");
        JobRun second = jobService.startRun(def.id(), "agent-1", null, "assignment-reuse");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jobService.listRuns(def.id())).hasSize(1);
    }

    @Test
    void startRunDefaultsToNoPersistentWorkspaceAndWritesJobOutputUnderEffectiveWorkspace() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Workspace Job", List.of(planItem("s1", "plan-1", 0)))
        );

        JobRun run = jobService.startRun(def.id(), "system", null, "assignment-output");
        assertThat(run.workspacePath()).isNull();
        assertStoredRelative(run.outputDir(), "workspace/system/outputs");
        assertThat(Files.isDirectory(resolveStored(run.outputDir()))).isTrue();
    }

    @Test
    void persistentJobWorkspaceFlagIsLegacyAndDoesNotAllocateWorkspace() {
        JobDefinition def = jobService.saveDefinition(new JobDefinition(
            null, "agent-1", null, null, true, "Persistent Job", "Summary",
            List.of(planItem("s1", "plan-1", 0)), null, null, null, null, null
        ));

        JobRun first = jobService.startRun(def.id(), "agent-1", null, "assignment-a");
        JobRun second = jobService.startRun(def.id(), "agent-1", null, "assignment-b");

        assertThat(def.persistentWorkspaceEnabled()).isFalse();
        assertThat(first.workspacePath()).isNull();
        assertThat(second.workspacePath()).isNull();
        assertStoredRelative(first.outputDir(), "workspace/agent-1/outputs");
        assertStoredRelative(second.outputDir(), "workspace/agent-1/outputs");
    }

    @Test
    void projectScopedJobUsesProjectEffectiveDurableWorkspace() {
        JobDefinition def = jobService.saveDefinition(new JobDefinition(
            null, "agent-1", "project-1", null, true, "Project Job", "Summary",
            List.of(planItem("s1", "plan-1", 0)), null, null, null, null, null
        ));

        JobRun run = jobService.startRun(def.id(), "agent-1", "project-1", "assignment-project");

        assertThat(run.workspacePath()).isNull();
        assertStoredRelative(run.outputDir(), "projects/project-1/outputs");
        assertThat(Files.isDirectory(resolveStored(run.outputDir()))).isTrue();
    }

    @Test
    void jobContextReceivesRunIdsWithoutJobWorkspacePath() {
        JobDefinition def = jobService.saveDefinition(new JobDefinition(
            null, "agent-1", null, null, true, "Persistent Job", "Summary",
            List.of(planItem("s1", "plan-1", 0)), null, null, null, null, null
        ));
        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-context");
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbcTemplate(), new ObjectMapper());
        AssignmentService assignmentService = new AssignmentService(
            runtime, new TestAgentProfileService("agent-1"), null, jobService);
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            runtime, assignmentService, jobService, null, null, null, null, null,
            null, null, null, null, null, workspaceDirectoryService,
            null, null, 300, 60);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "Agent 1", def.id(), null, null, "JOB_RUN", null, null));
        try {
            runner.installJobWorkspaceContext(run);
            OrchestrationTaskContext current = OrchestrationTaskContextHolder.current();
            assertThat(current.jobRunId()).isEqualTo(run.id());
            assertThat(current.hostJobWorkspacePath()).isNull();
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void executionSummaryLeavesStaleAbsoluteJobPathsDisplayOnlyAndDoesNotMutateOldRoot() throws Exception {
        JobDefinition def = jobService.saveDefinition(
            jobDef("stale-summary", "Stale Summary", List.of())
        );
        Path oldRoot = Files.createDirectories(tempDir.resolve("old-root/root"));
        Path oldWorkspace = Files.createDirectories(oldRoot.resolve("agents/agent-1/workspace/jobs/assignment-old"));
        Path oldOutput = Files.createDirectories(oldRoot.resolve("agents/agent-1/workspace/outputs/jobs/assignment-old/run-old"));
        jobRepository.saveRun(new JobRun(
            "run-stale", def.id(), "assignment-old", null, JobRunStatus.RUNNING,
            List.of(), oldWorkspace.toString(), oldOutput.toString(), null, null,
            Instant.now(), Instant.now(), null, null
        ));

        JobExecutionSummary summary = jobService.executionSummaryByAssignmentId("assignment-old").orElseThrow();

        assertThat(summary.persistentJobWorkspacePath()).isEqualTo(oldWorkspace.toString());
        assertThat(summary.outputDirectory()).isEqualTo(oldOutput.toString());
        try (var stream = Files.list(oldOutput)) {
            assertThat(stream).isEmpty();
        }
    }

    @Test
    void executionSummaryBridgesPendingAssignmentAndCreatedRun() {
        JdbcTemplate jdbc = jdbcTemplate();
        JobRepository jobs = new JobRepository(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());
        JobService service = new JobService(jobs, workspaceDirectoryService, null, null, null, runtime);
        JobDefinition def = service.saveDefinition(new JobDefinition(
            "summary-job", "agent-1", "project-1", "workspace-compat", true, "READY",
            "Summary Job", "Summary", List.of(planItem("s1", "plan-1", 0)),
            null, "model-a", null, null, null
        ));
        WorkAssignment assignment = runtime.saveAssignment(new WorkAssignment(
            "assignment-summary", "agent-1", def.id(), null, AssignmentType.JOB_RUN, 7,
            OrchestrationStatus.QUEUED, "model-b", "workspace-compat",
            "project-1", "workspace-effective", "PROJECT", 0,
            Map.of(), Map.of("jobId", def.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        JobExecutionSummary pending = service.executionSummaryByAssignmentId(assignment.id()).orElseThrow();
        assertThat(pending.assignmentId()).isEqualTo("assignment-summary");
        assertThat(pending.assignmentStatus()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(pending.jobRunId()).isNull();
        assertThat(pending.effectiveWorkspaceId()).isEqualTo("workspace-effective");
        assertThat(pending.persistentWorkspaceEnabled()).isFalse();
        assertThat(pending.persistentJobWorkspacePresent()).isFalse();

        JobRun run = service.startRun(def.id(), "agent-1", "project-1", assignment.id());
        JobExecutionSummary running = service.executionSummaryByAssignmentId(assignment.id()).orElseThrow();
        assertThat(running.jobRunId()).isEqualTo(run.id());
        assertThat(running.jobRunStatus()).isEqualTo(JobRunStatus.QUEUED);
        assertThat(running.outputDirectory()).isEqualTo("projects/project-1/outputs");
        assertThat(running.persistentJobWorkspacePath()).isNull();
        assertThat(Path.of(running.outputDirectory()).isAbsolute()).isFalse();
        assertThat(running.persistentJobWorkspacePresent()).isFalse();
    }

    @Test
    void executionSummaryUsesNewestRunWhenDuplicateAssignmentRunsExist() {
        JdbcTemplate jdbc = jdbcTemplate();
        JobRepository jobs = new JobRepository(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());
        JobService service = new JobService(jobs, workspaceDirectoryService, null, null, null, runtime);
        JobDefinition def = service.saveDefinition(jobDef("duplicate-summary", "Duplicate Summary", List.of()));
        WorkAssignment assignment = runtime.saveAssignment(new WorkAssignment(
            "assignment-duplicate-summary", "agent-1", def.id(), null, AssignmentType.JOB_RUN, 7,
            OrchestrationStatus.QUEUED, null, null, null, null, null, 0,
            Map.of(), Map.of("jobId", def.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));
        jobs.saveRun(new JobRun(
            "run-old", def.id(), assignment.id(), null, JobRunStatus.FAILED,
            List.of(), null, "/out/old", null, null,
            Instant.parse("2026-05-21T10:00:00Z"), Instant.parse("2026-05-21T10:00:00Z"),
            null, null
        ));
        jobs.saveRun(new JobRun(
            "run-new", def.id(), assignment.id(), null, JobRunStatus.RUNNING,
            List.of(), null, "/out/new", null, null,
            Instant.parse("2026-05-21T11:00:00Z"), Instant.parse("2026-05-21T11:00:00Z"),
            null, null
        ));

        JobExecutionSummary summary = service.executionSummaryByAssignmentId(assignment.id()).orElseThrow();

        assertThat(summary.jobRunId()).isEqualTo("run-new");
        assertThat(summary.jobRunStatus()).isEqualTo(JobRunStatus.RUNNING);
        assertThat(service.executionSummaries(def.id())).hasSize(1);
        assertThat(service.executionSummaries(def.id()).getFirst().jobRunId()).isEqualTo("run-new");
    }

    @Test
    void updateWorkItemComputesProgress() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Progress Job", List.of(
                planItem("a", "plan-1", 0),
                planItem("b", "plan-2", 1)
            ))
        );

        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-progress");
        run = jobService.markRunning(run.id());
        assertThat(run.status()).isEqualTo(JobRunStatus.RUNNING);

        // Complete first item
        run = jobService.updateWorkItemRun(run.id(), "a", "COMPLETED", "pr-1",
            Map.of("out", "val"), null);
        assertThat(run.progress()).isEqualTo(0.5);

        // Complete second item → job run completes
        run = jobService.updateWorkItemRun(run.id(), "b", "COMPLETED", "pr-2",
            Map.of("out", "val"), null);
        assertThat(run.status()).isEqualTo(JobRunStatus.COMPLETED);
        assertThat(run.progress()).isEqualTo(1.0);
    }

    @Test
    void completeRunTransitionsEmptyRunToCompleted() {
        JobDefinition def = jobService.saveDefinition(jobDef(null, "Empty Complete Job", List.of()));
        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-empty-complete");
        run = jobService.markRunning(run.id());

        JobRun completed = jobService.completeRun(run.id(), "Job run completed");
        JobRun completedAgain = jobService.completeRun(run.id(), "ignored");

        assertThat(completed.status()).isEqualTo(JobRunStatus.COMPLETED);
        assertThat(completed.workItemRuns()).isEmpty();
        assertThat(completed.finalMessage()).isEqualTo("Job run completed");
        assertThat(completed.completedAt()).isNotNull();
        assertThat(completedAgain).isEqualTo(completed);
    }

    @Test
    void failedWorkItemResultsInFailedJob() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Fail Job", List.of(
                planItem("a", "plan-1", 0),
                planItem("b", "plan-2", 1)
            ))
        );

        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-fail");
        run = jobService.markRunning(run.id());
        run = jobService.updateWorkItemRun(run.id(), "a", "COMPLETED", "pr-1",
            Map.of(), null);
        run = jobService.updateWorkItemRun(run.id(), "b", "FAILED", "pr-2",
            Map.of(), "error occurred");

        assertThat(run.status()).isEqualTo(JobRunStatus.FAILED);
    }

    @Test
    void recurrenceCreatesNewRunOnFire() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Recurring Job", List.of(planItem("s1", "plan-1", 0)))
        );

        Instant past = Instant.now().minusSeconds(3600);
        jobService.setRecurrence(def.id(), "0 9 * * *", "UTC", past);

        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbcTemplate(), new ObjectMapper());
        AssignmentService assignmentService = new AssignmentService(
            runtime, new TestAgentProfileService("system"), null, jobService);
        JobService recurringService = new JobService(
            jobRepository, workspaceDirectoryService, null, null, null, runtime, objectProvider(assignmentService),
            null, null, null, null, null);

        List<WorkAssignment> assignments = recurringService.fireDueRecurrences(Instant.now().plusSeconds(10));
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().jobId()).isEqualTo(def.id());
        assertThat(assignments.getFirst().assignmentType()).isEqualTo(AssignmentType.JOB_RUN);
        assertThat(assignments.getFirst().status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(recurringService.getRecurrence(def.id()).orElseThrow().nextFireTime()).isAfter(past);
    }

    @Test
    void cancelRunTransitionsToCancelled() {
        JobDefinition def = jobService.saveDefinition(
            jobDef(null, "Cancel Job", List.of(planItem("s1", "plan-1", 0)))
        );

        JobRun run = jobService.startRun(def.id(), "agent-1", null, "assignment-cancel");
        run = jobService.markRunning(run.id());

        JobRun cancelled = jobService.cancelRun(run.id());
        assertThat(cancelled.status()).isEqualTo(JobRunStatus.CANCELLED);
    }

    @Test
    void cancelAssignmentOwnedRunAlsoCancelsOwningAssignment() {
        JdbcTemplate jdbc = jdbcTemplate();
        JobRepository jobs = new JobRepository(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());
        JobService baseService = new JobService(jobs, workspaceDirectoryService, null, null, null, runtime);
        AssignmentService assignmentService = new AssignmentService(
            runtime, new TestAgentProfileService("agent-1"), null, baseService);
        JobService service = new JobService(
            jobs, workspaceDirectoryService, null, null, null, runtime, objectProvider(assignmentService),
            null, null, null, null, null);
        JobDefinition def = service.saveDefinition(jobDef("cancel-owned", "Cancel Owned", List.of(planItem("s1", "plan-1", 0))));
        runtime.saveAssignment(new WorkAssignment(
            "assignment-cancel-owned", "agent-1", def.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, null, null, null,
            0, Map.of(), Map.of("jobId", def.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));
        JobRun run = service.markRunning(service.startRun(def.id(), "agent-1", null, "assignment-cancel-owned").id());

        JobRun cancelled = service.cancelRun(run.id());

        assertThat(cancelled.status()).isEqualTo(JobRunStatus.CANCELLED);
        assertThat(runtime.findAssignment("assignment-cancel-owned").orElseThrow().status())
            .isEqualTo(OrchestrationStatus.CANCELLED);
    }

    @Test
    void activeJobAssignmentBlocksExecutionAffectingEditsAndDeleteButAllowsLabels() throws IOException {
        JdbcTemplate jdbc = jdbcTemplate();
        AiConfig aiConfig = new AiConfig(null, null, null, 10, tempDir.resolve("active-job"), Map.of(), Map.of());
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir.resolve("active-job"));
        JobRepository jobs = new JobRepository(jdbc, new ObjectMapper().registerModule(new JavaTimeModule()));
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());
        JobService service = new JobService(jobs, directoryService, null, null, null, runtime);
        JobDefinition definition = service.saveDefinition(jobDef("active-job", "Active Job", List.of(planItem("s1", "plan-1", 0))));
        runtime.saveAssignment(new WorkAssignment(
            "assignment-job-active", "agent-1", definition.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, null, null, null,
            0, Map.of(), Map.of("jobId", definition.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        JobDefinition labelOnly = new JobDefinition(
            definition.id(), definition.ownerAgentId(), definition.projectId(), definition.workspaceId(),
            definition.persistentWorkspaceEnabled(), definition.status(), "Renamed", "New summary",
            definition.items(), definition.promptProfile(), definition.model(), definition.settingsOverrideJson(),
            definition.createdAt(), definition.updatedAt()
        );
        assertThat(service.saveDefinition(labelOnly).title()).isEqualTo("Renamed");

        JobDefinition executionEdit = new JobDefinition(
            definition.id(), "agent-2", definition.projectId(), definition.workspaceId(),
            definition.persistentWorkspaceEnabled(), definition.status(), "Renamed", "New summary",
            definition.items(), definition.promptProfile(), definition.model(), definition.settingsOverrideJson(),
            definition.createdAt(), definition.updatedAt()
        );
        assertThatThrownBy(() -> service.saveDefinition(executionEdit))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active assignments or runs");
        assertThatThrownBy(() -> service.addItem(definition.id(), planItem("s2", "plan-2", 1)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active assignments or runs");
        assertThatThrownBy(() -> service.deleteDefinition(definition.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active assignments or runs");
    }

    // ── Helpers ──

    private JobDefinition jobDef(String id, String title, List<JobWorkItem> items) {
        return new JobDefinition(id, title, "Summary", items,
            null, null, null, null, null);
    }

    private JobWorkItem planItem(String key, String planId, int order) {
        return new JobWorkItem(key, JobWorkItemType.PLAN, planId, null,
            Map.of(), order, null, null);
    }

    private static ObjectProvider<AssignmentService> objectProvider(AssignmentService service) {
        return new ObjectProvider<>() {
            @Override
            public AssignmentService getObject(Object... args) {
                return service;
            }

            @Override
            public AssignmentService getIfAvailable() {
                return service;
            }

            @Override
            public AssignmentService getIfUnique() {
                return service;
            }

            @Override
            public AssignmentService getObject() {
                return service;
            }
        };
    }

    private static class TestAgentProfileService extends AgentProfileService {
        private final String agentId;

        TestAgentProfileService(String agentId) {
            super(null, null, null);
            this.agentId = agentId;
        }

        @Override
        public AgentProfile get(String id) {
            if (agentId.equals(id)) {
                return new AgentProfile(id, "Agent " + id, AgentProfileStatus.ACTIVE,
                    "main", "Prompt", List.of(), List.of(), true, null, null);
            }
            throw new IllegalStateException("Agent profile not found: " + id);
        }
    }

    private JobRepository repository() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        return new JobRepository(jdbcTemplate(), mapper);
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(ds);
    }

    private void assertStoredRelative(String value, String expectedPrefix) {
        assertThat(value).isNotBlank();
        assertThat(Path.of(value).isAbsolute()).isFalse();
        assertThat(value).startsWith(expectedPrefix);
        assertThat(value).doesNotContain(workspaceDirectoryService.dataRoot().toString());
        assertThat(value).doesNotContain("\\");
    }

    private Path resolveStored(String value) {
        return workspaceDirectoryService.dataRoot().resolve(value.replace('\\', '/')).normalize();
    }
}
