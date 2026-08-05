package io.mindspice.core;

/**
 * Thrown when a conversation session cannot be found.
 */
public final class ConversationNotFoundException extends TurnLoopException {

    private final String sessionId;

    public ConversationNotFoundException(@NonNull String sessionId) {
        super("Conversation not found: " + sessionId);
        this.sessionId = sessionId;
    }

    @NonNull
    public String sessionId() {
        return sessionId;
    }
}
