package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.PendingMessage;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed {@link MessageQueueStore} implementation.
 * <p>
 * Self-migrates the {@code turnloop_pending_messages} table on construction.
 * Claim atomicity uses {@code UPDATE ... WHERE claim_token IS NULL} and
 * checks that exactly one row was updated.
 */
public class SqliteMessageQueueStore implements MessageQueueStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteMessageQueueStore.class);

    private static final TypeReference<Map<String, String>> METADATA_MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqliteMessageQueueStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteMessageQueueStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        migrate();
    }

    // ── self-migration ──

    private void migrate() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS turnloop_pending_messages (
                message_id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                message_text TEXT NOT NULL,
                metadata_json TEXT,
                claim_token TEXT,
                claimed_at TEXT,
                enqueued_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_session
            ON turnloop_pending_messages(session_id)
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_claim
            ON turnloop_pending_messages(claim_token)
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_pending_stale
            ON turnloop_pending_messages(claimed_at)
            """);
    }

    // ── MessageQueueStore ──

    @Override
    public void insert(PendingMessage message) {
        try {
            jdbc.update("""
                INSERT INTO turnloop_pending_messages
                    (message_id, session_id, message_text, metadata_json, enqueued_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                message.messageId(),
                message.sessionId(),
                message.messageText(),
                metadataToJson(message.metadata()),
                message.enqueuedAt().toString()
            );
        } catch (DataAccessException e) {
            log.error("Failed to insert pending message {}", message.messageId(), e);
            throw new RuntimeException("Failed to insert pending message " + message.messageId(), e);
        }
    }

    @Override
    public List<PendingMessage> findBySessionId(String sessionId) {
        try {
            return jdbc.query(
                "SELECT message_id, session_id, message_text, metadata_json, enqueued_at " +
                "FROM turnloop_pending_messages " +
                "WHERE session_id = ? AND claim_token IS NULL " +
                "ORDER BY enqueued_at ASC",
                (rs, rowNum) -> rowToPendingMessage(rs),
                sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to find pending messages for session {}", sessionId, e);
            throw new RuntimeException("Failed to find pending messages for session " + sessionId, e);
        }
    }

    @Override
    public PendingMessage findById(String messageId) {
        try {
            List<PendingMessage> results = jdbc.query(
                "SELECT message_id, session_id, message_text, metadata_json, enqueued_at " +
                "FROM turnloop_pending_messages WHERE message_id = ?",
                (rs, rowNum) -> rowToPendingMessage(rs),
                messageId
            );
            return results.isEmpty() ? null : results.get(0);
        } catch (DataAccessException e) {
            log.error("Failed to find pending message {}", messageId, e);
            throw new RuntimeException("Failed to find pending message " + messageId, e);
        }
    }

    @Override
    public boolean claimAtomic(String messageId, String claimToken, Instant claimedAt) {
        try {
            int updated = jdbc.update(
                "UPDATE turnloop_pending_messages " +
                "SET claim_token = ?, claimed_at = ? " +
                "WHERE message_id = ? AND claim_token IS NULL",
                claimToken, claimedAt.toString(), messageId
            );
            return updated == 1;
        } catch (DataAccessException e) {
            log.error("Failed to claim message {}", messageId, e);
            throw new RuntimeException("Failed to claim message " + messageId, e);
        }
    }

    @Override
    public void deleteByMessageId(String messageId) {
        try {
            jdbc.update("DELETE FROM turnloop_pending_messages WHERE message_id = ?", messageId);
        } catch (DataAccessException e) {
            log.error("Failed to delete message {}", messageId, e);
            throw new RuntimeException("Failed to delete message " + messageId, e);
        }
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        try {
            jdbc.update("DELETE FROM turnloop_pending_messages WHERE session_id = ?", sessionId);
        } catch (DataAccessException e) {
            log.error("Failed to delete messages for session {}", sessionId, e);
            throw new RuntimeException("Failed to delete messages for session " + sessionId, e);
        }
    }

    @Override
    public void releaseClaim(String messageId) {
        try {
            jdbc.update(
                "UPDATE turnloop_pending_messages " +
                "SET claim_token = NULL, claimed_at = NULL " +
                "WHERE message_id = ?",
                messageId
            );
        } catch (DataAccessException e) {
            log.error("Failed to release claim for message {}", messageId, e);
            throw new RuntimeException("Failed to release claim for message " + messageId, e);
        }
    }

    @Override
    public List<PendingMessage> findStaleClaims(Instant staleThreshold) {
        try {
            return jdbc.query(
                "SELECT message_id, session_id, message_text, metadata_json, enqueued_at " +
                "FROM turnloop_pending_messages " +
                "WHERE claim_token IS NOT NULL AND claimed_at < ? " +
                "ORDER BY claimed_at ASC",
                (rs, rowNum) -> rowToPendingMessage(rs),
                staleThreshold.toString()
            );
        } catch (DataAccessException e) {
            log.error("Failed to find stale claims before {}", staleThreshold, e);
            throw new RuntimeException("Failed to find stale claims", e);
        }
    }

    // ── row mapping ──

    private PendingMessage rowToPendingMessage(ResultSet rs) throws SQLException {
        String enqueuedAtStr = rs.getString("enqueued_at");
        return new PendingMessage(
            rs.getString("message_id"),
            rs.getString("session_id"),
            rs.getString("message_text"),
            metadataFromJson(rs.getString("metadata_json")),
            enqueuedAtStr != null ? Instant.parse(enqueuedAtStr) : Instant.now()
        );
    }

    private String metadataToJson(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata, storing null", e);
            return null;
        }
    }

    private Map<String, String> metadataFromJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, METADATA_MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize metadata, returning empty map", e);
            return Map.of();
        }
    }
}
