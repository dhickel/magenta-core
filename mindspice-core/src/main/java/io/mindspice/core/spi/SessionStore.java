package io.mindspice.core.spi;

import io.mindspice.core.ConversationSession;
import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import io.mindspice.core.SessionFilter;

import java.util.List;

/**
 * Session metadata storage.
 */
public interface SessionStore {

    void save(@NonNull ConversationSession session);

    @Nullable
    ConversationSession findById(@NonNull String sessionId);

    @NonNull
    List<ConversationSession> list(@NonNull SessionFilter filter);

    void delete(@NonNull String sessionId);

    void updateTitle(@NonNull String sessionId, @NonNull String title);

    void setFavorite(@NonNull String sessionId, boolean favorite);

    void setArchived(@NonNull String sessionId, boolean archived);

    void updateModel(@NonNull String sessionId, @NonNull String model);
}
