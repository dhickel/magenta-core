package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ConversationSession;
import io.mindspice.magenta.core.SessionFilter;
import io.mindspice.magenta.core.spi.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Durable SQLite-backed session metadata storage. */
public class SqliteSessionStore implements SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteSessionStore.class);

    private static final String SELECT_COLUMNS = """
        session_id, title, model, favorite, archived, origin,
        metadata_json, created_at, updated_at
        """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteSessionStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteSessionStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void save(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        String now = Instant.now().toString();
        String createdAt = PersistenceSupport.timestamp(session.createdAt());
        String updatedAt = session.updatedAt() != null
            ? session.updatedAt().toString() : now;
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO magenta_core_session_metadata
                        (session_id, title, model, favorite, archived, origin,
                         metadata_json, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(session_id) DO UPDATE SET
                        title = excluded.title,
                        model = excluded.model,
                        favorite = excluded.favorite,
                        archived = excluded.archived,
                        origin = excluded.origin,
                        metadata_json = excluded.metadata_json,
                        updated_at = excluded.updated_at
                    """)) {
                    statement.setString(1, session.sessionId());
                    statement.setString(2, session.title());
                    statement.setString(3, session.model());
                    statement.setInt(4, session.favorite() ? 1 : 0);
                    statement.setInt(5, session.archived() ? 1 : 0);
                    statement.setString(6, session.origin());
                    statement.setString(7,
                        PersistenceSupport.metadataToJson(objectMapper, session.metadata()));
                    statement.setString(8, createdAt);
                    statement.setString(9, updatedAt);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to save session {}", session.sessionId(), e);
            throw new RuntimeException("Failed to save session " + session.sessionId(), e);
        }
    }

    @Override
    public ConversationSession findById(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + SELECT_COLUMNS
                        + " FROM magenta_core_session_metadata WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next() ? rowToSession(resultSet) : null;
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to find session {}", sessionId, e);
            throw new RuntimeException("Failed to find session " + sessionId, e);
        }
    }

    @Override
    public List<ConversationSession> list(SessionFilter filter) {
        Objects.requireNonNull(filter, "filter");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                StringBuilder sql = new StringBuilder(
                    "SELECT " + SELECT_COLUMNS
                        + " FROM magenta_core_session_metadata WHERE 1 = 1");
                List<Object> parameters = new ArrayList<>();
                if (filter.favorite() != null) {
                    sql.append(" AND favorite = ?");
                    parameters.add(filter.favorite() ? 1 : 0);
                }
                if (filter.archived() != null) {
                    sql.append(" AND archived = ?");
                    parameters.add(filter.archived() ? 1 : 0);
                }
                if (filter.origin() != null) {
                    sql.append(" AND origin = ?");
                    parameters.add(filter.origin());
                }
                if (filter.searchQuery() != null && !filter.searchQuery().isBlank()) {
                    sql.append(" AND title LIKE '%' || ? || '%'");
                    parameters.add(filter.searchQuery());
                }
                sql.append(" ORDER BY updated_at DESC, session_id ASC LIMIT ? OFFSET ?");
                parameters.add(Math.max(0, filter.limit()));
                parameters.add(Math.max(0, filter.offset()));

                try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                    bindParameters(statement, parameters);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        List<ConversationSession> sessions = new ArrayList<>();
                        while (resultSet.next()) {
                            sessions.add(rowToSession(resultSet));
                        }
                        return List.copyOf(sessions);
                    }
                }
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to list sessions", e);
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    @Override
    public void delete(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM magenta_core_session_metadata WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to delete session {}", sessionId, e);
            throw new RuntimeException("Failed to delete session " + sessionId, e);
        }
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        updateText(sessionId, title,
            "UPDATE magenta_core_session_metadata SET title = ?, "
                + "updated_at = ? WHERE session_id = ?", "title");
    }

    @Override
    public void setFavorite(String sessionId, boolean favorite) {
        updateFlag(sessionId, "favorite", favorite);
    }

    @Override
    public void setArchived(String sessionId, boolean archived) {
        updateFlag(sessionId, "archived", archived);
    }

    @Override
    public void updateModel(String sessionId, String model) {
        updateText(sessionId, model,
            "UPDATE magenta_core_session_metadata SET model = ?, "
                + "updated_at = ? WHERE session_id = ?", "model");
    }

    private void updateText(String sessionId, String value, String sql, String field) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, value);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, sessionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to update {} for session {}", field, sessionId, e);
            throw new RuntimeException("Failed to update " + field + " for session " + sessionId, e);
        }
    }

    private void updateFlag(String sessionId, String field, boolean value) {
        Objects.requireNonNull(sessionId, "sessionId");
        String sql = "UPDATE magenta_core_session_metadata SET " + field
            + " = ?, updated_at = ? WHERE session_id = ?";
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setInt(1, value ? 1 : 0);
                    statement.setString(2, Instant.now().toString());
                    statement.setString(3, sessionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to update {} for session {}", field, sessionId, e);
            throw new RuntimeException("Failed to update " + field + " for session " + sessionId, e);
        }
    }

    private void bindParameters(PreparedStatement statement, List<Object> parameters)
        throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            Object parameter = parameters.get(index);
            if (parameter instanceof Integer integer) {
                statement.setInt(index + 1, integer);
            } else {
                statement.setString(index + 1, (String) parameter);
            }
        }
    }

    private ConversationSession rowToSession(ResultSet resultSet) throws SQLException {
        return new ConversationSession(
            resultSet.getString("session_id"),
            resultSet.getString("title"),
            resultSet.getString("model"),
            resultSet.getInt("favorite") != 0,
            resultSet.getInt("archived") != 0,
            resultSet.getString("origin"),
            PersistenceSupport.parseTimestamp(resultSet.getString("created_at")),
            PersistenceSupport.parseTimestamp(resultSet.getString("updated_at")),
            PersistenceSupport.metadataFromJson(
                objectMapper, resultSet.getString("metadata_json"))
        );
    }
}
