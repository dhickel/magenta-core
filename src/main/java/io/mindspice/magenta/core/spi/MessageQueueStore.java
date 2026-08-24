package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.ClaimedMessage;
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

    /**
     * Whether this store can resolve and mutate claims by their durable token.
     * The default keeps existing custom stores source-compatible; such stores
     * continue to support tokens held by the live {@code MessageQueue} instance.
     */
    default boolean supportsDurableClaimTokens() {
        return false;
    }

    /** Returns the durable claim represented by {@code claimToken}, if active. */
    @Nullable
    default ClaimedMessage findByClaimToken(@NonNull String claimToken) {
        return null;
    }

    /** Deletes only the row currently owned by {@code claimToken}. */
    default boolean deleteByClaimToken(@NonNull String claimToken) {
        return false;
    }

    /** Releases only the row currently owned by {@code claimToken}. */
    default boolean releaseClaimByToken(@NonNull String claimToken) {
        return false;
    }

    /**
     * Atomically releases claims older than {@code staleThreshold} and returns
     * the claims that were actually recovered. Durable stores should compare
     * the token during mutation so a newer replacement claim is never cleared.
     */
    @NonNull
    default List<ClaimedMessage> releaseStaleClaims(@NonNull Instant staleThreshold) {
        return List.of();
    }

    void deleteByMessageId(@NonNull String messageId);

    void deleteBySessionId(@NonNull String sessionId);

    void releaseClaim(@NonNull String messageId);

    @NonNull
    List<PendingMessage> findStaleClaims(@NonNull Instant staleThreshold);
}
