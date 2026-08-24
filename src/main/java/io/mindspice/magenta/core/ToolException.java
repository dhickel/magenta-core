package io.mindspice.magenta.core;

import java.util.List;

/**
 * Sealed exception hierarchy for tool-related failures.
 */
public sealed class ToolException extends TurnLoopException
    permits ToolException.ArgumentCoercionFailed, ToolException.ExecutionFailed,
            ToolException.LoopExceeded, ToolException.AccessDenied {

    private final String toolName;

    protected ToolException(@NonNull String toolName, @NonNull String message) {
        super(message);
        this.toolName = toolName;
    }

    protected ToolException(@NonNull String toolName, @NonNull String message,
                            @Nullable Throwable cause) {
        super(message, cause);
        this.toolName = toolName;
    }

    @NonNull
    public String toolName() {
        return toolName;
    }

    // ── Subtypes ──

    public static final class ArgumentCoercionFailed extends ToolException {
        private final String callId;

        public ArgumentCoercionFailed(@NonNull String toolName,
                                      @NonNull String callId,
                                      @NonNull String message) {
            super(toolName, message);
            this.callId = callId;
        }

        @NonNull
        public String callId() {
            return callId;
        }
    }

    public static final class ExecutionFailed extends ToolException {
        private final String callId;

        public ExecutionFailed(@NonNull String toolName,
                               @NonNull String callId,
                               @NonNull String message,
                               @Nullable Throwable cause) {
            super(toolName, message, cause);
            this.callId = callId;
        }

        @NonNull
        public String callId() {
            return callId;
        }
    }

    public static final class LoopExceeded extends ToolException {
        private final List<String> recentErrors;

        public LoopExceeded(@NonNull String toolName,
                            @NonNull String message,
                            @NonNull List<String> recentErrors) {
            super(toolName, message);
            this.recentErrors = List.copyOf(recentErrors);
        }

        @NonNull
        public List<String> recentErrors() {
            return recentErrors;
        }
    }

    public static final class AccessDenied extends ToolException {

        public AccessDenied(@NonNull String toolName, @NonNull String message) {
            super(toolName, message);
        }
    }
}
