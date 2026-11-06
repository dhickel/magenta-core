package io.mindspice.magenta.core;

import io.mindspice.magenta.core.ConfigurationException;
import io.mindspice.magenta.core.ModelConfig;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.List;

/** Direct Spring AI Ollama construction. */
final class OllamaProviderAdapter {

    private OllamaProviderAdapter() {
    }

    static OwnedChatModel create(
        ModelConfig.Ollama config, ToolCallingManager toolCallingManager, Duration timeout) {
        ProviderHttpClientBuilders.ClientBuilders clients =
            ProviderHttpClientBuilders.forTimeout(timeout);
        OllamaApi api = OllamaApi.builder()
            .baseUrl(config.remoteEndpoint())
            .restClientBuilder(clients.restClient())
            .webClientBuilder(clients.webClient())
            .build();
        OllamaChatModel model = OllamaChatModel.builder()
            .ollamaApi(api)
            .options(options(config, List.of()))
            .toolCallingManager(toolCallingManager)
            .observationRegistry(ObservationRegistry.NOOP)
            .modelManagementOptions(ModelManagementOptions.defaults())
            .build();
        return new OwnedChatModel(model, null);
    }

    static OllamaChatOptions options(
        ModelConfig.Ollama config, List<ToolCallback> toolCallbacks) {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder();
        builder.model(config.remoteModelName());
        if (config.contextLength() != null) {
            builder.numCtx(config.contextLength());
        }
        applyThinking(builder, config.effectiveThinkLevel());
        builder.toolCallbacks(List.copyOf(toolCallbacks));
        return builder.build();
    }

    private static void applyThinking(OllamaChatOptions.Builder builder, int level) {
        switch (level) {
            case 0 -> builder.disableThinking();
            case 1 -> builder.thinkLow();
            case 2 -> builder.thinkMedium();
            case 3, 4 -> builder.thinkHigh();
            default -> throw new ConfigurationException(
                "Ollama think level must be between 0 and 4",
                java.util.Map.of("error", "invalid_think_level", "provider", "OLLAMA"));
        }
    }
}
