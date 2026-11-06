package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseErrorEvent;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseReasoningItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import io.mindspice.magenta.core.ModelConfig;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SynchronousSink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Direct Spring AI construction for OpenAI Responses-protocol providers
 * (OpenAI Responses API, DeepSeek V4, and OpenAI Codex endpoints).
 *
 * <p>Spring AI 2.0 exposes no Responses-API chat model, so this adapter
 * implements {@link ChatModel} directly over the official OpenAI Java SDK's
 * {@code responses()} service. Request and response mapping translate the
 * Spring AI message/tool contract to Responses items and back, keeping the
 * turn engine, tool loop, and thinking extraction unchanged.</p>
 */
final class ResponsesProviderAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResponsesProviderAdapter() {
    }

    static OwnedChatModel create(
        ModelConfig.Responses config,
        ToolCallingManager toolCallingManager,
        Duration timeout) {
        String apiKey = config.apiKey();
        OpenAIClient client = null;
        OpenAIClientAsync asyncClient = null;
        try {
            client = OpenAiSetup.setupSyncClient(
                config.remoteEndpoint(), apiKey, null, null, null, null,
                false, false, config.remoteModelName(), timeout, 0, null,
                Map.of(), ObservationRegistry.NOOP, null, List.of());
            asyncClient = OpenAiSetup.setupAsyncClient(
                config.remoteEndpoint(), apiKey, null, null, null, null,
                false, false, config.remoteModelName(), timeout, 0, null,
                Map.of(), ObservationRegistry.NOOP, null, List.of());
            ChatModel model = new ResponsesChatModel(
                client, config, Objects.requireNonNull(toolCallingManager, "toolCallingManager"));
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

    static ToolCallingChatOptions options(
        ModelConfig.Responses config, List<ToolCallback> toolCallbacks) {
        return org.springframework.ai.model.tool.DefaultToolCallingChatOptions.builder()
            .model(config.remoteModelName())
            .toolCallbacks(List.copyOf(toolCallbacks))
            .build();
    }

    private static final class ResponsesChatModel implements ChatModel {

        private final OpenAIClient client;
        private final ModelConfig.Responses config;
        private final ToolCallingManager toolCallingManager;

        private ResponsesChatModel(
            OpenAIClient client, ModelConfig.Responses config, ToolCallingManager toolCallingManager) {
            this.client = client;
            this.config = config;
            this.toolCallingManager = toolCallingManager;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            Map<String, String> nameMap = buildNameMap(prompt);
            ResponseCreateParams params = createParams(prompt, false, nameMap);
            Response response = client.responses().create(params);
            return toChatResponse(response, nameMap);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            Map<String, String> nameMap = buildNameMap(prompt);
            ResponseCreateParams params = createParams(prompt, true, nameMap);
            StreamResponse<ResponseStreamEvent> stream =
                client.responses().createStreaming(params);
            StreamState state = new StreamState(stream, nameMap);
            return Flux.using(
                () -> stream,
                s -> Flux.fromStream(s.stream())
                    .handle((event, sink) -> handleEvent(state, event, sink)),
                StreamResponse::close);
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return options(config, List.of());
        }

        private ResponseCreateParams createParams(
            Prompt prompt, boolean stream, Map<String, String> nameMap) {
            StringBuilder system = new StringBuilder();
            List<ResponseInputItem> input = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                switch (message) {
                    case SystemMessage sys -> appendSystem(system, sys);
                    case UserMessage user -> input.add(toInputMessage(user));
                    case AssistantMessage assistant -> addAssistantItems(input, assistant, nameMap);
                    case ToolResponseMessage tool -> addToolItems(input, tool);
                    default -> { /* ignore unknown Spring AI message kinds */ }
                }
            }

            ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(config.remoteModelName())
                .store(false);
            if (!system.isEmpty()) {
                builder.instructions(system.toString().trim());
            }
            if (!input.isEmpty()) {
                builder.input(ResponseCreateParams.Input.ofResponse(input));
            }
            List<com.openai.models.responses.Tool> tools = resolveTools(prompt, nameMap);
            if (!tools.isEmpty()) {
                builder.tools(tools);
            }
            String effort = resolveEffort();
            if (effort != null) {
                builder.reasoning(Reasoning.builder()
                    .effort(ReasoningEffort.of(effort))
                    .build());
            }
            return builder.build();
        }

        private static void appendSystem(StringBuilder system, SystemMessage message) {
            String text = message.getText();
            if (text == null || text.isBlank()) {
                return;
            }
            if (!system.isEmpty()) {
                system.append("\n\n");
            }
            system.append(text);
        }

        private static ResponseInputItem toInputMessage(UserMessage message) {
            return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .content(message.getText() == null ? "" : message.getText())
                .build());
        }

        private static void addAssistantItems(
            List<ResponseInputItem> input, AssistantMessage message, Map<String, String> nameMap) {
            String text = message.getText();
            if (text != null && !text.isBlank()) {
                input.add(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                    .role(EasyInputMessage.Role.ASSISTANT)
                    .content(text)
                    .build()));
            }
            if (message.getToolCalls() != null) {
                for (AssistantMessage.ToolCall call : message.getToolCalls()) {
                    input.add(ResponseInputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                        .callId(call.id())
                        .name(sanitizeName(call.name()))
                        .arguments(call.arguments() == null ? "" : call.arguments())
                        .build()));
                }
            }
        }

        private static void addToolItems(
            List<ResponseInputItem> input, ToolResponseMessage message) {
            if (message.getResponses() == null) {
                return;
            }
            for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
                input.add(ResponseInputItem.ofFunctionCallOutput(
                    ResponseInputItem.FunctionCallOutput.builder()
                        .callId(response.id())
                        .output(response.responseData() == null ? "" : response.responseData())
                        .build()));
            }
        }

        private List<com.openai.models.responses.Tool> resolveTools(
            Prompt prompt, Map<String, String> nameMap) {
            ChatOptions options = prompt.getOptions();
            if (!(options instanceof ToolCallingChatOptions toolOptions)) {
                return List.of();
            }
            List<ToolDefinition> definitions = toolCallingManager.resolveToolDefinitions(toolOptions);
            if (definitions.isEmpty()) {
                return List.of();
            }
            List<com.openai.models.responses.Tool> tools = new ArrayList<>(definitions.size());
            for (ToolDefinition definition : definitions) {
                FunctionTool.Builder builder = FunctionTool.builder()
                    .name(sanitizeName(definition.name()))
                    .strict(false);
                String description = definition.description();
                if (description != null && !description.isBlank()) {
                    builder.description(description);
                }
                String schema = definition.inputSchema();
                if (schema != null && !schema.isBlank()) {
                    FunctionTool.Parameters parameters = parseParameters(schema);
                    if (parameters != null) {
                        builder.parameters(parameters);
                    }
                }
                tools.add(com.openai.models.responses.Tool.ofFunction(builder.build()));
            }
            return tools;
        }

        private static FunctionTool.Parameters parseParameters(String schema) {
            try {
                JsonNode node = OBJECT_MAPPER.readTree(schema);
                if (node == null || !node.isObject()) {
                    return null;
                }
                Map<String, JsonValue> properties = new LinkedHashMap<>();
                node.fields().forEachRemaining(entry ->
                    properties.put(entry.getKey(), JsonValue.fromJsonNode(entry.getValue())));
                return FunctionTool.Parameters.builder()
                    .additionalProperties(properties)
                    .build();
            } catch (Exception e) {
                return null;
            }
        }

        private String resolveEffort() {
            String override = config.reasoningEffort();
            if (override != null) {
                return override;
            }
            return switch (config.effectiveThinkLevel()) {
                case 1 -> "low";
                case 2 -> "medium";
                case 3, 4 -> "high";
                default -> null;
            };
        }

        /**
         * Maps original dotted tool names (for example {@code event.create}) to
         * the provider-safe {@code [a-zA-Z0-9_-]+} form. Providers such as the
         * DeepSeek Responses endpoint reject dotted function names, so the
         * adapter sends {@code event_create} and restores the original name on
         * every incoming function_call so the engine and tool registry keep
         * their public names.
         */
        private Map<String, String> buildNameMap(Prompt prompt) {
            ChatOptions options = prompt.getOptions();
            if (!(options instanceof ToolCallingChatOptions toolOptions)) {
                return Map.of();
            }
            Map<String, String> map = new HashMap<>();
            for (ToolDefinition definition : toolCallingManager.resolveToolDefinitions(toolOptions)) {
                String name = definition.name();
                if (name != null && name.indexOf('.') >= 0) {
                    map.put(sanitizeName(name), name);
                }
            }
            return Map.copyOf(map);
        }

        private static String sanitizeName(String name) {
            return name != null && name.indexOf('.') >= 0
                ? name.replace('.', '_') : name;
        }

        private static String restoreName(Map<String, String> nameMap, String name) {
            String original = nameMap.get(name);
            return original != null ? original : name;
        }

        private ChatResponse toChatResponse(Response response, Map<String, String> nameMap) {
            StringBuilder text = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (ResponseOutputItem item : response.output()) {
                if (item.message().isPresent()) {
                    ResponseOutputMessage message = item.message().get();
                    for (ResponseOutputMessage.Content part : message.content()) {
                        if (part.isOutputText() && part.asOutputText().text() != null) {
                            text.append(part.asOutputText().text());
                        }
                    }
                } else if (item.functionCall().isPresent()) {
                    ResponseFunctionToolCall call = item.functionCall().get();
                    calls.add(new AssistantMessage.ToolCall(
                        call.callId(), "function", restoreName(nameMap, call.name()), call.arguments()));
                } else if (item.reasoning().isPresent()) {
                    appendReasoning(reasoning, item.reasoning().get());
                }
            }

            AssistantMessage.Builder messageBuilder = AssistantMessage.builder()
                .content(text.toString());
            if (!calls.isEmpty()) {
                messageBuilder.toolCalls(calls);
            }
            if (!reasoning.isEmpty()) {
                messageBuilder.properties(Map.of("reasoningContent", reasoning.toString()));
            }

            Generation generation = new Generation(
                messageBuilder.build(),
                ChatGenerationMetadata.builder()
                    .finishReason(finishReason(response))
                    .build());

            ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder()
                .id(response.id())
                .model(config.remoteModelName());
            if (response.usage().isPresent()) {
                metadataBuilder.usage(usage(response.usage().get()));
            }
            return new ChatResponse(
                List.of(generation), metadataBuilder.build());
        }

        private static void appendReasoning(StringBuilder reasoning, ResponseReasoningItem item) {
            for (ResponseReasoningItem.Summary summary : item.summary()) {
                String text = summary.text();
                if (text != null && !text.isBlank()) {
                    reasoning.append(text);
                }
            }
            if (item.content().isPresent()) {
                for (ResponseReasoningItem.Content content : item.content().get()) {
                    String text = content.text();
                    if (text != null && !text.isBlank()) {
                        reasoning.append(text);
                    }
                }
            }
        }

        private static String finishReason(Response response) {
            ResponseStatus status = response.status().orElse(ResponseStatus.COMPLETED);
            if (status == ResponseStatus.INCOMPLETE) {
                return "length";
            }
            if (status == ResponseStatus.FAILED) {
                return "error";
            }
            if (status == ResponseStatus.CANCELLED) {
                return "content_filter";
            }
            return "stop";
        }

        private static DefaultUsage usage(ResponseUsage usage) {
            return DefaultUsage.fromJson(
                (int) usage.inputTokens(),
                (int) usage.outputTokens(),
                (int) usage.totalTokens(),
                null, null, null);
        }

        private void handleEvent(
            StreamState state, ResponseStreamEvent event, SynchronousSink<ChatResponse> sink) {
            if (event.outputTextDelta().isPresent()) {
                String delta = event.outputTextDelta().get().delta();
                sink.next(deltaChunk(delta, null));
            } else if (event.reasoningTextDelta().isPresent()) {
                sink.next(deltaChunk("", event.reasoningTextDelta().get().delta()));
            } else if (event.reasoningSummaryTextDelta().isPresent()) {
                sink.next(deltaChunk("", event.reasoningSummaryTextDelta().get().delta()));
            } else if (event.outputItemAdded().isPresent()) {
                ResponseOutputItem item = event.outputItemAdded().get().item();
                if (item.functionCall().isPresent()) {
                    ResponseFunctionToolCall call = item.functionCall().get();
                    state.startCall(call.id().orElse(call.callId()), call.name());
                }
            } else if (event.functionCallArgumentsDelta().isPresent()) {
                state.appendArguments(event.functionCallArgumentsDelta().get().itemId(),
                    event.functionCallArgumentsDelta().get().delta());
            } else if (event.completed().isPresent()) {
                state.done = true;
                emitTerminal(state, event.completed().get().response(), sink);
                sink.complete();
            } else if (event.incomplete().isPresent()) {
                state.done = true;
                emitTerminal(state, event.incomplete().get().response(), sink);
                sink.complete();
            } else if (event.failed().isPresent()) {
                state.done = true;
                Response failed = event.failed().get().response();
                String message = failed.error().isPresent()
                    ? failed.error().get().message()
                    : "Responses provider request failed";
                sink.error(new IllegalStateException(message));
            } else if (event.error().isPresent()) {
                ResponseErrorEvent error = event.error().get();
                String message = error.message() != null ? error.message() : "Responses provider error";
                state.done = true;
                sink.error(new IllegalStateException(message));
            }
        }

        /**
         * Emits one streaming chunk carrying only the newest visible or
         * thinking delta. The turn engine accumulates chunk content itself,
         * so cumulative text must never be re-emitted (it would duplicate).
         */
        private static ChatResponse deltaChunk(String textDelta, String reasoningDelta) {
            AssistantMessage.Builder builder = AssistantMessage.builder()
                .content(textDelta == null ? "" : textDelta);
            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                builder.properties(Map.of("reasoningContent", reasoningDelta));
            }
            return new ChatResponse(List.of(new Generation(builder.build())));
        }

        /**
         * Emits the terminal chunk for a finished stream. Visible and
         * thinking text was already delivered as deltas, so the terminal chunk
         * carries only the authoritative tool calls plus finish/usage
         * metadata. For text-only responses no terminal chunk is emitted:
         * the last delta chunk is already non-empty, which keeps the turn
         * engine from treating the response as empty and repairing.
         */
        private void emitTerminal(StreamState state, Response response, SynchronousSink<ChatResponse> sink) {
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            for (ResponseOutputItem item : response.output()) {
                if (item.functionCall().isPresent()) {
                    ResponseFunctionToolCall call = item.functionCall().get();
                    calls.add(new AssistantMessage.ToolCall(
                        call.callId(), "function",
                        restoreName(state.nameMap, call.name()), call.arguments()));
                }
            }
            if (calls.isEmpty()) {
                return;
            }
            AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(calls)
                .build();
            Generation generation = new Generation(
                message, ChatGenerationMetadata.builder()
                    .finishReason(finishReason(response))
                    .build());
            ChatResponseMetadata.Builder metadataBuilder = ChatResponseMetadata.builder()
                .id(response.id())
                .model(config.remoteModelName());
            if (response.usage().isPresent()) {
                metadataBuilder.usage(usage(response.usage().get()));
            }
            sink.next(new ChatResponse(List.of(generation), metadataBuilder.build()));
        }
    }

    /** Mutable accumulation state for one streaming response. */
    private static final class StreamState implements AutoCloseable {

        private final StreamResponse<ResponseStreamEvent> stream;
        private final Map<String, String> nameMap;
        private final Map<String, ToolCallAccumulator> calls = new LinkedHashMap<>();
        private boolean done;

        private StreamState(StreamResponse<ResponseStreamEvent> stream, Map<String, String> nameMap) {
            this.stream = stream;
            this.nameMap = nameMap;
        }

        private void startCall(String itemId, String name) {
            calls.putIfAbsent(itemId, new ToolCallAccumulator(itemId, name));
        }

        private void appendArguments(String itemId, String delta) {
            ToolCallAccumulator call = calls.get(itemId);
            if (call != null) {
                call.arguments.append(delta);
            }
        }

        @Override
        public void close() {
            stream.close();
        }
    }

    private static final class ToolCallAccumulator {
        private final String itemId;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();

        private ToolCallAccumulator(String itemId, String name) {
            this.itemId = itemId;
            this.name = name;
        }
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
