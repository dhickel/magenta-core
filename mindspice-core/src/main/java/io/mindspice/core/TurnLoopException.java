package io.mindspice.core;

/**
 * Base for all turn-loop exceptions.
 * All framework exceptions are unchecked.
 */
public class TurnLoopException extends RuntimeException {

    public TurnLoopException(@NonNull String message) {
        super(message);
    }

    public TurnLoopException(@NonNull String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
