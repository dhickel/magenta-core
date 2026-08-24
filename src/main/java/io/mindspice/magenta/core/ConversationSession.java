package io.mindspice.magenta.core;

import java.time.Instant;
import java.util.Map;

/**
 * Metadata for a conversation session.
 *
 * @param sessionId unique session identifier
 * @param title     human-readable title
 * @param model     model alias used for this session
 * @param favorite  whether the session is favorited
 * @param archived  whether the session is archived
 * @param origin    origin tag (defaults to "turnloop")
 * @param createdAt session creation timestamp
 * @param updatedAt last-update timestamp
 * @param metadata  extension metadata
 */
public record ConversationSession(
    @NonNull String sessionId,
    @Nullable String title,
    @Nullable String model,
    boolean favorite,
    boolean archived,
    @NonNull String origin,
    @Nullable Instant createdAt,
    @Nullable Instant updatedAt,
    @NonNull Map<String, String> metadata
) {}
