package io.mindspice.magenta.core;

import java.time.Instant;
import java.util.Map;

/**
 * A message enqueued for a session awaiting processing.
 *
 * @param messageId   unique message identifier
 * @param sessionId   target session
 * @param messageText message content
 * @param metadata    extension metadata; reserved keys include {@code "turnloop.origin"}
 *                    and {@code "turnloop.priority"}
 * @param enqueuedAt  when the message was enqueued
 */
public record PendingMessage(
    @NonNull String messageId,
    @NonNull String sessionId,
    @NonNull String messageText,
    @NonNull Map<String, String> metadata,
    @NonNull Instant enqueuedAt
) {}
