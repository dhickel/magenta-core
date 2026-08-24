package io.mindspice.magenta.core;

import io.mindspice.magenta.core.ContextManager;
import io.mindspice.magenta.core.DefaultPromptAssembler;
import io.mindspice.magenta.core.DefaultTurnEngine;
import io.mindspice.magenta.core.ActiveTurnRegistry;
import io.mindspice.magenta.core.ConcurrentActiveTurnRegistry;
import io.mindspice.magenta.core.ModelRouter;
import io.mindspice.magenta.core.TurnExecutor;
import io.mindspice.magenta.core.DefaultTurnExecutor;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DefaultTurnLoopInteractionTest {

    @TempDir
    Path tempDir;

    private final List<DefaultTurnLoop> loops = new ArrayList<>();

    @AfterEach
    void closeLoops() {
        loops.forEach(DefaultTurnLoop::close);
    }

    @Test
    void setupFailureAfterGateAcquireDoesNotLeavePhantomActiveTurn() {
        ConversationMemory failingMemory = new ConversationMemory() {
            @Override public List<TurnMessage> getMessages(String sessionId) {
                throw new IllegalStateException("setup read failed");
            }
            @Override public void addMessages(String sessionId, List<TurnMessage> messages) { }
            @Override public void clear(String sessionId) { }
        };
        DefaultTurnLoop loop = createLoop(new PromptModel(List.of(response("unused"))),
            failingMemory, new InMemoryInteractionStore(), new RecordingAudit(),
            new RecordingMetrics(), result -> { });

        assertThrows(TurnLoopException.class,
            () -> loop.send(TurnRequest.of("session", "hello")));
        assertEquals(TurnGate.GateState.IDLE, loopGate(loop, "session"));
        assertNull(activeTurnId(loop, "session"));
    }

    @Test
    void callerSuppliedInteractionStoreIsSharedByFacadeAndEngine() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("supplied-interactions.db"));
        new MigrationRunner(dataSource).apply(List.of());
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        DefaultTurnLoop loop = createLoop(new PromptModel(List.of(
            askUserResponse("same store"))), memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        loop.send(TurnRequest.of("session", "start"));
        assertNotNull(interactions.load("session"));
        assertEquals(TurnGate.GateState.AWAITING_ANSWER, loopGate(loop, "session"));
        assertTrue(loop.cancelQuestion("session", "done"));
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
    void answeredClaimAfterRestartResumesIdempotently() {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("claimed-answer-restart.db"));
        new MigrationRunner(dataSource).apply(List.of());
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        SqliteInteractionStore interactions = new SqliteInteractionStore(dataSource);
        interactions.save("claimed-session", new QuestionPendingContext(
            "claimed-session", "old-turn", List.of("question"), "model", Map.of()));
        assertTrue(interactions.answer("claimed-session", "durable answer"));
        PromptModel model = new PromptModel(List.of(response("after clear")));
        DefaultTurnLoop restarted = createLoop(model, memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        assertEquals("after clear",
            restarted.answer("claimed-session", "durable answer").message());
        assertNull(interactions.load("claimed-session"));
    }

    @Test
    void durableAnswerClaimRejectsASecondLoopWhileResumeIsExecuting() throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("answer-race.db"));
        new MigrationRunner(dataSource).apply(List.of());
        SqliteConversationMemory memory = new SqliteConversationMemory(dataSource);
        SqliteInteractionStore interactions = new SqliteInteractionStore(dataSource);
        BlockingPromptModel winnerModel = new BlockingPromptModel(List.of(
            askUserResponse("answer once"), response("winner")), 2);
        DefaultTurnLoop winner = createLoop(winnerModel, memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        winner.send(TurnRequest.of("race-session", "start"));

        PromptModel loserModel = new PromptModel(List.of(response("duplicate")));
        DefaultTurnLoop loser = createLoop(loserModel, memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        CompletableFuture<TurnResult> winningAnswer = CompletableFuture.supplyAsync(
            () -> winner.answer("race-session", "first answer"));
        assertTrue(winnerModel.entered.await(2, TimeUnit.SECONDS));

        assertEquals(QuestionPendingContext.State.ANSWERED,
            interactions.load("race-session").state());
        assertThrows(TurnLoopException.class,
            () -> loser.answer("race-session", "duplicate answer"));
        assertFalse(loser.cancelQuestion("race-session", "too late"));
        assertEquals(TurnDiagnostic.OutcomeKind.QUEUED,
            loser.send(TurnRequest.of("race-session", "while answer runs"))
                .diagnostic().outcomeKind());
        assertEquals(0, loserModel.calls.get());

        winnerModel.release.countDown();
        assertEquals("winner", winningAnswer.get(2, TimeUnit.SECONDS).message());
        assertNull(interactions.load("race-session"));
    }

    @Test
    void observabilityCompletesBeforeTheSessionGateIsReleased() throws Exception {
        PromptModel model = new PromptModel(List.of(response("first"), response("second")));
        Memory memory = new Memory();
        CountDownLatch observing = new CountDownLatch(1);
        CountDownLatch releaseObservation = new CountDownLatch(1);
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> {
                observing.countDown();
                try {
                    assertTrue(releaseObservation.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
        CompletableFuture<TurnResult> first = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(observing.await(2, TimeUnit.SECONDS));

        CompletableFuture.runAsync(() -> assertThrows(TurnLoopException.class,
            () -> loop.send(TurnRequest.of("session", "second"))))
            .get(1, TimeUnit.SECONDS);
        assertEquals(1, model.calls.get());
        releaseObservation.countDown();
        assertEquals("first", first.get(2, TimeUnit.SECONDS).message());
    }

    @Test
    void activeSendDoesNotHoldSessionMonitorAndStillRejectsASecondTurn() throws Exception {
        BlockingPromptModel model = new BlockingPromptModel(List.of(response("done")), 1);
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        CompletableFuture<TurnResult> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        CompletableFuture<Void> rejected = CompletableFuture.runAsync(() ->
            assertThrows(TurnLoopException.class,
                () -> loop.send(TurnRequest.of("session", "second"))));
        rejected.get(1, TimeUnit.SECONDS);

        model.release.countDown();
        assertEquals("done", active.get(2, TimeUnit.SECONDS).message());
    }

    @Test
    void interruptAndClearReturnWhileModelCallIsBlockedAndCleanupWins() throws Exception {
        BlockingPromptModel model = new BlockingPromptModel(List.of(response("late")), 1);
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        CompletableFuture<TurnResult> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));
        String turnId = activeTurnId(loop, "session");

        assertTrue(loop.interrupt(turnId, "test interrupt"));
        CompletableFuture.runAsync(() -> loop.clearSession("session"))
            .get(1, TimeUnit.SECONDS);
        model.release.countDown();
        assertEquals(TurnDiagnostic.OutcomeKind.INTERRUPTED,
            active.get(2, TimeUnit.SECONDS).diagnostic().outcomeKind());
        assertTrue(memory.messages.isEmpty());
        assertEquals(TurnGate.GateState.IDLE, loopGate(loop, "session"));
    }

    @Test
    void deleteReturnsWhileModelCallIsBlockedAndPreventsLateSessionResurrection() throws Exception {
        BlockingPromptModel model = new BlockingPromptModel(List.of(response("late")), 1);
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { });
        CompletableFuture<TurnResult> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        CompletableFuture.runAsync(() -> loop.sessions().delete("session"))
            .get(1, TimeUnit.SECONDS);
        model.release.countDown();
        active.get(2, TimeUnit.SECONDS);
        assertNull(loop.sessions().get("session"));
        assertTrue(memory.messages.isEmpty());
    }

    @Test
    void closeHonorsZeroDrainWithoutWaitingForTheSessionMonitor() throws Exception {
        BlockingPromptModel model = new BlockingPromptModel(List.of(response("late")), 1);
        Memory memory = new Memory();
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { },
            new DefaultTurnExecutor(), 0);
        CompletableFuture<TurnResult> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        CompletableFuture.runAsync(loop::close).get(1, TimeUnit.SECONDS);
        model.release.countDown();
        active.handle((result, failure) -> null).get(2, TimeUnit.SECONDS);
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
    void subscriberCancellationFencesLatePersistenceBeforeASecondTurnStarts() throws Exception {
        CancellationIgnoringStreamingModel model = new CancellationIgnoringStreamingModel();
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        DefaultTurnLoop loop = createLoop(model, memory, interactions,
            new RecordingAudit(), new RecordingMetrics(), result -> { });

        Disposable first = loop.stream(new TurnRequest(
            "session", "cancelled request", null, null, true, Map.of())).subscribe();
        assertTrue(model.firstEntered.await(2, TimeUnit.SECONDS));
        first.dispose();
        awaitCondition(() -> loopGate(loop, "session") == TurnGate.GateState.IDLE);
        assertEquals(0, activeTurnCount(loop));

        List<TurnEvent> second = loop.stream(new TurnRequest(
            "session", "fresh request", null, null, true, Map.of()))
            .collectList().block(Duration.ofSeconds(2));
        assertTrue(second.stream().anyMatch(TurnEvent.Completed.class::isInstance));

        model.releaseFirst.countDown();
        assertTrue(model.firstExited.await(2, TimeUnit.SECONDS));
        awaitCondition(() -> model.calls.get() == 2 && activeTurnCount(loop) == 0);

        assertEquals(List.of("fresh request", "fresh"), memory.messages.stream()
            .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
            .map(TurnMessage::text)
            .toList());
        assertNull(interactions.load("session"));
        assertFalse(memory.messages.stream().anyMatch(message -> message.metadata() != null
            && "turnloop.pending_interaction".equals(
                message.metadata().get("turnloop.persistence_type"))));
    }

    @Test
    void timeoutFencesBlockedTurnAndItsLatePersistenceBeforeSuccessor() throws Exception {
        TimeoutIgnoringPromptModel model = new TimeoutIgnoringPromptModel();
        Memory memory = new Memory();
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T00:00:00Z"));
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofMinutes(1), Duration.ofDays(1), clock);
        CountDownLatch timedOut = new CountDownLatch(1);
        gate.setTimeoutCallback(sessionId -> timedOut.countDown());
        DefaultTurnLoop loop = createLoop(model, memory, new InMemoryInteractionStore(),
            new RecordingAudit(), new RecordingMetrics(), result -> { },
            new DefaultTurnExecutor(), 1, gate, true);

        CompletableFuture<TurnResult> first = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "timed out request")));
        assertTrue(model.firstEntered.await(2, TimeUnit.SECONDS));
        clock.advance(Duration.ofMinutes(2));
        gate.scanForTimeouts();
        assertTrue(timedOut.await(2, TimeUnit.SECONDS));
        awaitCondition(() -> loopGate(loop, "session") == TurnGate.GateState.IDLE);
        assertEquals(0, activeTurnCount(loop));

        CompletableFuture<TurnResult> successor = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "fresh request")));
        assertTrue(model.secondEntered.await(2, TimeUnit.SECONDS));

        model.releaseFirst.countDown();
        TurnResult timedOutResult = first.get(2, TimeUnit.SECONDS);
        assertEquals(TurnDiagnostic.OutcomeKind.TIMED_OUT,
            timedOutResult.diagnostic().outcomeKind());
        assertTrue(model.firstExited.await(2, TimeUnit.SECONDS));
        assertEquals(TurnGate.GateState.EXECUTING, loopGate(loop, "session"));

        model.releaseSecond.countDown();
        assertEquals("fresh", successor.get(2, TimeUnit.SECONDS).message());
        assertEquals(List.of("fresh request", "fresh"), memory.messages.stream()
            .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
            .map(TurnMessage::text)
            .toList());
        assertFalse(memory.messages.stream().anyMatch(message -> "late".equals(message.text())));
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
        return createLoop(model, memory, interactions, audit, metrics, lifecycle,
            new DefaultTurnExecutor(), 1);
    }

    private DefaultTurnLoop createLoop(PromptModel model, ConversationMemory memory,
                                       InteractionStore interactions, AuditWriter audit,
                                       TurnLoopMetrics metrics,
                                       TurnLifecycleListener lifecycle,
                                       TurnExecutor executor, long drainTimeoutSeconds) {
        return createLoop(model, memory, interactions, audit, metrics, lifecycle,
            executor, drainTimeoutSeconds, new SerialTurnGate(Duration.ofSeconds(30)), true);
    }

    private DefaultTurnLoop createLoop(PromptModel model, ConversationMemory memory,
                                       InteractionStore interactions, AuditWriter audit,
                                       TurnLoopMetrics metrics,
                                       TurnLifecycleListener lifecycle,
                                       TurnExecutor executor, long drainTimeoutSeconds,
                                       SerialTurnGate gate, boolean ownsGate) {
        ToolRegistry tools = new EmptyToolRegistry();
        TokenEstimator estimator = new SimpleEstimator();
        CompactionStrategy compaction = context -> new CompactionResult("summary", 0, 0);
        ContextManager manager = new ContextManager(memory, estimator, compaction, 0);
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
            engine, gate, new DefaultMessageQueue(new QueueStore()),
            interactions, new Sessions(), memory, activeRegistry,
            executor, tools, alias -> Set.of(ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING), lifecycle, metrics, null, "system", "model",
            drainTimeoutSeconds, audit, ownsGate);
        loops.add(loop);
        return loop;
    }

    private String activeTurnId(DefaultTurnLoop loop, String sessionId) {
        try {
            var field = DefaultTurnLoop.class.getDeclaredField("activeTurnIds");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> active = (Map<String, String>) field.get(loop);
            return active.get(sessionId);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private int activeTurnCount(DefaultTurnLoop loop) {
        try {
            var field = DefaultTurnLoop.class.getDeclaredField("activeTurnRegistry");
            field.setAccessible(true);
            return ((ActiveTurnRegistry) field.get(loop)).activeTurns().size();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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

    private static final class CancellationIgnoringStreamingModel extends PromptModel {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstExited = new CountDownLatch(1);

        private CancellationIgnoringStreamingModel() {
            super(List.of());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            int call = calls.incrementAndGet();
            if (call != 1) {
                return Flux.just(response("fresh"));
            }
            return Flux.create(sink -> {
                firstEntered.countDown();
                awaitUninterruptibly(releaseFirst);
                try {
                    sink.next(askUserResponse("late question"));
                    sink.complete();
                } finally {
                    firstExited.countDown();
                }
            });
        }
    }

    private static final class TimeoutIgnoringPromptModel extends PromptModel {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstExited = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSecond = new CountDownLatch(1);

        private TimeoutIgnoringPromptModel() {
            super(List.of());
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstEntered.countDown();
                awaitUninterruptibly(releaseFirst);
                firstExited.countDown();
                return response("late");
            }
            secondEntered.countDown();
            awaitUninterruptibly(releaseSecond);
            return response("fresh");
        }
    }

    private static final class BlockingPromptModel extends PromptModel {
        private final int blockingCall;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingPromptModel(List<ChatResponse> responses, int blockingCall) {
            super(responses);
            this.blockingCall = blockingCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            if (calls.get() + 1 == blockingCall) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("blocking model was not released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("blocking model interrupted", e);
                }
            }
            return super.call(prompt);
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

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean waiting = true;
        while (waiting) {
            try {
                latch.await();
                waiting = false;
            } catch (InterruptedException ignored) {
                // Deliberately model a provider that ignores task interruption.
            }
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
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
