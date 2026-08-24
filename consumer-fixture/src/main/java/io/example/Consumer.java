package io.example;

import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

/** Executable consumer smoke fixture with no Spring Boot or application context. */
public final class Consumer {
    private Consumer() { }

    public static TurnLoop create(Path dataRoot) {
        return builder().dataRoot(dataRoot).build();
    }

    public static TurnLoop create(DataSource dataSource) {
        return builder().dataSource(dataSource).build();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("magenta-consumer-");
        try (TurnLoop fileBacked = create(root)) {
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + root.resolve("caller.db"));
            try (TurnLoop callerOwned = create(dataSource)) {
                System.out.println("consumer-ok");
            }
        }
    }

    private static TurnLoopBuilder builder() {
        return TurnLoopBuilder.create()
            .model("local", new ModelConfig(
                "local", "llama3.2", "http://localhost:11434", null,
                EndpointType.OLLAMA, 32_000, 0));
    }
}
