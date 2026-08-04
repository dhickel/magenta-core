package io.mindspice.magenta2.ai.chat.tool.savedplan;

import java.util.List;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanToolContext;
import io.mindspice.magenta2.ai.chat.plan.PlanToolExecutionContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class SavedPlanTools {
    private final PlanService planService;

    public SavedPlanTools(PlanService planService) {
        this.planService = planService;
    }

    @Tool(
        name = "saved_plan_update_fields",
        description = "Set concise saved-plan title, summary, or goal fields by saved plan id context. Omitted fields are preserved."
    )
    public String updateFields(
        @ToolParam(required = false, description = "Short saved-plan title.")
        String title,
        @ToolParam(required = false, description = "One or two sentence saved-plan summary.")
        String summary,
        @ToolParam(required = false, description = "The synthesized reusable saved-plan goal.")
        String goal
    ) {
        PlanDefinition plan = planService.updateSavedTaskFields(requirePlanId("saved_plan_update_fields"), title, summary, goal);
        return "Updated saved plan fields: " + plan.title();
    }

    @Tool(
        name = "saved_plan_set_task",
        description = "Set the current saved-plan drafting phase, such as synthesize_saved_plan_seed, clarify_saved_plan, build_saved_plan_steps, or approval."
    )
    public String setTask(
        @ToolParam(description = "Short snake_case name for the current saved-plan drafting phase.")
        String planningTask
    ) {
        PlanDefinition plan = planService.setSavedTaskPlanningTask(requirePlanId("saved_plan_set_task"), planningTask);
        return "Set saved-plan drafting task: " + plan.planningTask();
    }

    @Tool(
        name = "saved_plan_put_item",
        description = "Add or replace one keyed saved-plan item. Sections: input, output, deliverable, assumption, note, step, validation_criterion."
    )
    public String putItem(
        @ToolParam(description = "Saved-plan section to edit: input, output, deliverable, assumption, note, step, or validation_criterion.")
        String section,
        @ToolParam(description = "Positive integer key for this item.")
        Integer key,
        @ToolParam(required = false, description = "Complete text for deliverable, assumption, note, step, or validation_criterion sections.")
        String text,
        @ToolParam(required = false, description = "Input/output field name.")
        String name,
        @ToolParam(required = false, description = "Input/output type: string, user_message, file_path, json, or number.")
        String type,
        @ToolParam(required = false, description = "Whether this input/output is an array.")
        Boolean array,
        @ToolParam(required = false, description = "Input/output description.")
        String description,
        @ToolParam(required = false, description = "Whether this input/output is required.")
        Boolean required,
        @ToolParam(required = false, description = "Optional JSON schema hint.")
        String schema
    ) {
        String planId = requirePlanId("saved_plan_put_item");
        PlanDefinition plan;
        if ("input".equals(section) || "output".equals(section)) {
            plan = planService.putSavedTaskFieldItem(planId, section, key, new PlanFieldDefinition(
                name,
                PlanFieldType.fromWireName(type),
                Boolean.TRUE.equals(array),
                description,
                Boolean.TRUE.equals(required),
                schema
            ));
        } else {
            plan = planService.putSavedTaskTextItem(planId, section, key, text);
        }
        return "Updated saved-plan " + section + " " + key + " for: " + plan.title();
    }

    @Tool(
        name = "saved_plan_delete_item",
        description = "Delete one keyed saved-plan item. Sections: input, output, deliverable, assumption, note, step, validation_criterion."
    )
    public String deleteItem(
        @ToolParam(description = "Saved-plan section to edit.")
        String section,
        @ToolParam(description = "Positive integer key to delete.")
        Integer key
    ) {
        planService.deleteSavedTaskItem(requirePlanId("saved_plan_delete_item"), section, key);
        return "Deleted saved-plan " + section + " " + key + ".";
    }

    @Tool(
        name = "saved_plan_ask_user_questions",
        description = "Queue one to five focused follow-up questions for the saved-plan user. Each string must be one atomic question."
    )
    public String askQuestions(
        @ToolParam(description = "One to five focused follow-up questions.")
        List<String> questions
    ) {
        PlanDefinition plan = planService.askSavedTaskQuestions(requirePlanId("saved_plan_ask_user_questions"), questions);
        return "Queued " + plan.pendingQuestions().size() + " saved-plan question(s).";
    }

    @Tool(
        name = "saved_plan_ready_for_approval",
        description = "Mark the saved-plan draft ready for approval after goal, outputs/deliverables, steps, and validation criteria are complete."
    )
    public String readyForApproval() {
        PlanDefinition plan = planService.markSavedTaskReadyForApproval(requirePlanId("saved_plan_ready_for_approval"));
        return "Saved plan ready for approval: " + plan.title();
    }

    private String requirePlanId(String toolName) {
        PlanToolContext context = PlanToolExecutionContext.current();
        if (context == null || context.mode() != PlanMode.TASK) {
            throw new IllegalStateException(toolName + " is available only during saved-plan drafting");
        }
        return context.conversationId();
    }
}
