package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.ConversationMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable SQLite-backed conversation memory.
 *
 * <p>The schema is installed by {@code MigrationRunner}. This repository does
 * not contain fallback DDL, so a caller cannot accidentally create a second,
 * incompatible schema by constructing repositories in a different order.</p>
 */
public class SqliteConversationMemory implements ConversationMemory {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteConversationMemory.class);

    private static final String SELECT_MESSAGES = """
        SELECT role, content, tool_name, tool_call_id, metadata_json
        FROM magenta_core_conversation_memory
        WHERE session_id = ?
        ORDER BY id ASC
        """;
    private static final String INSERT_MESSAGE = """
        INSERT INTO magenta_core_conversation_memory
            (session_id, role, content, tool_name, tool_call_id, metadata_json)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SqliteConversationMemory(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    public SqliteConversationMemory(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public List<TurnMessage> getMessages(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            return PersistenceSupport.read(dataSource, connection -> {
                List<TurnMessage> messages = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(SELECT_MESSAGES)) {
                    statement.setString(1, sessionId);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            messages.add(rowToMessage(resultSet));
                        }
                    }
                }
                return List.copyOf(messages);
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to read conversation memory for session {}", sessionId, e);
            throw new RuntimeException("Failed to get messages for session " + sessionId, e);
        }
    }

    @Override
    public void addMessages(String sessionId, List<TurnMessage> messages) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return;
        }
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, sessionId);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_MESSAGE)) {
                    for (TurnMessage message : messages) {
                        bindMessage(statement, sessionId, message);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to append {} messages for session {}", messages.size(), sessionId, e);
            throw new RuntimeException("Failed to add messages for session " + sessionId, e);
        }
    }

    @Override
    public void replaceMessages(String sessionId, List<TurnMessage> messages) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(messages, "messages");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                PersistenceSupport.ensureSession(connection, sessionId);
                try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM magenta_core_conversation_memory WHERE session_id = ?")) {
                    delete.setString(1, sessionId);
                    delete.executeUpdate();
                }
                if (!messages.isEmpty()) {
                    try (PreparedStatement insert = connection.prepareStatement(INSERT_MESSAGE)) {
                        for (TurnMessage message : messages) {
                            bindMessage(insert, sessionId, message);
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to replace conversation for session {}", sessionId, e);
            throw new RuntimeException("Failed to replace messages for session " + sessionId, e);
        }
    }

    @Override
    public void clear(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try {
            PersistenceSupport.transaction(dataSource, connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM magenta_core_conversation_memory WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException e) {
            LOG.error("Failed to clear conversation memory for session {}", sessionId, e);
            throw new RuntimeException("Failed to clear messages for session " + sessionId, e);
        }
    }

    private void bindMessage(PreparedStatement statement, String sessionId,
                             TurnMessage message) throws SQLException {
        Objects.requireNonNull(message, "messages must not contain null entries");
        String role;
        String text;
        String toolName = null;
        String toolCallId = null;
        switch (message) {
            case TurnMessage.System system -> {
                role = "system";
                text = system.text();
            }
            case TurnMessage.User user -> {
                role = "user";
                text = user.text();
            }
            case TurnMessage.Assistant assistant -> {
                role = "assistant";
                text = assistant.text();
            }
            case TurnMessage.Tool tool -> {
                role = "tool";
                text = tool.text();
                toolName = tool.toolName();
                toolCallId = tool.toolCallId();
            }
        }
        statement.setString(1, sessionId);
        statement.setString(2, role);
        statement.setString(3, text);
        statement.setString(4, toolName);
        statement.setString(5, toolCallId);
        statement.setString(6,
            PersistenceSupport.metadataToJson(objectMapper, message.metadata()));
    }

    private TurnMessage rowToMessage(ResultSet resultSet) throws SQLException {
        Map<String, String> metadata = PersistenceSupport.metadataFromJson(
            objectMapper, resultSet.getString("metadata_json"));
        String role = resultSet.getString("role");
        if (role == null) {
            throw new IllegalStateException("Persisted conversation message role is null");
        }
        String content = resultSet.getString("content");
        return switch (role) {
            case "system" -> new TurnMessage.System(content, metadata);
            case "user" -> new TurnMessage.User(content, metadata);
            case "assistant" -> new TurnMessage.Assistant(content, metadata);
            case "tool" -> new TurnMessage.Tool(
                resultSet.getString("tool_name"),
                resultSet.getString("tool_call_id"),
                content,
                metadata);
            default -> throw new IllegalStateException(
                "Unknown persisted conversation message role: " + role);
        };
    }
}
