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
        ModelConfig model = model("first", EndpointType.OLLAMA);

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
            .model("first", model("first", EndpointType.OLLAMA))
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
            assertTrue(names.contains("magenta_core_pending_messages"));
        }
    }

    @Test
    void rejectsADataRootThatIsAFile() throws Exception {
        Path file = tempDir.resolve("not-a-directory");
        Files.writeString(file, "not a directory");
        ConfigurationException exception = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", model("first", EndpointType.OLLAMA))
                .dataRoot(file)
                .build());
        assertEquals("invalid_data_root", exception.details().get("error"));
    }

    @Test
    void explicitDataSourceIsAcceptedWithoutADataRoot() {
        TurnLoop loop = TurnLoopBuilder.create()
            .model("first", model("first", EndpointType.OPENAI_COMPATIBLE))
            .dataSource(sqliteDataSource(tempDir.resolve("explicit.db")))
            .build();
        loop.close();
    }

    @Test
    void closeStopsOnlyTheDefaultOwnedTurnGateScanner() throws Exception {
        TurnLoop ownedLoop = TurnLoopBuilder.create()
            .model("first", model("first", EndpointType.OLLAMA))
            .dataSource(sqliteDataSource(tempDir.resolve("owned-gate.db")))
            .build();
        SerialTurnGate ownedGate = serialGateOf(ownedLoop);
        ownedLoop.close();
        assertTrue(scannerOf(ownedGate).isShutdown());

        SerialTurnGate suppliedGate = new SerialTurnGate(Duration.ofMinutes(1));
        try {
            TurnLoop suppliedLoop = TurnLoopBuilder.create()
                .model("first", model("first", EndpointType.OLLAMA))
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
    void validatesAliasesAndModelConfiguration() {
        ModelConfig valid = model("first", EndpointType.OLLAMA);

        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create().model(" ", valid));
        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", valid)
                .model("first", valid));
        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", new ModelConfig("first", "", "http://localhost:11434",
                    null, EndpointType.OLLAMA, 4096, 0))
                .dataRoot(tempDir)
                .build());
        assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("first", new ModelConfig("first", "model", "not-a-url",
                    null, EndpointType.OLLAMA, 4096, 0))
                .dataRoot(tempDir)
                .build());
    }

    @Test
    void rejectsUnsupportedDeepSeekRequestThinkingInsteadOfDroppingIt() {
        ConfigurationException exception = assertThrows(ConfigurationException.class,
            () -> TurnLoopBuilder.create()
                .model("deepseek", model("deepseek", EndpointType.DEEPSEEK, 3))
                .dataRoot(tempDir)
                .build());
        assertEquals("unsupported_provider_option", exception.details().get("error"));
    }

    private static ModelConfig model(String alias, EndpointType endpointType) {
        return model(alias, endpointType, 0);
    }

    private static ModelConfig model(String alias, EndpointType endpointType, int thinkLevel) {
        return new ModelConfig(alias, "remote-model", "http://localhost:11434",
            endpointType == EndpointType.DEEPSEEK ? "secret" : null,
            endpointType, 4096, thinkLevel);
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
