package io.mindspice.magenta.core.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta.core.DefaultToolRegistry;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import io.mindspice.magenta.core.ToolResult;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end LLM interaction coverage over the Responses protocol adapter:
 * a scripted Responses server drives the real turn engine, so the tests
 * exercise the exact wire messages the adapter sends — including the tool
 * round trip and multi-turn history that previously produced
 * "No tool call found for tool output with call_id ..." failures and
 * duplicated user messages.
 */
class ResponsesInteractionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private ScriptedResponsesServer script;
    private TurnLoop loop;
    private int responseCounter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        script = new ScriptedResponsesServer();
        server.createContext("/responses", script);
        server.start();
        Path root = Files.createTempDirectory("responses-interaction-");
        DefaultToolRegistry registry = new DefaultToolRegistry(JSON);
        registry.register(echoTool());
        registry.register(dottedTool());
        loop = TurnLoopBuilder.create()
                .model("resp", new ModelConfig.Responses(
                        "resp", "deepseek-v4-flash",
                        "http://localhost:" + server.getAddress().getPort(),
                        "test-key", 1_048_576, 4, "max"))
                .dataRoot(root)
                .defaultSystemPrompt("You are a helpful assistant.")
                .modelTimeout(Duration.ofSeconds(30))
                .toolRegistry(registry)
                .build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (loop != null) {
            loop.close();
        }
        server.stop(0);
    }

    @Test
    void toolRoundTripFeedsFunctionCallIntoFollowUpRequest() {
        script.script(functionCallItem("echo", "{}", "call_1"));
        script.script(textItem("tool done"));

        TurnResponse.Outcome outcome = loop.send(new TurnRequest(
                UUID.randomUUID().toString(), "use the echo tool", null, null, Map.of()));

        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class, outcome);
        assertEquals("tool done", message.text());

        List<String> requests = script.requestBodies();
        assertEquals(2, requests.size(), "tool round trip needs two model invocations");
        JsonNode followUp = parse(requests.get(1));
        assertTrue(hasFunctionCall(followUp, "call_1", "echo"),
            "follow-up request must contain the function_call item: " + requests.get(1));
        assertTrue(hasFunctionCallOutput(followUp, "call_1"),
            "follow-up request must contain the paired function_call_output: " + requests.get(1));
        assertNoOrphanedOutputs(followUp, requests.get(1));
    }

    @Test
    void toolTurnPersistsCleanConversationWithoutDuplicates() {
        script.script(functionCallItem("echo", "{}", "call_1"));
        script.script(textItem("tool done"));

        loop.send(new TurnRequest("tool-session", "use the echo tool", null, null, Map.of()));

        List<TurnMessage> history = loop.memory().history("tool-session");
        assertTrue(history.size() >= 4, "expected user + tool activity + tool + answer, got " + history);
        assertEquals(1, count(history, TurnMessage.User.class));
        assertEquals(1, count(history, TurnMessage.Tool.class));
        TurnMessage.Assistant toolActivity = (TurnMessage.Assistant) history.stream()
                .filter(TurnMessage.Assistant.class::isInstance)
                .filter(m -> m.metadata().containsKey("tool_calls"))
                .findFirst().orElseThrow();
        assertTrue(toolActivity.metadata().get("tool_calls").contains("call_1"),
            "tool activity must persist the structured call id");
        assertNoDuplicateUserTexts(history);
    }

    @Test
    void secondTurnReplaysPairedToolStateWithoutOrphanedOutputs() {
        script.script(functionCallItem("echo", "{}", "call_1"));
        script.script(textItem("tool done"));
        script.script(textItem("second answer"));

        loop.send(new TurnRequest("multi-session", "use the echo tool", null, null, Map.of()));
        TurnResponse.Outcome second = loop.send(new TurnRequest(
                "multi-session", "now reply plainly", null, null, Map.of()));

        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class, second);
        assertEquals("second answer", message.text());

        List<String> requests = script.requestBodies();
        assertEquals(3, requests.size());
        JsonNode turnThree = parse(requests.get(2));
        assertTrue(hasFunctionCall(turnThree, "call_1", "echo"),
            "history must replay the function_call item: " + requests.get(2));
        assertTrue(hasFunctionCallOutput(turnThree, "call_1"),
            "history must replay the paired function_call_output: " + requests.get(2));
        assertNoOrphanedOutputs(turnThree, requests.get(2));

        List<TurnMessage> history = loop.memory().history("multi-session");
        assertEquals(2, count(history, TurnMessage.User.class),
            "exactly one persisted user message per send");
        assertNoDuplicateUserTexts(history);
    }

    @Test
    void textOnlyConversationPersistsExactlyOneUserEntryPerSend() {
        script.script(textItem("first answer"));
        script.script(textItem("second answer"));

        loop.send(new TurnRequest("text-session", "first", null, null, Map.of()));
        loop.send(new TurnRequest("text-session", "second", null, null, Map.of()));

        List<TurnMessage> history = loop.memory().history("text-session");
        assertEquals(2, count(history, TurnMessage.User.class));
        assertEquals(2, count(history, TurnMessage.Assistant.class));
        assertNoDuplicateUserTexts(history);
        List<String> userTexts = history.stream()
                .filter(TurnMessage.User.class::isInstance)
                .map(TurnMessage.User.class::cast)
                .map(TurnMessage.User::text)
                .toList();
        assertEquals(List.of("first", "second"), userTexts);
    }

    @Test
    void dottedToolNamesAreSanitizedForProviderAndRestoredForEngine() {
        script.script(functionCallItem("event_create", "{}", "call_1"));
        script.script(textItem("created"));

        loop.send(new TurnRequest(
                "dotted-session", "create an event", null, null, Map.of()));

        String firstRequest = script.requestBodies().getFirst();
        assertTrue(firstRequest.contains("\"name\":\"event_create\""),
            "provider tool name must be sanitized (no dots): " + firstRequest);
        assertFalse(firstRequest.contains("\"name\":\"event.create\""),
            "dotted tool name must never reach the provider");

        List<TurnMessage> history = loop.memory().history("dotted-session");
        TurnMessage.Tool tool = (TurnMessage.Tool) history.stream()
                .filter(TurnMessage.Tool.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals("event.create", tool.toolName(),
            "the engine must execute the tool under its original dotted name");
    }

    @Test
    void streamingToolRoundTripWorksAndPersistsOnce() {
        script.script(functionCallItem("echo", "{}", "call_1"));
        script.script(textItem("streamed tool done"));

        List<TurnResponse> responses = loop.stream(new TurnRequest(
                UUID.randomUUID().toString(), "use the echo tool", null, null, Map.of()))
                .collectList().block(Duration.ofSeconds(30));

        assertNotNull(responses);
        TurnResponse.Outcome outcome = responses.stream()
                .filter(TurnResponse.Outcome.class::isInstance)
                .map(TurnResponse.Outcome.class::cast)
                .findFirst().orElseThrow();
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class, outcome);
        assertEquals("streamed tool done", message.text());
        assertTrue(script.requestBodies().size() >= 2, "streaming tool round trip needs two invocations");
    }

    // ── assertions ──

    private static int count(List<TurnMessage> history, Class<? extends TurnMessage> type) {
        return (int) history.stream().filter(type::isInstance).count();
    }

    private static void assertNoDuplicateUserTexts(List<TurnMessage> history) {
        List<String> texts = history.stream()
                .filter(TurnMessage.User.class::isInstance)
                .map(TurnMessage.User.class::cast)
                .map(TurnMessage.User::text)
                .toList();
        assertEquals(texts.stream().distinct().toList(), texts,
            "user messages must not be duplicated in the conversation");
    }

    private static boolean hasFunctionCall(JsonNode body, String callId, String name) {
        for (JsonNode item : body.path("input")) {
            if ("function_call".equals(item.path("type").asText())
                    && callId.equals(item.path("call_id").asText())
                    && name.equals(item.path("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFunctionCallOutput(JsonNode body, String callId) {
        for (JsonNode item : body.path("input")) {
            if ("function_call_output".equals(item.path("type").asText())
                    && callId.equals(item.path("call_id").asText())) {
                return true;
            }
        }
        return false;
    }

    private static void assertNoOrphanedOutputs(JsonNode body, String rawRequest) {
        List<String> callIds = new ArrayList<>();
        for (JsonNode item : body.path("input")) {
            if ("function_call".equals(item.path("type").asText())) {
                callIds.add(item.path("call_id").asText());
            }
        }
        for (JsonNode item : body.path("input")) {
            if ("function_call_output".equals(item.path("type").asText())) {
                String callId = item.path("call_id").asText();
                assertTrue(callIds.contains(callId),
                    "function_call_output " + callId + " has no matching function_call: " + rawRequest);
            }
        }
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new AssertionError("invalid request JSON: " + body, e);
        }
    }

    // ── scripted responses server ──

    private static ToolCallback dottedTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("event.create", "Create an event",
                    "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}");
            }

            @Override
            public ToolResult call(String input) {
                return new ToolResult.Success("event created");
            }
        };
    }

    private static ToolCallback echoTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return new ToolDefinition("echo", "Echo input",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}");
            }

            @Override
            public ToolResult call(String input) {
                return new ToolResult.Success("echoed: " + input);
            }
        };
    }

    private static String functionCallItem(String name, String arguments, String callId) {
        return "{\"type\":\"function_call\",\"id\":\"fc_" + callId + "\",\"call_id\":\"" + callId
            + "\",\"name\":\"" + name + "\",\"arguments\":\"" + arguments + "\"}";
    }

    private static String textItem(String text) {
        return "{\"type\":\"message\",\"id\":\"m_" + Math.abs(text.hashCode()) + "\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"output_text\",\"text\":\"" + text + "\",\"annotations\":[]}]}";
    }

    /** Serves one scripted response per request, blocking or SSE, and records bodies. */
    private final class ScriptedResponsesServer implements com.sun.net.httpserver.HttpHandler {
        private final Deque<List<String>> responses = new ArrayDeque<>();
        private final List<String> requestBodies = new ArrayList<>();

        private void script(String... items) {
            responses.add(List.of(items));
        }

        private List<String> requestBodies() {
            return List.copyOf(requestBodies);
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            boolean streaming = "text/event-stream".equals(
                exchange.getRequestHeaders().getFirst("Accept"));
            List<String> items = responses.isEmpty()
                ? List.of(textItem("default")) : responses.removeFirst();
            String id = "resp_" + (++responseCounter);
            String response = streaming
                ? sseResponse(id, items)
                : blockingResponse(id, items);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",
                streaming ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private String blockingResponse(String id, List<String> items) {
            return "{\"id\":\"" + id + "\",\"object\":\"response\",\"status\":\"completed\","
                + "\"model\":\"deepseek-v4-flash\",\"output\":[" + String.join(",", items) + "],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}";
        }

        private String sseResponse(String id, List<String> items) {
            StringBuilder events = new StringBuilder();
            int sequence = 1;
            events.append(raw("response.created", sequence++,
                "\"response\":{\"id\":\"" + id + "\",\"object\":\"response\",\"status\":\"in_progress\"}"));
            int outputIndex = 0;
            for (String item : items) {
                if (item.contains("\"type\":\"function_call\"")) {
                    events.append(raw("response.output_item.added", sequence++,
                        "\"output_index\":" + outputIndex + ",\"item\":" + item));
                    events.append(raw("response.function_call_arguments.delta", sequence++,
                        "\"item_id\":\"fc_0\",\"output_index\":" + outputIndex
                            + ",\"delta\":\"{}\""));
                } else {
                    events.append(raw("response.output_item.added", sequence++,
                        "\"output_index\":" + outputIndex + ",\"item\":" + item));
                    events.append(raw("response.output_text.delta", sequence++,
                        "\"item_id\":\"m_0\",\"output_index\":" + outputIndex
                            + ",\"content_index\":0,\"delta\":\"" + deltaOf(item) + "\""));
                }
                outputIndex++;
            }
            events.append(raw("response.completed", sequence,
                "\"response\":{\"id\":\"" + id + "\",\"object\":\"response\",\"status\":\"completed\","
                + "\"model\":\"deepseek-v4-flash\",\"output\":[" + String.join(",", items) + "],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15}}"));
            return events.toString();
        }

        private static String raw(String type, int sequence, String fields) {
            return "data: {\"type\":\"" + type + "\",\"sequence_number\":" + sequence
                + "," + fields + "}\n\n";
        }

        private static String deltaOf(String textItem) {
            int start = textItem.indexOf("\"text\":\"") + "\"text\":\"".length();
            int end = textItem.indexOf("\"", start);
            return textItem.substring(start, end);
        }
    }
}
