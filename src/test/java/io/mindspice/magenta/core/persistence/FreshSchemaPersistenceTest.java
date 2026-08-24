package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.ClaimedMessage;
import io.mindspice.magenta.core.ConversationSession;
import io.mindspice.magenta.core.DefaultMessageQueue;
import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.PendingMessage;
import io.mindspice.magenta.core.QuestionPendingContext;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.AuditWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreshSchemaPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void initializesOneNamespacedSchemaAndRepositoriesUseIt() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("fresh.db"));
        MigrationRunner runner = new MigrationRunner(dataSource);
        runner.apply(List.of());

        assertEquals(1, runner.currentVersion());
        assertEquals(Set.of(
            "magenta_core_audit_log",
            "magenta_core_conversation_memory",
            "magenta_core_pending_interaction_questions",
            "magenta_core_pending_interactions",
            "magenta_core_pending_messages",
            "magenta_core_schema_identity",
            "magenta_core_schema_version",
            "magenta_core_session_metadata"
        ), tableNames(dataSource).stream()
            .filter(name -> name.startsWith("magenta_core_"))
            .collect(Collectors.toSet()));

        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages("session", List.of(
            TurnMessage.user("hello"), TurnMessage.assistant("hi")));
        assertEquals(2, memory.getMessages("session").size());

        SqliteSessionStore sessions = new SqliteSessionStore(dataSource);
        assertNotNull(sessions.findById("session"));
        sessions.save(new ConversationSession(
            "session", "A title", "model-a", true, false, "test",
            Instant.now(), Instant.now(), Map.of("kind", "focused")));
        assertEquals("model-a", sessions.findById("session").model());

        SqliteMessageQueueStore queue = new SqliteMessageQueueStore(dataSource);
        queue.insert(new PendingMessage(
            "message", "session", "queued", Map.of("source", "test"), Instant.now()));
        assertTrue(queue.claimAtomic("message", "claim-a", Instant.now()));
        assertFalse(queue.claimAtomic("message", "claim-b", Instant.now()));
        queue.releaseClaim("message");
        assertEquals(1, queue.findBySessionId("session").size());

        new SqliteAuditWriter(dataSource).recordError(
            "session", new AuditWriter.TurnError("failure", null, "model-a"));
        assertEquals(1, count(dataSource, "magenta_core_audit_log"));
    }

    @Test
    void leavesHistoricalTablesAndRowsUntouched() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("coexistence.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE turnloop_schema_version (version INTEGER NOT NULL)");
            statement.execute("INSERT INTO turnloop_schema_version(version) VALUES (41)");
            statement.execute("CREATE TABLE turnloop_conversation_memory (id INTEGER PRIMARY KEY, content TEXT)");
            statement.execute("CREATE INDEX idx_memory_session ON turnloop_conversation_memory(id)");
            statement.execute("INSERT INTO turnloop_conversation_memory(id, content) VALUES (7, 'legacy')");
        }

        MigrationRunner runner = new MigrationRunner(dataSource);
        runner.apply(List.of());

        assertEquals(1, runner.currentVersion());
        assertEquals(41, scalarInt(dataSource,
            "SELECT version FROM turnloop_schema_version"));
        assertEquals("legacy", scalarString(dataSource,
            "SELECT content FROM turnloop_conversation_memory WHERE id = 7"));
        assertTrue(indexNames(dataSource).contains("idx_memory_session"));
        assertTrue(tableNames(dataSource).contains("magenta_core_conversation_memory"));
        assertTrue(tableNames(dataSource).contains("magenta_core_schema_identity"));
    }

    @Test
    void restoresOrderedPendingInteractionAcrossRepositoryInstances() throws Exception {
        Path database = tempDir.resolve("interaction.db");
        SQLiteDataSource dataSource = fileDataSource(database);
        new MigrationRunner(dataSource).apply(List.of());

        QuestionPendingContext original = new QuestionPendingContext(
            "session", "turn-1", List.of("first?", "second?", "third?"),
            "model-a", Map.of("continuation", "checkpoint-9"));
        new SqliteInteractionStore(dataSource).save("session", original);

        SQLiteDataSource restartedDataSource = fileDataSource(database);
        SqliteInteractionStore restarted = new SqliteInteractionStore(restartedDataSource);
        QuestionPendingContext restored = restarted.load("session");
        assertNotNull(restored);
        assertEquals(original.questions(), restored.questions());
        assertEquals(original.turnId(), restored.turnId());
        assertEquals(original.model(), restored.model());
        assertEquals(original.continuationContext(), restored.continuationContext());
        assertEquals(QuestionPendingContext.State.PENDING, restored.state());

        assertTrue(restarted.answer("session", "one combined answer"));
        assertFalse(restarted.answer("session", "duplicate answer"));
        assertEquals(QuestionPendingContext.State.ANSWERED,
            restarted.load("session").state());
        assertEquals(QuestionPendingContext.State.ANSWERED,
            restarted.findPersisted("session").state());
        assertEquals(1, count(restartedDataSource, "magenta_core_audit_log"));

        QuestionPendingContext cancellation = new QuestionPendingContext(
            "session", "turn-2", List.of("cancel me"), "model-b", Map.of());
        restarted.save("session", cancellation);
        assertTrue(restarted.cancel("session", "caller stopped waiting"));
        assertEquals(QuestionPendingContext.State.CANCELLED,
            restarted.load("session").state());
        assertEquals(QuestionPendingContext.State.CANCELLED,
            restarted.findPersisted("session").state());
        assertEquals(2, count(restartedDataSource, "magenta_core_audit_log"));
    }

    @Test
    void queueClaimsCanBeAcknowledgedAndReleasedAfterReconstruction() {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("queue-restart.db"));
        new MigrationRunner(dataSource).apply(List.of());

        DefaultMessageQueue first = new DefaultMessageQueue(
            new SqliteMessageQueueStore(dataSource));
        first.enqueue("session", "ack me", Map.of());
        ClaimedMessage ackClaim = first.claim("session");
        assertNotNull(ackClaim);

        DefaultMessageQueue restarted = new DefaultMessageQueue(
            new SqliteMessageQueueStore(dataSource));
        assertNotNull(new SqliteMessageQueueStore(dataSource)
            .findByClaimToken(ackClaim.claimToken()));
        restarted.ack(ackClaim.claimToken());
        assertNull(new SqliteMessageQueueStore(dataSource)
            .findById(ackClaim.message().messageId()));

        restarted.enqueue("session", "release me", Map.of());
        ClaimedMessage releaseClaim = restarted.claim("session");
        assertNotNull(releaseClaim);
        DefaultMessageQueue restartedAgain = new DefaultMessageQueue(
            new SqliteMessageQueueStore(dataSource));
        restartedAgain.release(releaseClaim.claimToken());
        ClaimedMessage reclaimed = restartedAgain.claim("session");
        assertNotNull(reclaimed);
        assertEquals(releaseClaim.message().messageId(), reclaimed.message().messageId());
    }

    @Test
    void staleClaimRecoveryRemainsDurableAcrossReconstruction() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("queue-stale.db"));
        new MigrationRunner(dataSource).apply(List.of());
        DefaultMessageQueue first = new DefaultMessageQueue(
            new SqliteMessageQueueStore(dataSource));
        PendingMessage pending = first.enqueue("session", "stale", Map.of());
        assertNotNull(first.claim("session"));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                 connection.prepareStatement("""
                     UPDATE magenta_core_pending_messages
                     SET claimed_at = ? WHERE message_id = ?
                     """)) {
            statement.setString(1, Instant.EPOCH.toString());
            statement.setString(2, pending.messageId());
            statement.executeUpdate();
        }

        DefaultMessageQueue restarted = new DefaultMessageQueue(
            new SqliteMessageQueueStore(dataSource));
        assertEquals(1, restarted.recoverStaleClaims(java.time.Duration.ofMinutes(1)).size());
        assertNotNull(restarted.claim("session"));
    }

    @Test
    void foreignKeysCascadeAndRejectOrphans() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("foreign-keys.db"));
        new MigrationRunner(dataSource).apply(List.of());

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                 connection.prepareStatement("""
                     INSERT INTO magenta_core_conversation_memory(session_id, role, content)
                     VALUES ('missing', 'user', 'orphan')
                     """)) {
            assertThrows(SQLException.class, statement::executeUpdate);
        }

        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages("session", List.of(TurnMessage.user("kept")));
        new SqliteMessageQueueStore(dataSource).insert(new PendingMessage(
            "message", "session", "queued", Map.of(), Instant.now()));
        new SqliteInteractionStore(dataSource).save("session", new QuestionPendingContext(
            "session", "turn", List.of("question")));
        new SqliteAuditWriter(dataSource).recordUserMessage("session", TurnMessage.user("audit"));

        new SqliteSessionStore(dataSource).delete("session");
        assertEquals(0, count(dataSource, "magenta_core_conversation_memory"));
        assertEquals(0, count(dataSource, "magenta_core_pending_messages"));
        assertEquals(0, count(dataSource, "magenta_core_pending_interactions"));
        assertEquals(0, count(dataSource, "magenta_core_audit_log"));
    }

    @Test
    void migrationAndVersionBookkeepingAreAtomic() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("migration.db"));
        MigrationRunner runner = new MigrationRunner(dataSource);
        AtomicBoolean fail = new AtomicBoolean(true);

        var migration = new io.mindspice.magenta.core.Migration() {
            @Override
            public int version() {
                return 2;
            }

            @Override
            public void migrate(javax.sql.DataSource migratedDataSource) throws Exception {
                try (Connection connection = migratedDataSource.getConnection();
                     Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE magenta_core_migration_probe (value TEXT)");
                    statement.execute("INSERT INTO magenta_core_migration_probe VALUES ('written')");
                    if (fail.get()) {
                        throw new SQLException("intentional rollback");
                    }
                }
            }
        };

        assertThrows(IllegalStateException.class, () -> runner.apply(List.of(migration)));
        assertEquals(1, runner.currentVersion());
        assertFalse(tableNames(dataSource).contains("magenta_core_migration_probe"));

        fail.set(false);
        runner.apply(List.of(migration));
        assertEquals(2, runner.currentVersion());
        assertEquals("written", scalarString(dataSource,
            "SELECT value FROM magenta_core_migration_probe"));
    }

    @Test
    void convertsTheBuilderNamedMemoryUrlToDurableFileStorage() throws Exception {
        Path database = tempDir.resolve("durable.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:file:" + database.toAbsolutePath()
            + "?mode=memory&cache=shared");
        new MigrationRunner(dataSource).apply(List.of());
        new SqliteSessionStore(dataSource).save(new ConversationSession(
            "durable", null, null, false, false, "test",
            null, null, Map.of()));

        assertTrue(Files.exists(database));
        SQLiteDataSource reopened = fileDataSource(database);
        assertNotNull(new SqliteSessionStore(reopened).findById("durable"));
    }

    private SQLiteDataSource fileDataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return dataSource;
    }

    private static Set<String> tableNames(SQLiteDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                 connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'table'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> names = new java.util.HashSet<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        }
    }

    private static Set<String> indexNames(SQLiteDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                 connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> names = new java.util.HashSet<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        }
    }

    private static int count(SQLiteDataSource dataSource, String table) throws SQLException {
        return scalarInt(dataSource, "SELECT COUNT(*) FROM " + table);
    }

    private static int scalarInt(SQLiteDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String scalarString(SQLiteDataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
