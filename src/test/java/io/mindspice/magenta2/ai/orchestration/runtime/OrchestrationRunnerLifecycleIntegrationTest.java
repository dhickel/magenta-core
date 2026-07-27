package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrchestrationRunnerLifecycleIntegrationTest {
    private static final Path ROOT = Path.of(System.getProperty("java.io.tmpdir"),
        "magenta-runner-lifecycle-" + UUID.randomUUID());
    private static final Path DB = ROOT.resolve("magenta.sqlite");
    private static final Path CONFIG = ROOT.resolve("ai-config.json");

    @Autowired AgentProfileService agents;
    @Autowired JobService jobs;
    @Autowired AssignmentService assignments;
    @Autowired OrchestrationRunnerService runner;
    @Autowired TaskRunLifecycleService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentContextService contexts;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("magenta.root.path", () -> ROOT.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB + "?foreign_keys=true&busy_timeout=5000");
        registry.add("app.ai.config-path", () -> config().toString());
        registry.add("magenta.orchestration.runner-delay-ms", () -> "600000");
    }

    @Test
    void emptyJobRunsThroughReservedNativeIdentityProvisionedRootAndClosedWriter() {
        String agentId = "runner-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Runner Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        contexts.initializeDefaultToPersonal(agentId, PersonalSelection.HOME, "integration fixture");
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Empty lifecycle job", "Summary",
            List.of(), null, null, null, null, null));

        WorkAssignment queued = assignments.create(new AssignmentRequest(
            agentId, job.id(), null, AssignmentType.JOB_RUN, null, 0, null, null,
            null, null, Map.of("jobId", job.id()), "runner-integration-op"));
        String taskRunId = queued.checkpoint().get("taskRunId").toString();

        assertThat(taskRunId).isNotEqualTo(queued.id());
        assertThat(Files.exists(lifecycle.resolveLocation(taskRunId).confinedPath())).isFalse();

        WorkAssignment completed = runner.runAssignment(queued.id());

        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(completed.checkpoint()).containsEntry("jobRunId", taskRunId);
        assertThat(jobs.getRun(taskRunId).status()).isEqualTo(JobRunStatus.COMPLETED);
        assertThat(Files.isDirectory(lifecycle.resolveLocation(taskRunId).confinedPath())).isTrue();
        assertThat(jdbc.queryForObject("""
            select count(*) from writer_claim_generations
            where task_run_id = ? and is_current = 1
            """, Integer.class, taskRunId)).isZero();
        assertThat(jdbc.queryForObject("""
            select status from task_run_attempts
            where task_run_id = ? and attempt_number = 1
            """, String.class, taskRunId)).isEqualTo("COMPLETED");
    }

    @Test
    void operationRetryConvergesBeforeCaptureAndContradictoryReuseCreatesNoBinding() {
        String agentId = "idempotent-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Idempotent Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Idempotent job", "Summary", List.of(),
            null, null, null, null, null));
        AssignmentRequest request = new AssignmentRequest(agentId, job.id(), null, AssignmentType.JOB_RUN,
            null, 0, null, null, null, null, Map.of("jobId", job.id()), "same-operation");

        WorkAssignment first = assignments.create(request);
        int bindings = jdbc.queryForObject("select count(*) from context_bindings", Integer.class);
        WorkAssignment replay = assignments.create(request);
        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("select count(*) from context_bindings", Integer.class)).isEqualTo(bindings);
        assertThatThrownBy(() -> assignments.create(new AssignmentRequest(agentId, job.id(), null,
            AssignmentType.JOB_RUN, null, 0, null, null, null, null,
            Map.of("jobId", job.id(), "different", true), "same-operation")))
            .isInstanceOf(TaskRunLifecycleService.OperationConflictException.class);
        assertThat(jdbc.queryForObject("select count(*) from context_bindings", Integer.class)).isEqualTo(bindings);
    }

    @Test
    void concurrentSameOperationCreatesOneAssignmentBindingAndReservation() throws Exception {
        String agentId = "race-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Race Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Race job", "Summary", List.of(),
            null, null, null, null, null));
        AssignmentRequest request = new AssignmentRequest(agentId, job.id(), null, AssignmentType.JOB_RUN,
            null, 0, null, null, null, null, Map.of("jobId", job.id()), "concurrent-operation");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        java.util.function.Supplier<WorkAssignment> submit = () -> {
            ready.countDown();
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("race did not start");
                return assignments.create(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
        CompletableFuture<WorkAssignment> first = CompletableFuture.supplyAsync(submit);
        CompletableFuture<WorkAssignment> second = CompletableFuture.supplyAsync(submit);
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        WorkAssignment winner = first.get(15, TimeUnit.SECONDS);
        WorkAssignment replay = second.get(15, TimeUnit.SECONDS);

        assertThat(replay.id()).isEqualTo(winner.id());
        assertThat(jdbc.queryForObject("select count(*) from work_assignments where agent_id = ?", Integer.class, agentId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from binding_owner_links where owner_type = 'ASSIGNMENT' and owner_id = ?",
            Integer.class, winner.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from task_run_native_links where assignment_id = ?",
            Integer.class, winner.id())).isEqualTo(1);
    }

    @Test
    void workflowAndJobChildrenReceiveTypedDurableBindingOwnerLinks() {
        String agentId = "child-link-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Child Link Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Child link job", "Summary", List.of(),
            null, null, null, null, null));
        WorkAssignment parent = assignments.create(new AssignmentRequest(agentId, job.id(), null,
            AssignmentType.JOB_RUN, null, 0, null, null, null, null, Map.of("jobId", job.id()), "child-parent"));
        String parentRunId = parent.checkpoint().get("taskRunId").toString();
        LifecycleResult workflowChild = lifecycle.reserveChild(parentRunId, "WORKFLOW_CHILD", "workflow-node",
            "workflow-child-link", io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind.TASK,
            "task-child", UUID.randomUUID().toString(), "workflow-child-input");
        LifecycleResult jobChild = lifecycle.reserveChild(parentRunId, "JOB_WORK_ITEM", "job-item",
            "job-child-link", io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind.TASK,
            "task-child", UUID.randomUUID().toString(), "job-child-input");
        String binding = jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'TASK_RUN' and owner_id = ?",
            String.class, parentRunId);
        for (String child : List.of(workflowChild.taskRunId(), jobChild.taskRunId())) {
            assertThat(jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'TASK_RUN' and owner_id = ?",
                String.class, child)).isEqualTo(binding);
        }
        assertThat(jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'WORKFLOW_CHILD' and owner_id = ?",
            String.class, workflowChild.taskRunId())).isEqualTo(binding);
        assertThat(jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'JOB_WORK_ITEM' and owner_id = ?",
            String.class, jobChild.taskRunId())).isEqualTo(binding);
    }

    @Test
    void runnerRejectsMutableCheckpointThatDisagreesWithDurableAssignmentAndRunLinks() {
        String agentId = "stale-binding-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Stale Binding Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Stale binding job", "Summary", List.of(),
            null, null, null, null, null));
        WorkAssignment queued = assignments.create(new AssignmentRequest(agentId, job.id(), null,
            AssignmentType.JOB_RUN, null, 0, null, null, null, null, Map.of("jobId", job.id()), "stale-op"));
        String taskRunId = queued.checkpoint().get("taskRunId").toString();
        jdbc.update("update work_assignments set checkpoint_json = ? where id = ?",
            "{\"taskRunId\":\"" + taskRunId + "\",\"contextBindingKey\":\"forged\"}", queued.id());

        WorkAssignment failed = runner.runAssignment(queued.id());
        assertThat(failed.status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(Files.exists(lifecycle.resolveLocation(taskRunId).confinedPath())).isFalse();
    }

    @Test
    void retryCopiesBindingFactsIntoNewBindingAndLinksItsNewRun() {
        String agentId = "lineage-agent-" + UUID.randomUUID();
        agents.create(new AgentProfile(agentId, "Lineage Agent", AgentProfileStatus.ACTIVE,
            "local", "Prompt", List.of(), List.of(), true, null, null));
        JobDefinition job = jobs.saveDefinition(new JobDefinition(null, agentId, null,
            "agent", agentId, null, "DRAFT", "Lineage job", "Summary", List.of(),
            null, null, null, null, null));
        WorkAssignment source = assignments.create(new AssignmentRequest(agentId, job.id(), null,
            AssignmentType.JOB_RUN, null, 0, null, null, null, null, Map.of("jobId", job.id()), "lineage-source"));
        assertThat(runner.runAssignment(source.id()).status()).isEqualTo(OrchestrationStatus.COMPLETED);
        WorkAssignment retry = assignments.retry(agentId, source.id(), "lineage-retry");
        String sourceBinding = jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'ASSIGNMENT' and owner_id = ?",
            String.class, source.id());
        String retryBinding = jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'ASSIGNMENT' and owner_id = ?",
            String.class, retry.id());
        String retryRunId = retry.checkpoint().get("taskRunId").toString();
        assertThat(retryBinding).isNotEqualTo(sourceBinding);
        assertThat(jdbc.queryForObject("select binding_key from binding_owner_links where owner_type = 'TASK_RUN' and owner_id = ?",
            String.class, retryRunId)).isEqualTo(retryBinding);
    }

    private static Path config() {
        try {
            Files.createDirectories(ROOT.resolve("data"));
            Files.createDirectories(ROOT.resolve("prompts"));
            Files.writeString(ROOT.resolve("prompts/system.md"), "Runner lifecycle test agent.");
            Files.writeString(CONFIG, """
                {
                  "defaultAgent": "magenta",
                  "defaultModel": "local",
                  "summaryModel": "local",
                  "planningModel": "local",
                  "compactionModel": "local",
                  "contextBufferPercent": 33,
                  "unsafeAllowWildcardShellCommands": false,
                  "dataRoot": "%s",
                  "models": {
                    "local": {
                      "remoteModelName": "qwen3.6:35b",
                      "remoteEndpoint": "http://127.0.0.1:11434",
                      "endpointType": "OLLAMA",
                      "contextLength": 32000,
                      "thinkLevel": 0
                    }
                  },
                  "agents": {
                    "magenta": {
                      "model": "local",
                      "systemPrompt": "prompts/system.md",
                      "approvedTools": [],
                      "allowedShellCommands": []
                    }
                  }
                }
                """.formatted(ROOT.resolve("data").toString().replace("\\", "\\\\")));
            return CONFIG;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
