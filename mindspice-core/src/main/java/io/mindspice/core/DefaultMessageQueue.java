package io.mindspice.core;

import io.mindspice.core.spi.MessageQueue;
import io.mindspice.core.spi.MessageQueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link MessageQueue} implementation backed by a {@link MessageQueueStore}.
 *
 * <p>Maintains an in-memory map of claim tokens to message IDs so
 * {@link #ack(String)} and {@link #release(String)} can resolve the
 * underlying store row. Claim atomicity is delegated to the store's
 * {@link MessageQueueStore#claimAtomic(String, String, Instant)}.</p>
 */
public final class DefaultMessageQueue implements MessageQueue {

    private static final Logger log = LoggerFactory.getLogger(DefaultMessageQueue.class);

    private final MessageQueueStore store;
    private final ConcurrentHashMap<String, String> claimToMessageId = new ConcurrentHashMap<>();

    public DefaultMessageQueue(@NonNull MessageQueueStore store) {
        this.store = store;
    }

    // ── MessageQueue ──

    @Override
    @NonNull
    public PendingMessage enqueue(@NonNull String sessionId,
                                   @NonNull String messageText,
                                   @NonNull Map<String, String> metadata) {
        PendingMessage message = new PendingMessage(
            UUID.randomUUID().toString(),
            sessionId,
            messageText,
            Map.copyOf(metadata),
            Instant.now()
        );
        store.insert(message);
        return message;
    }

    @Override
    @NonNull
    public List<PendingMessage> list(@NonNull String sessionId) {
        return store.findBySessionId(sessionId);
    }

    @Override
    @Nullable
    public ClaimedMessage claim(@NonNull String sessionId) {
        List<PendingMessage> pending = store.findBySessionId(sessionId);
        if (pending.isEmpty()) {
            return null;
        }

        // Try each unclaimed message oldest-first
        for (PendingMessage msg : pending) {
            String claimToken = UUID.randomUUID().toString();
            Instant claimedAt = Instant.now();
            if (store.claimAtomic(msg.messageId(), claimToken, claimedAt)) {
                claimToMessageId.put(claimToken, msg.messageId());
                return new ClaimedMessage(msg, claimToken, claimedAt);
            }
        }
        return null;
    }

    @Override
    public void ack(@NonNull String claimToken) {
        String messageId = claimToMessageId.remove(claimToken);
        if (messageId == null) {
            log.debug("ack on unknown/stale claim token: {}", claimToken);
            return;
        }
        store.deleteByMessageId(messageId);
    }

    @Override
    public void release(@NonNull String claimToken) {
        String messageId = claimToMessageId.remove(claimToken);
        if (messageId == null) {
            log.debug("release on unknown/stale claim token: {}", claimToken);
            return;
        }
        store.releaseClaim(messageId);
    }

    @Override
    @NonNull
    public List<ClaimedMessage> recoverStaleClaims(@NonNull Duration staleAge) {
        Instant threshold = Instant.now().minus(staleAge);
        List<PendingMessage> stale = store.findStaleClaims(threshold);

        // Release each stale claim and build result list
        List<ClaimedMessage> result = new ArrayList<>();
        for (PendingMessage msg : stale) {
            // Release the claim so it can be claimed again
            store.releaseClaim(msg.messageId());
            // We don't have the original claim token; create a synthetic one
            String syntheticToken = "recovered-" + UUID.randomUUID();
            result.add(new ClaimedMessage(msg, syntheticToken, Instant.now()));
        }
        return List.copyOf(result);
    }

    @Override
    public void deleteBySessionId(@NonNull String sessionId) {
        // Clean up any claim-token mappings for this session
        List<PendingMessage> messages = store.findBySessionId(sessionId);
        for (PendingMessage msg : messages) {
            // Remove any claimToken mapping that points to this messageId
            claimToMessageId.values().remove(msg.messageId());
        }
        store.deleteBySessionId(sessionId);
    }
}
