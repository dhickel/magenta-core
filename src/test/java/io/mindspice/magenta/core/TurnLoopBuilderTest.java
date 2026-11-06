package io.mindspice.magenta.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnLoopBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void requiresExactlyOneStorageSource() {
        ModelConfig model = model("first");

        ConfigurationException missing = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create().model("first", model).build());
        assertEquals("storage_missing", missing.details().get("error"));

        SQLiteDataSource dataSource = sqliteDataSource(tempDir.resolve("conflict.db"));
        ConfigurationException conflict = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", model)
                .dataRoot(tempDir)
                .dataSource(dataSource)
                .build());
        assertEquals("storage_conflict", conflict.details().get("error"));
    }

    @Test
    void dataRootCreatesDurableDatabaseAndFrameworkSchema() throws Exception {
        TurnLoop loop = TurnLoopBuilder.create()
            .model("first", model("first"))
            .dataRoot(tempDir)
            .modelTimeout(Duration.ofSeconds(7))
            .build();
        loop.close();

        Path database = tempDir.resolve("magenta-core.db");
        assertTrue(Files.isRegularFile(database));
        try (Connection connection = sqliteDataSource(database).getConnection();
             ResultSet result = connection.createStatement().executeQuery(
                 "SELECT name FROM sqlite_master WHERE type = 'table'")) {
            java.util.Set<String> names = new java.util.HashSet<>();
            while (result.next()) {
                names.add(result.getString(1));
            }
            assertTrue(names.contains("magenta_core_schema_version"));
            assertTrue(names.contains("magenta_core_conversation_memory"));
            assertTrue(names.contains("magenta_core_session_metadata"));
            assertTrue(names.contains("magenta_core_queued_requests"));
            assertTrue(names.contains("magenta_core_interactions"));
        }
    }

    @Test
    void rejectsADataRootThatIsAFile() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "not a directory");
        ConfigurationException exception = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", model("first"))
                .dataRoot(file)
                .build());
        assertEquals("invalid_data_root", exception.details().get("error"));
    }

    @Test
    void explicitDataSourceIsAcceptedWithoutADataRoot() {
        TurnLoop loop = TurnLoopBuilder.create()
            .model("first", openAiModel("first"))
            .dataSource(sqliteDataSource(tempDir.resolve("explicit.db")))
            .build();
        loop.close();
    }

    @Test
    void closeStopsOnlyTheDefaultOwnedTurnGateScanner() throws Exception {
        TurnLoop ownedLoop = TurnLoopBuilder.create()
            .model("first", model("first"))
            .dataSource(sqliteDataSource(tempDir.resolve("owned-gate.db")))
            .build();
        SerialTurnGate ownedGate = serialGateOf(ownedLoop);
        ownedLoop.close();
        assertTrue(scannerOf(ownedGate).isShutdown());

        SerialTurnGate suppliedGate = new SerialTurnGate(Duration.ofMinutes(1));
        try {
            TurnLoop suppliedLoop = TurnLoopBuilder.create()
                .model("first", model("first"))
                .dataSource(sqliteDataSource(tempDir.resolve("supplied-gate.db")))
                .turnGate(suppliedGate)
                .build();
            suppliedLoop.close();
            assertFalse(scannerOf(suppliedGate).isShutdown());
        } finally {
            suppliedGate.close();
        }
    }

    @Test
    void compactionTriggerDefaultsAndClamps() throws Exception {
        TurnLoopBuilder builder = TurnLoopBuilder.create();
        assertEquals(75, compactionTriggerPercentOf(builder));
        assertEquals(1, compactionTriggerPercentOf(builder.compactionTriggerPercent(0)));
        assertEquals(99, compactionTriggerPercentOf(builder.compactionTriggerPercent(100)));
        assertEquals(50, compactionTriggerPercentOf(builder.compactionTriggerPercent(50)));
    }

    @Test
    void toolLoopLimitsHaveConservativeDefaultsValidateAndWireIntoTheEngine()
            throws Exception {
        TurnLoopBuilder defaults = TurnLoopBuilder.create();
        assertEquals(5, intField(defaults, "repeatedErroredToolCallLimit"));
        assertEquals(10, intField(defaults, "maxToolIterations"));

        ConfigurationException repeated = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create().repeatedErroredToolCallLimit(0));
        assertEquals("invalid_repeated_errored_tool_call_limit",
            repeated.details().get("error"));
        ConfigurationException iterations = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create().maxToolIterations(0));
        assertEquals("invalid_max_tool_iterations", iterations.details().get("error"));

        TurnLoop loop = TurnLoopBuilder.create()
            .model("first", model("first"))
            .dataSource(sqliteDataSource(tempDir.resolve("tool-loop-limits.db")))
            .repeatedErroredToolCallLimit(8)
            .maxToolIterations(100)
            .build();
        try {
            DefaultTurnEngine engine = engineOf(loop);
            assertEquals(100, intField(engine, "maxToolIterations"));
            ToolLoopGuard guard = (ToolLoopGuard) field(engine, "toolLoopGuard");
            assertEquals(8, guard.repeatedErroredToolCallLimit());
        } finally {
            loop.close();
        }
    }

    @Test
    void validatesAliasesAndModelConfiguration() {
        ModelConfig valid = model("first");

        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create().model(" ", valid));
        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", valid)
                .model("first", valid));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama(
                "first", "", "http://localhost:11434", null, 4096, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama(
                "first", "model", "not-a-url", null, 4096, 0));
    }

    @Test
    void rejectsUnsupportedDeepSeekRequestThinkingAtConstruction() {
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.DeepSeek(
                "deepseek", "remote-model", "http://localhost:11434",
                "secret", 4096, 3));
    }

    private static ModelConfig model(String alias) {
        return ModelConfig.ollama()
            .alias(alias)
            .remoteModelName("remote-model")
            .remoteEndpoint("http://localhost:11434")
            .contextLength(4096)
            .thinkLevel(0)
            .build();
    }

    private static ModelConfig openAiModel(String alias) {
        return ModelConfig.openAiCompatible()
            .alias(alias)
            .remoteModelName("remote-model")
            .remoteEndpoint("http://localhost:11434")
            .contextLength(4096)
            .thinkLevel(0)
            .build();
    }

    private static int compactionTriggerPercentOf(TurnLoopBuilder builder) throws Exception {
        return intField(builder, "compactionTriggerPercent");
    }

    private static DefaultTurnEngine engineOf(TurnLoop loop) throws Exception {
        return (DefaultTurnEngine) field(loop, "engine");
    }

    private static int intField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static SerialTurnGate serialGateOf(TurnLoop loop) throws Exception {
        var field = DefaultTurnLoop.class.getDeclaredField("gate");
        field.setAccessible(true);
        return (SerialTurnGate) field.get(loop);
    }

    private static ScheduledExecutorService scannerOf(SerialTurnGate gate) throws Exception {
        var field = SerialTurnGate.class.getDeclaredField("timeoutScanner");
        field.setAccessible(true);
        return (ScheduledExecutorService) field.get(gate);
    }

    private static SQLiteDataSource sqliteDataSource(Path database) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        return dataSource;
    }
}
