package io.mindspice.magenta.core;

import io.mindspice.magenta.core.engine.ContextManager;
import io.mindspice.magenta.core.engine.DefaultPromptAssembler;
import io.mindspice.magenta.core.engine.DefaultTurnEngine;
import io.mindspice.magenta.core.internal.ActiveTurnRegistry;
import io.mindspice.magenta.core.internal.ConcurrentActiveTurnRegistry;
import io.mindspice.magenta.core.internal.ModelRouter;
import io.mindspice.magenta.core.internal.TurnExecutor;
import io.mindspice.magenta.core.internal.DefaultTurnExecutor;
import io.mindspice.magenta.core.persistence.InMemoryInteractionStore;
import io.mindspice.magenta.core.persistence.SqliteConversationMemory;
import io.mindspice.magenta.core.persistence.SqliteInteractionStore;
import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.sqlite.SQLiteDataSource;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnLoopInteractionTest {

    @TempDir
    Path tempDir;

    private final List<DefaultTurnLoop> loops = new ArrayList<>();

    @AfterEach
    void closeLoops() {
        loops.forEach(DefaultTurnLoop::close);
    }

    @Test
    void sendsCurrentUserMessageOnceAndPersistsAssistantResponse() {
        PromptModel model = new PromptModel(List.of(response("answer")));
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        TurnResult result = loop.send(TurnRequest.of("session", "hello"));

        assertEquals("answer", result.message());
        assertEquals(4096, result.contextUsage().maxTokens());
        assertEquals(List.of("hello"), memory.messages.stream()
            .filter(message -> "user".equals(message.role()))
            .map(TurnMessage::text).toList());
        assertEquals(1, model.prompts.size());
        assertEquals(1, model.prompts.getFirst().getUserMessages().stream()
            .filter(message -> "hello".equals(message.getText())).count());
        assertEquals(2, memory.messages.size());
    }

    @Test
    void queuesWhileAwaitingAnswerAndResumesWithOrderedQuestionContext() {
        PromptModel model = new PromptModel(List.of(
            askUserResponse("first question", "second question"),
            response("resumed")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingMetrics metrics = new RecordingMetrics();
        DefaultTurnLoop loop = createLoop(model, memory, interactions,
            new RecordingAudit(), metrics, result -> { });

        TurnResult suspended = loop.send(TurnRequest.of("session", "start"));
        assertEquals(TurnDiagnostic.OutcomeKind.SUSPENDED, suspended.diagnostic().outcomeKind());
        assertEquals(List.of("first question", "second question"),
            interactions.load("session").questions());

        TurnResult queued = loop.send(TurnRequest.of("session", "while waiting"));
        assertEquals(TurnDiagnostic.OutcomeKind.QUEUED, queued.diagnostic().outcomeKind());
        assertEquals(1, model.calls.get());
        assertNotNull(loop.claim("session"));

        TurnResult resumed = loop.answer("session", "one combined answer");
        assertEquals("resumed", resumed.message());
        assertEquals(TurnDiagnostic.OutcomeKind.COMPLETED, resumed.diagnostic().outcomeKind());
        assertNull(interactions.load("session"));
        assertEquals(TurnGate.GateState.IDLE, loopGate(loop, "session"));
        assertEquals(2, model.calls.get());

        Prompt resumedPrompt = model.prompts.get(1);
        assertTrue(resumedPrompt.getInstructions().stream()
            .anyMatch(message -> message.getText().contains("Question 1: first question")));
        assertTrue(resumedPrompt.getInstructions().stream()
            .anyMatch(message -> message.getText().contains("Combined answer: one combined answer")));
        assertEquals(1, memory.messages.stream()
            .filter(message -> "one combined answer".equals(message.text()))
            .count());
        assertTrue(metrics.queueDepths.stream().anyMatch(depth -> depth > 0));
    }

    @Test
    void streamEmitsOrderedQuestionAndQueuedTerminalEvents() {
        PromptModel model = new PromptModel(List.of(
            askUserResponse("one", "two")));
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        List<TurnEvent> pendingEvents = loop.stream(
            new TurnRequest("session", "start", null, null, true, Map.of()))
            .collectList().block();
        TurnEvent.QuestionPending pending = pendingEvents.stream()
            .filter(TurnEvent.QuestionPending.class::isInstance)
            .map(TurnEvent.QuestionPending.class::cast)
            .findFirst().orElseThrow();
        assertEquals(List.of("one", "two"), pending.questions());

        List<TurnEvent> queuedEvents = loop.stream(
            new TurnRequest("session", "queued", null, null, true, Map.of()))
            .collectList().block();
        TurnEvent.Queued queued = queuedEvents.stream()
            .filter(TurnEvent.Queued.class::isInstance)
            .map(TurnEvent.Queued.class::cast)
            .findFirst().orElseThrow();
        assertFalse(queued.queuedMessageId().isBlank());
        assertEquals(1, model.calls.get());
    }

    @Test
    void cancellationReleasesGateAndPreservesQueuedMessages() {
        PromptModel model = new PromptModel(List.of(askUserResponse("cancel question")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        DefaultTurnLoop loop = createLoop(model, memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        loop.send(TurnRequest.of("session", "start"));
        loop.send(TurnRequest.of("session", "keep queued"));

        assertTrue(loop.cancelQuestion("session", "caller cancelled"));
        assertEquals(TurnGate.GateState.IDLE, loopGate(loop, "session"));
        assertNull(interactions.load("session"));
        ClaimedMessage claimed = loop.claim("session");
        assertNotNull(claimed);
        assertEquals("keep queued", claimed.message().messageText());
    }

    @Test
    void pendingInteractionRestoresAcrossLoopInstances() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("restart.db"));
        new MigrationRunner(dataSource).apply(List.of());
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        SqliteInteractionStore interactions = new SqliteInteractionStore(dataSource);

        DefaultTurnLoop first = createLoop(new PromptModel(List.of(
            askUserResponse("restart question"))), memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        first.send(TurnRequest.of("restart-session", "start"));

        DefaultTurnLoop restarted = createLoop(new PromptModel(List.of(
            response("after restart"))), memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        TurnResult resumed = restarted.answer("restart-session", "restart answer");

        assertEquals("after restart", resumed.message());
        assertNull(interactions.load("restart-session"));
    }

    @Test
    void streamCancellationReleasesGateAndActiveRegistration() throws Exception {
        PromptModel model = new PromptModel(List.of(response("never used"))) {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                prompts.add(prompt);
                calls.incrementAndGet();
                return Flux.never();
            }
        };
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        reactor.core.Disposable subscription = loop.stream(
            new TurnRequest("session", "stream", null, null, true, Map.of()))
            .subscribe();
        Thread.sleep(100);
        subscription.dispose();
        Thread.sleep(100);

        assertEquals(TurnGate.GateState.IDLE, loopGate(loop, "session"));
        assertTrue(model.calls.get() <= 1);
    }

    @Test
    void lifecycleAuditAndToolMetricsAreInvoked() {
        PromptModel model = new PromptModel(List.of(response("observed")));
        Memory memory = new Memory();
        RecordingAudit audit = new RecordingAudit();
        RecordingMetrics metrics = new RecordingMetrics();
        AtomicInteger lifecycle = new AtomicInteger();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(), audit,
            metrics, result -> lifecycle.incrementAndGet());

        loop.send(TurnRequest.of("session", "observe"));

        assertTrue(audit.userMessages > 0);
        assertTrue(audit.assistantMessages > 0);
        assertTrue(audit.contextUsages > 0);
        assertTrue(metrics.durations > 0);
        assertTrue(metrics.activeUpdates > 0);
        assertEquals(1, lifecycle.get());
    }

    private DefaultTurnLoop createLoop(PromptModel model, ConversationMemory memory,
                                       InteractionStore interactions, AuditWriter audit,
                                       TurnLoopMetrics metrics,
                                       TurnLifecycleListener lifecycle) {
        ToolRegistry tools = new EmptyToolRegistry();
        TokenEstimator estimator = new SimpleEstimator();
        CompactionStrategy compaction = context -> new CompactionResult("summary", 0, 0);
        ContextManager manager = new ContextManager(memory, estimator, compaction, 0);
        TurnExecutor executor = new DefaultTurnExecutor();
        ActiveTurnRegistry activeRegistry = new ConcurrentActiveTurnRegistry();
        DefaultTurnEngine engine = new DefaultTurnEngine.Builder()
            .toolExecution(tools, ToolAccessPolicy.allowAll(), new DefaultToolResultSummarizer(),
                new ToolLoopGuard())
            .contextAssembly(memory, estimator, compaction)
            .contextManager(manager)
            .promptAssembler(new DefaultPromptAssembler("system", List.of()))
            .responseRepair(ctx -> false, (ctx, reason) -> List.of())
            .interaction(context -> new AnswerResult.Continue(), interactions)
            .infrastructure(new FakeRouter(model), executor, activeRegistry,
                new CompositeThinkingExtractor())
            .build();
        DefaultTurnLoop loop = new DefaultTurnLoop(
            engine, new SerialTurnGate(Duration.ofSeconds(30)), new DefaultMessageQueue(new QueueStore()),
            interactions, new Sessions(), memory, activeRegistry,
            executor, tools, alias -> Set.of(ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING), lifecycle, metrics, null, "system", "model", 1, audit);
        loops.add(loop);
        return loop;
    }

    private TurnGate.GateState loopGate(DefaultTurnLoop loop, String sessionId) {
        try {
            var field = DefaultTurnLoop.class.getDeclaredField("gate");
            field.setAccessible(true);
            return ((TurnGate) field.get(loop)).state(sessionId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static ChatResponse askUserResponse(String... questions) {
        String json = "{\"questions\":[\"" + String.join("\",\"", questions) + "\"]}";
        AssistantMessage message = AssistantMessage.builder()
            .content("")
            .toolCalls(List.of(new AssistantMessage.ToolCall(
                "call-1", "function", "ask_user", json)))
            .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private static class PromptModel implements ChatModel {
        private final Deque<ChatResponse> responses;
        final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();

        private PromptModel(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            synchronized (responses) {
                return responses.isEmpty() ? response("default") : responses.removeFirst();
            }
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    private static final class FakeRouter implements ModelRouter {
        private final ChatModel model;

        private FakeRouter(ChatModel model) {
            this.model = model;
        }

        @Override public ChatModel springAiChatModel(String alias) { return model; }
        @Override public String remoteModelName(String alias) { return alias; }
        @Override public String selectModelAlias(String requested, String stored) {
            return requested != null && !requested.isBlank() ? requested : "model";
        }
        @Override public int contextLength(String alias) { return 4096; }
        @Override public ToolCallingChatOptions toolCallingOptions(String alias, List<ToolCallback> tools) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public ToolCallingChatOptions basicChatOptions(String alias) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public boolean supportsStreaming(String alias) { return true; }
        @Override public void closeChatModels() { }
    }

    private static final class Memory implements ConversationMemory {
        private final List<TurnMessage> messages = new CopyOnWriteArrayList<>();
        @Override public List<TurnMessage> getMessages(String sessionId) { return List.copyOf(messages); }
        @Override public void addMessages(String sessionId, List<TurnMessage> messages) { this.messages.addAll(messages); }
        @Override public void clear(String sessionId) { messages.clear(); }
    }

    private static final class Sessions implements SessionStore {
        private final Map<String, ConversationSession> sessions = new HashMap<>();
        @Override public void save(ConversationSession session) { sessions.put(session.sessionId(), session); }
        @Override public ConversationSession findById(String id) { return sessions.get(id); }
        @Override public List<ConversationSession> list(SessionFilter filter) { return List.copyOf(sessions.values()); }
        @Override public void delete(String id) { sessions.remove(id); }
        @Override public void updateTitle(String id, String title) { }
        @Override public void setFavorite(String id, boolean value) { }
        @Override public void setArchived(String id, boolean value) { }
        @Override public void updateModel(String id, String model) { }
    }

    private static final class EmptyToolRegistry implements ToolRegistry {
        @Override public void register(ToolCallback tool) { }
        @Override public void registerFrameworkTool(ToolCallback tool) { }
        @Override public void registerToolBean(Object bean) { }
        @Override public void unregister(String name) { }
        @Override public List<ToolDefinition> definitions() { return List.of(); }
        @Override public List<ToolCallback> resolveForTurn(TurnContext context) { return List.of(); }
    }

    private static final class QueueStore implements MessageQueueStore {
        private final List<PendingMessage> messages = new ArrayList<>();
        private final Map<String, String> claims = new HashMap<>();
        @Override public synchronized void insert(PendingMessage message) { messages.add(message); }
        @Override public synchronized List<PendingMessage> findBySessionId(String id) {
            return messages.stream().filter(m -> m.sessionId().equals(id) && !claims.containsKey(m.messageId())).toList();
        }
        @Override public synchronized PendingMessage findById(String id) {
            return messages.stream().filter(m -> m.messageId().equals(id)).findFirst().orElse(null);
        }
        @Override public synchronized boolean claimAtomic(String id, String token, Instant at) {
            if (claims.containsKey(id)) return false;
            if (findById(id) == null) return false;
            claims.put(id, token); return true;
        }
        @Override public synchronized void deleteByMessageId(String id) { messages.removeIf(m -> m.messageId().equals(id)); claims.remove(id); }
        @Override public synchronized void deleteBySessionId(String id) { messages.removeIf(m -> m.sessionId().equals(id)); }
        @Override public synchronized void releaseClaim(String id) { claims.remove(id); }
        @Override public synchronized List<PendingMessage> findStaleClaims(Instant threshold) { return List.of(); }
    }

    private static final class SimpleEstimator implements TokenEstimator {
        @Override public int estimateTokens(String text) { return text == null ? 0 : Math.max(1, text.length() / 4); }
        @Override public int estimateTokens(TurnMessage message) { return estimateTokens(message.text()) + 1; }
    }

    private static final class RecordingAudit implements AuditWriter {
        int userMessages;
        int assistantMessages;
        int contextUsages;
        @Override public void recordUserMessage(String s, TurnMessage m) { userMessages++; }
        @Override public void recordAssistantMessage(String s, TurnMessage m) { assistantMessages++; }
        @Override public void recordToolExecution(String s, ToolExecutionRecord r) { }
        @Override public void recordContextUsage(String s, ContextUsage u, String m) { contextUsages++; }
        @Override public void recordError(String s, TurnError e) { }
    }

    private static final class RecordingMetrics implements TurnLoopMetrics {
        int durations;
        int activeUpdates;
        final List<Long> queueDepths = new CopyOnWriteArrayList<>();
        @Override public void recordTurnDuration(String model, String outcome, long durationMs) { durations++; }
        @Override public void recordToolCall(String toolName, String status) { }
        @Override public void setActiveTurns(long count) { activeUpdates++; }
        @Override public void setQueueDepth(String sessionId, long depth) { queueDepths.add(depth); }
    }
}
