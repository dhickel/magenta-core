package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.service.ToolUseAbort;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.StringUtils;

/**
 * Enforces terminal behavior guarantees for PLAN and EXECUTE modes.
 * Pure logic — no mutable state, no I/O beyond PlanService/TaskService queries.
 */
public class TerminalTurnRepair {

    static final int EMPTY_FINAL_RESPONSE_RETRY_LIMIT = 2;
    static final int PLAN_TURN_REPAIR_RETRY_LIMIT = 2;
    static final int EXECUTION_COMPLETION_REPAIR_RETRY_LIMIT = 2;

    private final PlanService planService;
    private final TaskService taskService;

    public TerminalTurnRepair(PlanService planService, TaskService taskService) {
        this.planService = planService;
        this.taskService = taskService;
    }

    // ── Empty final response detection ──

    public boolean hasNoContentOrToolCalls(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.hasToolCalls()) {
            return false;
        }
        AssistantMessage output = response.getResult().getOutput();
        return output == null || !StringUtils.hasText(output.getText());
    }

    public SystemMessage emptyFinalResponseControlMessage(PlanMode mode) {
        String instruction = switch (mode) {
            case PLAN -> """
                Your previous response had thinking but no user-visible message and no tool calls, so Magenta cannot treat it as a completed planning turn.
                Continue the PLAN-mode turn now. Use planning edit tools if the draft state should change, then end by calling ask_user_questions or plan_ready_for_approval.
                Do not return an empty assistant message.
                """;
            case TASK -> """
                Your previous response had thinking but no user-visible message and no tool calls, so Magenta cannot treat it as a completed task-design turn.
                Continue the TASK-mode turn now. Use task edit tools if the draft state should change, then end by calling ask_user_questions or task_ready_for_approval.
                Do not return an empty assistant message.
                """;
            case EXECUTE_PLAN -> """
                Your previous response had thinking but no user-visible message and no tool calls, so Magenta cannot treat it as completed saved-plan execution.
                Continue executing the approved plan now. Use tools as needed and call plan_complete before any final user-visible completion answer.
                Do not return an empty assistant message.
                """;
            case EXECUTE_TASK -> """
                Your previous response had thinking but no user-visible message and no tool calls, so Magenta cannot treat it as completed task execution.
                Continue executing the task now and call task_complete with declared output values before any final user-visible completion answer.
                Do not return an empty assistant message.
                """;
            case NORMAL -> """
                Your previous response had thinking but no user-visible message and no tool calls.
                Continue the turn now with a concise user-visible answer or an appropriate tool call.
                Do not return an empty assistant message.
                """;
        };
        return new SystemMessage(instruction.trim());
    }

    // ── Plan turn repair ──

    public boolean needsPlanTurnRepair(String conversationId, PlanMode mode) {
        if (mode != PlanMode.PLAN || planService == null) {
            return false;
        }
        ChatPlanState state = planService.view(conversationId);
        return !"READY_FOR_APPROVAL".equals(state.status())
            && !StringUtils.hasText(state.promptQuestion());
    }

    public SystemMessage invalidPlanTurnControlMessage() {
        return new SystemMessage("""
            Your PLAN-mode turn attempted to finish without a queued clarification question or a plan ready for approval.
            Continue the same turn now. Update the draft with keyed planning tools as needed, then call exactly one terminal planning tool:
            - ask_user_questions if the user needs to clarify, choose an approach, confirm constraints, or provide more context.
            - plan_ready_for_approval only when the plan is complete enough to execute without guessing.
            Do not finish with ordinary assistant text.
            """.trim());
    }

    // ── Execution completion repair ──

    public boolean needsExecutionCompletionRepair(String conversationId, PlanMode mode) {
        if (mode == PlanMode.EXECUTE_PLAN) {
            return planService != null && planService.mode(conversationId) == PlanMode.EXECUTE_PLAN;
        }
        return mode == PlanMode.EXECUTE_TASK
            && taskService != null
            && taskService.mode(conversationId) == PlanMode.EXECUTE_TASK;
    }

    public SystemMessage invalidExecutionCompletionControlMessage(PlanMode mode) {
        if (mode == PlanMode.EXECUTE_TASK) {
            return new SystemMessage("""
                Your task execution attempted to finish without task completion.
                Continue the same execution turn now. You may keep working or report incomplete work, but before any final user-visible completion answer you must call task_complete.
                task_complete must include outputValues keyed exactly by declared output name. Required outputs may not be empty.
                Do not finish with ordinary assistant text until task_complete has accepted the run.
                """.trim());
        }
        return new SystemMessage("""
            Your saved-plan execution attempted to finish without validator-gated completion.
            Continue the same execution turn now. You may keep working or report incomplete work, but before any final user-visible completion answer you must call plan_complete.
            plan_complete must include one evidence entry per approved validation criterion, formatted as:
            Criterion: <exact criterion text> | Evidence: <specific proof>
            Also include your finalMessage: the exact text that will be shown to the user after validation passes.
            If a criterion is not met, include it in unmetCriteria with the specific missing work or evidence.
            If validation fails, address the returned remediation and call plan_complete again.
            Do not finish with ordinary assistant text until plan_complete has passed validation.
            """.trim());
    }

    // ── Tool use abort ──

    public SystemMessage toolUseAbortControlMessage(ToolUseAbort abort) {
        StringBuilder message = new StringBuilder("""
            Tool use was aborted by Magenta before another tool call was allowed.
            Reason: %s
            """.formatted(abort.getMessage()).trim());
        if (!abort.recentErrors().isEmpty()) {
            message.append("\nRecent tool errors:");
            for (String error : abort.recentErrors()) {
                message.append("\n- ").append(error);
            }
        }
        message.append(
            "\nThe prior tool results remain available in the conversation context. "
                + "Do not request more tools for this turn; explain the failure state or continue from the available information."
        );
        return new SystemMessage(message.toString());
    }
}
