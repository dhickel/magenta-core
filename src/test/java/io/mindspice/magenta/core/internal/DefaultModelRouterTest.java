package io.mindspice.magenta.core;

import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import io.mindspice.magenta.core.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultModelRouterTest {

    @Test
    void providerOptionsAreTypedAndProviderNeutralToolsAreAdapted() {
        Map<String, ModelConfig> models = new LinkedHashMap<>();
        models.put("ollama", ollama("ollama", 2));
        models.put("openai", openAi("openai", "key", 3));
        models.put("deepseek", deepSeek("deepseek", "key", 0));

        DefaultModelRouter router = new DefaultModelRouter(models, Duration.ofSeconds(11));
        ToolCallback tool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("echo", "Echo input", "{\"type\":\"object\"}");
            }

            @Override
            public ToolResult call(String toolInput) {
                return new ToolResult.Success(toolInput);
            }
        };

        OllamaChatOptions ollama = (OllamaChatOptions) router.toolCallingOptions(
            "ollama", List.of(tool));
        assertEquals("remote-ollama", ollama.getModel());
        assertEquals(4096, ollama.getNumCtx());
        assertEquals(ThinkOption.ThinkLevel.MEDIUM, ollama.getThinkOption());
        assertEquals("echo", ollama.getToolCallbacks().getFirst().getToolDefinition().name());

        OpenAiChatOptions openAi = (OpenAiChatOptions) router.toolCallingOptions(
            "openai", List.of(tool));
        assertEquals("http://localhost:8080/v1", openAi.getBaseUrl());
        assertEquals("key", openAi.getApiKey());
        assertEquals("remote-openai", openAi.getModel());
        assertEquals(Duration.ofSeconds(11), openAi.getTimeout());
        assertEquals("high", openAi.getReasoningEffort());
        assertEquals("echo", openAi.getToolCallbacks().getFirst().getToolDefinition().name());

        DeepSeekChatOptions deepSeek = (DeepSeekChatOptions) router.toolCallingOptions(
            "deepseek", List.of(tool));
        assertEquals("remote-deepseek", deepSeek.getModel());
        assertEquals("echo", deepSeek.getToolCallbacks().getFirst().getToolDefinition().name());
        assertEquals(4096, router.contextLength("deepseek"));
        assertNotNull(router.springAiChatModel("ollama"));
        assertNotNull(router.springAiChatModel("deepseek"));
        router.closeChatModels();
    }

    @Test
    void selectionUsesRequestThenSessionThenRegistrationOrderAndCopiesTheRegistry() {
        LinkedHashMap<String, ModelConfig> models = new LinkedHashMap<>();
        models.put("first", ollama("first", 0));
        models.put("second", ollama("second", 0));

        DefaultModelRouter router = new DefaultModelRouter(models);
        models.put("third", ollama("third", 0));

        assertEquals("second", router.selectModelAlias(null, "second"));
        assertEquals("first", router.selectModelAlias(null, "missing"));
        assertEquals("second", router.selectModelAlias("second", "first"));
        assertEquals("first", router.selectModelAlias(null, null));
        assertEquals(4096, router.contextLength("first"));
    }

    @Test
    void ownedOpenAiClientCanBeClosedRepeatedly() {
        DefaultModelRouter router = new DefaultModelRouter(
            Map.of("openai", openAi("openai", "key", 0)));
        assertNotNull(router.springAiChatModel("openai"));
        assertTrue(router.supportsStreaming("openai"));
        router.closeChatModels();
        router.closeChatModels();
    }

    private static ModelConfig.Ollama ollama(String alias, int thinkLevel) {
        return new ModelConfig.Ollama(alias, "remote-" + alias,
            "http://localhost:11434", null, 4096, thinkLevel);
    }

    private static ModelConfig.OpenAiCompatible openAi(
        String alias, String apiKey, int thinkLevel) {
        return new ModelConfig.OpenAiCompatible(alias, "remote-" + alias,
            "http://localhost:8080/v1", apiKey, 4096, thinkLevel, null);
    }

    private static ModelConfig.DeepSeek deepSeek(
        String alias, String apiKey, int thinkLevel) {
        return new ModelConfig.DeepSeek(alias, "remote-" + alias,
            "http://localhost:8080/v1", apiKey, 4096, thinkLevel);
    }
}
