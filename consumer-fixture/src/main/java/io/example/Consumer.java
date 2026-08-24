package io.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.EndpointType;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.NoticeKind;
import io.mindspice.magenta.core.QueueReason;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolActivityStatus;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.TurnResponseRenderer;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Executable consumer smoke fixture with no Spring Boot or application context. */
public final class Consumer {

    private static final Instant SAMPLE_TIME = Instant.parse("2026-08-24T13:49:27Z");

    private Consumer() {
    }

    public static TurnLoop create(Path dataRoot, String endpoint) {
        return builder(endpoint).dataRoot(dataRoot).build();
    }

    public static TurnLoop create(DataSource dataSource, String endpoint) {
        return builder(endpoint).dataSource(dataSource).build();
    }

    public static void main(String[] args) throws Exception {
        patternMatchProtocol();

        Path root = Files.createTempDirectory("magenta-consumer-");
        try (FixtureServer server = new FixtureServer();
             TurnLoop fileBacked = create(root, server.endpoint())) {
            exerciseFileBackedLoop(fileBacked);

            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + root.resolve("caller-owned.db"));
            try (TurnLoop callerOwned = create(dataSource, server.endpoint())) {
                exerciseCallerOwnedLoop(callerOwned);
            }
        }

        System.out.println("consumer-ok");
    }

    private static TurnLoopBuilder builder(String endpoint) {
        return TurnLoopBuilder.create()
            .model("fixture", new ModelConfig(
                "fixture", "fixture-model", endpoint + "/v1", "fixture-key",
                EndpointType.OPENAI_COMPATIBLE, 4096, 0));
    }

    private static void exerciseFileBackedLoop(TurnLoop loop) {
        TurnResponse.Message direct = requireType(
            loop.send(TurnRequest.of("direct-session", "direct request")),
            TurnResponse.Message.class);
        require(direct.text().contains("fixture"), "direct message text");

        List<TurnResponse> streamed = Objects.requireNonNull(loop.stream(
                TurnRequest.of("stream-session", "stream request"))
            .collectList()
            .block(Duration.ofSeconds(5)), "stream result");
        requireOneOutcome(streamed);
        requireType(streamed.getLast(), TurnResponse.Message.class);
        require(streamed.getFirst() instanceof TurnResponse.Started,
            "stream starts with Started");

        TurnResponse.Questions questions = requireType(
            loop.send(TurnRequest.of("question-session", "ask a question")),
            TurnResponse.Questions.class);
        require(loop.pendingQuestions("question-session").orElseThrow().equals(questions),
            "pending question snapshot");

        TurnResponse.Message answered = requireType(loop.answer(
            "question-session", questions.interactionId(), List.of("blue")),
            TurnResponse.Message.class);
        require(answered.execution().origin().equals(
            new ExecutionOrigin.Answer(questions.interactionId())),
            "answer origin");

        TurnResponse.Queued receipt = loop.enqueue(
            TurnRequest.of("queue-session", "queued request"));
        require(receipt.reason() == QueueReason.EXPLICIT, "explicit queue reason");
        List<TurnResponse> drained = Objects.requireNonNull(loop.drain("queue-session")
            .collectList()
            .block(Duration.ofSeconds(5)), "drain result");
        requireOneOutcome(drained);
        TurnResponse.Message drainedMessage = requireType(drained.getLast(), TurnResponse.Message.class);
        require(drainedMessage.execution().origin().equals(
            new ExecutionOrigin.Queued(receipt.messageId())), "queued origin");
    }

    private static void exerciseCallerOwnedLoop(TurnLoop loop) {
        TurnResponse.Queued receipt = loop.enqueue(
            TurnRequest.of("data-source-session", "queued through DataSource"));
        List<TurnResponse> drained = Objects.requireNonNull(loop.drain("data-source-session")
            .collectList()
            .block(Duration.ofSeconds(5)), "DataSource drain result");
        requireOneOutcome(drained);
        TurnResponse.Message message = requireType(drained.getLast(), TurnResponse.Message.class);
        require(message.execution().origin().equals(
            new ExecutionOrigin.Queued(receipt.messageId())), "DataSource queued origin");
    }

    private static void patternMatchProtocol() {
        String interactionId = UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        ContextUsage usage = new ContextUsage(10, 100, 4096);
        ToolActivityData activity = new ToolActivityData(
            "lookup", "call-1", ToolActivityStatus.COMPLETED, "one result");
        ExecutionInfo direct = execution(new ExecutionOrigin.Direct(), usage, activity);
        ExecutionInfo answer = execution(new ExecutionOrigin.Answer(interactionId), usage, activity);

        List<TurnResponse> samples = List.of(
            new TurnResponse.Started("session", "turn", SAMPLE_TIME,
                new ExecutionOrigin.Direct(), "fixture", usage),
            new TurnResponse.ContentChunk("session", "turn", SAMPLE_TIME, "visible"),
            new TurnResponse.ThinkingChunk("session", "turn", SAMPLE_TIME, "private"),
            new TurnResponse.ToolActivity("session", "turn", SAMPLE_TIME, activity),
            new TurnResponse.Notice("session", "turn", SAMPLE_TIME,
                NoticeKind.COMPACTION, "compacted"),
            new TurnResponse.Message(direct, "message"),
            new TurnResponse.Questions(direct, interactionId, List.of("question")),
            new TurnResponse.Queued("session", messageId, SAMPLE_TIME, QueueReason.EXPLICIT),
            new TurnResponse.Interrupted(direct, "interrupted"),
            new TurnResponse.TimedOut(direct, "timed out"),
            new TurnResponse.QuestionsCancelled("session", interactionId,
                SAMPLE_TIME, "cancelled"),
            new TurnResponse.TurnCancelled(answer, "cancelled")
        );

        for (TurnResponse sample : samples) {
            require(!describe(sample).isBlank(), "sealed response description");
        }

        String json = TurnResponseRenderer.json().apply(samples.get(5));
        require(json.contains("\"type\":\"message\""), "message JSON discriminator");
    }

    private static String describe(TurnResponse response) {
        return switch (response) {
            case TurnResponse.Progress progress -> describeProgress(progress);
            case TurnResponse.Outcome outcome -> describeOutcome(outcome);
        };
    }

    private static String describeProgress(TurnResponse.Progress progress) {
        return switch (progress) {
            case TurnResponse.Started started -> "started:" + started.model();
            case TurnResponse.ContentChunk chunk -> "content:" + chunk.text();
            case TurnResponse.ThinkingChunk chunk -> "thinking:" + chunk.text();
            case TurnResponse.ToolActivity activity ->
                "tool:" + activity.activity().toolName();
            case TurnResponse.Notice notice -> "notice:" + notice.kind();
        };
    }

    private static String describeOutcome(TurnResponse.Outcome outcome) {
        return switch (outcome) {
            case TurnResponse.Message message -> "message:" + message.text();
            case TurnResponse.Questions questions ->
                "questions:" + questions.interactionId();
            case TurnResponse.Queued queued -> "queued:" + queued.messageId();
            case TurnResponse.Interrupted interrupted ->
                "interrupted:" + interrupted.reason();
            case TurnResponse.TimedOut timedOut -> "timed_out:" + timedOut.reason();
            case TurnResponse.QuestionsCancelled cancelled ->
                "questions_cancelled:" + cancelled.reason();
            case TurnResponse.TurnCancelled cancelled ->
                "turn_cancelled:" + cancelled.reason();
        };
    }

    private static ExecutionInfo execution(ExecutionOrigin origin,
                                           ContextUsage usage,
                                           ToolActivityData activity) {
        return new ExecutionInfo("session", "turn", origin, SAMPLE_TIME, SAMPLE_TIME,
            usage, List.of(activity));
    }

    private static void requireOneOutcome(List<TurnResponse> responses) {
        require(responses != null && !responses.isEmpty(), "non-empty response stream");
        long outcomes = responses.stream()
            .filter(TurnResponse.Outcome.class::isInstance)
            .count();
        require(outcomes == 1, "exactly one stream outcome");
    }

    private static <T extends TurnResponse> T requireType(TurnResponse response,
                                                            Class<T> type) {
        return type.cast(Objects.requireNonNull(response, "response"));
    }

    private static void require(boolean condition, String description) {
        if (!condition) {
            throw new IllegalStateException("Consumer fixture check failed: " + description);
        }
    }

    private static final class FixtureServer implements AutoCloseable {

        private final HttpServer server;

        private FixtureServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.start();
        }

        private String endpoint() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String request = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
                boolean streaming = request.contains("\"stream\":true");
                boolean asksQuestion = request.contains("ask a question")
                    && !request.contains("blue");
                String response = asksQuestion
                    ? questionResponse()
                    : streaming ? streamingResponse() : messageResponse();
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                    streaming ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        private static String messageResponse() {
            return "{\"id\":\"fixture-id\",\"object\":\"chat.completion\","
                + "\"created\":1,\"model\":\"fixture-model\","
                + "\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\","
                + "\"content\":\"fixture answer\"},"
                + "\"finish_reason\":\"stop\"}]}";
        }

        private static String streamingResponse() {
            return "data: {\"id\":\"fixture-id\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"fixture-model\","
                + "\"choices\":[{\"index\":0,\"delta\":{"
                + "\"role\":\"assistant\",\"content\":\"fixture stream\"},"
                + "\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"fixture-id\",\"object\":\"chat.completion.chunk\","
                + "\"created\":1,\"model\":\"fixture-model\","
                + "\"choices\":[{\"index\":0,\"delta\":{},"
                + "\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        }

        private static String questionResponse() {
            return "{\"id\":\"fixture-question\",\"object\":\"chat.completion\","
                + "\"created\":1,\"model\":\"fixture-model\","
                + "\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\","
                + "\"tool_calls\":[{\"id\":\"call-question\","
                + "\"type\":\"function\",\"function\":{"
                + "\"name\":\"ask_user\","
                + "\"arguments\":\"{\\\"questions\\\":[\\\"Choose a color\\\"]}\"}}]},"
                + "\"finish_reason\":\"tool_calls\"}]}";
        }
    }
}
