package io.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ToolFailureCategory;
import io.mindspice.magenta.core.ToolResult;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.ModelConfig;
import io.mindspice.magenta.core.NoticeKind;
import io.mindspice.magenta.core.QueueReason;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.TurnLoop;
import io.mindspice.magenta.core.TurnLoopBuilder;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.TurnRequest;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.TurnResponseRenderer;
import io.mindspice.magenta.core.spi.InteractionStore;
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
import java.util.Map;
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
            .model("fixture", ModelConfig.openAiCompatible()
                .alias("fixture")
                .remoteModelName("fixture-model")
                .remoteEndpoint(endpoint + "/v1")
                .apiKey("fixture-key")
                .contextLength(4096)
                .thinkLevel(0)
                .build());
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
        ToolActivityData activity = new ToolActivityData.Completed(
            "lookup", "call-1", "one result");
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
            new TurnResponse.QuestionsMissing("session", interactionId),
            new TurnResponse.QuestionsAlreadyAnswered("session", interactionId),
            new TurnResponse.QuestionsAlreadyCancelled("session", interactionId),
            new TurnResponse.TurnCancelled(answer, "cancelled")
        );

        for (TurnResponse sample : samples) {
            require(!describe(sample).isBlank(), "sealed response description");
        }

        List<TurnMessage> messages = List.of(
            new TurnMessage.System("system", Map.of()),
            new TurnMessage.User("user", Map.of()),
            new TurnMessage.Assistant("assistant", Map.of()),
            new TurnMessage.Tool("lookup", "call-1", "result", Map.of()));
        for (TurnMessage message : messages) {
            require(!describeMessage(message).isBlank(), "sealed message description");
        }

        List<ModelConfig> modelConfigs = List.of(
            ModelConfig.ollama()
                .alias("ollama")
                .remoteModelName("llama")
                .remoteEndpoint("http://localhost:11434")
                .contextLength(4096)
                .thinkLevel(0)
                .build(),
            ModelConfig.openAiCompatible()
                .alias("openai")
                .remoteModelName("gpt")
                .remoteEndpoint("http://localhost:8080/v1")
                .apiKey("open-key")
                .contextLength(4096)
                .thinkLevel(0)
                .build(),
            ModelConfig.deepSeek()
                .alias("deepseek")
                .remoteModelName("deep")
                .remoteEndpoint("https://api.deepseek.test")
                .apiKey("deep-key")
                .contextLength(4096)
                .thinkLevel(0)
                .build(),
            ModelConfig.responses()
                .alias("responses")
                .remoteModelName("gpt-5-codex")
                .remoteEndpoint("https://api.openai.test/v1")
                .apiKey("responses-key")
                .contextLength(4096)
                .thinkLevel(4)
                .reasoningEffort("high")
                .build());
        for (ModelConfig config : modelConfigs) {
            require(!describeModelConfig(config).isBlank(), "sealed model configuration description");
        }

        ToolResult[] toolResults = {
            new ToolResult.Success("tool output"),
            new ToolResult.Failure("tool failed", ToolFailureCategory.EXECUTION_FAILED)
        };
        for (ToolResult result : toolResults) {
            require(!describeToolResult(result).isBlank(), "sealed tool result description");
        }

        InteractionStore.InteractionInfo interactionInfo = new InteractionStore.InteractionInfo(
            interactionId, "session", new TurnResponse.Questions(
                direct, interactionId, List.of("question")), "fixture", null,
            Map.of(), Map.of(), SAMPLE_TIME, SAMPLE_TIME);
        InteractionStore.Interaction[] interactions = {
            new InteractionStore.Interaction.Pending(interactionInfo),
            new InteractionStore.Interaction.Answered(interactionInfo, List.of("answer")),
            new InteractionStore.Interaction.Cancelled(
                interactionInfo, SAMPLE_TIME.plusSeconds(1), "cancelled")
        };
        for (InteractionStore.Interaction interaction : interactions) {
            require(!describeInteraction(interaction).isBlank(),
                "sealed interaction description");
        }

        InteractionStore.MutationResult[] mutations = {
            new InteractionStore.MutationResult.Applied(interactions[1]),
            new InteractionStore.MutationResult.Missing(),
            new InteractionStore.MutationResult.AlreadyAnswered(),
            new InteractionStore.MutationResult.AlreadyCancelled()
        };
        for (InteractionStore.MutationResult mutation : mutations) {
            require(!describeMutation(mutation).isBlank(),
                "sealed mutation description");
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
                "tool:" + describeActivity(activity.activity());
            case TurnResponse.Notice notice -> "notice:" + notice.kind();
        };
    }

    private static String describeToolResult(ToolResult result) {
        return switch (result) {
            case ToolResult.Success success -> "success:" + success.output();
            case ToolResult.Failure failure -> "failure:" + failure.category();
        };
    }

    private static String describeInteraction(InteractionStore.Interaction interaction) {
        return switch (interaction) {
            case InteractionStore.Interaction.Pending pending ->
                "pending:" + pending.interactionId();
            case InteractionStore.Interaction.Answered answered ->
                "answered:" + answered.answers().size();
            case InteractionStore.Interaction.Cancelled cancelled ->
                "cancelled:" + cancelled.reason();
        };
    }

    private static String describeMutation(InteractionStore.MutationResult mutation) {
        return switch (mutation) {
            case InteractionStore.MutationResult.Applied applied ->
                "applied:" + describeInteraction(applied.snapshot());
            case InteractionStore.MutationResult.Missing ignored -> "missing";
            case InteractionStore.MutationResult.AlreadyAnswered ignored -> "answered";
            case InteractionStore.MutationResult.AlreadyCancelled ignored -> "cancelled";
        };
    }

    private static String describeActivity(ToolActivityData activity) {
        return switch (activity) {
            case ToolActivityData.Completed completed ->
                completed.toolName() + ":completed:" + completed.summary();
            case ToolActivityData.Failed failed ->
                failed.toolName() + ":failed:" + failed.category();
        };
    }

    private static String describeMessage(TurnMessage message) {
        return switch (message) {
            case TurnMessage.System system -> "system:" + system.text();
            case TurnMessage.User user -> "user:" + user.text();
            case TurnMessage.Assistant assistant -> "assistant:" + assistant.text();
            case TurnMessage.Tool tool ->
                "tool:" + tool.toolName() + ":" + tool.toolCallId() + ":" + tool.text();
        };
    }

    private static String describeModelConfig(ModelConfig config) {
        return switch (config) {
            case ModelConfig.Ollama ollama -> "ollama:" + ollama.alias();
            case ModelConfig.OpenAiCompatible openAi -> "openai:" + openAi.alias();
            case ModelConfig.DeepSeek deepSeek -> "deepseek:" + deepSeek.alias();
            case ModelConfig.Responses responses -> "responses:" + responses.alias();
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
            case TurnResponse.QuestionsMissing missing ->
                "questions_missing:" + missing.interactionId();
            case TurnResponse.QuestionsAlreadyAnswered answered ->
                "questions_already_answered:" + answered.interactionId();
            case TurnResponse.QuestionsAlreadyCancelled cancelled ->
                "questions_already_cancelled:" + cancelled.interactionId();
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
