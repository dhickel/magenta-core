package io.mindspice.core;

/**
 * Abstract base for all turn-loop exceptions.
 * All framework exceptions are unchecked.
 */
public abstract class TurnLoopException extends RuntimeException {

    protected TurnLoopException(@NonNull String message) {
        super(message);
    }

    protected TurnLoopException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
