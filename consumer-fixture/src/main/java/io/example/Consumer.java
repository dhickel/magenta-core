package io.example;

import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;

import java.nio.file.Path;

/** Minimal consumer compiled without Spring Boot or an application context. */
public final class Consumer {
    private Consumer() { }

    public static TurnLoop create(Path dataRoot) {
        return TurnLoopBuilder.create()
            .dataRoot(dataRoot)
            .model("local", new ModelConfig(
                "local", "llama3.2", "http://localhost:11434", null,
                EndpointType.OLLAMA, 32_000, 0))
            .build();
    }
}
