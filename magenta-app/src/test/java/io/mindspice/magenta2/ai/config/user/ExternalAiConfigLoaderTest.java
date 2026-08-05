package io.mindspice.magenta2.ai.config.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalAiConfigLoaderTest {

    @Test
    void loadsExternalYamlIntoConfigRecords(@TempDir Path tempDir) throws IOException {
        Path yaml = tempDir.resolve("ai-config.yaml");
        Files.writeString(yaml, """
            defaultModel: local-fast
            summaryModel: local-fast
            contextBufferPercent: 10
            webSearch:
              enabled: true
              provider: searxng
              baseUrl: http://localhost:8080
            models:
              local-fast:
                remoteModelName: qwen3:8b
                remoteEndpoint: http://localhost:11434
                endpointType: OLLAMA
                contextLength: 8192
              remote-large:
                remoteModelName: gpt-4o-mini
                remoteEndpoint: https://api.example.com/v1
                endpointType: OPENAI_COMPATIBLE
                contextLength: 128000
            """);

        AiConfig config = ExternalAiConfigLoader.load(yaml);

        assertNotNull(config);
        assertNotNull(config.models());
        assertEquals("local-fast", config.defaultModel());
        assertEquals("local-fast", config.resolvedDefaultModelKey());
        assertEquals("local-fast", config.summaryModel());
        assertEquals("local-fast", config.resolvedSummaryModelKey());
        assertEquals(10, config.resolvedContextBufferPercent());
        assertNotNull(config.webSearch());
        assertTrue(config.webSearch().isEnabled());
        assertEquals("searxng", config.webSearch().provider());
        assertEquals("http://localhost:8080", config.webSearch().baseUrl());
        assertEquals(2, config.models().size());
    }

    @Test
    void loadsExternalJsonIntoConfigRecords(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultModel": "local-qwen",
              "summaryModel": "local-qwen",
              "contextBufferPercent": 10,
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3.6:35b",
                  "remoteEndpoint": "http://192.168.1.112:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 32768
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);

        assertNotNull(config);
        assertNotNull(config.models());
        assertEquals("local-qwen", config.defaultModel());
        assertEquals("local-qwen", config.resolvedDefaultModelKey());
        assertEquals("local-qwen", config.summaryModel());
        assertEquals("local-qwen", config.resolvedSummaryModelKey());
        assertEquals(1, config.models().size());
        assertEquals(EndpointType.OLLAMA, config.models().get("local-qwen").endpointType());
    }

    @Test
    void acceptsLegacySummeryModelAsSummaryModelFallback(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "summeryModel": "local-qwen",
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);

        assertEquals("local-qwen", config.summeryModel());
        assertEquals("local-qwen", config.resolvedSummaryModelKey());
    }

    @Test
    void failsWhenSummaryModelIsMissing(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              }
            }
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExternalAiConfigLoader.load(json)
        );
        assertTrue(exception.getMessage().contains("must define summaryModel"));
    }

    @Test
    void failsWhenSummaryModelReferencesMissingModel(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "summaryModel": "missing-model",
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              }
            }
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExternalAiConfigLoader.load(json)
        );
        assertTrue(exception.getMessage().contains("summaryModel references missing model"));
    }

    @Test
    void failsWhenDefaultModelReferencesMissingModel(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultModel": "missing-model",
              "summaryModel": "local-qwen",
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              }
            }
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExternalAiConfigLoader.load(json)
        );
        assertTrue(exception.getMessage().contains("defaultModel references missing model"));
    }

    @Test
    void loadsThinkLevelConfig(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "summaryModel": "local-qwen",
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192,
                  "thinkLevel": 4
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);
        ModelConfig model = config.models().get("local-qwen");
        assertEquals(4, model.thinkLevel());
    }
}
