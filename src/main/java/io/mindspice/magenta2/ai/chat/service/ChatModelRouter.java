package io.mindspice.magenta2.ai.chat.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.micrometer.observation.ObservationRegistry;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class ChatModelRouter {
    private final AiConfig aiConfig;
    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> modelsByKey = new ConcurrentHashMap<>();
    private final Duration openAiCompatibleReadTimeout;
    private final RuntimeSettingsService runtimeSettingsService;

    public ChatModelRouter(
        AiConfig aiConfig,
        ToolCallingManager toolCallingManager,
        ObservationRegistry observationRegistry
    ) {
        this(aiConfig, toolCallingManager, observationRegistry, 360, null);
    }

    @Autowired
    public ChatModelRouter(
        AiConfig aiConfig,
        ToolCallingManager toolCallingManager,
        ObservationRegistry observationRegistry,
        @Value("${magenta.ai.openai-compatible-read-timeout-seconds:360}") long openAiCompatibleReadTimeoutSeconds,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService
    ) {
        this.aiConfig = aiConfig;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry == null ? ObservationRegistry.NOOP : observationRegistry;
        this.openAiCompatibleReadTimeout = Duration.ofSeconds(Math.max(1, openAiCompatibleReadTimeoutSeconds));
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public ChatModel chatModel(String model) {
        ResolvedModel resolvedModel = resolve(model);
        return modelsByKey.computeIfAbsent(resolvedModel.key(), ignored -> buildModel(resolvedModel.config()));
    }

    public ChatClient chatClient(String model) {
        return ChatClient.builder(chatModel(model)).build();
    }

    public String remoteModelName(String model) {
        return resolve(model).config().remoteModelName();
    }

    public ModelConfig modelConfig(String model) {
        return resolve(model).config();
    }

    /** Returns Ollama-specific options. Throws if the model is not an Ollama endpoint.
     * Prefer {@link #chatOptions(String)} for endpoint-agnostic callers. */
    public OllamaChatOptions ollamaOptions(String model) {
        ModelConfig config = modelConfig(model);
        if (config.endpointType() != EndpointType.OLLAMA) {
            throw new IllegalStateException("ollamaOptions called for non-Ollama model: " + model);
        }
        return ollamaOptionsBuilder(model).build();
    }

    public OllamaChatOptions.Builder ollamaOptionsBuilder(String model) {
        ModelConfig config = modelConfig(model);
        if (config.endpointType() != EndpointType.OLLAMA) {
            throw new IllegalStateException("ollamaOptionsBuilder called for non-Ollama model: " + model);
        }
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
            .model(config.remoteModelName());
        applyOllamaThink(builder, effectiveThinkLevel(config));
        return builder;
    }

    public ToolCallingChatOptions toolCallingOptions(String model) {
        ModelConfig config = modelConfig(model);
        return switch (config.endpointType()) {
            case OLLAMA -> ollamaOptionsBuilder(model).build();
            case OPENAI_COMPATIBLE -> {
                OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                    .model(config.remoteModelName());
                applyOpenAiReasoningEffort(builder, effectiveThinkLevel(config));
                yield builder.build();
            }
            case DEEPSEEK -> {
                DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder()
                    .model(config.remoteModelName());
                applyDeepSeekReasoningEffort(builder, effectiveThinkLevel(config));
                yield builder.build();
            }
        };
    }

    /** Endpoint-polymorphic options for any chat call. Use this instead of
     * {@link #ollamaOptions(String)} unless you specifically need the
     * concrete {@code OllamaChatOptions} type. */
    public ToolCallingChatOptions chatOptions(String model) {
        return toolCallingOptions(model);
    }

    /** Endpoint-polymorphic options without think/reasoning-effort settings.
     * Use for utility calls like title generation that should work across
     * all model types without extra configuration. */
    public ToolCallingChatOptions basicChatOptions(String model) {
        ModelConfig config = modelConfig(model);
        return switch (config.endpointType()) {
            case OLLAMA -> OllamaChatOptions.builder()
                .model(config.remoteModelName())
                .build();
            case OPENAI_COMPATIBLE -> OpenAiChatOptions.builder()
                .model(config.remoteModelName())
                .build();
            case DEEPSEEK -> DeepSeekChatOptions.builder()
                .model(config.remoteModelName())
                .build();
        };
    }

    private static int effectiveThinkLevel(ModelConfig config) {
        Integer level = config.thinkLevel();
        if (level == null) return 0;
        if (level < 0) return 0;
        if (level > 4) return 4;
        return level;
    }

    private static void applyOllamaThink(OllamaChatOptions.Builder builder, int level) {
        switch (level) {
            case 0 -> builder.disableThinking();
            case 1 -> builder.thinkLow();
            case 2 -> builder.thinkMedium();
            case 3 -> builder.thinkHigh();
            default -> builder.thinkHigh(); // level >= 4 clamped
        }
    }

    private static void applyOpenAiReasoningEffort(OpenAiChatOptions.Builder builder, int level) {
        if (level <= 0) return;
        String effort = switch (level) {
            case 1 -> "low";
            case 2 -> "medium";
            case 3 -> "high";
            default -> "high";
        };
        builder.reasoningEffort(effort);
    }

    private static void applyDeepSeekReasoningEffort(DeepSeekChatOptions.Builder builder, int level) {
        if (level <= 0) return;
        builder.enableThinking()
            .reasoningEffort(level >= 4 ? "max" : "high");
    }

    private ChatModel buildModel(ModelConfig modelConfig) {
        if (!StringUtils.hasText(modelConfig.remoteEndpoint())) {
            throw new IllegalStateException("Model must define remoteEndpoint: " + modelConfig.remoteModelName());
        }
        if (!StringUtils.hasText(modelConfig.remoteModelName())) {
            throw new IllegalStateException("Model must define remoteModelName");
        }

        return switch (modelConfig.endpointType()) {
            case OLLAMA -> buildOllamaModel(modelConfig);
            case OPENAI_COMPATIBLE -> buildOpenAiModel(modelConfig);
            case DEEPSEEK -> buildDeepSeekModel(modelConfig);
        };
    }

    private OllamaChatModel buildOllamaModel(ModelConfig modelConfig) {
        OllamaChatOptions.Builder optionsBuilder = OllamaChatOptions.builder()
            .model(modelConfig.remoteModelName());
        applyOllamaThink(optionsBuilder, effectiveThinkLevel(modelConfig));
        return OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(modelConfig.remoteEndpoint()).build())
            .options(optionsBuilder.build())
            .toolCallingManager(toolCallingManager)
            .observationRegistry(observationRegistry)
            .modelManagementOptions(ModelManagementOptions.defaults())
            .build();
    }

    private OpenAiChatModel buildOpenAiModel(ModelConfig modelConfig) {
        if (!StringUtils.hasText(modelConfig.apiKey())) {
            throw new IllegalStateException(
                "OpenAI-compatible model must define apiKey: " + modelConfig.remoteModelName());
        }
        OpenAiChatOptions options = buildOpenAiOptions(modelConfig);
        return buildOpenAiChatModel(modelConfig, options);
    }

    private DeepSeekChatModel buildDeepSeekModel(ModelConfig modelConfig) {
        if (!StringUtils.hasText(modelConfig.apiKey())) {
            throw new IllegalStateException(
                "DeepSeek model must define apiKey: " + modelConfig.remoteModelName());
        }
        DeepSeekChatOptions.Builder optionsBuilder = DeepSeekChatOptions.builder()
            .model(modelConfig.remoteModelName());
        applyDeepSeekReasoningEffort(optionsBuilder, effectiveThinkLevel(modelConfig));
        DeepSeekChatOptions options = optionsBuilder.build();
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(openAiCompatibleReadTimeout);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        DeepSeekApi api = DeepSeekApi.builder()
            .baseUrl(modelConfig.remoteEndpoint())
            .apiKey(modelConfig.apiKey())
            .restClientBuilder(restClientBuilder)
            .build();
        return DeepSeekChatModel.builder()
            .deepSeekApi(api)
            .options(options)
            .toolCallingManager(toolCallingManager)
            .observationRegistry(observationRegistry)
            .build();
    }

    private OpenAiChatOptions buildOpenAiOptions(ModelConfig modelConfig) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
            .baseUrl(modelConfig.remoteEndpoint())
            .apiKey(modelConfig.apiKey())
            .timeout(openAiCompatibleReadTimeout)
            .model(modelConfig.remoteModelName());
        applyOpenAiReasoningEffort(optionsBuilder, effectiveThinkLevel(modelConfig));
        return optionsBuilder.build();
    }

    private OpenAiChatModel buildOpenAiChatModel(ModelConfig modelConfig, OpenAiChatOptions options) {
        return OpenAiChatModel.builder()
            .options(options)
            .toolCallingManager(toolCallingManager)
            .observationRegistry(observationRegistry)
            .build();
    }

    private ResolvedModel resolve(String model) {
        if (aiConfig == null || aiConfig.models() == null || aiConfig.models().isEmpty()) {
            throw new IllegalStateException("AI config must define models");
        }
        String selected = StringUtils.hasText(model) ? model : defaultRemoteModelName();
        ModelConfig byKey = aiConfig.models().get(selected);
        if (byKey != null) {
            return new ResolvedModel(selected, byKey);
        }
        return aiConfig.models().entrySet().stream()
            .filter(entry -> selected.equals(entry.getValue().remoteModelName()))
            .findFirst()
            .map(entry -> new ResolvedModel(entry.getKey(), entry.getValue()))
            .orElseThrow(() -> new IllegalArgumentException("Unknown configured model: " + selected));
    }

    private String defaultRemoteModelName() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.defaultModel();
        }
        if (!StringUtils.hasText(aiConfig.defaultAgent()) || aiConfig.agents() == null) {
            throw new IllegalStateException("AI config must define defaultAgent");
        }
        String defaultModelKey = aiConfig.agents().get(aiConfig.defaultAgent()).model();
        ModelConfig defaultModel = aiConfig.models().get(defaultModelKey);
        if (defaultModel == null) {
            throw new IllegalStateException("defaultAgent references missing model: " + defaultModelKey);
        }
        return defaultModel.remoteModelName();
    }

    private record ResolvedModel(String key, ModelConfig config) { }
}
