package io.mindspice.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.core.TurnMessage;
import io.mindspice.core.spi.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed {@link ConversationMemory} implementation.
 * <p>
 * Self-migrates the {@code turnloop_conversation_memory} table on construction.
 * Message metadata is stored as JSON in {@code metadata_json}; null or empty
 * metadata is normalized to {@link Map#of()} when read back.
 */
public class SqliteConversationMemory implements ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(SqliteConversationMemory.class);

    private static final TypeReference<Map<String, String>> METADATA_MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqliteConversationMemory(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteConversationMemory(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        migrate();
    }

    // ── self-migration ──

    private void migrate() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS turnloop_conversation_memory (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                tool_name TEXT,
                tool_call_id TEXT,
                metadata_json TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_memory_session
            ON turnloop_conversation_memory(session_id, id)
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_memory_session_role
            ON turnloop_conversation_memory(session_id, role)
            """);
    }

    // ── ConversationMemory ──

    @Override
    public List<TurnMessage> getMessages(String sessionId) {
        try {
            return jdbc.query(
                "SELECT id, role, content, tool_name, tool_call_id, metadata_json " +
                "FROM turnloop_conversation_memory WHERE session_id = ? ORDER BY id ASC",
                (rs, rowNum) -> rowToTurnMessage(rs),
                sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to get messages for session {}", sessionId, e);
            throw new RuntimeException("Failed to get messages for session " + sessionId, e);
        }
    }

    @Override
    public void addMessages(String sessionId, List<TurnMessage> messages) {
        if (messages.isEmpty()) return;
        try {
            jdbc.batchUpdate(
                """
                INSERT INTO turnloop_conversation_memory
                    (session_id, role, content, tool_name, tool_call_id, metadata_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                messages,
                messages.size(),
                (PreparedStatement ps, TurnMessage msg) -> {
                    ps.setString(1, sessionId);
                    ps.setString(2, msg.role());
                    ps.setString(3, msg.text());
                    ps.setString(4, msg.toolName());
                    ps.setString(5, msg.toolCallId());
                    ps.setString(6, metadataToJson(msg.metadata()));
                }
            );
        } catch (DataAccessException e) {
            log.error("Failed to add {} messages for session {}", messages.size(), sessionId, e);
            throw new RuntimeException("Failed to add messages for session " + sessionId, e);
        }
    }

    @Override
    public void clear(String sessionId) {
        try {
            jdbc.update("DELETE FROM turnloop_conversation_memory WHERE session_id = ?", sessionId);
        } catch (DataAccessException e) {
            log.error("Failed to clear messages for session {}", sessionId, e);
            throw new RuntimeException("Failed to clear messages for session " + sessionId, e);
        }
    }

    // ── row mapping ──

    private TurnMessage rowToTurnMessage(ResultSet rs) throws SQLException {
        return new TurnMessage(
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("tool_name"),
            rs.getString("tool_call_id"),
            metadataFromJson(rs.getString("metadata_json"))
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
