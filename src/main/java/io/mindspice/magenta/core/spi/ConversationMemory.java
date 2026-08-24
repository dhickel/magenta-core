package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnMessage;

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

    /** Replaces a session transcript as one logical memory operation. */
    default void replaceMessages(@NonNull String sessionId, @NonNull List<TurnMessage> messages) {
        clear(sessionId);
        addMessages(sessionId, messages);
    }
}
