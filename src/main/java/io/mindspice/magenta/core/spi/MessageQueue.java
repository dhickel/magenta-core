package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.ClaimedMessage;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.PendingMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Claim/ack/release queue for messages received mid-turn.
 */
public interface MessageQueue {

    @NonNull
    PendingMessage enqueue(@NonNull String sessionId,
                           @NonNull String messageText,
                           @NonNull Map<String, String> metadata);

    @NonNull
    List<PendingMessage> list(@NonNull String sessionId);

    @Nullable
    ClaimedMessage claim(@NonNull String sessionId);

    void ack(@NonNull String claimToken);

    void release(@NonNull String claimToken);

    @NonNull
    List<ClaimedMessage> recoverStaleClaims(@NonNull Duration staleAge);

    void deleteBySessionId(@NonNull String sessionId);
}
