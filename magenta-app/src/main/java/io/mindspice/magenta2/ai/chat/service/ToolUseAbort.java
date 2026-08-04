package io.mindspice.magenta2.ai.chat.service;

import java.util.List;

/**
 * Thrown by {@link ToolLoopGuard} when runaway tool use is detected.
 *
 * <p>Carries a human-readable reason and optional recent error details
 * so the tool loop can construct a control message for the model.
 */
public final class ToolUseAbort extends IllegalStateException {
    private final List<String> recentErrors;

    public ToolUseAbort(String message) {
        this(message, List.of());
    }

    public ToolUseAbort(String message, List<String> recentErrors) {
        super(message);
        this.recentErrors = recentErrors == null ? List.of() : List.copyOf(recentErrors);
    }

    public List<String> recentErrors() {
        return recentErrors;
    }
}
