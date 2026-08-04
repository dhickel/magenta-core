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
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/support.md"), "You are support.");
        Files.writeString(tempDir.resolve("prompts/planner.md"), "You are a planner.");

        Path yaml = tempDir.resolve("ai-config.yaml");
        Files.writeString(yaml, """
            defaultAgent: support
            summaryModel: local-fast
            planningModel: remote-large
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
            agents:
              support:
                model: local-fast
                systemPrompt: prompts/support.md
                approvedTools: []
              planner:
                model: remote-large
                systemPrompt: prompts/planner.md
                approvedTools:
                  - web_search
                  - sql_query
                allowedShellCommands:
                  - mv
                  - rm
            """);

        AiConfig config = ExternalAiConfigLoader.load(yaml);

        assertNotNull(config);
        assertNotNull(config.models());
        assertNotNull(config.agents());
        assertEquals("support", config.defaultAgent());
        assertEquals("local-fast", config.summaryModel());
        assertEquals("local-fast", config.resolvedSummaryModelKey());
        assertEquals("remote-large", config.resolvedPlanningModelKey());
        assertEquals(10, config.resolvedContextBufferPercent());
        assertNotNull(config.webSearch());
        assertTrue(config.webSearch().isEnabled());
        assertEquals("searxng", config.webSearch().provider());
        assertEquals("http://localhost:8080", config.webSearch().baseUrl());
        assertEquals(2, config.models().size());
        assertEquals(2, config.agents().size());

        ModelConfig localFast = config.models().get("local-fast");
        assertNotNull(localFast);
        assertEquals("qwen3:8b", localFast.remoteModelName());
        assertEquals("http://localhost:11434", localFast.remoteEndpoint());
        assertEquals(EndpointType.OLLAMA, localFast.endpointType());
        assertEquals(8192, localFast.contextLength());

        AgentConfig support = config.agents().get("support");
        assertNotNull(support);
        assertEquals("local-fast", support.model());
        assertEquals("You are support.", support.systemPrompt());
        assertNotNull(support.approvedTools());
        assertTrue(support.approvedTools().isEmpty());
        assertEquals(
            java.util.List.of("mv", "rm"),
            config.agents().get("planner").allowedShellCommands()
        );
    }

    @Test
    void loadsExternalJsonIntoConfigRecords(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/system.md"), "You are Magenta, a practical system administration assistant.");

        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "local-qwen",
              "planningModel": "local-qwen",
              "unsafeAllowWildcardShellCommands": true,
              "contextBufferPercent": 10,
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3.6:35b",
                  "remoteEndpoint": "http://192.168.1.112:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 32768
                }
              },
              "agents": {
                "magenta": {
                  "model": "local-qwen",
                  "systemPrompt": "prompts/system.md",
                  "approvedTools": ["file_read", "web_fetch"],
                  "allowedShellCommands": ["printf"]
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);

        assertNotNull(config);
        assertNotNull(config.models());
        assertNotNull(config.agents());
        assertEquals("magenta", config.defaultAgent());
        assertEquals("local-qwen", config.summaryModel());
        assertEquals("local-qwen", config.resolvedSummaryModelKey());
        assertEquals("local-qwen", config.resolvedPlanningModelKey());
        assertEquals(1, config.models().size());
        assertEquals(1, config.agents().size());
        assertEquals("local-qwen", config.agents().get("magenta").model());
        assertEquals(
            "You are Magenta, a practical system administration assistant.",
            config.agents().get("magenta").systemPrompt()
        );
        assertTrue(config.unsafeAllowWildcardShellCommandsEnabled());
        assertEquals(java.util.List.of("file_read", "web_fetch"), config.agents().get("magenta").approvedTools());
        assertEquals(java.util.List.of("printf"), config.agents().get("magenta").allowedShellCommands());
        assertEquals(EndpointType.OLLAMA, config.models().get("local-qwen").endpointType());
    }

    @Test
    void acceptsLegacySummeryModelAsSummaryModelFallback(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/system.md"), "Prompt.");

        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summeryModel": "local-qwen",
              "planningModel": "local-qwen",
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
                  "approvedTools": []
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);

        assertEquals("local-qwen", config.summeryModel());
        assertEquals("local-qwen", config.resolvedSummaryModelKey());
        assertEquals("local-qwen", config.resolvedCompactionModelKey());
    }

    @Test
    void resolvesPromptPathRelativeToConfigFileDirectory(@TempDir Path tempDir) throws IOException {
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir.resolve("prompts"));
        Files.writeString(configDir.resolve("prompts/system.md"), "Prompt beside config.");

        Path json = configDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "local-qwen",
              "planningModel": "local-qwen",
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
                  "approvedTools": []
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);

        assertEquals("Prompt beside config.", config.agents().get("magenta").systemPrompt());
    }

    @Test
    void failsWhenPromptFileIsMissing(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "local-qwen",
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
                  "systemPrompt": "prompts/missing.md",
                  "approvedTools": []
                }
              }
            }
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExternalAiConfigLoader.load(json)
        );
        assertTrue(exception.getMessage().contains("systemPrompt file does not exist"));
    }

    @Test
    void failsWhenPromptPathIsBlank(@TempDir Path tempDir) throws IOException {
        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "local-qwen",
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
                  "systemPrompt": " ",
                  "approvedTools": []
                }
              }
            }
            """);

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ExternalAiConfigLoader.load(json)
        );
        assertTrue(exception.getMessage().contains("must configure a systemPrompt file path"));
    }

    @Test
    void failsWhenSummaryModelIsMissing(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/system.md"), "Prompt.");

        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
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
                  "approvedTools": []
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
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/system.md"), "Prompt.");

        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "missing-model",
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
                  "approvedTools": []
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
    void loadsThinkLevelConfig(@TempDir Path tempDir) throws IOException {
        Files.createDirectories(tempDir.resolve("prompts"));
        Files.writeString(tempDir.resolve("prompts/system.md"), "Prompt.");

        Path json = tempDir.resolve("ai-config.json");
        Files.writeString(json, """
            {
              "defaultAgent": "magenta",
              "summaryModel": "local-qwen",
              "planningModel": "local-qwen",
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192,
                  "thinkLevel": 4
                }
              },
              "agents": {
                "magenta": {
                  "model": "local-qwen",
                  "systemPrompt": "prompts/system.md",
                  "approvedTools": []
                }
              }
            }
            """);

        AiConfig config = ExternalAiConfigLoader.load(json);
        ModelConfig model = config.models().get("local-qwen");
        assertEquals(4, model.thinkLevel());
    }
}
