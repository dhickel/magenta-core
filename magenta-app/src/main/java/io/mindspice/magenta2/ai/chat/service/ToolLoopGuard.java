package io.mindspice.magenta2.ai.chat.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.util.StringUtils;

/**
 * Guards the tool execution loop against runaway tool use.
 *
 * <p>Tracks identical tool calls (same name + normalized arguments) and recent tool
 * error rates. Throws {@link ToolUseAbort} when either threshold is exceeded, which
 * the tool loop catches to send a control message before the final model call.
 */
public final class ToolLoopGuard {
    private static final int TOOL_ERROR_WINDOW_SIZE = 8;
    private static final int TOOL_ERROR_WINDOW_LIMIT = 5;
    private static final int IDENTICAL_TOOL_CALL_LIMIT = 5;

    private final Map<String, Integer> identicalToolCallCounts = new HashMap<>();
    private final Deque<ToolOutcome> recentToolOutcomes = new ArrayDeque<>();
    private int recentErrorCount = 0;

    void recordToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        for (AssistantMessage.ToolCall toolCall : toolCalls == null ? List.<AssistantMessage.ToolCall>of() : toolCalls) {
            String key = toolCall.name() + "\n" + normalizeArguments(toolCall.arguments());
            int count = identicalToolCallCounts.merge(key, 1, Integer::sum);
            if (count >= IDENTICAL_TOOL_CALL_LIMIT) {
                throw new ToolUseAbort(
                    "Tool execution stopped after " + count + " identical calls to " + toolCall.name()
                );
            }
        }
    }

    void recordToolResponses(ToolExecutionResult toolExecutionResult) {
        ToolResponseMessage latestToolResponseMessage = latestToolResponseMessage(toolExecutionResult);
        if (latestToolResponseMessage == null) {
            recordToolResult(false, null);
            return;
        }
        for (ToolResponseMessage.ToolResponse response : latestToolResponseMessage.getResponses()) {
            String responseData = response.responseData();
            recordToolResult(isToolError(responseData), responseData);
        }
    }

    private ToolResponseMessage latestToolResponseMessage(ToolExecutionResult toolExecutionResult) {
        if (toolExecutionResult == null || toolExecutionResult.conversationHistory() == null) {
            return null;
        }
        ToolResponseMessage latest = null;
        for (org.springframework.ai.chat.messages.Message message : toolExecutionResult.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                latest = toolResponseMessage;
            }
        }
        return latest;
    }

    private void recordToolResult(boolean error, String responseData) {
        recentToolOutcomes.addLast(new ToolOutcome(error, error ? summarizeToolError(responseData) : null));
        if (error) {
            recentErrorCount++;
        }
        while (recentToolOutcomes.size() > TOOL_ERROR_WINDOW_SIZE) {
            if (recentToolOutcomes.removeFirst().error()) {
                recentErrorCount--;
            }
        }
        if (recentToolOutcomes.size() == TOOL_ERROR_WINDOW_SIZE && recentErrorCount >= TOOL_ERROR_WINDOW_LIMIT) {
            throw new ToolUseAbort(
                "Tool execution stopped after " + recentErrorCount + " errors in the last "
                    + TOOL_ERROR_WINDOW_SIZE + " tool responses",
                recentErrors()
            );
        }
    }

    private List<String> recentErrors() {
        return recentToolOutcomes.stream()
            .filter(ToolOutcome::error)
            .map(ToolOutcome::detail)
            .filter(StringUtils::hasText)
            .toList();
    }

    private String summarizeToolError(String responseData) {
        if (!StringUtils.hasText(responseData)) {
            return "Tool returned an empty error response.";
        }
        String summary = responseData.replaceAll("\\s+", " ").trim();
        return summary.length() > 500 ? summary.substring(0, 500) + " [truncated]" : summary;
    }

    private boolean isToolError(String responseData) {
        if (!StringUtils.hasText(responseData)) {
            return false;
        }
        String normalized = responseData.toLowerCase(Locale.ROOT);
        return normalized.contains("\"timedout\":true");
    }

    private String normalizeArguments(String arguments) {
        return StringUtils.hasText(arguments) ? arguments.replaceAll("\\s+", " ").trim() : "";
    }

    private record ToolOutcome(boolean error, String detail) {
    }
}
