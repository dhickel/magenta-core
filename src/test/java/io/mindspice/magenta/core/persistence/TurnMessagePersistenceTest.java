package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.ConversationMemory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnMessagePersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void sqliteRoundTripPreservesEveryVariantAndTaggedColumns() throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("messages.db"));
        new MigrationRunner(dataSource);
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        List<TurnMessage> expected = List.of(
            new TurnMessage.System("", Map.of("kind", "system")),
            new TurnMessage.User("user", Map.of("kind", "user")),
            new TurnMessage.Assistant("assistant", Map.of("kind", "assistant")),
            new TurnMessage.Tool("lookup", "call-1", "", Map.of("kind", "tool")));

        memory.addMessages("session", expected);

        assertEquals(expected, memory.getMessages("session"));
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT role, tool_name, tool_call_id FROM "
                     + "magenta_core_conversation_memory ORDER BY id")) {
            try (var rows = statement.executeQuery()) {
                rows.next();
                assertEquals("system", rows.getString(1));
                assertEquals(null, rows.getString(2));
                assertEquals(null, rows.getString(3));
                rows.next();
                assertEquals("user", rows.getString(1));
                assertEquals(null, rows.getString(2));
                assertEquals(null, rows.getString(3));
                rows.next();
                assertEquals("assistant", rows.getString(1));
                assertEquals(null, rows.getString(2));
                assertEquals(null, rows.getString(3));
                rows.next();
                assertEquals("tool", rows.getString(1));
                assertEquals("lookup", rows.getString(2));
                assertEquals("call-1", rows.getString(3));
            }
        }
    }

    @Test
    void schemaRejectsUnknownRolesAndToolIdentityOnNonToolRows() throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("message-checks.db"));
        new MigrationRunner(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            assertThrows(SQLException.class, () -> insertRaw(connection,
                "unknown", "text", null, null));
            assertThrows(SQLException.class, () -> insertRaw(connection,
                "user", "text", "lookup", "call-1"));
            assertThrows(SQLException.class, () -> insertRaw(connection,
                "tool", "text", "", "call-1"));
            assertThrows(SQLException.class, () -> insertRaw(connection,
                "tool", "text", "lookup", " "));
            try (var pragma = connection.createStatement()) {
                pragma.execute("PRAGMA ignore_check_constraints = ON");
            }
            insertRaw(connection, "unknown", "text", null, null);
        }
        RuntimeException failure = assertThrows(RuntimeException.class,
            () -> new SqliteConversationMemory(dataSource).getMessages("raw-session"));
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void springAiAdapterUsesExhaustiveRoleConversionWithoutUserFallback() {
        RecordingMemory memory = new RecordingMemory();
        SpringAiConversationMemoryAdapter adapter = new SpringAiConversationMemoryAdapter(memory);
        adapter.add("session", List.of(
            SystemMessage.builder().text("system").metadata(Map.of("kind", "system")).build(),
            UserMessage.builder().text("user").metadata(Map.of("kind", "user")).build(),
            AssistantMessage.builder().content("assistant")
                .properties(Map.of("kind", "assistant")).build(),
            ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    "call-1", "lookup", "tool")))
                .metadata(Map.of("kind", "tool"))
                .build()));

        assertEquals(List.of(
            new TurnMessage.System("system", Map.of("kind", "system")),
            new TurnMessage.User("user", Map.of("kind", "user")),
            new TurnMessage.Assistant("assistant", Map.of("kind", "assistant")),
            new TurnMessage.Tool("lookup", "call-1", "tool", Map.of("kind", "tool"))),
            memory.messages);

        List<Message> springMessages = adapter.get("session");
        assertEquals(4, springMessages.size());
        assertInstanceOf(SystemMessage.class, springMessages.get(0));
        assertInstanceOf(UserMessage.class, springMessages.get(1));
        assertInstanceOf(AssistantMessage.class, springMessages.get(2));
        assertInstanceOf(ToolResponseMessage.class, springMessages.get(3));
        assertTrue(springMessages.get(3).getMetadata().containsKey("kind"));
    }

    @Test
    void sqliteAuditUsesVariantRoleTagsAndToolFieldsOnlyForToolMessages() throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("audit.db"));
        new MigrationRunner(dataSource);
        SqliteAuditWriter writer = new SqliteAuditWriter(dataSource);
        writer.recordUserMessage("session", new TurnMessage.User("user", Map.of()));
        writer.recordAssistantMessage("session", new TurnMessage.Assistant(
            "assistant", Map.of("kind", "answer")));

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT event_type, payload_json FROM magenta_core_audit_log "
                     + "WHERE event_type IN ('USER_MESSAGE', 'ASSISTANT_MESSAGE') "
                     + "ORDER BY id")) {
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("USER_MESSAGE", rows.getString(1));
                assertTrue(rows.getString(2).contains("\"role\":\"user\""));
                assertTrue(rows.next());
                assertEquals("ASSISTANT_MESSAGE", rows.getString(1));
                assertTrue(rows.getString(2).contains("\"role\":\"assistant\""));
                assertTrue(!rows.getString(2).contains("toolName"));
            }
        }
    }

    private static void insertRaw(Connection connection, String role, String content,
                                  String toolName, String toolCallId) throws SQLException {
        try (PreparedStatement session = connection.prepareStatement(
            "INSERT OR IGNORE INTO magenta_core_session_metadata(session_id) VALUES (?)")) {
            session.setString(1, "raw-session");
            session.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO magenta_core_conversation_memory "
                + "(session_id, role, content, tool_name, tool_call_id) "
                + "VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, "raw-session");
            statement.setString(2, role);
            statement.setString(3, content);
            statement.setString(4, toolName);
            statement.setString(5, toolCallId);
            statement.executeUpdate();
        }
    }

    private SQLiteDataSource dataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file);
        return dataSource;
    }

    private static final class RecordingMemory implements ConversationMemory {
        private final List<TurnMessage> messages = new ArrayList<>();

        @Override
        public List<TurnMessage> getMessages(String sessionId) {
            return List.copyOf(messages);
        }

        @Override
        public void addMessages(String sessionId, List<TurnMessage> additions) {
            messages.addAll(additions);
        }

        @Override
        public void clear(String sessionId) {
            messages.clear();
        }
    }
}
