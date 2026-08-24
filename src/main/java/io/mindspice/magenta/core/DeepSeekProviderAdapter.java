package io.mindspice.magenta.core;

import io.mindspice.magenta.core.ConfigurationException;
import io.mindspice.magenta.core.ModelConfig;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Direct Spring AI DeepSeek construction. */
final class DeepSeekProviderAdapter {

    private DeepSeekProviderAdapter() {
    }

    static OwnedChatModel create(
        ModelConfig config, ToolCallingManager toolCallingManager, Duration timeout) {
        ProviderHttpClientBuilders.ClientBuilders clients =
            ProviderHttpClientBuilders.forTimeout(timeout);
        DeepSeekApi api = DeepSeekApi.builder()
            .baseUrl(config.remoteEndpoint())
            .apiKey(config.apiKey())
            .restClientBuilder(clients.restClient())
            .webClientBuilder(clients.webClient())
            .build();
        DeepSeekChatModel model = DeepSeekChatModel.builder()
            .deepSeekApi(api)
            .options(options(config, List.of()))
            .toolCallingManager(toolCallingManager)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
        return new OwnedChatModel(model, null);
    }

    static DeepSeekChatOptions options(
        ModelConfig config, List<ToolCallback> toolCallbacks) {
        if (config.effectiveThinkLevel() > 0) {
            // Spring AI 2.0.0 exposes typed reasoning content on responses but
            // no request-side thinking/reasoning option. Do not silently drop a
            // caller's setting or invent an unsupported request shape.
            throw new ConfigurationException(
                "DeepSeek thinking/reasoning is not exposed by the pinned Spring AI 2.0.0 API",
                Map.of("error", "unsupported_provider_option", "provider", "DEEPSEEK",
                    "option", "thinkLevel", "alias", config.alias()));
        }
        DeepSeekChatOptions.Builder builder = DeepSeekChatOptions.builder();
        builder.model(config.remoteModelName());
        builder.toolCallbacks(List.copyOf(toolCallbacks));
        return builder.build();
    }
}
