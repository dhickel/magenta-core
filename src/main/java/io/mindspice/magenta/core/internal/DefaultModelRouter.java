package io.mindspice.magenta.core.internal;

import io.mindspice.magenta.core.ConfigurationException;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.ToolCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, deterministic model routing boundary.
 *
 * <p>All provider construction is direct and compile-time linked to the
 * provider artifacts bundled by the root POM. Spring AI types stop at this
 * internal boundary; callers provide only Magenta model and tool contracts.</p>
 */
public final class DefaultModelRouter implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelRouter.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(360);

    private final Map<String, ModelConfig> configs;
    private final Map<String, OwnedChatModel> modelCache = new LinkedHashMap<>();
    private final ToolCallingManager toolCallingManager;
    private final Duration requestTimeout;
    private final Object lifecycleMonitor = new Object();
    private boolean closed;

    /** Creates a router with the default provider request timeout. */
    public DefaultModelRouter(@NonNull Map<String, ModelConfig> configs) {
        this(configs, null, DEFAULT_TIMEOUT);
    }

    /** Creates a router with an explicit provider request timeout. */
    public DefaultModelRouter(@NonNull Map<String, ModelConfig> configs,
                              @NonNull Duration requestTimeout) {
        this(configs, null, requestTimeout);
    }

    /** Creates a router with a caller-supplied internal tool manager. */
    public DefaultModelRouter(@NonNull Map<String, ModelConfig> configs,
                              @NonNull ToolCallingManager toolCallingManager) {
        this(configs, toolCallingManager, DEFAULT_TIMEOUT);
    }

    /**
     * Compatibility constructor for internal callers that already own a
     * Spring AI tool-calling manager.
     */
    public DefaultModelRouter(@NonNull Map<String, ModelConfig> configs,
                              @NonNull ToolCallingManager toolCallingManager,
                              long requestTimeoutSeconds) {
        this(configs, toolCallingManager,
            Duration.ofSeconds(Math.max(1, requestTimeoutSeconds)));
    }

    /** Internal constructor used by the public-free provider composition path. */
    public DefaultModelRouter(@NonNull Map<String, ModelConfig> configs,
                              @Nullable ToolCallingManager toolCallingManager,
                              @NonNull Duration requestTimeout) {
        this.configs = immutableOrderedCopy(configs);
        this.toolCallingManager = toolCallingManager != null
            ? toolCallingManager
            : ToolCallingManager.builder().build();
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new ConfigurationException(
                "Provider request timeout must be greater than zero",
                Map.of("error", "invalid_model_timeout"));
        }
        this.requestTimeout = requestTimeout;
    }

    @Override
    @NonNull
    public ChatModel springAiChatModel(@NonNull String modelAlias) {
        String alias = canonicalAlias(modelAlias, true);
        synchronized (lifecycleMonitor) {
            if (closed) {
                throw new IllegalStateException("Model router is closed");
            }
            OwnedChatModel owned = modelCache.get(alias);
            if (owned == null) {
                owned = buildModel(alias);
                modelCache.put(alias, owned);
            }
            return owned.model();
        }
    }

    @Override
    @NonNull
    public String remoteModelName(@NonNull String modelAlias) {
        return requireConfig(modelAlias).remoteModelName();
    }

    @Override
    @NonNull
    public String selectModelAlias(@Nullable String requestedAlias,
                                   @Nullable String sessionAlias) {
        if (requestedAlias != null && !requestedAlias.isBlank()) {
            return canonicalAlias(requestedAlias, true);
        }
        if (sessionAlias != null && !sessionAlias.isBlank()) {
            String resolvedSessionAlias = canonicalAlias(sessionAlias, false);
            if (resolvedSessionAlias != null) {
                return resolvedSessionAlias;
            }
        }
        return configs.keySet().iterator().next();
    }

    @Override
    public int contextLength(@NonNull String modelAlias) {
        Integer contextLength = requireConfig(modelAlias).contextLength();
        return contextLength != null ? contextLength : 0;
    }

    @Override
    @NonNull
    public ToolCallingChatOptions toolCallingOptions(
        @NonNull String modelAlias,
        @NonNull List<ToolCallback> tools
    ) {
        ModelConfig config = requireConfig(modelAlias);
        List<org.springframework.ai.tool.ToolCallback> springTools = tools.stream()
            .map(tool -> (org.springframework.ai.tool.ToolCallback)
                new SpringAiToolCallbackAdapter(Objects.requireNonNull(tool, "tool")))
            .toList();
        return providerOptions(config, springTools);
    }

    @Override
    @NonNull
    public ToolCallingChatOptions basicChatOptions(@NonNull String modelAlias) {
        return providerOptions(requireConfig(modelAlias), List.of());
    }

    @Override
    public boolean supportsStreaming(@NonNull String modelAlias) {
        requireConfig(modelAlias);
        // All three supported Spring AI ChatModel implementations expose the
        // same synchronous/streaming boundary. Streaming is selected by the
        // TurnRequest, not by mutating a provider-specific public option.
        return true;
    }

    @Override
    public void closeChatModels() {
        synchronized (lifecycleMonitor) {
            if (closed) {
                return;
            }
            closed = true;
            for (Map.Entry<String, OwnedChatModel> entry : modelCache.entrySet()) {
                try {
                    entry.getValue().close();
                } catch (Exception e) {
                    log.warn("Failed to close model client for alias '{}': {}",
                        entry.getKey(), e.getMessage());
                }
            }
            modelCache.clear();
        }
    }

    private OwnedChatModel buildModel(String alias) {
        ModelConfig config = requireConfig(alias);
        return switch (config.endpointType()) {
            case OLLAMA -> OllamaProviderAdapter.create(
                config, toolCallingManager, requestTimeout);
            case OPENAI_COMPATIBLE -> OpenAiProviderAdapter.create(
                config, toolCallingManager, requestTimeout);
            case DEEPSEEK -> DeepSeekProviderAdapter.create(
                config, toolCallingManager, requestTimeout);
        };
    }

    private ToolCallingChatOptions providerOptions(
        ModelConfig config, List<org.springframework.ai.tool.ToolCallback> tools) {
        return switch (config.endpointType()) {
            case OLLAMA -> OllamaProviderAdapter.options(config, tools);
            case OPENAI_COMPATIBLE -> OpenAiProviderAdapter.options(
                config, tools, requestTimeout);
            case DEEPSEEK -> DeepSeekProviderAdapter.options(config, tools);
        };
    }

    private ModelConfig requireConfig(String value) {
        String alias = canonicalAlias(value, true);
        return configs.get(alias);
    }

    /**
     * Resolves canonical aliases first and then accepts a remote model name as
     * a compatibility value for persisted settings. If several aliases point
     * at one remote name, registration order supplies the deterministic alias.
     */
    private String canonicalAlias(String value, boolean failIfMissing) {
        if (value == null || value.isBlank()) {
            if (failIfMissing) {
                throw new IllegalArgumentException("Model alias must not be blank");
            }
            return null;
        }
        if (configs.containsKey(value)) {
            return value;
        }
        for (Map.Entry<String, ModelConfig> entry : configs.entrySet()) {
            if (value.equals(entry.getValue().remoteModelName())) {
                return entry.getKey();
            }
        }
        if (failIfMissing) {
            throw new IllegalArgumentException("Unknown model alias: " + value);
        }
        return null;
    }

    private static Map<String, ModelConfig> immutableOrderedCopy(
        Map<String, ModelConfig> source) {
        Objects.requireNonNull(source, "configs");
        LinkedHashMap<String, ModelConfig> copy = new LinkedHashMap<>();
        source.forEach((alias, config) -> {
            if (alias == null || config == null) {
                throw new IllegalArgumentException("Model aliases and configurations must not be null");
            }
            if (copy.putIfAbsent(alias, config) != null) {
                throw new IllegalArgumentException("Duplicate model alias: " + alias);
            }
        });
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("At least one model must be configured");
        }
        return Collections.unmodifiableMap(copy);
    }
}
