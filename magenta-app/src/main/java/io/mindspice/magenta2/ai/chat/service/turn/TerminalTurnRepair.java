package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.service.ToolUseAbort;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Enforces terminal behavior guarantees for chat turns.
 * Pure logic — no mutable state.
 */
public class TerminalTurnRepair implements RepairStrategy, CompletionDetector {

    public TerminalTurnRepair() {
    }

    // ── CompletionDetector SPI ──

    @Override
    public boolean isComplete(TurnContext context) {
        return true;
    }

    // ── RepairStrategy SPI ──

    @Override
    public List<Message> repair(TurnContext context, RepairReason reason) {
        return switch (reason) {
            case EMPTY_RESPONSE -> List.of(emptyFinalResponseControlMessage());
            case TOOL_LOOP_EXCEEDED -> List.of();
            case COMPLETION_NOT_DETECTED -> List.of();
            case CUSTOM -> List.of();
        };
    }

    // ── Empty final response detection ──

    public boolean hasNoContentOrToolCalls(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.hasToolCalls()) {
            return false;
        }
        AssistantMessage output = response.getResult().getOutput();
        return output == null || !StringUtils.hasText(output.getText());
    }

    public SystemMessage emptyFinalResponseControlMessage() {
        return new SystemMessage("""
            Your previous response had thinking but no user-visible message and no tool calls.
            Continue the turn now with a concise user-visible answer or an appropriate tool call.
            Do not return an empty assistant message.
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
