package io.mindspice.magenta.core;

/** Indicates that a session is already owned by another execution or drain. */
public final class TurnLoopBusyException extends TurnLoopException {

    private final String sessionId;

    public TurnLoopBusyException(@NonNull String sessionId) {
        super("Session is busy: " + sessionId);
        this.sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
    }

    @NonNull
    public String sessionId() {
        return sessionId;
    }
}
