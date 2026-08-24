package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.PendingMessage;

import java.time.Instant;
import java.util.List;

/**
 * Persistence SPI backing {@link MessageQueue}.
 */
public interface MessageQueueStore {

    void insert(@NonNull PendingMessage message);

    @NonNull
    List<PendingMessage> findBySessionId(@NonNull String sessionId);

    @Nullable
    PendingMessage findById(@NonNull String messageId);

    boolean claimAtomic(@NonNull String messageId,
                        @NonNull String claimToken,
                        @NonNull Instant claimedAt);

    void deleteByMessageId(@NonNull String messageId);

    void deleteBySessionId(@NonNull String sessionId);

    void releaseClaim(@NonNull String messageId);

    @NonNull
    List<PendingMessage> findStaleClaims(@NonNull Instant staleThreshold);
}
