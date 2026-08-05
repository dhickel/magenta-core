package io.mindspice.core.internal;

import io.mindspice.core.ConfigurationException;
import io.mindspice.core.EndpointType;
import io.mindspice.core.ModelConfig;
import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import io.mindspice.core.ToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of {@link ModelRouter}.
 *
 * <p>Constructs {@link ChatModel} instances per alias from a configuration
 * map. Provider detection uses classpath probing so the router loads
 * successfully even when only a subset of provider dependencies are
 * present. This is the <strong>sole</strong> point where Spring AI
 * provider types appear — it is internal, not public API.</p>
 */
public class DefaultModelRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelRouter.class);

    private static final boolean OLLAMA_AVAILABLE = probeClass("org.springframework.ai.ollama.OllamaChatModel");
    private static final boolean OPENAI_AVAILABLE = probeClass("org.springframework.ai.openai.OpenAiChatModel");
    private static final boolean DEEPSEEK_AVAILABLE = probeClass("org.springframework.ai.deepseek.DeepSeekChatModel");

    private final Map<String, ModelConfig> configs;
    private final Map<String, ChatModel> modelCache = new ConcurrentHashMap<>();
    private final ToolCallingManager toolCallingManager;
    private final Duration openAiReadTimeout;

    /**
     * Creates a router from a map of model alias → config.
     *
     * @param configs             model configurations keyed by alias
     * @param toolCallingManager  Spring AI tool-calling manager
     * @param openAiReadTimeoutSeconds read timeout for OpenAI-compatible endpoints
     */
    public DefaultModelRouter(
        @NonNull Map<String, ModelConfig> configs,
        @NonNull ToolCallingManager toolCallingManager,
        long openAiReadTimeoutSeconds
    ) {
        this.configs = Map.copyOf(configs);
        this.toolCallingManager = toolCallingManager;
        this.openAiReadTimeout = Duration.ofSeconds(Math.max(1, openAiReadTimeoutSeconds));
    }

    public DefaultModelRouter(
        @NonNull Map<String, ModelConfig> configs,
        @NonNull ToolCallingManager toolCallingManager
    ) {
        this(configs, toolCallingManager, 360);
    }

    /**
     * Factory that resolves the {@link ToolCallingManager} from the Spring
     * {@link ApplicationContext}. This is the preferred entry point for
     * {@code TurnLoopBuilder} so that the builder itself never imports
     * Spring AI types.
     *
     * @param configs model configurations keyed by alias
     * @param context Spring application context
     * @return a fully initialised router
     * @throws ConfigurationException if {@code ToolCallingManager} cannot be resolved
     */
    public static DefaultModelRouter fromContext(
        @NonNull Map<String, ModelConfig> configs,
        @NonNull ApplicationContext context
    ) {
        ToolCallingManager tcm = context.getBeanProvider(ToolCallingManager.class)
            .getIfAvailable(() -> {
                log.info("No ToolCallingManager bean found — creating default");
                try {
                    var builderMethod = ToolCallingManager.class.getMethod("builder");
                    Object builder = builderMethod.invoke(null);
                    var buildMethod = builder.getClass().getMethod("build");
                    return (ToolCallingManager) buildMethod.invoke(builder);
                } catch (Exception e) {
                    throw new ConfigurationException(
                        "ToolCallingManager is required but could not be created. "
                        + "Ensure spring-ai-model is on the classpath.",
                        Map.of("error", "tool_calling_manager_missing"));
                }
            });
        return new DefaultModelRouter(configs, tcm);
    }

    // ── ModelRouter ──

    @Override
    @NonNull
    public ChatModel springAiChatModel(@NonNull String modelAlias) {
        return modelCache.computeIfAbsent(modelAlias, this::buildModel);
    }

    @Override
    @NonNull
    public String remoteModelName(@NonNull String modelAlias) {
        return requireConfig(modelAlias).remoteModelName();
    }

    @Override
    @NonNull
    public ToolCallingChatOptions toolCallingOptions(
        @NonNull String modelAlias,
        @NonNull List<ToolCallback> tools
    ) {
        ModelConfig config = requireConfig(modelAlias);
        ToolCallingChatOptions base = buildOptions(config);
        List<org.springframework.ai.tool.ToolCallback> springAiTools =
            tools.stream().map(SpringAiToolCallbackAdapter::new).collect(java.util.stream.Collectors.toList());
        return base.mutate().toolCallbacks(springAiTools).build();
    }

    @Override
    @NonNull
    public ToolCallingChatOptions basicChatOptions(@NonNull String modelAlias) {
        return buildOptions(requireConfig(modelAlias));
    }

    @Override
    public boolean supportsStreaming(@NonNull String modelAlias) {
        ModelConfig config = requireConfig(modelAlias);
        return config.endpointType() != EndpointType.OLLAMA || OLLAMA_AVAILABLE;
    }

    @Override
    public void closeChatModels() {
        for (var entry : modelCache.entrySet()) {
            ChatModel model = entry.getValue();
            try {
                if (model instanceof AutoCloseable ac) {
                    ac.close();
                }
            } catch (Exception e) {
                log.warn("Failed to close ChatModel for alias '{}': {}", entry.getKey(), e.getMessage());
            }
        }
        modelCache.clear();
    }

    // ── Model construction ──

    private ChatModel buildModel(String alias) {
        ModelConfig config = requireConfig(alias);
        return switch (config.endpointType()) {
            case OLLAMA -> buildOllamaModel(config);
            case OPENAI_COMPATIBLE -> buildOpenAiModel(config);
            case DEEPSEEK -> buildDeepSeekModel(config);
        };
    }

    @SuppressWarnings("unchecked")
    private ChatModel buildOllamaModel(ModelConfig config) {
        if (!OLLAMA_AVAILABLE) {
            throw new IllegalStateException("Ollama model requested but spring-ai-ollama is not on the classpath: "
                + config.alias());
        }
        try {
            // Reflection to avoid compile-time dependency on optional provider
            Class<?> ollamaApiClass = Class.forName("org.springframework.ai.ollama.api.OllamaApi");
            Class<?> ollamaChatOptionsClass = Class.forName("org.springframework.ai.ollama.api.OllamaChatOptions");
            Class<?> ollamaChatModelClass = Class.forName("org.springframework.ai.ollama.OllamaChatModel");
            Class<?> modelMgmtClass = Class.forName("org.springframework.ai.ollama.management.ModelManagementOptions");

            Object ollamaApi = ollamaApiClass.getMethod("builder").invoke(null);
            ollamaApi = ollamaApi.getClass().getMethod("baseUrl", String.class)
                .invoke(ollamaApi, config.remoteEndpoint());
            ollamaApi = ollamaApi.getClass().getMethod("build").invoke(ollamaApi);

            Object optionsBuilder = ollamaChatOptionsClass.getMethod("builder").invoke(null);
            optionsBuilder = optionsBuilder.getClass().getMethod("model", String.class)
                .invoke(optionsBuilder, config.remoteModelName());
            applyOllamaThinkReflective(optionsBuilder, config.effectiveThinkLevel());
            Object options = optionsBuilder.getClass().getMethod("build").invoke(optionsBuilder);

            Object modelMgmt = modelMgmtClass.getMethod("defaults").invoke(null);

            Object builder = ollamaChatModelClass.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("ollamaApi", ollamaApi.getClass().getInterfaces()[0])
                .invoke(builder, ollamaApi);
            builder = builder.getClass().getMethod("options",
                    Class.forName("org.springframework.ai.chat.prompt.ChatOptions"))
                .invoke(builder, options);
            builder = builder.getClass().getMethod("toolCallingManager", ToolCallingManager.class)
                .invoke(builder, toolCallingManager);
            builder = builder.getClass().getMethod("modelManagementOptions", modelMgmtClass)
                .invoke(builder, modelMgmt);
            builder = builder.getClass().getMethod("observationRegistry",
                    Class.forName("io.micrometer.observation.ObservationRegistry"))
                .invoke(builder, Class.forName("io.micrometer.observation.ObservationRegistry")
                    .getField("NOOP").get(null));

            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Ollama model for alias " + config.alias(), e);
        }
    }

    private ChatModel buildOpenAiModel(ModelConfig config) {
        if (!OPENAI_AVAILABLE) {
            throw new IllegalStateException("OpenAI-compatible model requested but spring-ai-openai is not on the classpath: "
                + config.alias());
        }
        try {
            Class<?> openAiChatOptionsClass = Class.forName("org.springframework.ai.openai.OpenAiChatOptions");
            Class<?> openAiChatModelClass = Class.forName("org.springframework.ai.openai.OpenAiChatModel");

            Object optionsBuilder = openAiChatOptionsClass.getMethod("builder").invoke(null);
            optionsBuilder = optionsBuilder.getClass().getMethod("model", String.class)
                .invoke(optionsBuilder, config.remoteModelName());
            applyOpenAiReasoningReflective(optionsBuilder, config.effectiveThinkLevel());
            Object options = optionsBuilder.getClass().getMethod("build").invoke(optionsBuilder);

            Object builder = openAiChatModelClass.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("options",
                    Class.forName("org.springframework.ai.chat.prompt.ChatOptions"))
                .invoke(builder, options);
            builder = builder.getClass().getMethod("toolCallingManager", ToolCallingManager.class)
                .invoke(builder, toolCallingManager);
            builder = builder.getClass().getMethod("observationRegistry",
                    Class.forName("io.micrometer.observation.ObservationRegistry"))
                .invoke(builder, Class.forName("io.micrometer.observation.ObservationRegistry")
                    .getField("NOOP").get(null));

            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build OpenAI model for alias " + config.alias(), e);
        }
    }

    private ChatModel buildDeepSeekModel(ModelConfig config) {
        if (!DEEPSEEK_AVAILABLE) {
            throw new IllegalStateException("DeepSeek model requested but spring-ai-deepseek is not on the classpath: "
                + config.alias());
        }
        try {
            Class<?> deepSeekChatOptionsClass = Class.forName("org.springframework.ai.deepseek.DeepSeekChatOptions");
            Class<?> deepSeekChatModelClass = Class.forName("org.springframework.ai.deepseek.DeepSeekChatModel");
            Class<?> deepSeekApiClass = Class.forName("org.springframework.ai.deepseek.api.DeepSeekApi");

            Object optionsBuilder = deepSeekChatOptionsClass.getMethod("builder").invoke(null);
            optionsBuilder = optionsBuilder.getClass().getMethod("model", String.class)
                .invoke(optionsBuilder, config.remoteModelName());
            applyDeepSeekReasoningReflective(optionsBuilder, config.effectiveThinkLevel());
            Object options = optionsBuilder.getClass().getMethod("build").invoke(optionsBuilder);

            Object apiBuilder = deepSeekApiClass.getMethod("builder").invoke(null);
            apiBuilder = apiBuilder.getClass().getMethod("baseUrl", String.class)
                .invoke(apiBuilder, config.remoteEndpoint());
            if (config.apiKey() != null) {
                apiBuilder = apiBuilder.getClass().getMethod("apiKey", String.class)
                    .invoke(apiBuilder, config.apiKey());
            }
            Object api = apiBuilder.getClass().getMethod("build").invoke(apiBuilder);

            Object builder = deepSeekChatModelClass.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("deepSeekApi", deepSeekApiClass)
                .invoke(builder, api);
            builder = builder.getClass().getMethod("options",
                    Class.forName("org.springframework.ai.chat.prompt.ChatOptions"))
                .invoke(builder, options);
            builder = builder.getClass().getMethod("toolCallingManager", ToolCallingManager.class)
                .invoke(builder, toolCallingManager);
            builder = builder.getClass().getMethod("observationRegistry",
                    Class.forName("io.micrometer.observation.ObservationRegistry"))
                .invoke(builder, Class.forName("io.micrometer.observation.ObservationRegistry")
                    .getField("NOOP").get(null));

            return (ChatModel) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build DeepSeek model for alias " + config.alias(), e);
        }
    }

    // ── Options ──

    private ToolCallingChatOptions buildOptions(ModelConfig config) {
        return switch (config.endpointType()) {
            case OLLAMA -> buildOllamaOptions(config);
            case OPENAI_COMPATIBLE -> buildOpenAiOptions(config);
            case DEEPSEEK -> buildDeepSeekOptions(config);
        };
    }

    private ToolCallingChatOptions buildOllamaOptions(ModelConfig config) {
        try {
            Class<?> cls = Class.forName("org.springframework.ai.ollama.api.OllamaChatOptions");
            Object builder = cls.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("model", String.class)
                .invoke(builder, config.remoteModelName());
            applyOllamaThinkReflective(builder, config.effectiveThinkLevel());
            return (ToolCallingChatOptions) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private ToolCallingChatOptions buildOpenAiOptions(ModelConfig config) {
        try {
            Class<?> cls = Class.forName("org.springframework.ai.openai.OpenAiChatOptions");
            Object builder = cls.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("model", String.class)
                .invoke(builder, config.remoteModelName());
            applyOpenAiReasoningReflective(builder, config.effectiveThinkLevel());
            return (ToolCallingChatOptions) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private ToolCallingChatOptions buildDeepSeekOptions(ModelConfig config) {
        try {
            Class<?> cls = Class.forName("org.springframework.ai.deepseek.DeepSeekChatOptions");
            Object builder = cls.getMethod("builder").invoke(null);
            builder = builder.getClass().getMethod("model", String.class)
                .invoke(builder, config.remoteModelName());
            applyDeepSeekReasoningReflective(builder, config.effectiveThinkLevel());
            return (ToolCallingChatOptions) builder.getClass().getMethod("build").invoke(builder);
        } catch (Exception e) {
            return ToolCallingChatOptions.builder().build();
        }
    }

    // ── Thinking / reasoning effort (reflective) ──

    private static void applyOllamaThinkReflective(Object builder, int level) {
        try {
            switch (level) {
                case 0 -> builder.getClass().getMethod("disableThinking").invoke(builder);
                case 1 -> builder.getClass().getMethod("thinkLow").invoke(builder);
                case 2 -> builder.getClass().getMethod("thinkMedium").invoke(builder);
                case 3, 4 -> builder.getClass().getMethod("thinkHigh").invoke(builder);
            }
        } catch (Exception ignored) {
            // method may not exist in all versions
        }
    }

    private static void applyOpenAiReasoningReflective(Object builder, int level) {
        if (level <= 0) return;
        try {
            String effort = switch (level) {
                case 1 -> "low";
                case 2 -> "medium";
                default -> "high";
            };
            builder.getClass().getMethod("reasoningEffort", String.class).invoke(builder, effort);
        } catch (Exception ignored) {
            // method may not exist in all versions
        }
    }

    private static void applyDeepSeekReasoningReflective(Object builder, int level) {
        if (level <= 0) return;
        try {
            builder.getClass().getMethod("enableThinking").invoke(builder);
            String effort = level >= 4 ? "max" : "high";
            builder.getClass().getMethod("reasoningEffort", String.class).invoke(builder, effort);
        } catch (Exception ignored) {
            // method may not exist in all versions
        }
    }

    // ── Helpers ──

    private ModelConfig requireConfig(String alias) {
        ModelConfig config = configs.get(alias);
        if (config == null) {
            throw new IllegalArgumentException("Unknown model alias: " + alias);
        }
        return config;
    }

    private static boolean probeClass(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ── Spring AI ToolCallback adapter ──

    /**
     * Adapts framework {@link ToolCallback} to Spring AI {@code ToolCallback}
     * so framework tools can be passed to {@code ToolCallingChatOptions}.
     */
    private record SpringAiToolCallbackAdapter(ToolCallback delegate)
        implements org.springframework.ai.tool.ToolCallback {

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            io.mindspice.core.ToolDefinition fd = delegate.getToolDefinition();
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(fd.name())
                .description(fd.description())
                .inputSchema(fd.inputSchema())
                .build();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }
    }
}
