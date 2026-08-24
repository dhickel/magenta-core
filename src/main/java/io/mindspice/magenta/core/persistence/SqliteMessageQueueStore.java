package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.QueueReason;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Durable SQLite-backed storage for framework-managed queued requests. */
public final class SqliteMessageQueueStore implements MessageQueueStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteMessageQueueStore.class);

    /** Default maximum lease used when the builder does not supply one. */
    public static final Duration DEFAULT_CLAIM_LEASE = Duration.ofMinutes(5);
    private static final Duration MAX_CLAIM_LEASE = Duration.ofHours(1);

    private static final String REQUEST_COLUMNS = """
        message_id, session_id, message, explicit_model,
        explicit_tool_names_json, metadata_json, queue_reason, enqueued_at
        """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;
    private final Duration maxClaimLease;

    public SqliteMessageQueueStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper(), DEFAULT_CLAIM_LEASE);
    }

    public SqliteMessageQueueStore(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, objectMapper, DEFAULT_CLAIM_LEASE);
    }

    /**
     * Creates a store with a bounded maximum lease. A request for a longer
     * lease is clamped to this value when a claim is made.
     */
    public SqliteMessageQueueStore(DataSource dataSource,
                                   ObjectMapper objectMapper,
                                   Duration maxClaimLease) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.maxClaimLease = boundedMaximum(maxClaimLease);
    }

    @Override
    public void insert(QueuedRequest request) {
        Objects.requireNonNull(request, "request");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, request.sessionId());
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO magenta_core_queued_requests
                        (message_id, session_id, message, explicit_model,
                         explicit_tool_names_json, metadata_json, queue_reason,
                         enqueued_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                    statement.setString(1, request.messageId());
                    statement.setString(2, request.sessionId());
                    statement.setString(3, request.message());
                    statement.setString(4, request.model());
                    statement.setString(5, PersistenceSupport.stringListToJson(
                        objectMapper, request.toolNames()));
                    statement.setString(6, PersistenceSupport.toJson(
                        objectMapper, request.metadata()));
                    statement.setString(7, request.reason().name());
                    statement.setString(8, PersistenceSupport.timestamp(request.enqueuedAt()));
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to insert queued request {}", request.messageId(), e);
            throw new RuntimeException(
                "Failed to insert queued request " + request.messageId(), e);
        }
    }

    @Override
    public List<QueuedRequest> list(String sessionId) {
        requireText(sessionId, "sessionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT %s
                    FROM magenta_core_queued_requests
                    WHERE session_id = ?
                    ORDER BY enqueued_at ASC, message_id ASC
                    """.formatted(REQUEST_COLUMNS))) {
                    statement.setString(1, sessionId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<QueuedRequest> requests = new ArrayList<>();
                        while (resultSet.next()) {
                            requests.add(rowToRequest(resultSet));
                        }
                        return List.copyOf(requests);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to list queued requests for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to list queued requests for session " + sessionId, e);
        }
    }

    @Override
    public Optional<Claim> claimOldest(String sessionId,
                                       Instant claimedAt,
                                       Duration requestedLease) {
        requireText(sessionId, "sessionId");
        Objects.requireNonNull(claimedAt, "claimedAt");
        Duration lease = boundedLease(requestedLease);
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                QueuedRequest request;
                try (PreparedStatement select = connection.prepareStatement("""
                    SELECT %s
                    FROM magenta_core_queued_requests
                    WHERE session_id = ? AND claim_token IS NULL
                    ORDER BY enqueued_at ASC, message_id ASC
                    LIMIT 1
                    """.formatted(REQUEST_COLUMNS))) {
                    select.setString(1, sessionId);
                    try (ResultSet resultSet = select.executeQuery()) {
                        if (!resultSet.next()) {
                            return Optional.empty();
                        }
                        request = rowToRequest(resultSet);
                    }
                }

                String claimToken = UUID.randomUUID().toString();
                Instant leaseExpiresAt = claimedAt.plus(lease);
                try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE magenta_core_queued_requests
                    SET claim_token = ?, claimed_at = ?, lease_expires_at = ?
                    WHERE message_id = ? AND claim_token IS NULL
                    """)) {
                    update.setString(1, claimToken);
                    update.setString(2, PersistenceSupport.timestamp(claimedAt));
                    update.setString(3, PersistenceSupport.timestamp(leaseExpiresAt));
                    update.setString(4, request.messageId());
                    if (update.executeUpdate() != 1) {
                        return Optional.empty();
                    }
                }
                return Optional.of(new Claim(
                    request, claimToken, claimedAt, leaseExpiresAt));
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to claim queued request for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to claim queued request for session " + sessionId, e);
        }
    }

    @Override
    public boolean ack(String claimToken) {
        requireText(claimToken, "claimToken");
        return updateCount(
            "DELETE FROM magenta_core_queued_requests WHERE claim_token = ?",
            claimToken, "acknowledge claim") == 1;
    }

    @Override
    public boolean release(String claimToken) {
        requireText(claimToken, "claimToken");
        return updateCount("""
            UPDATE magenta_core_queued_requests
            SET claim_token = NULL, claimed_at = NULL, lease_expires_at = NULL
            WHERE claim_token = ?
            """, claimToken, "release claim") == 1;
    }

    @Override
    public int recoverExpiredClaims(Instant now) {
        Objects.requireNonNull(now, "now");
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE magenta_core_queued_requests
                    SET claim_token = NULL, claimed_at = NULL, lease_expires_at = NULL
                    WHERE claim_token IS NOT NULL
                      AND lease_expires_at IS NOT NULL
                      AND lease_expires_at <= ?
                    """)) {
                    statement.setString(1, PersistenceSupport.timestamp(now));
                    return statement.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to recover expired queue claims at {}", now, e);
            throw new RuntimeException("Failed to recover expired queue claims", e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        requireText(sessionId, "sessionId");
        updateCount(
            "DELETE FROM magenta_core_queued_requests WHERE session_id = ?",
            sessionId, "delete queued requests for session");
    }

    private QueuedRequest rowToRequest(ResultSet resultSet) throws SQLException {
        return new QueuedRequest(
            resultSet.getString("message_id"),
            resultSet.getString("session_id"),
            resultSet.getString("message"),
            resultSet.getString("explicit_model"),
            PersistenceSupport.stringListFromJson(
                objectMapper, resultSet.getString("explicit_tool_names_json")),
            PersistenceSupport.metadataFromJson(
                objectMapper, resultSet.getString("metadata_json")),
            QueueReason.valueOf(resultSet.getString("queue_reason")),
            PersistenceSupport.parseTimestamp(resultSet.getString("enqueued_at"))
        );
    }

    private int updateCount(String sql, String parameter, String operation) {
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, parameter);
                    return statement.executeUpdate();
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to {} for {}", operation, parameter, e);
            throw new RuntimeException("Failed to " + operation + " " + parameter, e);
        }
    }

    private Duration boundedLease(Duration requestedLease) {
        Objects.requireNonNull(requestedLease, "lease");
        if (requestedLease.isZero() || requestedLease.isNegative()) {
            throw new IllegalArgumentException("lease must be positive");
        }
        return requestedLease.compareTo(maxClaimLease) > 0
            ? maxClaimLease : requestedLease;
    }

    private static Duration boundedMaximum(Duration maximum) {
        Objects.requireNonNull(maximum, "maxClaimLease");
        if (maximum.isZero() || maximum.isNegative()) {
            throw new IllegalArgumentException("maxClaimLease must be positive");
        }
        if (maximum.compareTo(MAX_CLAIM_LEASE) > 0) {
            throw new IllegalArgumentException(
                "maxClaimLease must not exceed " + MAX_CLAIM_LEASE);
        }
        return maximum;
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
