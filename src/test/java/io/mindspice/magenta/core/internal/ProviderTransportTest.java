package io.mindspice.magenta.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import io.mindspice.magenta.core.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderTransportTest {
    private HttpServer server;
    private final Map<String, String> bodies = new LinkedHashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void directAdaptersSendConfiguredEndpointKeyModelAndReturnResponses() {
        String root = "http://localhost:" + server.getAddress().getPort();
        LinkedHashMap<String, ModelConfig> configs = new LinkedHashMap<>();
        configs.put("ollama", new ModelConfig.Ollama(
            "ollama", "llama", root, null, 4096, 0));
        configs.put("openai", new ModelConfig.OpenAiCompatible(
            "openai", "gpt", root + "/v1", "open-key", 4096, 0, null));
        configs.put("deepseek", new ModelConfig.DeepSeek(
            "deepseek", "deep", root + "/v1", "deep-key", 4096, 0));

        DefaultModelRouter router = new DefaultModelRouter(configs);
        try {
            assertEquals("ok", text(router, "ollama"));
            assertEquals("ok", text(router, "openai"));
            assertEquals("ok", text(router, "deepseek"));
        } finally {
            router.closeChatModels();
        }

        assertTrue(bodies.keySet().stream().anyMatch(path -> path.contains("/api/chat")));
        assertTrue(bodies.keySet().stream().filter(path -> path.contains("/v1/chat/completions"))
            .allMatch(path -> bodies.get(path).contains("\"messages\"")));
        assertTrue(bodies.keySet().stream().anyMatch(path -> path.contains("Bearer open-key")));
        assertTrue(bodies.keySet().stream().anyMatch(path -> path.contains("Bearer deep-key")));
        assertTrue(bodies.values().stream().anyMatch(body -> body.contains("\"model\":\"gpt\"")));
        assertTrue(bodies.values().stream().anyMatch(body -> body.contains("\"model\":\"deep\"")));
    }

    @Test
    void openAiCompatibleAdapterStreamsFromDeterministicServer() {
        String root = "http://localhost:" + server.getAddress().getPort();
        DefaultModelRouter router = new DefaultModelRouter(Map.of("openai",
            new ModelConfig.OpenAiCompatible(
                "openai", "gpt", root + "/v1", "open-key", 4096, 0, null)));
        try {
            router.springAiChatModel("openai")
                .stream(new Prompt("hello")).collectList().block();
            assertTrue(bodies.values().stream().anyMatch(body -> body.contains("\"stream\":true")));
        } finally {
            router.closeChatModels();
        }
    }

    @Test
    void responsesAdapterSendsResponsesProtocolWithInstructionsAndTools() {
        String root = "http://localhost:" + server.getAddress().getPort();
        DefaultModelRouter router = new DefaultModelRouter(Map.of("responses",
            new ModelConfig.Responses(
                "responses", "deepseek-v4-flash", root, "resp-key",
                1_048_576, 4, "max")));
        try {
            Prompt prompt = new Prompt(List.of(
                new org.springframework.ai.chat.messages.SystemMessage("Be helpful."),
                new org.springframework.ai.chat.messages.UserMessage("hello")),
                router.toolCallingOptions("responses", List.of(echoTool())));
            assertEquals("ok", text(router, "responses", prompt));
        } finally {
            router.closeChatModels();
        }

        Map.Entry<String, String> request = bodies.entrySet().stream()
            .filter(entry -> entry.getKey().contains("/responses"))
            .findFirst().orElseThrow();
        String body = request.getValue();
        assertTrue(request.getKey().contains("Bearer resp-key"));
        assertTrue(body.contains("\"instructions\":\"Be helpful.\""));
        assertTrue(body.contains("\"model\":\"deepseek-v4-flash\""));
        assertTrue(body.contains("\"reasoning\""));
        assertTrue(body.contains("\"effort\":\"max\""));
        assertTrue(body.contains("\"tools\""));
        assertTrue(body.contains("\"name\":\"echo\""));
    }

    @Test
    void responsesAdapterStreamsReasoningTextAndFinalToolCalls() {
        String root = "http://localhost:" + server.getAddress().getPort();
        DefaultModelRouter router = new DefaultModelRouter(Map.of("responses",
            new ModelConfig.Responses(
                "responses", "deepseek-v4-flash", root, "resp-key",
                1_048_576, 4, "max")));
        try {
            List<ChatResponse> chunks = router.springAiChatModel("responses")
                .stream(new Prompt("hello")).collectList().block();
            assertTrue(chunks.size() >= 3, "expected reasoning, text, and terminal chunks");
            boolean reasoningSeen = chunks.stream().anyMatch(chunk -> {
                var output = chunk.getResult().getOutput();
                return output.getMetadata() != null
                    && "think".equals(output.getMetadata().get("reasoningContent"));
            });
            assertTrue(reasoningSeen, "reasoning deltas must surface reasoningContent metadata");
            String accumulated = chunks.stream()
                .map(chunk -> chunk.getResult().getOutput().getText())
                .reduce("", String::concat);
            assertEquals("ok", accumulated, "deltas must accumulate to the full visible text");
            var finalOutput = chunks.getLast().getResult().getOutput();
            assertTrue(finalOutput.getToolCalls() != null
                && finalOutput.getToolCalls().size() == 1, "terminal chunk must carry tool calls");
            assertEquals("echo", finalOutput.getToolCalls().getFirst().name());
            assertEquals("{}", finalOutput.getToolCalls().getFirst().arguments());
        } finally {
            router.closeChatModels();
        }
    }

    private static ToolCallback echoTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("echo", "Echo the input text",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
            }

            @Override
            public ToolResult call(String input) {
                return new ToolResult.Success(input);
            }
        };
    }

    private String text(DefaultModelRouter router, String alias) {
        ChatResponse response = router.springAiChatModel(alias).call(new Prompt("hello"));
        return response.getResult().getOutput().getText();
    }

    private String text(DefaultModelRouter router, String alias, Prompt prompt) {
        ChatResponse response = router.springAiChatModel(alias).call(prompt);
        return response.getResult().getOutput().getText();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.put(path + " " + exchange.getRequestHeaders().getFirst("Authorization"), body);
        boolean streaming = body.contains("\"stream\":true") || "text/event-stream".equals(
            exchange.getRequestHeaders().getFirst("Accept"));
        String response = responsesResponse(path, body, streaming);
        if (response == null) {
            response = path.contains("/api/chat")
                ? "{\"model\":\"llama\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}"
                : streaming
                    ? "data: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\\n\\ndata: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\\n\\ndata: [DONE]\\n\\n"
                    : "{\"id\":\"id\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        }
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String responsesResponse(String path, String body, boolean streaming) {
        if (!path.endsWith("/responses")) {
            return null;
        }
        if (!streaming) {
            return "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"deep\","
                + "\"output\":[{\"type\":\"message\",\"id\":\"m1\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\",\"annotations\":[]}]}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}";
        }
        String created = "data: {\"type\":\"response.created\",\"sequence_number\":1,"
            + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"in_progress\"}}\n\n";
        String reasoning = "data: {\"type\":\"response.reasoning_text.delta\","
            + "\"sequence_number\":2,\"item_id\":\"rs_1\",\"output_index\":0,\"content_index\":0,\"delta\":\"think\"}\n\n";
        String textDelta = "data: {\"type\":\"response.output_text.delta\","
            + "\"sequence_number\":3,\"item_id\":\"m1\",\"output_index\":1,\"content_index\":0,\"delta\":\"ok\"}\n\n";
        String completed = "data: {\"type\":\"response.completed\",\"sequence_number\":4,"
            + "\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"deep\","
            + "\"output\":["
            + "{\"type\":\"reasoning\",\"id\":\"rs_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"think\"}],\"content\":[]},"
            + "{\"type\":\"message\",\"id\":\"m1\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"output_text\",\"text\":\"ok\",\"annotations\":[]}]},"
            + "{\"type\":\"function_call\",\"id\":\"fc_1\",\"call_id\":\"call_1\",\"name\":\"echo\",\"arguments\":\"{}\"}"
            + "],\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}}\n\n";
        return created + reasoning + textDelta + completed;
    }
}
