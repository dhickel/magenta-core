package io.mindspice.magenta.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ModelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
        configs.put("ollama", new ModelConfig("ollama", "llama", root, null,
            EndpointType.OLLAMA, 4096, 0));
        configs.put("openai", new ModelConfig("openai", "gpt", root + "/v1", "open-key",
            EndpointType.OPENAI_COMPATIBLE, 4096, 0));
        configs.put("deepseek", new ModelConfig("deepseek", "deep", root + "/v1", "deep-key",
            EndpointType.DEEPSEEK, 4096, 0));

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
            new ModelConfig("openai", "gpt", root + "/v1", "open-key",
                EndpointType.OPENAI_COMPATIBLE, 4096, 0)));
        try {
            router.springAiChatModel("openai")
                .stream(new Prompt("hello")).collectList().block();
            assertTrue(bodies.values().stream().anyMatch(body -> body.contains("\"stream\":true")));
        } finally {
            router.closeChatModels();
        }
    }

    private String text(DefaultModelRouter router, String alias) {
        ChatResponse response = router.springAiChatModel(alias).call(new Prompt("hello"));
        return response.getResult().getOutput().getText();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.put(path + " " + exchange.getRequestHeaders().getFirst("Authorization"), body);
        boolean streaming = body.contains("\"stream\":true");
        String response = path.contains("/api/chat")
            ? "{\"model\":\"llama\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"done\":true}"
            : streaming
                ? "data: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":null}]}\\n\\ndata: {\"id\":\"id\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\\n\\ndata: [DONE]\\n\\n"
                : "{\"id\":\"id\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", streaming ? "text/event-stream" : "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
