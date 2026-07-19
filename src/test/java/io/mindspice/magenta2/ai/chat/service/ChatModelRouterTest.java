package io.mindspice.magenta2.ai.chat.service;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import io.micrometer.observation.ObservationRegistry;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatModelRouterTest {

    @Test
    void resolvesModelByConfigKeyOrRemoteModelName() {
        ChatModelRouter router = new ChatModelRouter(aiConfig(), null, ObservationRegistry.NOOP);

        assertThat(router.remoteModelName("local-qwen")).isEqualTo("qwen3");
        assertThat(router.remoteModelName("qwen3")).isEqualTo("qwen3");
    }

    @Test
    void rejectsUnknownModel() {
        ChatModelRouter router = new ChatModelRouter(aiConfig(), null, ObservationRegistry.NOOP);

        assertThatThrownBy(() -> router.remoteModelName("missing"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown configured model");
    }

    @Test
    void buildsOpenAiCompatibleModel() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("openai", new ModelConfig("gpt-4o-mini", "https://api.example.test/v1", EndpointType.OPENAI_COMPATIBLE, 128000, null, "test-key")),
            Map.of("magenta", new AgentConfig("openai", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);

        assertThat(router.remoteModelName("openai")).isEqualTo("gpt-4o-mini");
        assertThat(router.chatModel("openai")).isNotNull();
    }

    @Test
    void buildsDeepSeekModel() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("ds", new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, "sk-test")),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);

        assertThat(router.remoteModelName("ds")).isEqualTo("deepseek-v4-pro");
        assertThat(router.chatModel("ds")).isInstanceOf(DeepSeekChatModel.class);
    }

    @Test
    void officialDeepSeekModelReplaysReasoningContent() throws Exception {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("ds", new ModelConfig("deepseek-reasoner", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, "sk-test")),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        DeepSeekChatModel model = (DeepSeekChatModel) router.chatModel("ds");
        DeepSeekAssistantMessage assistant = DeepSeekAssistantMessage.builder()
            .content("")
            .reasoningContent("reasoning that DeepSeek requires on the next turn")
            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
            .build();
        Prompt prompt = new Prompt(List.of(assistant), router.chatOptions("ds"));

        Method createRequest = DeepSeekChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
        createRequest.setAccessible(true);
        DeepSeekApi.ChatCompletionRequest request =
            (DeepSeekApi.ChatCompletionRequest) createRequest.invoke(model, prompt, false);

        assertThat(request.messages()).singleElement().satisfies(message -> {
            assertThat(message.reasoningContent()).isEqualTo("reasoning that DeepSeek requires on the next turn");
            assertThat(message.toolCalls()).hasSize(1);
        });
    }

    @Test
    void rejectsDeepSeekModelWithoutApiKey() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("ds", new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, null)),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);

        assertThatThrownBy(() -> router.chatModel("ds"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("apiKey");
    }

    @Test
    void defaultsThinkLevelToZero() {
        ChatModelRouter router = new ChatModelRouter(aiConfig(), null, ObservationRegistry.NOOP);
        var options = router.ollamaOptions("local-qwen");

        assertThat(options.getThinkOption()).isNotNull();
        assertThat(options.getThinkOption()).isInstanceOf(org.springframework.ai.ollama.api.ThinkOption.ThinkBoolean.class);
    }

    @Test
    void appliesThinkLevelToOllama() {
        ModelConfig gemmaModel = new ModelConfig("gemma4", "http://localhost:11434", EndpointType.OLLAMA, 32768, 3, null);
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("local-gemma", gemmaModel),
            Map.of("magenta", new AgentConfig("local-gemma", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = router.ollamaOptions("local-gemma");

        assertThat(options.getThinkOption()).isInstanceOf(org.springframework.ai.ollama.api.ThinkOption.ThinkLevel.class);
        assertThat(options.getThinkOption().toString()).contains("high");
    }

    @Test
    void usesDedicatedDeepSeekOptions() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("ds", new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, "sk-test")),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = (org.springframework.ai.deepseek.DeepSeekChatOptions) router.toolCallingOptions("ds");

        assertThat(options.getModel()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void mapsOpenAiLevelsToReasoningEffort() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("openai", new ModelConfig("gpt-4o", "https://api.example.test/v1", EndpointType.OPENAI_COMPATIBLE, 128000, 2, "sk-test")),
            Map.of("magenta", new AgentConfig("openai", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = (org.springframework.ai.openai.OpenAiChatOptions) router.toolCallingOptions("openai");

        assertThat(options.getReasoningEffort()).isEqualTo("medium");
    }

    @Test
    void openAiLevel4ClampsToHigh() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("openai", new ModelConfig("gpt-4o", "https://api.example.test/v1", EndpointType.OPENAI_COMPATIBLE, 128000, 4, "sk-test")),
            Map.of("magenta", new AgentConfig("openai", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = (org.springframework.ai.openai.OpenAiChatOptions) router.toolCallingOptions("openai");

        assertThat(options.getReasoningEffort()).isEqualTo("high");
    }

    @Test
    void deepSeekThinkingIsSelectedByModelRatherThanReasoningEffort() {
        AiConfig config = new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of("ds", new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 0, "sk-test")),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = (org.springframework.ai.deepseek.DeepSeekChatOptions) router.toolCallingOptions("ds");

        assertThat(options.getModel()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void overflowThinkLevelClampsToMax() {
        // DeepSeek uses model selection rather than reasoning-effort levels.
        AiConfig dsConfig = new AiConfig(
            "magenta", "magenta", 10, null,
            Map.of("ds", new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 100, "sk-test")),
            Map.of("magenta", new AgentConfig("ds", "Prompt.", List.of()))
        );
        ChatModelRouter dsRouter = new ChatModelRouter(dsConfig, null, ObservationRegistry.NOOP);
        var dsOptions = (org.springframework.ai.deepseek.DeepSeekChatOptions) dsRouter.toolCallingOptions("ds");
        assertThat(dsOptions.getModel()).isEqualTo("deepseek-v4-pro");

        // OpenAI overflow (100 → high)
        AiConfig oaiConfig = new AiConfig(
            "magenta", "magenta", 10, null,
            Map.of("openai", new ModelConfig("gpt-4o", "https://api.example.test/v1", EndpointType.OPENAI_COMPATIBLE, 128000, 100, "sk-test")),
            Map.of("magenta", new AgentConfig("openai", "Prompt.", List.of()))
        );
        ChatModelRouter oaiRouter = new ChatModelRouter(oaiConfig, null, ObservationRegistry.NOOP);
        var oaiOptions = (org.springframework.ai.openai.OpenAiChatOptions) oaiRouter.toolCallingOptions("openai");
        assertThat(oaiOptions.getReasoningEffort()).isEqualTo("high");

        // Ollama overflow (100 → thinkHigh)
        ModelConfig ollamaModel = new ModelConfig("gemma4", "http://localhost:11434", EndpointType.OLLAMA, 32768, 100, null);
        AiConfig ollamaConfig = new AiConfig(
            "magenta", "magenta", 10, null,
            Map.of("local-gemma", ollamaModel),
            Map.of("magenta", new AgentConfig("local-gemma", "Prompt.", List.of()))
        );
        ChatModelRouter ollamaRouter = new ChatModelRouter(ollamaConfig, null, ObservationRegistry.NOOP);
        var ollamaOptions = ollamaRouter.ollamaOptions("local-gemma");
        assertThat(ollamaOptions.getThinkOption()).isInstanceOf(org.springframework.ai.ollama.api.ThinkOption.ThinkLevel.class);
        assertThat(ollamaOptions.getThinkOption().toString()).contains("high");
    }

    @Test
    void negativeThinkLevelClampsToZero() {
        ModelConfig ollamaModel = new ModelConfig("gemma4", "http://localhost:11434", EndpointType.OLLAMA, 32768, -5, null);
        AiConfig config = new AiConfig(
            "magenta", "magenta", 10, null,
            Map.of("local-gemma", ollamaModel),
            Map.of("magenta", new AgentConfig("local-gemma", "Prompt.", List.of()))
        );
        ChatModelRouter router = new ChatModelRouter(config, null, ObservationRegistry.NOOP);
        var options = router.ollamaOptions("local-gemma");
        assertThat(options.getThinkOption()).isInstanceOf(org.springframework.ai.ollama.api.ThinkOption.ThinkBoolean.class);
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "magenta",
            "magenta",
            10,
            null,
            Map.of(
                "local-qwen", new ModelConfig("qwen3", "http://localhost:11434", EndpointType.OLLAMA, 8192, null, null),
                "local-gemma", new ModelConfig("gemma4", "http://other-host:11434", EndpointType.OLLAMA, 32768, null, null)
            ),
            Map.of("magenta", new AgentConfig("local-qwen", "Prompt.", List.of()))
        );
    }
}
