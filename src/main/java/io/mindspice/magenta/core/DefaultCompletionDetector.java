package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.CompletionDetector;

/**
 * Default {@link CompletionDetector} that considers a turn complete when
 * there are no pending tool calls. This is the simplest plausible heuristic
 * and is suitable for most chat-oriented use cases.
 *
 * <p>Embedding applications can provide a custom detector for specialised
 * multi-step workflows that require explicit completion signals.</p>
 */
public final class DefaultCompletionDetector implements CompletionDetector {

    private static final DefaultCompletionDetector INSTANCE = new DefaultCompletionDetector();

    private DefaultCompletionDetector() {}

    /** Returns the singleton instance. */
    public static DefaultCompletionDetector instance() {
        return INSTANCE;
    }

    /**
     * Always returns {@code false} — the engine's evaluate phase already
     * handles tool-call-absence natively. This detector delegates the
     * completion decision to the engine's built-in logic, which advances
     * to FINALIZE when no tool calls and no empty-response repairs remain.
     */
    @Override
    public boolean isComplete(@NonNull TurnContext context) {
        return false;
    }
}
