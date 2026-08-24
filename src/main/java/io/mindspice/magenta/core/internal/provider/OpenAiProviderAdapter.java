package io.mindspice.magenta.core.internal.provider;

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
public final class OpenAiProviderAdapter {

    private OpenAiProviderAdapter() {
    }

    public static OwnedChatModel create(
        ModelConfig config, ToolCallingManager toolCallingManager, Duration timeout) {
        OpenAiChatOptions options = options(config, List.of(), timeout);
        String apiKey = config.apiKey() != null ? config.apiKey() : "";
        OpenAIClient client = OpenAiSetup.setupSyncClient(
            config.remoteEndpoint(), apiKey, null, null, null, null,
            false, false, config.remoteModelName(), timeout, 0, null,
            java.util.Map.of(), ObservationRegistry.NOOP, null, List.of());
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
            config.remoteEndpoint(), apiKey, null, null, null, null,
            false, false, config.remoteModelName(), timeout, 0, null,
            java.util.Map.of(), ObservationRegistry.NOOP, null, List.of());
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(asyncClient)
                .options(options)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(ObservationRegistry.NOOP)
                .build();
            return new OwnedChatModel(model, () -> closeClients(client, asyncClient));
        } catch (RuntimeException | Error e) {
            try {
                closeClients(client, asyncClient);
            } catch (RuntimeException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    public static OpenAiChatOptions options(
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
        try {
            asyncClient.close();
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = e;
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
