package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelConfigProtocolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void rootIsSealedToTheProviderVariants() {
        assertEquals(Set.of(
            ModelConfig.Ollama.class,
            ModelConfig.OpenAiCompatible.class,
            ModelConfig.DeepSeek.class,
            ModelConfig.Responses.class),
            Set.of(ModelConfig.class.getPermittedSubclasses()));
    }

    @Test
    void everyVariantUsesStableJsonTagsAndRoundTripsApiKeys() throws Exception {
        List<ModelConfig> configs = List.of(
            new ModelConfig.Ollama(
                "local", "llama", "http://localhost:11434", null, 4096, 4),
            new ModelConfig.OpenAiCompatible(
                "compatible", "gpt", "https://example.test/v1", "open-secret", null, 2, "max"),
            new ModelConfig.DeepSeek(
                "deep", "deepseek-chat", "https://api.deepseek.test", "deep-secret", 8192, 0),
            new ModelConfig.Responses(
                "responses", "deepseek-v4-flash", "https://api.deepseek.test", "resp-secret",
                1_048_576, 4, "max"));
        List<String> tags = List.of("ollama", "openai_compatible", "deepseek", "responses");

        for (int index = 0; index < configs.size(); index++) {
            ModelConfig config = configs.get(index);
            String json = objectMapper.writeValueAsString(config);
            assertTrue(json.contains("\"type\":\"" + tags.get(index) + "\""), json);
            if (config.apiKey() != null) {
                assertTrue(json.contains(config.apiKey()), json);
            }
            assertEquals(config, objectMapper.readValue(json, ModelConfig.class));
        }
    }

    @Test
    void constructorsEnforceSharedValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama(" ", "model", "http://localhost", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias\u0000", "model", "http://localhost",
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "", "http://localhost", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model\n", "http://localhost",
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model", "relative", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model", "ftp://localhost", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model", "http://localhost", null, 0, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model", "http://localhost", null, null, 5));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama("alias", "model", "http://localhost",
                "ollama-secret", null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.OpenAiCompatible("alias", "model", "http://localhost",
                " ", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.OpenAiCompatible("alias", "model", "http://localhost",
                null, null, null, "maximum"));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.OpenAiCompatible("alias", "model", "http://localhost",
                null, null, null, "max\u0000"));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.DeepSeek("alias", "model", "http://localhost",
                null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.DeepSeek("alias", "model", "http://localhost",
                "deep-secret", null, 1));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Responses("alias", "model", "http://localhost",
                null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Responses("alias", "model", "http://localhost",
                "secret", null, null, "turbo"));
    }

    @Test
    void reasoningEffortIsNormalizedToLowercaseAndOverridesThinkLevel() {
        ModelConfig.OpenAiCompatible normalized =
            new ModelConfig.OpenAiCompatible("alias", "model", "http://localhost",
                null, null, null, " MAX ");
        assertEquals("max", normalized.reasoningEffort());
        assertEquals(0, normalized.effectiveThinkLevel());

        ModelConfig.OpenAiCompatible unset =
            new ModelConfig.OpenAiCompatible("alias", "model", "http://localhost",
                null, 2, null, null);
        assertEquals(null, unset.reasoningEffort());

        ModelConfig.Responses responses =
            new ModelConfig.Responses("alias", "model", "http://localhost",
                "secret", null, 4, "max");
        assertEquals("max", responses.reasoningEffort());
        assertEquals(4, responses.effectiveThinkLevel());
    }

    @Test
    void effectiveThinkLevelRetainsTheZeroDefaultAndValidatedLevels() {
        assertEquals(0, new ModelConfig.Ollama(
            "alias", "model", "http://localhost", null, null, null)
            .effectiveThinkLevel());
        assertEquals(4, new ModelConfig.Ollama(
            "alias", "model", "http://localhost", null, null, 4)
            .effectiveThinkLevel());
        assertEquals(0, new ModelConfig.DeepSeek(
            "alias", "model", "http://localhost", "secret", null, null)
            .effectiveThinkLevel());
    }

    @Test
    void diagnosticsAndValidationMessagesRedactApiKeysWhileJsonDoesNot() throws Exception {
        String secret = "config-secret-123";
        ModelConfig.OpenAiCompatible config = new ModelConfig.OpenAiCompatible(
            "alias", "model", "http://localhost", secret, null, null, null);

        assertFalse(config.toString().contains(secret));
        assertTrue(objectMapper.writeValueAsString(config).contains(secret));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> new ModelConfig.Ollama(
                "alias", "model", "http://localhost", secret, null, null));
        assertFalse(failure.getMessage().contains(secret));
    }
}
