package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.QueueReason;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Advanced persistence SPI for framework-managed queued work.
 *
 * <p>Claim ownership, lease expiry, acknowledgement, and release are kept
 * entirely behind this SPI. The public turn facade receives only a durable
 * queue receipt and never exposes either {@link Claim} or its token.</p>
 */
public interface MessageQueueStore {

    /** Complete caller intent retained until framework-managed execution. */
    record QueuedRequest(
        @NonNull String messageId,
        @NonNull String sessionId,
        @NonNull String message,
        @Nullable String model,
        @Nullable List<String> toolNames,
        @NonNull Map<String, String> metadata,
        @NonNull QueueReason reason,
        @NonNull Instant enqueuedAt
    ) {
        public QueuedRequest {
            messageId = nonBlank(messageId, "messageId");
            sessionId = nonBlank(sessionId, "sessionId");
            message = nonBlank(message, "message");
            if (model != null) {
                model = nonBlank(model, "model");
            }
            if (toolNames != null) {
                toolNames = List.copyOf(toolNames);
                validateNonBlank(toolNames, "toolNames");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            reason = Objects.requireNonNull(reason, "reason");
            enqueuedAt = Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        }

        private static String nonBlank(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        private static void validateNonBlank(List<String> values, String name) {
            for (int index = 0; index < values.size(); index++) {
                nonBlank(values.get(index), name + "[" + index + "]");
            }
        }
    }

    /** Internal claim facts returned only by the store SPI. */
    record Claim(
        @NonNull QueuedRequest request,
        @NonNull String claimToken,
        @NonNull Instant claimedAt,
        @NonNull Instant leaseExpiresAt
    ) {
        public Claim {
            request = Objects.requireNonNull(request, "request");
            claimToken = nonBlank(claimToken, "claimToken");
            claimedAt = Objects.requireNonNull(claimedAt, "claimedAt");
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            if (leaseExpiresAt.isBefore(claimedAt)) {
                throw new IllegalArgumentException(
                    "leaseExpiresAt must not be before claimedAt");
            }
        }

        private static String nonBlank(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }

    /** Inserts a queued request exactly as supplied. */
    void insert(@NonNull QueuedRequest request);

    /** Clear name for callers that think in terms of enqueue rather than insert. */
    default void enqueue(@NonNull QueuedRequest request) {
        insert(request);
    }

    /** Lists all retained requests for a session in enqueue order. */
    @NonNull
    List<QueuedRequest> list(@NonNull String sessionId);

    /**
     * Claims the oldest unclaimed request for a bounded lease. The overload
     * with an explicit clock exists for deterministic store tests and custom
     * persistence implementations.
     */
    @NonNull
    default Optional<Claim> claimOldest(@NonNull String sessionId,
                                        @NonNull Duration lease) {
        return claimOldest(sessionId, Instant.now(), lease);
    }

    @NonNull
    Optional<Claim> claimOldest(@NonNull String sessionId,
                                @NonNull Instant claimedAt,
                                @NonNull Duration lease);

    /** Acknowledges exactly the active claim represented by the token. */
    boolean ack(@NonNull String claimToken);

    /** Releases exactly the active claim represented by the token. */
    boolean release(@NonNull String claimToken);

    /** Recovers only claims whose lease has expired at the supplied instant. */
    int recoverExpiredClaims(@NonNull Instant now);

    /** Uses the store clock for expired-claim recovery. */
    default int recoverExpiredClaims() {
        return recoverExpiredClaims(Instant.now());
    }

    /** Deletes queued work, including active claims, for a session. */
    void deleteBySessionId(@NonNull String sessionId);
}
