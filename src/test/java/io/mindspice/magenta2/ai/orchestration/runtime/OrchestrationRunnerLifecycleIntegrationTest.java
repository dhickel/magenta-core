package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

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
