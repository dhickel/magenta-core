package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.Migration;
import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.QueueReason;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolFailureCategory;
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

import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshSchemaPersistenceTest {

    private static final Instant TIME = Instant.parse("2026-08-24T13:49:27Z");
    private static final String CURRENT_SCHEMA_ID =
        "io.mindspice:magenta-core:sealed-adts-v1";
    private static final String PRIOR_SCHEMA_ID =
        "io.mindspice:magenta-core:turn-response-protocol-v2";
    private static final String PRIOR_SCHEMA_HASH =
        "75246903932f3c9d5ccd9521646523f634333c3842301080cdd44a0c70314a97";

    @TempDir
    Path tempDir;

    @Test
    void freshDataSourceSchemaContainsOnlyTheNewNamespacedStructures() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("fresh.db"));
        MigrationRunner runner = new MigrationRunner(dataSource);
        runner.apply(List.of());

        assertEquals(1, runner.currentVersion());
        assertEquals(CURRENT_SCHEMA_ID, scalarString(dataSource,
            "SELECT schema_id FROM magenta_core_schema_identity"));
        assertEquals(1, scalarInt(dataSource,
            "SELECT schema_version FROM magenta_core_schema_identity"));
        assertEquals(checkedInSchemaHash(), scalarString(dataSource,
            "SELECT schema_hash FROM magenta_core_schema_identity"));
        assertEquals(1, scalarInt(dataSource,
            "SELECT version FROM magenta_core_schema_version"));
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
        memory.addMessages("session", List.of(
            new TurnMessage.User("hello", Map.of()),
            new TurnMessage.Assistant("hi", Map.of())));
        assertEquals(List.of("hello", "hi"), memory.getMessages("session").stream()
            .map(TurnMessage::text).toList());

        SqliteSessionStore sessions = new SqliteSessionStore(dataSource);
        assertNotNull(sessions.findById("session"));
        sessions.updateModel("session", "model-a");
        assertEquals("model-a", sessions.findById("session").model());

        SqliteAuditWriter audit = new SqliteAuditWriter(dataSource);
        audit.recordError("session", new AuditWriter.TurnError("failure", null, "model-a"));
        assertEquals(1, count(dataSource, "magenta_core_audit_log"));
        assertAllStoreRoundTrips(dataSource, "store-session");
    }

    @Test
    void builderDataRootUsesTheSameFreshSchemaWithoutProviderCalls() throws Exception {
        Path root = tempDir.resolve("data-root");
        TurnLoop loop = TurnLoopBuilder.create()
            .model("model", new ModelConfig.Ollama(
                "model", "remote-model", "http://localhost:11434", null, 4096, 0))
            .dataRoot(root)
            .build();
        try {
            assertTrue(java.nio.file.Files.isRegularFile(root.resolve("magenta-core.db")));
            SQLiteDataSource dataSource = fileDataSource(root.resolve("magenta-core.db"));
            Set<String> names = tableNames(dataSource);
            assertTrue(names.contains("magenta_core_schema_identity"));
            assertTrue(names.contains("magenta_core_queued_requests"));
            assertTrue(names.contains("magenta_core_interactions"));
            assertAllStoreRoundTrips(dataSource, "data-root-session");
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
    void immediatelyPriorSchemaFailsBeforeAnyMutationAndPreservesAllRows() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("prior-schema.db"));
        createIdentity(dataSource, new SchemaIdentity(
            PRIOR_SCHEMA_ID, 1, PRIOR_SCHEMA_HASH));
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Incompatible existing Magenta Core schema"));
        assertEquals(before, snapshot(dataSource));
        assertEquals("preserve me", scalarString(dataSource,
            "SELECT value FROM magenta_core_probe"));
        assertFalse(tableNames(dataSource).contains("magenta_core_schema_version"));
        assertFalse(tableNames(dataSource).contains("magenta_core_queued_requests"));
    }

    @Test
    void mismatchedVersionAndHashFailBeforeAnyMutation() throws Exception {
        List<SchemaIdentity> identities = List.of(
            new SchemaIdentity(CURRENT_SCHEMA_ID, 2, checkedInSchemaHash()),
            new SchemaIdentity(CURRENT_SCHEMA_ID, 1, "not-the-checked-in-hash"));
        for (int index = 0; index < identities.size(); index++) {
            SQLiteDataSource dataSource = fileDataSource(
                tempDir.resolve("mismatched-identity-" + index + ".db"));
            createIdentity(dataSource, identities.get(index));
            DatabaseSnapshot before = snapshot(dataSource);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new MigrationRunner(dataSource));

            assertTrue(failure.getMessage().contains("Incompatible existing Magenta Core schema"));
            assertEquals(before, snapshot(dataSource));
        }
    }

    @Test
    void currentIdentityWithMissingBaseTablesFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("incomplete-current.db"));
        createIdentity(dataSource, new SchemaIdentity(
            CURRENT_SCHEMA_ID, 1, checkedInSchemaHash()));
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Framework schema definitions differ")
            || failure.getMessage().contains("Required table is missing"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void currentIdentityWithMissingRequiredIndexFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("missing-current-index.db"));
        new MigrationRunner(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX idx_magenta_core_queue_session_order");
        }
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Framework schema definitions differ")
            || failure.getMessage().contains("Required indexes are missing"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void alteredCheckConstraintFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("altered-check.db"));
        new MigrationRunner(dataSource);
        mutateSqliteMaster(dataSource, "magenta_core_schema_version",
            "CHECK (version > 0)", "CHECK (version >= 0)");
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Framework schema definitions differ"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void alteredForeignKeyFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("altered-foreign-key.db"));
        new MigrationRunner(dataSource);
        mutateSqliteMaster(dataSource, "magenta_core_conversation_memory",
            "ON DELETE CASCADE", "ON DELETE SET NULL");
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Framework schema definitions differ"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void alteredIndexDefinitionFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("altered-index.db"));
        new MigrationRunner(dataSource);
        mutateSqliteMaster(dataSource, "idx_magenta_core_memory_session",
            "(session_id, id)", "(session_id, role)");
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("Framework schema definitions differ"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void emptyIdentityTableFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("empty-identity.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE magenta_core_schema_identity "
                + "(schema_id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, schema_hash TEXT NOT NULL)");
            statement.execute("CREATE TABLE magenta_core_empty_probe (value TEXT)");
            statement.execute("INSERT INTO magenta_core_empty_probe(value) VALUES ('preserve me')");
        }
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("identity is present but empty"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void magentaTablesWithoutIdentityFailBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("missing-identity.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE magenta_core_orphan (value TEXT)");
            statement.execute("INSERT INTO magenta_core_orphan(value) VALUES ('preserve me')");
            statement.execute("CREATE TABLE turnloop_legacy (value TEXT)");
            statement.execute("INSERT INTO turnloop_legacy(value) VALUES ('legacy')");
        }
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("tables or indexes exist without a schema identity"));
        assertEquals(before, snapshot(dataSource));
        assertEquals("legacy", scalarString(dataSource,
            "SELECT value FROM turnloop_legacy"));
    }

    @Test
    void reservedIndexWithoutIdentityFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("missing-index-identity.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE turnloop_index_probe (value TEXT)");
            statement.execute("CREATE INDEX idx_magenta_core_orphan ON turnloop_index_probe(value)");
            statement.execute("INSERT INTO turnloop_index_probe(value) VALUES ('legacy')");
        }
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("tables or indexes exist without a schema identity"));
        assertEquals(before, snapshot(dataSource));
        assertEquals("legacy", scalarString(dataSource,
            "SELECT value FROM turnloop_index_probe"));
    }

    @Test
    void malformedIdentityTableFailsBeforeAnyMutation() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("malformed-identity.db"));
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE magenta_core_schema_identity (schema_id TEXT)");
            statement.execute("INSERT INTO magenta_core_schema_identity(schema_id) VALUES ('malformed')");
            statement.execute("CREATE TABLE magenta_core_malformed_probe (value TEXT)");
            statement.execute("INSERT INTO magenta_core_malformed_probe(value) VALUES ('preserve me')");
        }
        DatabaseSnapshot before = snapshot(dataSource);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> new MigrationRunner(dataSource));

        assertTrue(failure.getMessage().contains("identity table is malformed"));
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void repeatInitializationPreservesFrameworkTablesAndRows() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("repeat.db"));
        new MigrationRunner(dataSource);
        assertAllStoreRoundTrips(dataSource, "repeat-session");
        DatabaseSnapshot before = snapshot(dataSource);

        MigrationRunner repeated = new MigrationRunner(dataSource);

        assertEquals(1, repeated.currentVersion());
        assertEquals(before, snapshot(dataSource));
    }

    @Test
    void freshSchemaEnforcesInteractionDiscriminatorOrderingAndToolCategories() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("schema-checks.db"));
        new MigrationRunner(dataSource);
        InteractionStore.Interaction.Pending pending = assertInstanceOf(
            InteractionStore.Interaction.Pending.class,
            interaction("schema-check-session", new ExecutionOrigin.Direct(),
                List.of("question?"), false, null, Map.of(), Map.of()));
        new SqliteInteractionStore(dataSource).save(pending);
        String interactionId = pending.interactionId();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE magenta_core_interactions SET state = 'UNKNOWN' WHERE interaction_id = '"
                    + interactionId + "'"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO magenta_core_interaction_questions "
                    + "(interaction_id, question_index, question_text) VALUES ('"
                    + interactionId + "', -1, 'invalid')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO magenta_core_interaction_questions "
                    + "(interaction_id, question_index, question_text) VALUES ('"
                    + interactionId + "', 0, 'duplicate')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO magenta_core_interaction_tool_activities "
                    + "(interaction_id, activity_index, tool_name, tool_call_id, status, "
                    + "failure_category, summary) VALUES ('" + interactionId
                    + "', 2, 'lookup', 'call-2', 'FAILED', 'UNKNOWN', 'failure')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO magenta_core_interaction_tool_activities "
                    + "(interaction_id, activity_index, tool_name, tool_call_id, status, "
                    + "failure_category, summary) VALUES ('" + interactionId
                    + "', 2, 'lookup', 'call-2', 'FAILED', NULL, 'failure')"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "INSERT INTO magenta_core_interaction_tool_activities "
                    + "(interaction_id, activity_index, tool_name, tool_call_id, status, "
                    + "failure_category, summary) VALUES ('" + interactionId
                    + "', 2, 'lookup', 'call-2', 'COMPLETED', 'ACCESS_DENIED', 'done')"));
        }
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
            List.of("first?", "second?"), false,
            null, Map.of("caller", "metadata"), Map.of("continuation", "checkpoint"));
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

        assertInstanceOf(InteractionStore.MutationResult.Missing.class,
            restarted.answer("interaction-session", UUID.randomUUID().toString(),
                List.of("wrong", "target")));
        assertThrows(IllegalArgumentException.class,
            () -> restarted.answer("interaction-session", original.interactionId(), List.of("one")));
        assertThrows(IllegalArgumentException.class,
            () -> restarted.answer("interaction-session", original.interactionId(),
                List.of("one", " ")));

        InteractionStore.MutationResult answered = restarted.answer(
            "interaction-session", original.interactionId(), List.of("one", "two"));
        InteractionStore.MutationResult.Applied applied =
            assertInstanceOf(InteractionStore.MutationResult.Applied.class, answered);
        InteractionStore.Interaction answeredSnapshot = restarted.find(
            "interaction-session", original.interactionId()).orElseThrow();
        InteractionStore.Interaction.Answered answeredState =
            assertInstanceOf(InteractionStore.Interaction.Answered.class, answeredSnapshot);
        assertEquals(List.of("one", "two"), answeredState.answers());
        assertEquals(answeredState, applied.snapshot());
        InteractionStore.MutationResult duplicate = restarted.answer(
            "interaction-session", original.interactionId(), List.of("different", "answers"));
        assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class, duplicate);
        assertEquals(List.of("one", "two"),
            assertInstanceOf(InteractionStore.Interaction.Answered.class,
                restarted.find("interaction-session", original.interactionId()).orElseThrow())
                .answers());

        InteractionStore.Interaction cancellation = interaction(
            "interaction-session", new ExecutionOrigin.Direct(), List.of("cancel?"),
            false, null, Map.of(), Map.of());
        restarted.save(cancellation);
        InteractionStore.MutationResult cancelled = restarted.cancel(
            "interaction-session", cancellation.interactionId(), "no longer needed");
        assertInstanceOf(InteractionStore.MutationResult.Applied.class, cancelled);
        assertInstanceOf(InteractionStore.Interaction.Cancelled.class,
            assertInstanceOf(InteractionStore.MutationResult.Applied.class, cancelled)
                .snapshot());
        assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
            restarted.cancel("interaction-session", cancellation.interactionId(), "again"));
        assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
            restarted.answer("interaction-session", cancellation.interactionId(),
                List.of("late")));
        assertInstanceOf(InteractionStore.Interaction.Cancelled.class,
            restarted.find("interaction-session", cancellation.interactionId()).orElseThrow());
    }

    @Test
    void foreignKeysCascadeAllNewFrameworkRowsWhenSessionIsDeleted() throws Exception {
        SQLiteDataSource dataSource = fileDataSource(tempDir.resolve("foreign-keys.db"));
        new MigrationRunner(dataSource);
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages("session", List.of(new TurnMessage.User("kept", Map.of())));
        new SqliteMessageQueueStore(dataSource).insert(new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), "session", "queued", null, null, Map.of(),
            QueueReason.EXPLICIT, TIME));
        new SqliteInteractionStore(dataSource).save(interaction(
            "session", new ExecutionOrigin.Direct(), List.of("question"),
            false, null, Map.of(), Map.of()));
        new SqliteAuditWriter(dataSource).recordUserMessage(
            "session", new TurnMessage.User("audit", Map.of()));

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

        MigrationRunner restarted = new MigrationRunner(dataSource);
        assertEquals(2, restarted.currentVersion());
        assertEquals("written", scalarString(dataSource,
            "SELECT value FROM magenta_core_migration_probe"));
    }

    private static InteractionStore.Interaction interaction(
        String sessionId,
        ExecutionOrigin origin,
        List<String> questions,
        boolean cancelled,
        List<String> explicitTools,
        Map<String, String> metadata,
        Map<String, String> continuation) {
        String interactionId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        ExecutionInfo execution = new ExecutionInfo(sessionId, turnId, origin, TIME, TIME,
            new ContextUsage(12, 3000, 4096), List.of(
                new ToolActivityData.Completed("ask_user", "call-question", "Questions pending"),
                new ToolActivityData.Failed("lookup", "call-failure", "access denied",
                    ToolFailureCategory.ACCESS_DENIED)));
        TurnResponse.Questions questionOutcome = new TurnResponse.Questions(
            execution, interactionId, questions);
        Instant updated = cancelled ? TIME.plusSeconds(2) : TIME;
        InteractionStore.InteractionInfo info = new InteractionStore.InteractionInfo(
            interactionId, sessionId, questionOutcome, "model", explicitTools, metadata,
            continuation, TIME, updated);
        return cancelled
            ? new InteractionStore.Interaction.Cancelled(info, updated, "cancelled")
            : new InteractionStore.Interaction.Pending(info);
    }

    private static void assertAllStoreRoundTrips(SQLiteDataSource dataSource,
                                                  String sessionId) throws Exception {
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        memory.addMessages(sessionId, List.of(new TurnMessage.User("message", Map.of())));
        assertEquals(List.of("message"), memory.getMessages(sessionId).stream()
            .map(TurnMessage::text).toList());

        SqliteSessionStore sessions = new SqliteSessionStore(dataSource);
        assertNotNull(sessions.findById(sessionId));
        sessions.updateModel(sessionId, "round-trip-model");
        assertEquals("round-trip-model", sessions.findById(sessionId).model());

        MessageQueueStore.QueuedRequest request = new MessageQueueStore.QueuedRequest(
            UUID.randomUUID().toString(), sessionId, "queued", null, null, Map.of(),
            QueueReason.EXPLICIT, TIME);
        SqliteMessageQueueStore queue = new SqliteMessageQueueStore(dataSource);
        queue.insert(request);
        assertEquals(List.of(request), queue.list(sessionId));

        InteractionStore.Interaction saved = interaction(
            sessionId, new ExecutionOrigin.Direct(), List.of("question?"), false,
            null, Map.of(), Map.of());
        SqliteInteractionStore interactions = new SqliteInteractionStore(dataSource);
        interactions.save(saved);
        assertEquals(saved, interactions.find(sessionId, saved.interactionId()).orElseThrow());

        new SqliteAuditWriter(dataSource).recordUserMessage(
            sessionId, new TurnMessage.User("audit", Map.of()));
        assertTrue(count(dataSource, "magenta_core_audit_log") > 0);
    }

    private static void createIdentity(SQLiteDataSource dataSource,
                                       SchemaIdentity identity) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE magenta_core_schema_identity "
                + "(schema_id TEXT PRIMARY KEY, schema_version INTEGER NOT NULL, "
                + "schema_hash TEXT NOT NULL, initialized_at TEXT)");
            try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO magenta_core_schema_identity "
                    + "(schema_id, schema_version, schema_hash) VALUES (?, ?, ?)")) {
                insert.setString(1, identity.schemaId());
                insert.setInt(2, identity.version());
                insert.setString(3, identity.hash());
                insert.executeUpdate();
            }
            statement.execute("CREATE TABLE magenta_core_probe (value TEXT)");
            statement.execute("INSERT INTO magenta_core_probe(value) VALUES ('preserve me')");
            statement.execute("CREATE TABLE turnloop_identity_probe (value TEXT)");
            statement.execute("INSERT INTO turnloop_identity_probe(value) VALUES ('legacy')");
        }
    }

    private static void mutateSqliteMaster(SQLiteDataSource dataSource,
                                           String objectName,
                                           String expected,
                                           String replacement) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA writable_schema = ON");
            try (PreparedStatement update = connection.prepareStatement(
                "UPDATE sqlite_master SET sql = replace(sql, ?, ?) WHERE name = ?")) {
                update.setString(1, expected);
                update.setString(2, replacement);
                update.setString(3, objectName);
                assertEquals(1, update.executeUpdate());
            }
            statement.execute("PRAGMA schema_version = 2");
            statement.execute("PRAGMA writable_schema = OFF");
        }
    }

    private static String checkedInSchemaHash() throws Exception {
        try (InputStream input = FreshSchemaPersistenceTest.class
            .getResourceAsStream("/schema.sql")) {
            assertNotNull(input);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        }
    }

    private static DatabaseSnapshot snapshot(SQLiteDataSource dataSource) throws SQLException {
        Map<String, String> objects = new LinkedHashMap<>();
        Map<String, List<List<String>>> rows = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                 SELECT type, name, sql
                 FROM sqlite_master
                 WHERE name NOT LIKE 'sqlite_%'
                 ORDER BY type, name
                 """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String type = resultSet.getString("type");
                    String name = resultSet.getString("name");
                    objects.put(type + ":" + name, resultSet.getString("sql"));
                    if (!"table".equals(type)) {
                        continue;
                    }
                    List<List<String>> tableRows = new ArrayList<>();
                    try (Statement rowsStatement = connection.createStatement();
                         ResultSet tableResult = rowsStatement.executeQuery(
                             "SELECT * FROM " + quoteIdentifier(name))) {
                        var metadata = tableResult.getMetaData();
                        while (tableResult.next()) {
                            List<String> row = new ArrayList<>();
                            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                                Object value = tableResult.getObject(column);
                                row.add(value == null ? null : value.toString());
                            }
                            tableRows.add(row);
                        }
                    }
                    rows.put(name, tableRows);
                }
            }
        }
        return new DatabaseSnapshot(
            Collections.unmodifiableMap(objects), Collections.unmodifiableMap(rows));
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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

    private record SchemaIdentity(String schemaId, int version, String hash) {
    }

    private record DatabaseSnapshot(
        Map<String, String> objects,
        Map<String, List<List<String>>> rows) {
    }
}
