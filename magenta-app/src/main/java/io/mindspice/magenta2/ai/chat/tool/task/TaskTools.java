package io.mindspice.magenta2.ai.chat.tool.task;

import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanToolContext;
import io.mindspice.magenta2.ai.chat.plan.PlanToolExecutionContext;
import io.mindspice.magenta2.ai.chat.task.TaskDraft;
import io.mindspice.magenta2.ai.chat.task.TaskFieldDefinition;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.task.TaskValueType;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TaskTools {
    private final TaskService taskService;

    public TaskTools(TaskService taskService) {
        this.taskService = taskService;
    }

    @Tool(name = "task_set_goal", description = "Set or replace the single goal for the active reusable task draft.")
    public String setGoal(@ToolParam(description = "The reusable task goal.") String goal) {
        PlanToolContext context = requireMode(PlanMode.TASK, "task_set_goal");
        TaskDraft draft = taskService.setGoal(context.conversationId(), goal);
        return "Set task goal: " + draft.goal();
    }

    @Tool(name = "task_set_task", description = "Set the current task-design phase, such as define_runtime_inputs, define_outputs, build_task_steps, or approval_readiness.")
    public String setTask(@ToolParam(description = "Short snake_case name for the current task-design phase.") String planningTask) {
        PlanToolContext context = requireMode(PlanMode.TASK, "task_set_task");
        TaskDraft draft = taskService.setTask(context.conversationId(), planningTask);
        return "Set current task-design phase: " + draft.planningTask();
    }

    @Tool(name = "task_put_item", description = "Add or replace one keyed task draft item. Sections: input, output, assumption, note, step, validation_criterion.")
    public String putItem(
        @ToolParam(description = "Task section to edit: input, output, assumption, note, step, or validation_criterion.")
        String section,
        @ToolParam(description = "Positive integer key for this item.")
        Integer key,
        @ToolParam(required = false, description = "Complete text for text sections.")
        String text,
        @ToolParam(required = false, description = "Input/output name for typed definition sections.")
        String name,
        @ToolParam(required = false, description = "Input/output type: string, long_text, file_path, json, number, boolean.")
        String type,
        @ToolParam(required = false, description = "Input/output description.")
        String description,
        @ToolParam(required = false, description = "Whether this input/output is required.")
        Boolean required,
        @ToolParam(required = false, description = "Optional loose schema hint.")
        String schema
    ) {
        PlanToolContext context = requireMode(PlanMode.TASK, "task_put_item");
        TaskDraft draft;
        if ("input".equals(section) || "output".equals(section)) {
            draft = taskService.putFieldItem(
                context.conversationId(),
                section,
                key,
                new TaskFieldDefinition(
                    name,
                    TaskValueType.fromWireName(type),
                    description,
                    Boolean.TRUE.equals(required),
                    schema
                )
            );
        } else {
            draft = taskService.putTextItem(context.conversationId(), section, key, text);
        }
        return "Updated " + section + " " + key + " for task: " + (draft.title() == null ? "untitled" : draft.title());
    }

    @Tool(name = "task_delete_item", description = "Delete one keyed task draft item. Sections: input, output, assumption, note, step, validation_criterion.")
    public String deleteItem(
        @ToolParam(description = "Task section to edit.") String section,
        @ToolParam(description = "Positive integer key to delete.") Integer key
    ) {
        PlanToolContext context = requireMode(PlanMode.TASK, "task_delete_item");
        taskService.deleteItem(context.conversationId(), section, key);
        return "Deleted " + section + " " + key + ".";
    }

    @Tool(name = "task_ready_for_approval", description = "Mark the current reusable task draft ready for user approval.")
    public String readyForApproval() {
        PlanToolContext context = requireMode(PlanMode.TASK, "task_ready_for_approval");
        TaskDraft draft = taskService.markReadyForApproval(context.conversationId());
        return "Task ready for approval: " + draft.title();
    }

    @Tool(name = "task_report", description = "Record evidence for the active task run.")
    public String report(
        @ToolParam(required = false, description = "Brief execution result summary.") String summary,
        @ToolParam(required = false, description = "Specific execution evidence entries.") List<String> evidence
    ) {
        PlanToolContext context = requireMode(PlanMode.EXECUTE_TASK, "task_report");
        TaskRun run = taskService.recordReport(context.runId(), summary, evidence);
        return "Recorded task evidence for run: " + run.id();
    }

    @Tool(name = "task_complete", description = "Complete the active task run with output values keyed by declared output name. Missing required outputs are rejected.")
    public String complete(
        @ToolParam(description = "Output values keyed by declared output name.") Map<String, Object> outputValues,
        @ToolParam(required = false, description = "Final user-facing completion message.") String finalMessage,
        @ToolParam(required = false, description = "Specific execution evidence entries.") List<String> evidence,
        @ToolParam(required = false, description = "Copy retained temp/run files into the final output directory and register them as copied output artifacts.")
        Boolean includeTempWithOutput
    ) {
        PlanToolContext context = requireMode(PlanMode.EXECUTE_TASK, "task_complete");
        TaskRun run = taskService.completeRun(
            context.runId(), outputValues, finalMessage, evidence, Boolean.TRUE.equals(includeTempWithOutput));
        taskService.clearExecutionContext(context.conversationId());
        return "Task completed: " + run.id();
    }

    private PlanToolContext requireMode(PlanMode mode, String toolName) {
        PlanToolContext context = PlanToolExecutionContext.current();
        if (context == null || context.mode() != mode) {
            throw new IllegalStateException(toolName + " is available only in " + mode.name().toLowerCase() + " mode");
        }
        return context;
    }
}
