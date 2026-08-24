package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ConversationSession;
import io.mindspice.magenta.core.SessionFilter;
import io.mindspice.magenta.core.spi.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQLite-backed {@link SessionStore} implementation.
 * <p>
 * Self-migrates the {@code turnloop_session_metadata} table on construction.
 * Session metadata is stored as JSON in {@code metadata_json}.
 */
public class SqliteSessionStore implements SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteSessionStore.class);

    private static final TypeReference<Map<String, String>> METADATA_MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SqliteSessionStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteSessionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        migrate();
    }

    // ── self-migration ──

    private void migrate() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS turnloop_session_metadata (
                session_id TEXT PRIMARY KEY,
                title TEXT,
                model TEXT,
                favorite INTEGER NOT NULL DEFAULT 0,
                archived INTEGER NOT NULL DEFAULT 0,
                origin TEXT NOT NULL DEFAULT 'turnloop',
                metadata_json TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now')),
                updated_at TEXT NOT NULL DEFAULT (datetime('now'))
            )
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_session_origin
            ON turnloop_session_metadata(origin)
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_session_favorite
            ON turnloop_session_metadata(favorite)
            """);
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS idx_session_archived
            ON turnloop_session_metadata(archived)
            """);
    }

    // ── SessionStore ──

    @Override
    public void save(ConversationSession session) {
        try {
            jdbc.update("""
                INSERT OR REPLACE INTO turnloop_session_metadata
                    (session_id, title, model, favorite, archived, origin,
                     metadata_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                session.sessionId(),
                session.title(),
                session.model(),
                session.favorite() ? 1 : 0,
                session.archived() ? 1 : 0,
                session.origin(),
                metadataToJson(session.metadata()),
                session.createdAt() != null ? session.createdAt().toString() : null,
                session.updatedAt() != null ? session.updatedAt().toString() : null
            );
        } catch (DataAccessException e) {
            log.error("Failed to save session {}", session.sessionId(), e);
            throw new RuntimeException("Failed to save session " + session.sessionId(), e);
        }
    }

    @Override
    public ConversationSession findById(String sessionId) {
        try {
            List<ConversationSession> results = jdbc.query(
                "SELECT session_id, title, model, favorite, archived, origin, " +
                "metadata_json, created_at, updated_at " +
                "FROM turnloop_session_metadata WHERE session_id = ?",
                (rs, rowNum) -> rowToSession(rs),
                sessionId
            );
            return results.isEmpty() ? null : results.get(0);
        } catch (DataAccessException e) {
            log.error("Failed to find session {}", sessionId, e);
            throw new RuntimeException("Failed to find session " + sessionId, e);
        }
    }

    @Override
    public List<ConversationSession> list(SessionFilter filter) {
        try {
            StringBuilder sql = new StringBuilder(
                "SELECT session_id, title, model, favorite, archived, origin, " +
                "metadata_json, created_at, updated_at " +
                "FROM turnloop_session_metadata WHERE 1=1"
            );
            List<Object> params = new ArrayList<>();

            if (filter.favorite() != null) {
                sql.append(" AND favorite = ?");
                params.add(filter.favorite() ? 1 : 0);
            }
            if (filter.archived() != null) {
                sql.append(" AND archived = ?");
                params.add(filter.archived() ? 1 : 0);
            }
            if (filter.origin() != null) {
                sql.append(" AND origin = ?");
                params.add(filter.origin());
            }
            if (filter.searchQuery() != null && !filter.searchQuery().isBlank()) {
                sql.append(" AND title LIKE '%' || ? || '%'");
                params.add(filter.searchQuery());
            }
            sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
            params.add(filter.limit());
            params.add(filter.offset());

            return jdbc.query(
                sql.toString(),
                (rs, rowNum) -> rowToSession(rs),
                params.toArray()
            );
        } catch (DataAccessException e) {
            log.error("Failed to list sessions with filter", e);
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        try {
            jdbc.update("DELETE FROM turnloop_session_metadata WHERE session_id = ?", sessionId);
        } catch (DataAccessException e) {
            log.error("Failed to delete session {}", sessionId, e);
            throw new RuntimeException("Failed to delete session " + sessionId, e);
        }
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        try {
            jdbc.update(
                "UPDATE turnloop_session_metadata SET title = ?, updated_at = datetime('now') " +
                "WHERE session_id = ?",
                title, sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to update title for session {}", sessionId, e);
            throw new RuntimeException("Failed to update title for session " + sessionId, e);
        }
    }

    @Override
    public void setFavorite(String sessionId, boolean favorite) {
        try {
            jdbc.update(
                "UPDATE turnloop_session_metadata SET favorite = ?, updated_at = datetime('now') " +
                "WHERE session_id = ?",
                favorite ? 1 : 0, sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to set favorite for session {}", sessionId, e);
            throw new RuntimeException("Failed to set favorite for session " + sessionId, e);
        }
    }

    @Override
    public void setArchived(String sessionId, boolean archived) {
        try {
            jdbc.update(
                "UPDATE turnloop_session_metadata SET archived = ?, updated_at = datetime('now') " +
                "WHERE session_id = ?",
                archived ? 1 : 0, sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to set archived for session {}", sessionId, e);
            throw new RuntimeException("Failed to set archived for session " + sessionId, e);
        }
    }

    @Override
    public void updateModel(String sessionId, String model) {
        try {
            jdbc.update(
                "UPDATE turnloop_session_metadata SET model = ?, updated_at = datetime('now') " +
                "WHERE session_id = ?",
                model, sessionId
            );
        } catch (DataAccessException e) {
            log.error("Failed to update model for session {}", sessionId, e);
            throw new RuntimeException("Failed to update model for session " + sessionId, e);
        }
    }

    // ── row mapping ──

    private ConversationSession rowToSession(ResultSet rs) throws SQLException {
        String createdAtStr = rs.getString("created_at");
        String updatedAtStr = rs.getString("updated_at");
        return new ConversationSession(
            rs.getString("session_id"),
            rs.getString("title"),
            rs.getString("model"),
            rs.getInt("favorite") != 0,
            rs.getInt("archived") != 0,
            rs.getString("origin"),
            createdAtStr != null ? Instant.parse(createdAtStr) : null,
            updatedAtStr != null ? Instant.parse(updatedAtStr) : null,
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
