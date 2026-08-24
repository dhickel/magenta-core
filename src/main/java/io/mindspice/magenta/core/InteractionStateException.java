package io.mindspice.magenta.core;

/**
 * Indicates that an answer or cancellation targeted an interaction that is
 * absent or no longer in an applicable durable state.
 */
public final class InteractionStateException extends TurnLoopException {

    private final String sessionId;
    private final String interactionId;
    private final InteractionState state;

    public InteractionStateException(@NonNull String sessionId,
                                     @NonNull String interactionId,
                                     @NonNull InteractionState state) {
        super("Interaction '" + interactionId + "' for session '" + sessionId
            + "' is in state " + state);
        this.sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
        this.interactionId = ResponseValidation.nonBlank(interactionId, "interactionId");
        this.state = ResponseValidation.required(state, "state");
    }

    @NonNull
    public String sessionId() {
        return sessionId;
    }

    @NonNull
    public String interactionId() {
        return interactionId;
    }

    @NonNull
    public InteractionState state() {
        return state;
    }
}
