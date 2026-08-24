package io.mindspice.magenta.core;

/**
 * Thrown when an execution fails unexpectedly and carries the safe typed
 * failure facts exposed to callers and lifecycle observers.
 */
public sealed class TurnFailedException extends TurnLoopException
    permits TurnFailedException.ContextExhaustedException {

    private final TurnFailure failure;

    public TurnFailedException(@NonNull String message,
                               @NonNull TurnFailure failure) {
        super(ResponseValidation.nonBlank(message, "message"));
        this.failure = ResponseValidation.required(failure, "failure");
    }

    @NonNull
    public TurnFailure failure() {
        return failure;
    }

    /**
     * Context window exhaustion after the configured compaction and trimming
     * strategies have been attempted.
     */
    public static final class ContextExhaustedException extends TurnFailedException {

        private final ContextUsage contextUsage;

        public ContextExhaustedException(@NonNull String message,
                                         @NonNull TurnFailure failure,
                                         @NonNull ContextUsage contextUsage) {
            super(message, failure);
            this.contextUsage = ResponseValidation.required(contextUsage, "contextUsage");
        }

        @NonNull
        public ContextUsage contextUsage() {
            return contextUsage;
        }
    }
}
