package io.mindspice.magenta2.ai.chat.tool.plan;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.mindspice.magenta2.ai.chat.plan.PlanCompletionService;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanToolContext;
import io.mindspice.magenta2.ai.chat.plan.PlanToolExecutionContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class PlanSaveTools {
    private final PlanService planService;
    private final ObjectProvider<PlanCompletionService> planCompletionServiceProvider;

    public PlanSaveTools(PlanService planService) {
        this(planService, null);
    }

    @Autowired
    public PlanSaveTools(
        PlanService planService,
        @Autowired(required = false) ObjectProvider<PlanCompletionService> planCompletionServiceProvider
    ) {
        this.planService = planService;
        this.planCompletionServiceProvider = planCompletionServiceProvider;
    }

    @Tool(
        name = "plan_update",
        description = "Legacy broad draft update. Prefer plan_set_goal, plan_set_task, plan_put_item, and plan_delete_item for deterministic keyed edits."
    )
    public String update(
        @ToolParam(required = false, description = "The clarified user goal this plan is intended to accomplish.")
        String goal,
        @ToolParam(required = false, description = "Short title for the plan.")
        String title,
        @ToolParam(required = false, description = "One or two sentence summary of the intended work.")
        String summary,
        @ToolParam(required = false, description = "Planning notes, constraints, risks, gotchas, and context that do not fit elsewhere.")
        String notes,
        @ToolParam(required = false, description = "Concrete expected user-facing outputs or work products.")
        List<String> deliverables,
        @ToolParam(required = false, description = "Ignored for anonymous chat plans. Saved /plans chats collect typed inputs.")
        List<String> inputs,
        @ToolParam(required = false, description = "Ignored for anonymous chat plans. Use deliverables instead.")
        List<String> outputs,
        @ToolParam(required = false, description = "Explicit defaults, decisions, or assumptions locked into the plan.")
        List<String> assumptions,
        @ToolParam(required = false, description = "Ordered execution steps. Each step must state what to inspect or change and how to verify it.")
        List<String> steps,
        @ToolParam(required = false, description = "Criteria for judging whether execution succeeded.")
        List<String> validationCriteria
    ) {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_update");
        PlanDefinition plan = planService.updateDraftPlan(
            context.conversationId(),
            goal,
            title,
            summary,
            notes,
            deliverables,
            inputs,
            outputs,
            assumptions,
            steps,
            validationCriteria
        );
        return "Updated plan draft: " + (plan.title() == null ? "untitled" : plan.title());
    }

    String update(
        String goal,
        String title,
        String summary,
        String notes,
        List<String> deliverables,
        List<String> steps,
        List<String> validationCriteria
    ) {
        return update(goal, title, summary, notes, deliverables, null, null, null, steps, validationCriteria);
    }

    @Tool(
        name = "plan_set_goal",
        description = "Set or replace the single goal for the active draft plan. Use this as soon as the user's goal is known."
    )
    public String setGoal(
        @ToolParam(description = "The clarified user goal this plan is intended to accomplish.")
        String goal
    ) {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_set_goal");
        PlanDefinition plan = planService.setGoal(context.conversationId(), goal);
        return "Set plan goal: " + plan.goal();
    }

    @Tool(
        name = "plan_set_task",
        description = "Set the current planning task or phase, such as goal_and_deliverables, collect_user_guidance, clarify_and_elaborate, build_plan_steps, or approval_readiness."
    )
    public String setTask(
        @ToolParam(description = "Short snake_case name for the current planning task.")
        String planningTask
    ) {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_set_task");
        PlanDefinition plan = planService.setPlanningTask(context.conversationId(), planningTask);
        return "Set current planning task: " + plan.planningTask();
    }

    @Tool(
        name = "plan_put_item",
        description = "Add or replace one keyed anonymous plan item. Sections: deliverable, assumption, note, step, validation_criterion. The key is a positive integer; using an existing key replaces that item."
    )
    public String putItem(
        @ToolParam(description = "Plan section to edit: deliverable, assumption, note, step, or validation_criterion.")
        String section,
        @ToolParam(description = "Positive integer key for this item.")
        Integer key,
        @ToolParam(description = "Complete item text. Step text must include concrete work and verification details.")
        String text
    ) {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_put_item");
        PlanDefinition plan = planService.putItem(context.conversationId(), section, key, text);
        return "Updated " + section + " " + key + " for plan: " + (plan.title() == null ? "untitled" : plan.title());
    }

    @Tool(
        name = "plan_delete_item",
        description = "Delete one keyed anonymous plan item. Sections: deliverable, assumption, note, step, validation_criterion."
    )
    public String deleteItem(
        @ToolParam(description = "Plan section to edit: deliverable, assumption, note, step, or validation_criterion.")
        String section,
        @ToolParam(description = "Positive integer key to delete.")
        Integer key
    ) {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_delete_item");
        planService.deleteItem(context.conversationId(), section, key);
        return "Deleted " + section + " " + key + ".";
    }

    @Tool(
        name = "plan_ready_for_approval",
        description = "Mark the current draft plan ready for user approval only after goal, deliverables/outputs, assumptions, detailed steps, and validation criteria are clear enough to execute without guessing."
    )
    public String readyForApproval() {
        PlanToolContext context = requireMode(PlanMode.PLAN, "plan_ready_for_approval");
        PlanDefinition plan = planService.markReadyForApproval(context.conversationId());
        return "Plan ready for approval: " + plan.title();
    }

    @Tool(
        name = "plan_report",
        description = "Record execution evidence for the active saved plan. Each entry should address one validation criterion from the approved plan. Use plan_complete, not plan_report, when requesting final completion validation."
    )
    public String report(
        @ToolParam(required = false, description = "Brief execution result summary.")
        String summary,
        @ToolParam(required = false, description = "Per-criterion evidence. Each item may be a string or an object with criterion/evidence/text fields.")
        List<Object> evidence,
        @ToolParam(required = false, description = "Any deviations from the saved plan or validation criteria.")
        List<Object> deviations,
        @ToolParam(required = false, description = "Validation criteria that were not met.")
        List<Object> unmetCriteria,
        @ToolParam(required = false, description = "Artifacts created or used during execution, such as reports, scripts, or evidence files.")
        List<Object> artifactPaths,
        @ToolParam(required = false, description = "Alias for artifactPaths. Artifacts created or used during execution.")
        List<Object> artifacts
    ) {
        PlanToolContext context = requireMode(PlanMode.EXECUTE_PLAN, "plan_report");
        PlanDefinition plan = planService.recordExecutionReport(
            context.conversationId(),
            summary,
            normalizeEvidence(evidence),
            normalizeTextList(deviations),
            normalizeTextList(unmetCriteria),
            normalizeArtifacts(artifactPaths, artifacts)
        );
        return "Recorded execution evidence for plan: " + plan.title();
    }

    @Tool(
        name = "plan_complete",
        description = "Request final validation for an executed saved plan. Provide evidence for each validation criterion from the approved plan. A validator reviews the plan, evidence, artifact contents, and the proposed final message; if validation passes, finalMessage is delivered verbatim as the completion message to the user. If validation fails, continue with the returned remediation."
    )
    public String complete(
        @ToolParam(required = false, description = "Brief execution result summary.")
        String summary,
        @ToolParam(required = false, description = "Per-criterion evidence. Each item may be a string or an object with criterion/evidence/text fields.")
        List<Object> evidence,
        @ToolParam(required = false, description = "Any deviations from the saved plan or validation criteria.")
        List<Object> deviations,
        @ToolParam(required = false, description = "Validation criteria that remain unmet.")
        List<Object> unmetCriteria,
        @ToolParam(required = false, description = "Artifacts created or used during execution. These files will be auto-read and their contents included in validation.")
        List<Object> artifactPaths,
        @ToolParam(required = false, description = "Alias for artifactPaths. Artifacts created or used during execution.")
        List<Object> artifacts,
        @ToolParam(required = false, description = "Intended final user-facing message summarizing the completed work. This exact text is delivered verbatim to the user after validation passes. Include a concise summary of what was accomplished and the outcome for each deliverable. If the deliverable itself IS a chat message (e.g., a drafted report, summary, or response), this IS the deliverable — write the complete user-facing text here.")
        String finalMessage
    ) {
        PlanToolContext context = requireMode(PlanMode.EXECUTE_PLAN, "plan_complete");
        PlanCompletionService planCompletionService = planCompletionServiceProvider == null
            ? null
            : planCompletionServiceProvider.getIfAvailable();
        if (planCompletionService == null) {
            throw new IllegalStateException("plan_complete requires PlanCompletionService");
        }
        return planCompletionService.complete(
            context.conversationId(),
            summary,
            normalizeEvidence(evidence),
            normalizeTextList(deviations),
            normalizeTextList(unmetCriteria),
            normalizeArtifacts(artifactPaths, artifacts),
            finalMessage
        );
    }

    static List<String> normalizeEvidence(List<Object> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(PlanSaveTools::evidenceText)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    static List<String> normalizeTextList(List<Object> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
            .map(PlanSaveTools::plainText)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private static List<String> normalizeArtifacts(List<Object> artifactPaths, List<Object> artifacts) {
        List<String> normalized = normalizeTextList(artifactPaths);
        return normalized.isEmpty() ? normalizeTextList(artifacts) : normalized;
    }

    private static String evidenceText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object criterion = firstPresent(map, "criterion", "validationCriterion", "validation_criterion");
            Object evidence = firstPresent(map, "evidence", "proof", "result");
            if (criterion != null && evidence != null) {
                return "Criterion: " + criterion + " | Evidence: " + evidence;
            }
            Object text = firstPresent(map, "text", "summary", "message");
            return text == null ? map.toString() : text.toString();
        }
        return plainText(value);
    }

    private static String plainText(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object text = firstPresent(map, "path", "file", "artifact", "text", "summary", "message", "value");
            return text == null ? map.toString() : text.toString();
        }
        return value == null ? null : value.toString();
    }

    private static Object firstPresent(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key)) {
                return map.get(key);
            }
        }
        return null;
    }

    private PlanToolContext requireMode(PlanMode mode, String toolName) {
        PlanToolContext context = PlanToolExecutionContext.current();
        if (context == null || context.mode() != mode) {
            throw new IllegalStateException(toolName + " is available only in " + mode.name().toLowerCase() + " mode");
        }
        return context;
    }
}
