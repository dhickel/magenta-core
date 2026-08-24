package io.mindspice.magenta.core;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import io.mindspice.magenta.core.ModelConfig;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.observation.ObservationRegistry;

import java.time.Duration;
import java.util.List;

/** Direct Spring AI/OpenAI-compatible construction. */
final class OpenAiProviderAdapter {

    private OpenAiProviderAdapter() {
    }

    static OwnedChatModel create(
        ModelConfig config, ToolCallingManager toolCallingManager, Duration timeout) {
        OpenAiChatOptions options = options(config, List.of(), timeout);
        String apiKey = config.apiKey() != null ? config.apiKey() : "";
        OpenAIClient client = null;
        OpenAIClientAsync asyncClient = null;
        try {
            client = OpenAiSetup.setupSyncClient(
                config.remoteEndpoint(), apiKey, null, null, null, null,
                false, false, config.remoteModelName(), timeout, 0, null,
                java.util.Map.of(), ObservationRegistry.NOOP, null, List.of());
            asyncClient = OpenAiSetup.setupAsyncClient(
                config.remoteEndpoint(), apiKey, null, null, null, null,
                false, false, config.remoteModelName(), timeout, 0, null,
                java.util.Map.of(), ObservationRegistry.NOOP, null, List.of());
            OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(options)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            OpenAIClient ownedClient = client;
            OpenAIClientAsync ownedAsyncClient = asyncClient;
            return new OwnedChatModel(model, () -> closeClients(ownedClient, ownedAsyncClient));
        } catch (RuntimeException | Error e) {
            try {
                closeClients(client, asyncClient);
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    static OpenAiChatOptions options(
        ModelConfig config, List<ToolCallback> toolCallbacks, Duration timeout) {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
            .baseUrl(config.remoteEndpoint())
            .model(config.remoteModelName())
            .timeout(timeout);
        if (config.apiKey() != null) {
            builder.apiKey(config.apiKey());
        }
        applyReasoning(builder, config.effectiveThinkLevel());
        builder.toolCallbacks(List.copyOf(toolCallbacks));
        return builder.build();
    }

    private static void applyReasoning(OpenAiChatOptions.Builder builder, int level) {
        if (level <= 0) {
            return;
        }
        String effort = switch (level) {
            case 1 -> "low";
            case 2 -> "medium";
            case 3, 4 -> "high";
            default -> throw new IllegalArgumentException("think level must be between 0 and 4");
        };
        builder.reasoningEffort(effort);
    }

    private static void closeClients(OpenAIClient client, OpenAIClientAsync asyncClient) {
        RuntimeException failure = null;
        if (asyncClient != null) {
            try {
                asyncClient.close();
            } catch (RuntimeException e) {
                failure = e;
            }
        }
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
