package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.PendingMessage;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Durable SQLite-backed pending-message queue storage. */
public class SqliteMessageQueueStore implements MessageQueueStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteMessageQueueStore.class);

    private static final String SELECT_COLUMNS = """
        message_id, session_id, message_text, metadata_json, enqueued_at
        """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteMessageQueueStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteMessageQueueStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void insert(PendingMessage message) {
        Objects.requireNonNull(message, "message");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, message.sessionId());
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO magenta_core_pending_messages
                        (message_id, session_id, message_text, metadata_json, enqueued_at)
                    VALUES (?, ?, ?, ?, ?)
                    """)) {
                    statement.setString(1, message.messageId());
                    statement.setString(2, message.sessionId());
                    statement.setString(3, message.messageText());
                    statement.setString(4,
                        PersistenceSupport.metadataToJson(objectMapper, message.metadata()));
                    statement.setString(5, PersistenceSupport.timestamp(message.enqueuedAt()));
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to insert pending message {}", message.messageId(), e);
            throw new RuntimeException(
                "Failed to insert pending message " + message.messageId(), e);
        }
    }

    @Override
    public List<PendingMessage> findBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            return query("""
                SELECT %s
                FROM magenta_core_pending_messages
                WHERE session_id = ? AND claim_token IS NULL
                ORDER BY enqueued_at ASC, message_id ASC
                """.formatted(SELECT_COLUMNS), sessionId);
        } catch (RuntimeException e) {
            LOG.error("Failed to find pending messages for session {}", sessionId, e);
            throw new RuntimeException(
                "Failed to find pending messages for session " + sessionId, e);
        }
    }

    @Override
    public PendingMessage findById(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        try {
            List<PendingMessage> messages = query(
                "SELECT " + SELECT_COLUMNS
                    + " FROM magenta_core_pending_messages WHERE message_id = ?",
                messageId);
            return messages.isEmpty() ? null : messages.get(0);
        } catch (RuntimeException e) {
            LOG.error("Failed to find pending message {}", messageId, e);
            throw new RuntimeException("Failed to find pending message " + messageId, e);
        }
    }

    @Override
    public boolean claimAtomic(String messageId, String claimToken, Instant claimedAt) {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(claimedAt, "claimedAt");
        try {
            return PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE magenta_core_pending_messages
                    SET claim_token = ?, claimed_at = ?
                    WHERE message_id = ? AND claim_token IS NULL
                    """)) {
                    statement.setString(1, claimToken);
                    statement.setString(2, claimedAt.toString());
                    statement.setString(3, messageId);
                    return statement.executeUpdate() == 1;
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to claim pending message {}", messageId, e);
            throw new RuntimeException("Failed to claim message " + messageId, e);
        }
    }

    @Override
    public void deleteByMessageId(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        update("DELETE FROM magenta_core_pending_messages WHERE message_id = ?",
            messageId, "delete message");
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        update("DELETE FROM magenta_core_pending_messages WHERE session_id = ?",
            sessionId, "delete session messages");
    }

    @Override
    public void releaseClaim(String messageId) {
        Objects.requireNonNull(messageId, "messageId");
        update("""
            UPDATE magenta_core_pending_messages
            SET claim_token = NULL, claimed_at = NULL
            WHERE message_id = ?
            """, messageId, "release claim");
    }

    @Override
    public List<PendingMessage> findStaleClaims(Instant staleThreshold) {
        Objects.requireNonNull(staleThreshold, "staleThreshold");
        try {
            return query("""
                SELECT %s
                FROM magenta_core_pending_messages
                WHERE claim_token IS NOT NULL AND claimed_at < ?
                ORDER BY claimed_at ASC, message_id ASC
                """.formatted(SELECT_COLUMNS), staleThreshold.toString());
        } catch (RuntimeException e) {
            LOG.error("Failed to find stale queue claims before {}", staleThreshold, e);
            throw new RuntimeException("Failed to find stale claims", e);
        }
    }

    private List<PendingMessage> query(String sql, String parameter) {
        return PersistenceSupport.read(dataSource, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, parameter);
                try (ResultSet resultSet = statement.executeQuery()) {
                    List<PendingMessage> messages = new ArrayList<>();
                    while (resultSet.next()) {
                        messages.add(rowToMessage(resultSet));
                    }
                    return List.copyOf(messages);
                }
            }
        });
    }

    private void update(String sql, String parameter, String operation) {
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, parameter);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to {} for {}", operation, parameter, e);
            throw new RuntimeException("Failed to " + operation + " " + parameter, e);
        }
    }

    private PendingMessage rowToMessage(ResultSet resultSet) throws SQLException {
        return new PendingMessage(
            resultSet.getString("message_id"),
            resultSet.getString("session_id"),
            resultSet.getString("message_text"),
            PersistenceSupport.metadataFromJson(
                objectMapper, resultSet.getString("metadata_json")),
            PersistenceSupport.parseTimestamp(resultSet.getString("enqueued_at"))
        );
    }
}
