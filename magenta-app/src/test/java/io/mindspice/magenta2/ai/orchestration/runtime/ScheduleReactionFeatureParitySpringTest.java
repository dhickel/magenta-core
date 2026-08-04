package io.mindspice.magenta2.ai.orchestration.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {
    "magenta.features.schedules-enabled=true",
    "magenta.features.reactions-enabled=true",
    "magenta.orchestration.scheduler-delay-ms=86400000"
})
class ScheduleReactionFeatureParitySpringTest {
    private static final Path DB_PATH = Path.of(
        System.getProperty("java.io.tmpdir"),
        "magenta-schedule-reaction-parity-" + UUID.randomUUID() + ".db"
    );
    private static final Path DATA_ROOT = Path.of(
        System.getProperty("java.io.tmpdir"),
        "magenta-schedule-reaction-parity-root-" + UUID.randomUUID()
    );
    private static final Path AI_CONFIG_PATH = DATA_ROOT.resolve("ai-config.json");

    @Autowired
    private AgentProfileService agentProfileService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private EventReactionService reactionService;

    @Autowired
    private OrchestrationEventService eventService;

    @Autowired
    private OrchestrationRuntimeRepository repository;

    @DynamicPropertySource
    static void sqliteProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB_PATH + "?foreign_keys=true");
        registry.add("app.ai.config-path", () -> aiConfigPath().toString());
    }

    @Test
    void enabledScheduleBeanRejectsInvalidTemplatesAndPollsDueSchedule() {
        AgentProfile agent = agent();

        assertThatThrownBy(() -> scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "NOT_A_TYPE"),
            "0 * * * * *", "UTC", true, null, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid assignmentType");

        AgentSchedule schedule = scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "REPORT", "input", Map.of("source", "spring-schedule")),
            "0 * * * * *", "UTC", true, Instant.now().minusSeconds(60), null, null
        ));

        scheduleService.pollDueSchedules();
        scheduleService.pollDueSchedules();

        List<WorkAssignment> assignments = repository.findAssignmentsForAgent(agent.id());
        assertThat(assignments).isNotEmpty();
        assertThat(assignments.getFirst().assignmentType()).isEqualTo(AssignmentType.REPORT);
        assertThat(assignments.getFirst().input()).containsEntry("source", "spring-schedule");
    }

    @Test
    void scheduleRevisionsAppendAndDeleteCreatesAnImmutableTombstone() {
        AgentProfile agent = agent();
        AgentSchedule saved = scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "REPORT"),
            "0 0 * * * *", "UTC", true, null, null, null));

        ScheduleService.ScheduleDefinition revised = scheduleService.update(agent.id(), saved.id(),
            new ScheduleService.ScheduleDefinition(saved.id(), agent.id(), 0,
                ScheduleMode.CRON, "0 */5 * * * *", "UTC", null,
                true, false, null, null, Map.of("assignmentType", "REPORT"), null, null, null, null));

        assertThat(revised.revision()).isEqualTo(2);
        assertThat(scheduleService.definitionByRevision(agent.id(), saved.id(), 1).cronExpression())
            .isEqualTo("0 0 * * * *");

        scheduleService.delete(agent.id(), saved.id());
        assertThat(scheduleService.definitionByRevision(agent.id(), saved.id(), 3).deleted()).isTrue();
        assertThat(scheduleService.schedules(agent.id())).extracting(AgentSchedule::id)
            .doesNotContain(saved.id());
    }

    @Test
    void enabledReactionBeanRejectsInvalidTemplatesAndPublishesAssignments() {
        AgentProfile agent = agent();

        assertThatThrownBy(() -> reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "NOT_A_TYPE"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid assignmentType");

        reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of("kind", "match"),
            ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "REPORT", "input", Map.of("source", "spring-reaction")),
            true, null, null
        ));

        eventService.publish(EventType.MANUAL_USER_EVENT, "test", "skip", Map.of("kind", "skip"));
        eventService.publish(EventType.MANUAL_USER_EVENT, "test", "match", Map.of("kind", "match"));

        List<WorkAssignment> assignments = repository.findAssignmentsForAgent(agent.id());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().assignmentType()).isEqualTo(AssignmentType.REPORT);
        assertThat(assignments.getFirst().input()).containsEntry("source", "spring-reaction");
    }

    private AgentProfile agent() {
        String agentId = "parity-agent-" + UUID.randomUUID();
        return agentProfileService.create(new AgentProfile(
            agentId, "Parity Agent " + agentId, AgentProfileStatus.ACTIVE, "local-qwen", "Prompt",
            List.of(), List.of(), true, null, null
        ));
    }

    private static Path aiConfigPath() {
        try {
            Files.createDirectories(DATA_ROOT);
            Files.createDirectories(DATA_ROOT.resolve("prompts"));
            Files.writeString(DATA_ROOT.resolve("prompts/system.md"), "Schedule reaction parity test agent.");
            Files.writeString(AI_CONFIG_PATH, """
                {
                  "defaultAgent": "magenta",
                  "defaultModel": "local-qwen",
                  "summaryModel": "local-qwen",
                  "planningModel": "local-qwen",
                  "compactionModel": "local-qwen",
                  "contextBufferPercent": 33,
                  "unsafeAllowWildcardShellCommands": false,
                  "dataRoot": "%s",
                  "models": {
                    "local-qwen": {
                      "remoteModelName": "qwen3.6:35b",
                      "remoteEndpoint": "http://127.0.0.1:11434",
                      "endpointType": "OLLAMA",
                      "contextLength": 32000,
                      "thinkLevel": 0
                    }
                  },
                  "agents": {
                    "magenta": {
                      "model": "local-qwen",
                      "systemPrompt": "prompts/system.md",
                      "approvedTools": [],
                      "allowedShellCommands": []
                    }
                  }
                }
                """.formatted(DATA_ROOT.toAbsolutePath()));
            return AI_CONFIG_PATH;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create schedule/reaction parity AI config", exception);
        }
    }
}
