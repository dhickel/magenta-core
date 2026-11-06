package io.mindspice.magenta.core;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/** Package-private normalization shared by callback and provider boundaries. */
final class ToolResultSupport {

    private ToolResultSupport() {
    }

    static ToolResult nonNull(ToolResult result) {
        return result == null
            ? failure("Tool execution failed", ToolFailureCategory.EXECUTION_FAILED)
            : result;
    }

    static ToolResult notFound(String toolName) {
        return failure("Tool not found: " + safeToolName(toolName), ToolFailureCategory.NOT_FOUND);
    }

    static ToolResult fromThrowable(Throwable failure, String toolName) {
        Throwable cause = unwrap(failure);
        ToolFailureCategory category = category(cause);
        String safeToolName = safeToolName(toolName);
        String message = switch (category) {
            case NOT_FOUND -> "Tool not found: " + safeToolName;
            case ACCESS_DENIED -> "Tool access denied: " + safeToolName;
            case INVALID_ARGUMENTS -> "Invalid arguments for tool: " + safeToolName;
            case LOOP_EXCEEDED -> "Tool loop limit exceeded";
            case EXECUTION_FAILED -> "Tool execution failed";
        };
        return failure(message, category);
    }

    static String modelText(ToolResult result) {
        return switch (result) {
            case ToolResult.Success success -> success.output();
            case ToolResult.Failure failure -> failure.message();
        };
    }

    static ToolFailureCategory category(Throwable failure) {
        if (failure instanceof ToolException.ArgumentCoercionFailed) {
            return ToolFailureCategory.INVALID_ARGUMENTS;
        }
        if (failure instanceof ToolException.AccessDenied) {
            return ToolFailureCategory.ACCESS_DENIED;
        }
        if (failure instanceof ToolException.LoopExceeded) {
            return ToolFailureCategory.LOOP_EXCEEDED;
        }
        return ToolFailureCategory.EXECUTION_FAILED;
    }

    private static ToolResult.Failure failure(String message, ToolFailureCategory category) {
        return new ToolResult.Failure(message, category);
    }

    private static String safeToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return "requested tool";
        }
        return toolName.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable result = failure;
        while ((result instanceof CompletionException || result instanceof ExecutionException)
            && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
