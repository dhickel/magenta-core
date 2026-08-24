package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.InteractionState;
import io.mindspice.magenta.core.Migration;
import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.QueueReason;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolActivityStatus;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.spi.AuditWriter;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshSchemaPersistenceTest {

    private static final Instant TIME = Instant.parse("2026-08-24T13:49:27Z");

    @TempDir
    Path tempDir;

    @Test
    void freshDataSourceSchemaContainsOnlyTheNewNamespacedStructures() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("fresh.db"));
        MigrationRunner runner = new MigrationRunner(dataSource);
        runner.apply(List.of());

        assertEquals(1, runner.currentVersion());
        assertEquals(Set.of(
            "magenta_core_audit_log",
            "magenta_core_conversation_memory",
            "magenta_core_interaction_answers",
            "magenta_core_interaction_questions",
            "magenta_core_interaction_tool_activities",
            "magenta_core_interactions",
            "magenta_core_queued_requests",
            "magenta_core_schema_identity",
            "magenta_core_schema_version",
            "magenta_core_session_metadata"
        ), tableNames(dataSource).stream()
            .filter(name -> name.startsWith("magenta_core_"))
            .collect(Collectors.toSet()));

        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages("session", List.of(TurnMessage.user("hello"), TurnMessage.assistant("hi")));
        assertEquals(List.of("hello", "hi"), memory.getMessages("session").stream()
            .map(TurnMessage::text).toList());

        SqliteSessionStore sessions = new SqliteSessionStore(dataSource);
        assertNotNull(sessions.findById("session"));
        sessions.updateModel("session", "model-a");
        assertEquals("model-a", sessions.findById("session").model());

        SqliteAuditWriter audit = new SqliteAuditWriter(dataSource);
        audit.recordError("session", new AuditWriter.TurnError("failure", null, "model-a"));
        assertEquals(1, count(dataSource, "magenta_core_audit_log"));
    }

    @Test
    void builderDataRootUsesTheSameFreshSchemaWithoutProviderCalls() throws Exception {
        Path root = tempDir.resolve("data-root");
        TurnLoop loop = TurnLoopBuilder.create()
            .model("model", new ModelConfig("model", "remote-model", "http://localhost:11434",
                null, EndpointType.OLLAMA, 4096, 0))
            .dataRoot(root)
            .build();
        try {
            assertTrue(java.nio.file.Files.isRegularFile(root.resolve("magenta-core.db")));
            Set<String> names = tableNames(fileDataSource(root.resolve("magenta-core.db")));
            assertTrue(names.contains("magenta_core_schema_identity"));
            assertTrue(names.contains("magenta_core_queued_requests"));
            assertTrue(names.contains("magenta_core_interactions"));
        } finally {
            loop.close();
        }
    }

    @Test
    void callerOwnedSqliteConfigurationIsNotRewritten() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        String url = "jdbc:sqlite:file:caller-owned?mode=memory&cache=shared";
        dataSource.setUrl(url);

        new MigrationRunner(dataSource);

        assertEquals(url, dataSource.getUrl());
    }

    @Test
    void incompatibleMagentaSchemaFailsBeforeAnyRewriteAndPreservesRows() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("incompatible.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE magenta_core_schema_identity "
                + "(schema_id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, schema_hash TEXT NOT NULL)");
            statement.execute("INSERT INTO magenta_core_schema_identity "
                + "(schema_id, schema_version, schema_hash) VALUES ('old-protocol', 1, 'old-hash')");
            statement.execute("CREATE TABLE magenta_core_old_rows (value TEXT)");
            statement.execute("INSERT INTO magenta_core_old_rows(value) VALUES ('preserve me')");
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));
        assertTrue(failure.getMessage().contains("Incompatible existing Magenta Core schema"));
        assertEquals("preserve me", scalarString(dataSource,
            "SELECT value FROM magenta_core_old_rows"));
        assertFalse(tableNames(dataSource).contains("magenta_core_schema_version"));
        assertFalse(tableNames(dataSource).contains("magenta_core_queued_requests"));
    }

    @Test
    void historicalTurnloopTablesAndIndexesRemainUntouched() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("coexistence.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE turnloop_schema_version (version INTEGER NOT NULL)");
            statement.execute("INSERT INTO turnloop_schema_version(version) VALUES (41)");
            statement.execute("CREATE TABLE turnloop_conversation_memory "
                + "(id INTEGER PRIMARY KEY, content TEXT)");
            statement.execute("CREATE INDEX idx_memory_session ON turnloop_conversation_memory(id)");
            statement.execute("INSERT INTO turnloop_conversation_memory(id, content) "
                + "VALUES (7, 'legacy')");
        }

        MigrationRunner runner = new MigrationRunner(dataSource);
        assertEquals(1, runner.currentVersion());
        assertEquals(41, scalarInt(dataSource, "SELECT version FROM turnloop_schema_version"));
        assertEquals("legacy", scalarString(dataSource,
            "SELECT content FROM turnloop_conversation_memory WHERE id = 7"));
        assertTrue(indexNames(dataSource).contains("idx_memory_session"));
        assertTrue(tableNames(dataSource).contains("magenta_core_queued_requests"));
    }

    @Test
    void queuedIntentAndNestedClaimCarrierSurviveReconstructionAndLeaseRecovery() throws Exception {
        Path database = tempDir.resolve("queue-restart.db");
        SQLiteDataSource firstDataSource = fileDataSource(database);
        new MigrationRunner(firstDataSource);
        SqliteMessageQueueStore first = new SqliteMessageQueueStore(firstDataSource);
        Instant earlier = TIME;
        MessageQueueStore.QueuedRequest deferred = new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), "session", "deferred", null, null,
            Map.of("source", "caller"), QueueReason.EXPLICIT, earlier);
        MessageQueueStore.QueuedRequest explicit = new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), "session", "explicit", "chosen-model",
            List.of("tool-a", "tool-b"), Map.of("source", "caller", "index", "2"),
            QueueReason.SESSION_ACTIVE, earlier.plusMillis(1));
        first.insert(deferred);
        first.insert(explicit);

        SqliteMessageQueueStore restarted = new SqliteMessageQueueStore(
            fileDataSource(database));
        List<MessageQueueStore.QueuedRequest> restored = restarted.list("session");
        assertEquals(List.of(deferred, explicit), restored);
        assertNull(restored.getFirst().model());
        assertNull(restored.getFirst().toolNames());
        assertEquals(List.of("tool-a", "tool-b"), restored.get(1).toolNames());

        MessageQueueStore.Claim claim = restarted.claimOldest(
            "session", TIME.plusSeconds(1), Duration.ofMinutes(5)).orElseThrow();
        assertEquals(deferred, claim.request());
        assertTrue(new SqliteMessageQueueStore(fileDataSource(database)).ack(claim.claimToken()));
        assertEquals(List.of(explicit), restarted.list("session"));

        MessageQueueStore.Claim releaseClaim = restarted.claimOldest(
            "session", TIME.plusSeconds(2), Duration.ofMinutes(5)).orElseThrow();
        SqliteMessageQueueStore reconstructed = new SqliteMessageQueueStore(
            fileDataSource(database));
        assertTrue(reconstructed.release(releaseClaim.claimToken()));
        MessageQueueStore.Claim reclaimed = reconstructed.claimOldest(
            "session", TIME.plusSeconds(3), Duration.ofMinutes(5)).orElseThrow();
        assertEquals(explicit.messageId(), reclaimed.request().messageId());
        assertTrue(reconstructed.ack(reclaimed.claimToken()));

        MessageQueueStore.QueuedRequest staleRequest = new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), "stale-session", "stale", null, null, Map.of(),
            QueueReason.EXPLICIT, TIME);
        reconstructed.insert(staleRequest);
        MessageQueueStore.Claim stale = reconstructed.claimOldest(
            "stale-session", TIME, Duration.ofMinutes(1)).orElseThrow();
        assertEquals(0, reconstructed.recoverExpiredClaims(TIME.plusSeconds(59)));
        assertEquals(1, new SqliteMessageQueueStore(fileDataSource(database))
            .recoverExpiredClaims(TIME.plus(Duration.ofMinutes(1))));
        MessageQueueStore.Claim recovered = reconstructed.claimOldest(
            "stale-session", TIME.plus(Duration.ofMinutes(1)), Duration.ofMinutes(1)).orElseThrow();
        assertEquals(stale.request().messageId(), recovered.request().messageId());
    }

    @Test
    void exactInteractionSnapshotAnswersAndIdQualifiedCasSurviveRestart() throws Exception {
        Path database = tempDir.resolve("interaction-restart.db");
        SQLiteDataSource dataSource = fileDataSource(database);
        new MigrationRunner(dataSource);
        SqliteInteractionStore store = new SqliteInteractionStore(dataSource);
        InteractionStore.Interaction original = interaction(
            "interaction-session", new ExecutionOrigin.Queued("queued-message"),
            List.of("first?", "second?"), InteractionState.PENDING,
            List.of(), null, Map.of("caller", "metadata"), Map.of("continuation", "checkpoint"));
        store.save(original);

        SqliteInteractionStore restarted = new SqliteInteractionStore(fileDataSource(database));
        InteractionStore.Interaction restored = restarted.pending("interaction-session").orElseThrow();
        assertEquals(original.interactionId(), restored.interactionId());
        assertEquals(original.questions(), restored.questions());
        assertEquals(original.model(), restored.model());
        assertEquals(original.explicitToolNames(), restored.explicitToolNames());
        assertEquals(original.metadata(), restored.metadata());
        assertEquals(original.continuationMetadata(), restored.continuationMetadata());
        assertEquals(original.questions().execution().toolActivities(),
            restored.questions().execution().toolActivities());
        assertEquals(new ExecutionOrigin.Queued("queued-message"),
            restored.questions().execution().origin());

        assertFalse(restarted.answer("interaction-session", UUID.randomUUID().toString(),
            List.of("wrong", "target")).applied());
        assertThrows(IllegalArgumentException.class,
            () -> restarted.answer("interaction-session", original.interactionId(), List.of("one")));
        assertThrows(IllegalArgumentException.class,
            () -> restarted.answer("interaction-session", original.interactionId(),
                List.of("one", " ")));

        InteractionStore.MutationResult answered = restarted.answer(
            "interaction-session", original.interactionId(), List.of("one", "two"));
        assertTrue(answered.applied());
        InteractionStore.Interaction answeredSnapshot = restarted.find(
            "interaction-session", original.interactionId()).orElseThrow();
        assertEquals(List.of("one", "two"), answeredSnapshot.answers());
        assertEquals(InteractionState.ANSWERED, answeredSnapshot.state());
        InteractionStore.MutationResult duplicate = restarted.answer(
            "interaction-session", original.interactionId(), List.of("different", "answers"));
        assertFalse(duplicate.applied());
        assertEquals(InteractionState.ANSWERED, duplicate.state());
        assertEquals(List.of("one", "two"), restarted.find(
            "interaction-session", original.interactionId()).orElseThrow().answers());

        InteractionStore.Interaction cancellation = interaction(
            "interaction-session", new ExecutionOrigin.Direct(), List.of("cancel?"),
            InteractionState.PENDING, List.of(), null, Map.of(), Map.of());
        restarted.save(cancellation);
        InteractionStore.MutationResult cancelled = restarted.cancel(
            "interaction-session", cancellation.interactionId(), "no longer needed");
        assertTrue(cancelled.applied());
        assertEquals(InteractionState.CANCELLED, cancelled.state());
        assertFalse(restarted.cancel("interaction-session", cancellation.interactionId(), "again")
            .applied());
        assertFalse(restarted.answer("interaction-session", cancellation.interactionId(),
            List.of("late")).applied());
        assertEquals(InteractionState.CANCELLED, restarted.find(
            "interaction-session", cancellation.interactionId()).orElseThrow().state());
    }

    @Test
    void foreignKeysCascadeAllNewFrameworkRowsWhenSessionIsDeleted() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("foreign-keys.db"));
        new MigrationRunner(dataSource);
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages("session", List.of(TurnMessage.user("kept")));
        new SqliteMessageQueueStore(dataSource).insert(new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), "session", "queued", null, null, Map.of(),
            QueueReason.EXPLICIT, TIME));
        new SqliteInteractionStore(dataSource).save(interaction(
            "session", new ExecutionOrigin.Direct(), List.of("question"),
            InteractionState.PENDING, List.of(), null, Map.of(), Map.of()));
        new SqliteAuditWriter(dataSource).recordUserMessage("session", TurnMessage.user("audit"));

        new SqliteSessionStore(dataSource).delete("session");
        assertEquals(0, count(dataSource, "magenta_core_conversation_memory"));
        assertEquals(0, count(dataSource, "magenta_core_queued_requests"));
        assertEquals(0, count(dataSource, "magenta_core_interactions"));
        assertEquals(0, count(dataSource, "magenta_core_interaction_questions"));
        assertEquals(0, count(dataSource, "magenta_core_audit_log"));
    }

    @Test
    void migrationAndVersionBookkeepingRemainAtomicAfterFreshInitialization() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("migration.db"));
        MigrationRunner runner = new MigrationRunner(dataSource);
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Migration migration = new Migration() {
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
                    if (fail.get()) throw new SQLException("intentional rollback");
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

    private static InteractionStore.Interaction interaction(
        String sessionId,
        ExecutionOrigin origin,
        List<String> questions,
        InteractionState state,
        List<String> answers,
        List<String> explicitTools,
        Map<String, String> metadata,
        Map<String, String> continuation) {
        String interactionId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        ExecutionInfo execution = new ExecutionInfo(sessionId, turnId, origin, TIME, TIME,
            new ContextUsage(12, 3000, 4096), List.of(new ToolActivityData(
                "ask_user", "call-question", ToolActivityStatus.COMPLETED, "Questions pending")));
        TurnResponse.Questions questionOutcome = new TurnResponse.Questions(
            execution, interactionId, questions);
        Instant updated = state == InteractionState.CANCELLED ? TIME.plusSeconds(2) : TIME;
        return new InteractionStore.Interaction(
            interactionId, sessionId, questionOutcome, "model", explicitTools, metadata,
            continuation, answers, state, TIME, updated,
            state == InteractionState.CANCELLED ? updated : null,
            state == InteractionState.CANCELLED ? "cancelled" : null);
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
                Set<String> names = new HashSet<>();
                while (resultSet.next()) names.add(resultSet.getString(1));
                return names;
            }
        }
    }

    private static Set<String> indexNames(SQLiteDataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement =
                 connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = 'index'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                Set<String> names = new HashSet<>();
                while (resultSet.next()) names.add(resultSet.getString(1));
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
