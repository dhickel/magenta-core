package io.mindspice.magenta2.ai.config.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiUserConfigConfigurationTest {

    @Test
    void missingDataRootDefaultsUnderMagentaRoot(@TempDir Path tempDir) throws IOException {
        Path magentaRoot = tempDir.resolve("magenta-root");
        Path config = writeConfig(tempDir.resolve("config"), null);

        AiConfig aiConfig = load(config, magentaRoot);

        assertEquals(magentaRoot.resolve("root").normalize(), aiConfig.dataRoot());
        assertEquals("Prompt beside config.", aiConfig.agents().get("magenta").systemPrompt());
    }

    @Test
    void relativeDataRootResolvesUnderMagentaRootNotConfigDirectory(@TempDir Path tempDir) throws IOException {
        Path magentaRoot = tempDir.resolve("magenta-root");
        Path configDir = tempDir.resolve("config");
        Path config = writeConfig(configDir, "\"dataRoot\": \"custom-data\",");

        AiConfig aiConfig = load(config, magentaRoot);

        assertEquals(magentaRoot.resolve("custom-data").normalize(), aiConfig.dataRoot());
        assertFalse(aiConfig.dataRoot().equals(configDir.resolve("custom-data").normalize()));
    }

    @Test
    void absoluteDataRootRemainsSupported(@TempDir Path tempDir) throws IOException {
        Path magentaRoot = tempDir.resolve("magenta-root");
        Path externalRoot = tempDir.resolve("external-data").toAbsolutePath().normalize();
        Path config = writeConfig(
            tempDir.resolve("config"),
            "\"dataRoot\": \"" + escapeJson(externalRoot.toString()) + "\","
        );

        AiConfig aiConfig = load(config, magentaRoot);

        assertEquals(externalRoot, aiConfig.dataRoot());
    }

    private AiConfig load(Path config, Path magentaRoot) throws IOException {
        return new AiUserConfigConfiguration().aiConfig(
            config.toString(),
            new MagentaRootProperties(magentaRoot)
        );
    }

    private static Path writeConfig(Path configDir, String dataRootLine) throws IOException {
        Files.createDirectories(configDir.resolve("prompts"));
        Files.writeString(configDir.resolve("prompts/system.md"), "Prompt beside config.");
        Path config = configDir.resolve("ai-config.json");
        Files.writeString(config, """
            {
              "defaultAgent": "magenta",
              "defaultModel": "local-qwen",
              "summaryModel": "local-qwen",
              "planningModel": "local-qwen",
              "compactionModel": "local-qwen",
              "contextBufferPercent": 33,
              "unsafeAllowWildcardShellCommands": false,
              %s
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              },
              "agents": {
                "magenta": {
                  "model": "local-qwen",
                  "systemPrompt": "prompts/system.md",
                  "approvedTools": [],
                  "allowedShellCommands": []
                }
              }
            }
            """.formatted(dataRootLine == null ? "" : dataRootLine));
        return config;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
