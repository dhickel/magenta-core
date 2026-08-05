package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnMessage;

import java.util.List;

/**
 * Stores and retrieves conversation messages for a session.
 * Messages are returned in insertion order (oldest first).
 */
public interface ConversationMemory {

    @NonNull
    List<TurnMessage> getMessages(@NonNull String sessionId);

    void addMessages(@NonNull String sessionId, @NonNull List<TurnMessage> messages);

    void clear(@NonNull String sessionId);
}
