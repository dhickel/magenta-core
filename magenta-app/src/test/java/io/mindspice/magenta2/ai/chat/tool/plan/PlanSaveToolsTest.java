package io.mindspice.magenta2.ai.chat.tool.plan;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStep;
import io.mindspice.magenta2.ai.chat.plan.PlanToolContext;
import io.mindspice.magenta2.ai.chat.plan.PlanToolExecutionContext;
import io.mindspice.magenta2.ai.chat.tool.InteractionQuestionTools;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanSaveToolsTest {

    @Test
    void planningToolDescriptionsPreferQuestionsBeforeApproval() throws Exception {
        Tool askQuestions = InteractionQuestionTools.class
            .getMethod("askQuestions", List.class)
            .getAnnotation(Tool.class);
        Tool readyForApproval = PlanSaveTools.class
            .getMethod("readyForApproval")
            .getAnnotation(Tool.class);

        assertThat(askQuestions.description())
            .contains("active plan or task interaction")
            .contains("one at a time with progress");
        assertThat(readyForApproval.description())
            .contains("only after goal, deliverables/outputs, assumptions, detailed steps, and validation criteria")
            .contains("without guessing");
    }

    @Test
    void savesPlanOnlyWhenPlanContextIsActive() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanService service = new PlanService(
            new PlanRepository(jdbcTemplate, new ObjectMapper()),
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        service.beginPlan("conversation-1");
        PlanSaveTools tools = new PlanSaveTools(service);

        PlanToolExecutionContext.set(new PlanToolContext("conversation-1", PlanMode.PLAN));
        try {
            assertThat(tools.update(
                "Clarified goal",
                "Plan",
                "Summary",
                "Important note",
                List.of("Deliverable"),
                List.of("Step"),
                List.of("Show evidence")
            ))
                .isEqualTo("Updated plan draft: Plan");
        } finally {
            PlanToolExecutionContext.clear();
        }

        PlanDefinition plan = service.activePlan("conversation-1").orElseThrow();
        assertThat(plan.title()).isEqualTo("Plan");
        assertThat(plan.goal()).isEqualTo("Clarified goal");
        assertThat(plan.notes()).isEqualTo("Important note");
        assertThat(plan.deliverables()).containsExactly("Deliverable");
        assertThat(plan.validationCriteria()).containsExactly("Show evidence");
        assertThatThrownBy(() -> tools.update("Clarified goal", "Plan", null, null, List.of("Deliverable"), List.of("Step"), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("plan mode");
    }

    @Test
    void keyedPlanningToolsMutateSingleItems() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanService service = new PlanService(
            new PlanRepository(jdbcTemplate, new ObjectMapper()),
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        service.beginPlan("conversation-1");
        PlanSaveTools tools = new PlanSaveTools(service);

        PlanToolExecutionContext.set(new PlanToolContext("conversation-1", PlanMode.PLAN));
        try {
            tools.setGoal("Build a robust planner");
            tools.setTask("collect_user_guidance");
            tools.putItem("deliverable", 1, "Implementation plan");
            tools.putItem("step", 2, "Run focused tests and inspect failures before changing code.");
            tools.putItem("step", 1, "<![CDATA[Review current planning state and recent changelogs.]]>");
            tools.deleteItem("step", 2);
            tools.putItem("validation_criterion", 1, "Planning ends with a question or approval.");
        } finally {
            PlanToolExecutionContext.clear();
        }

        PlanDefinition plan = service.activePlan("conversation-1").orElseThrow();
        assertThat(plan.goal()).isEqualTo("Build a robust planner");
        assertThat(plan.planningTask()).isEqualTo("approval_readiness");
        assertThat(plan.deliverables()).containsExactly("Implementation plan");
        assertThat(plan.steps()).containsExactly(new PlanStep(
            1,
            "Review current planning state and recent changelogs."
        ));
        assertThat(plan.validationCriteria()).containsExactly("Planning ends with a question or approval.");
    }

    @Test
    void reportsOnlyWhileExecutingPlan() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        PlanService service = new PlanService(
            new PlanRepository(jdbcTemplate, new ObjectMapper()),
            new ChatMemoryRepository(jdbcTemplate, new ObjectMapper())
        );
        service.beginPlan("conversation-1");
        service.saveDraftPlan("conversation-1", "Goal", "Plan", null, null,
            List.of("Step"), List.of(), List.of("Validate"));
        service.markExecuting("conversation-1");
        PlanSaveTools tools = new PlanSaveTools(service);

        PlanToolExecutionContext.set(new PlanToolContext("conversation-1", PlanMode.EXECUTE_PLAN));
        try {
            assertThat(tools.report(
                "Done",
                List.of("Actual count: 1"),
                List.of(),
                List.of(),
                List.of("report.md"),
                List.of()
            )).isEqualTo("Recorded execution evidence for plan: Plan");
        } finally {
            PlanToolExecutionContext.clear();
        }

        assertThat(service.activePlan("conversation-1").orElseThrow().executionEvidence())
            .contains("Summary: Done")
            .contains("Artifact: report.md");
        assertThatThrownBy(() -> tools.report(null, List.of(), List.of(), List.of(), List.of(), List.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("execute_plan mode");
    }

    @Test
    void normalizesStructuredExecutionEvidenceAndArtifactAliases() {
        assertThat(PlanSaveTools.normalizeEvidence(List.of(
            Map.of("criterion", "Cites sources", "evidence", "Report includes source appendix"),
            Map.of("text", "Plain evidence field"),
            "Already formatted"
        ))).containsExactly(
            "Criterion: Cites sources | Evidence: Report includes source appendix",
            "Plain evidence field",
            "Already formatted"
        );

        assertThat(PlanSaveTools.normalizeTextList(List.of(
            Map.of("path", "workspace/report.md"),
            Map.of("message", "Cloudflare blocked one source"),
            "Manual note"
        ))).containsExactly(
            "workspace/report.md",
            "Cloudflare blocked one source",
            "Manual note"
        );
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }
}
