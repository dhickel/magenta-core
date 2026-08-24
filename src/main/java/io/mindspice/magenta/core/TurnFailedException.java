package io.mindspice.magenta.core;

/**
 * Turn failed with a diagnostic. Sealed so callers can match on known failure modes.
 */
public sealed class TurnFailedException extends TurnLoopException
    permits TurnFailedException.ContextExhaustedException {

    private final TurnDiagnostic diagnostic;

    public TurnFailedException(@NonNull String message, @NonNull TurnDiagnostic diagnostic) {
        super(message);
        this.diagnostic = diagnostic;
    }

    @NonNull
    public TurnDiagnostic diagnostic() {
        return diagnostic;
    }

    /**
     * Context window exhausted — the turn cannot continue without compaction or
     * trimming that has already been attempted.
     */
    public static final class ContextExhaustedException extends TurnFailedException {

        private final ContextUsage contextUsage;

        public ContextExhaustedException(@NonNull String message,
                                         @NonNull TurnDiagnostic diagnostic,
                                         @NonNull ContextUsage contextUsage) {
            super(message, diagnostic);
            this.contextUsage = contextUsage;
        }

        @NonNull
        public ContextUsage contextUsage() {
            return contextUsage;
        }
    }
}
