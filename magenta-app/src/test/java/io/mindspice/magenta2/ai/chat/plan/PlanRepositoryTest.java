package io.mindspice.magenta2.ai.chat.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRepositoryTest {

    @Test
    void savesAndFindsSessionPlan() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = sessionPlan("conv-1", "Test Plan", List.of("Step 1", "Step 2"));
        repository.saveDefinition(def);

        PlanDefinition saved = repository.findDefinition("conv-1").orElseThrow();
        assertThat(saved.title()).isEqualTo("Test Plan");
        assertThat(saved.goal()).isEqualTo("Goal text");
        assertThat(saved.steps()).extracting(PlanStep::text).containsExactly("Step 1", "Step 2");
        assertThat(saved.deliverables()).containsExactly("Deliverable 1");
        assertThat(saved.validationCriteria()).containsExactly("Criterion 1");
        assertThat(saved.kind()).isEqualTo(PlanKind.SESSION_PLAN);
    }

    @Test
    void savesAndFindsTaskTemplate() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = taskTemplate("task-1", "Research Task", List.of(
            new PlanFieldDefinition("topic", PlanFieldType.STRING, false, "Topic", true, null),
            new PlanFieldDefinition("notes", PlanFieldType.STRING, false, "Notes", true, null)
        ));
        repository.saveDefinition(def);

        PlanDefinition saved = repository.findDefinition("task-1").orElseThrow();
        assertThat(saved.kind()).isEqualTo(PlanKind.TASK_TEMPLATE);
        assertThat(saved.inputs()).hasSize(3);
        assertThat(saved.outputs()).hasSize(1);
        assertThat(saved.inputs()).extracting(PlanFieldDefinition::name).contains("topic", "notes");
    }

    @Test
    void findsByConversationId() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = taskTemplateDraft("task-2", "draft-conv");
        repository.saveDefinition(def);

        assertThat(repository.findDefinitionByConversationId("draft-conv")).isPresent();
        assertThat(repository.findDefinitionByConversationId("nonexistent")).isEmpty();
    }

    @Test
    void savesAndFindsRunWithSnapshot() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = taskTemplate("task-3", "Run Task", List.of());
        repository.saveDefinition(def);

        PlanRun run = new PlanRun(
            "run-1", "task-3", "Weekly research run", PlanRunStatus.RUNNING,
            Map.of("topic", "test"), Map.of(), def,
            null, null, null,
            List.of("Started"), List.of(), List.of(),
            null, null,
            Instant.now(), Instant.now(), Instant.now(), null
        );
        repository.saveRun(run);

        PlanRun saved = repository.findRun("run-1").orElseThrow();
        assertThat(saved.planSnapshot().title()).isEqualTo("Run Task");
        assertThat(saved.runDisplayName()).isEqualTo("Weekly research run");
        assertThat(saved.inputValues()).containsEntry("topic", "test");
        assertThat(saved.status()).isEqualTo(PlanRunStatus.RUNNING);
    }

    @Test
    void findsRunsByPlanId() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = taskTemplate("task-4", "Multi Run Task", List.of());
        repository.saveDefinition(def);

        repository.saveRun(new PlanRun("run-a", "task-4", PlanRunStatus.COMPLETED,
            Map.of(), Map.of(), def, null, null, null,
            List.of(), List.of(), List.of(), null, null,
            Instant.now(), Instant.now(), null, Instant.now()));
        repository.saveRun(new PlanRun("run-b", "task-4", PlanRunStatus.FAILED,
            Map.of(), Map.of(), def, null, null, null,
            List.of(), List.of(), List.of(), null, "error",
            Instant.now(), Instant.now(), null, Instant.now()));

        List<PlanRun> runs = repository.findRunsByPlanId("task-4");
        assertThat(runs).hasSize(2);
    }

    @Test
    void deleteDefinitionCascadesToRuns() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = taskTemplate("task-5", "Delete Test", List.of());
        repository.saveDefinition(def);
        repository.saveRun(new PlanRun("run-x", "task-5", PlanRunStatus.RUNNING,
            Map.of(), Map.of(), def, null, null, null,
            List.of(), List.of(), List.of(), null, null,
            Instant.now(), Instant.now(), Instant.now(), null));

        repository.deleteDefinition("task-5");

        assertThat(repository.findDefinition("task-5")).isEmpty();
        assertThat(repository.findRun("run-x")).isEmpty();
    }

    @Test
    void listsConversationIdsForSessionPlans() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        repository.saveDefinition(sessionPlan("conv-a", "Plan A", List.of("Step")));
        repository.saveDefinition(sessionPlan("conv-b", "Plan B", List.of("Step")));
        repository.saveDefinition(taskTemplate("task-x", "Task", List.of()));

        List<String> ids = repository.findConversationIds();
        assertThat(ids).contains("conv-a", "conv-b");
        assertThat(ids).doesNotContain("task-x");
    }

    @Test
    void handlesEmptyListsAndMaps() {
        PlanRepository repository = new PlanRepository(jdbcTemplate(), new ObjectMapper());
        PlanDefinition def = new PlanDefinition(
            "minimal", PlanKind.SESSION_PLAN, PlanStatus.DRAFT,
            "Minimal", null, null, null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(),
            null, null, null, null,
            null, List.of(), 0, 0, null, null, Instant.now(), Instant.now()
        );
        repository.saveDefinition(def);
        PlanDefinition saved = repository.findDefinition("minimal").orElseThrow();
        assertThat(saved.deliverables()).isEmpty();
        assertThat(saved.steps()).isEmpty();
    }

    // ── Helpers ──

    private PlanDefinition sessionPlan(String id, String title, List<String> stepTexts) {
        List<PlanStep> steps = stepTexts.stream()
            .map(text -> new PlanStep(stepTexts.indexOf(text) + 1, text))
            .toList();
        return new PlanDefinition(
            id, PlanKind.SESSION_PLAN, PlanStatus.DRAFT,
            title, "Summary", "Goal text", "Notes",
            List.of("Deliverable 1"), List.of(), List.of(),
            List.of("Assumption"), steps, List.of("Criterion 1"),
            List.of(), List.of(),
            null, "model-a", "model-b", null,
            "goal_and_deliverables", List.of(), 0, 5, null, null,
            Instant.now(), Instant.now()
        );
    }

    private PlanDefinition taskTemplate(String id, String title, List<PlanFieldDefinition> extraInputs) {
        List<PlanFieldDefinition> inputs = new java.util.ArrayList<>(extraInputs);
        inputs.add(new PlanFieldDefinition("output_file", PlanFieldType.FILE_PATH, false, "Output", true, null));
        return new PlanDefinition(
            id, PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            title, "A reusable task", "Do something useful.", null,
            List.of("result"), inputs,
            List.of(new PlanFieldDefinition("report", PlanFieldType.USER_MESSAGE, false, "Report", true, null)),
            List.of("Assumption"),
            List.of(new PlanStep(1, "First step.")),
            List.of("Report is valid."),
            List.of(), List.of(),
            "default", null, null, null,
            null, List.of(), 0, 0, null, null,
            Instant.now(), Instant.now()
        );
    }

    private PlanDefinition taskTemplateDraft(String id, String conversationId) {
        return new PlanDefinition(
            id, PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Draft Task", null, "Goal", null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(),
            null, null, null, null,
            "define_runtime_inputs",
            List.of("What inputs?"), 0, 0, null, conversationId,
            Instant.now(), Instant.now()
        );
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }
}
