package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.AuditWriter;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.persistence.InMemoryInteractionStore;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import io.mindspice.magenta.core.spi.SessionStore;
import io.mindspice.magenta.core.spi.TokenEstimator;
import io.mindspice.magenta.core.spi.ToolAccessPolicy;
import io.mindspice.magenta.core.spi.TurnLifecycleListener;
import io.mindspice.magenta.core.spi.TurnLoopMetrics;
import io.mindspice.magenta.core.spi.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultTurnLoopInteractionTest {

    private final List<DefaultTurnLoop> loops = new ArrayList<>();

    @AfterEach
    void closeLoops() {
        loops.forEach(DefaultTurnLoop::close);
    }

    @Test
    void directAndStreamingExecutionsHaveOneTypedTerminalOutcome() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("<think>consider the request</think>answer"),
            response("<think>consider the request</think>answer")));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle);

        TurnResponse.Message direct = assertInstanceOf(TurnResponse.Message.class,
            loop.send(TurnRequest.of("direct-session", "hello")));
        List<TurnResponse> streamed = loop.stream(
            TurnRequest.of("stream-session", "hello")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(streamed);
        assertEquals(1, streamed.stream().filter(TurnResponse.Outcome.class::isInstance).count());
        TurnResponse.Message streaming = assertInstanceOf(TurnResponse.Message.class,
            streamed.getLast());
        assertEquals("answer", direct.text());
        assertEquals(direct.text(), streaming.text());
        assertEquals(new ExecutionOrigin.Direct(), direct.execution().origin());
        assertEquals(new ExecutionOrigin.Direct(), streaming.execution().origin());
        assertEquals(direct.execution().finalContextUsage(),
            streaming.execution().finalContextUsage());
        assertTrue(streamed.getFirst() instanceof TurnResponse.Started);
        assertEquals(streaming, streamed.getLast());
        assertEquals(2, lifecycle.outcomes.size());
    }

    @Test
    void streamingProgressSeparatesVisibleContentAndThinking() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("<think>private reasoning</think>visible answer")));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle());

        List<TurnResponse> responses = loop.stream(
            TurnRequest.of("session", "show progress")).collectList().block(Duration.ofSeconds(2));

        assertNotNull(responses);
        assertEquals(List.of("visible answer"), responses.stream()
            .filter(TurnResponse.ContentChunk.class::isInstance)
            .map(TurnResponse.ContentChunk.class::cast)
            .map(TurnResponse.ContentChunk::text)
            .toList());
        assertEquals(List.of("private reasoning"), responses.stream()
            .filter(TurnResponse.ThinkingChunk.class::isInstance)
            .map(TurnResponse.ThinkingChunk.class::cast)
            .map(TurnResponse.ThinkingChunk::text)
            .toList());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            responses.getLast());
        assertEquals("visible answer", message.text());
        assertTrue(responses.indexOf(responses.stream()
            .filter(TurnResponse.ContentChunk.class::isInstance).findFirst().orElseThrow())
            < responses.indexOf(message));
        assertTrue(responses.stream().noneMatch(response -> response instanceof TurnResponse.ContentChunk chunk
            && chunk.text().contains("private reasoning")));
    }

    @Test
    void questionsAreDurableRehydratableAndAnsweredInOrder() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("first question", "second question"), response("continued")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, memory, interactions, queue,
            new RecordingLifecycle());

        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "start")));
        UUID interactionUuid = UUID.fromString(questions.interactionId());

        assertNotNull(interactionUuid);
        assertNotNull(questions.execution().turnId());
        assertFalse(questions.interactionId().equals(questions.execution().turnId()));
        assertEquals(List.of("first question", "second question"), questions.questions());
        assertTrue(questions.execution().completedAt().compareTo(
            questions.execution().startedAt()) >= 0);
        assertEquals(List.of(new ToolActivityData(
                "ask_user", "call-1", ToolActivityStatus.COMPLETED, "Questions pending")),
            questions.execution().toolActivities());
        assertEquals(loop.memory().contextUsage("session"),
            questions.execution().finalContextUsage());
        assertEquals(Optional.of(questions), loop.pendingQuestions("session"));
        assertEquals(questions, interactions.find("session", questions.interactionId())
            .orElseThrow().questions());

        TurnResponse.Queued queued = assertInstanceOf(TurnResponse.Queued.class,
            loop.send(new TurnRequest("session", "while waiting", null, null,
                Map.of("source", "ui"))));
        assertEquals(QueueReason.AWAITING_QUESTIONS, queued.reason());
        assertEquals(1, model.calls.get());
        assertEquals(List.of("while waiting"), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::message).toList());

        TurnResponse.Message continued = assertInstanceOf(TurnResponse.Message.class,
            loop.answer("session", questions.interactionId(),
                List.of("first answer", "second answer")));

        assertEquals("continued", continued.text());
        assertEquals(new ExecutionOrigin.Answer(questions.interactionId()),
            continued.execution().origin());
        assertTrue(loop.pendingQuestions("session").isEmpty());
        assertEquals(2, model.calls.get());
        assertTrue(model.prompts.get(1).getInstructions().stream()
            .map(org.springframework.ai.chat.messages.Message::getText)
            .anyMatch(text -> text.contains("Question 1: first question")
                && text.contains("Answer 1: first answer")
                && text.contains("Question 2: second question")
                && text.contains("Answer 2: second answer")));
        assertTrue(memory.getMessages("session").stream()
            .anyMatch(message -> message.text() != null
                && message.text().contains("Question 1: first question")));
        assertTrue(interactions.find("session", questions.interactionId()).isEmpty());
    }

    @Test
    void streamingAnswerUsesTheSameInteractionCasAndOrigin() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("one", "two"), response("streamed continuation")));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), new RecordingLifecycle());
        TurnResponse.Questions questions = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "ask")));

        StreamCapture capture = subscribe(loop.streamAnswer("session", questions.interactionId(),
            List.of("answer one", "answer two")));
        assertDoesNotThrow(capture::await);

        assertNull(capture.error.get());
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            capture.values.getLast());
        assertEquals("streamed continuation", message.text());
        assertEquals(new ExecutionOrigin.Answer(questions.interactionId()),
            message.execution().origin());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
    }

    @Test
    void exactAnswerCardinalityAndStaleOrConsumedIdsAreRejected() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("first"), response("first complete"),
            askUserResponse("second")));
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), interactions,
            new RecordingQueueStore(), new RecordingLifecycle());

        TurnResponse.Questions first = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "first")));
        assertThrows(IllegalArgumentException.class,
            () -> loop.answer("session", first.interactionId(), List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> loop.answer("session", first.interactionId(), List.of(" ")));

        assertInstanceOf(TurnResponse.Message.class,
            loop.answer("session", first.interactionId(), List.of("answer")));
        assertThrows(InteractionStateException.class,
            () -> loop.answer("session", first.interactionId(), List.of("duplicate")));

        TurnResponse.Questions second = assertInstanceOf(TurnResponse.Questions.class,
            loop.send(TurnRequest.of("session", "second")));
        assertThrows(InteractionStateException.class,
            () -> loop.cancelQuestions("session", first.interactionId(), "stale"));
        assertThrows(InteractionStateException.class,
            () -> loop.answer("other-session", second.interactionId(), List.of("wrong session")));
        assertEquals(Optional.of(second), loop.pendingQuestions("session"));

        TurnResponse.QuestionsCancelled cancelled = loop.cancelQuestions(
            "session", second.interactionId(), "caller stopped waiting");
        assertEquals(second.interactionId(), cancelled.interactionId());
        assertEquals("caller stopped waiting", cancelled.reason());
        assertTrue(loop.pendingQuestions("session").isEmpty());
        assertEquals(InteractionState.CANCELLED,
            interactions.find("session", second.interactionId()).orElseThrow().state());
        assertThrows(InteractionStateException.class,
            () -> loop.cancelQuestions("session", second.interactionId(), "again"));
    }

    @Test
    void activeSubmissionIsQueuedWithoutASecondProviderCall() throws Exception {
        BlockingChatModel model = new BlockingChatModel(
            List.of(response("first complete"), response("queued complete")), 1);
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());

        CompletableFuture<TurnResponse.Outcome> active = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("session", "first")));
        assertTrue(model.entered.await(5, TimeUnit.SECONDS),
            "model calls=" + model.calls.get() + ", active done=" + active.isDone());

        TurnResponse.Queued queued = assertInstanceOf(TurnResponse.Queued.class,
            loop.send(TurnRequest.of("session", "second")));
        assertEquals(QueueReason.SESSION_ACTIVE, queued.reason());
        assertEquals(1, model.calls.get());
        assertEquals(queued.messageId(), queue.list("session").getFirst().messageId());

        model.release.countDown();
        assertEquals("first complete",
            assertInstanceOf(TurnResponse.Message.class,
                active.get(2, TimeUnit.SECONDS)).text());
        assertEquals(1, model.calls.get());
    }

    @Test
    void drainIsColdOldestFirstAndUsesQueuedExecutionOrigins() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("first result"), response("second result")));
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());

        TurnResponse.Queued first = loop.enqueue(new TurnRequest(
            "session", "first", null, null, Map.of("order", "1")));
        TurnResponse.Queued second = loop.enqueue(new TurnRequest(
            "session", "second", "model", List.of("tool"), Map.of("order", "2")));
        Flux<TurnResponse> cold = loop.drain("session");
        assertEquals(0, model.calls.get());

        List<TurnResponse> responses = cold.collectList().block(Duration.ofSeconds(5));
        assertNotNull(responses);
        List<TurnResponse.Message> messages = responses.stream()
            .filter(TurnResponse.Message.class::isInstance)
            .map(TurnResponse.Message.class::cast)
            .toList();
        assertEquals(List.of("first result", "second result"),
            messages.stream().map(TurnResponse.Message::text).toList());
        assertEquals(List.of(first.messageId(), second.messageId()),
            messages.stream().map(message -> ((ExecutionOrigin.Queued)
                message.execution().origin()).messageId()).toList());
        assertEquals(List.of(first.messageId(), second.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.releasedMessageIds.isEmpty());
        assertTrue(responses.stream().noneMatch(TurnResponse.Queued.class::isInstance));
        assertTrue(loop.drain("session").collectList().block(Duration.ofSeconds(2)).isEmpty());
    }

    @Test
    void drainRecoversAnsweredContinuationBeforeClaimingQueuedWork() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("answered recovery"), response("queued result")));
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingQueueStore queue = new RecordingQueueStore();
        String interactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        TurnResponse.Questions questions = new TurnResponse.Questions(
            new ExecutionInfo("session", UUID.randomUUID().toString(),
                new ExecutionOrigin.Direct(), now, now,
                new ContextUsage(1, 100, 4096), List.of()),
            interactionId, List.of("recover me"));
        InteractionStore.Interaction answered = new InteractionStore.Interaction(
            interactionId, "session", questions, "model", null, Map.of(), Map.of(),
            List.of("recovered answer"), InteractionState.ANSWERED,
            now, now, null, null);
        interactions.save(answered);
        memory.addMessages("session", List.of(new TurnMessage(
            "system", "[Magenta interaction pending]", null, null, Map.of(
                "turnloop.persistence_type", "turnloop.pending_interaction",
                "turnloop.pending_interaction_id", interactionId,
                "turnloop.pending_answers", "[\"recovered answer\"]"))));
        DefaultTurnLoop loop = createLoop(model, memory, interactions, queue,
            new RecordingLifecycle());
        TurnResponse.Queued queued = loop.enqueue(TurnRequest.of("session", "queued"));

        List<TurnResponse> responses = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));

        assertNotNull(responses);
        List<TurnResponse.Message> messages = responses.stream()
            .filter(TurnResponse.Message.class::isInstance)
            .map(TurnResponse.Message.class::cast).toList();
        assertEquals(List.of("answered recovery", "queued result"),
            messages.stream().map(TurnResponse.Message::text).toList());
        assertEquals(new ExecutionOrigin.Answer(interactionId),
            messages.getFirst().execution().origin());
        assertEquals(new ExecutionOrigin.Queued(queued.messageId()),
            messages.getLast().execution().origin());
        assertEquals(List.of(queued.messageId()), queue.acknowledgedMessageIds);
        assertEquals(2, queue.claimCalls);
        assertTrue(interactions.find("session", interactionId).isEmpty());
    }

    @Test
    void drainRetainsAndReleasesClaimWhenAcknowledgementFails() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            response("first execution"), response("retried execution")));
        RecordingQueueStore queue = new RecordingQueueStore();
        queue.failedAcknowledgements = 1;
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued receipt = loop.enqueue(TurnRequest.of("session", "retry oldest"));

        StreamCapture firstDrain = subscribe(loop.drain("session"));
        assertDoesNotThrow(firstDrain::await);

        assertTrue(firstDrain.error.get() instanceof TurnLoopException);
        assertEquals(List.of(receipt.messageId()), queue.releasedMessageIds);
        assertEquals(List.of(receipt.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());
        assertTrue(queue.acknowledgedMessageIds.isEmpty());

        List<TurnResponse> retry = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        assertEquals("retried execution", assertInstanceOf(TurnResponse.Message.class,
            retry.getLast()).text());
        assertEquals(List.of(receipt.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.list("session").isEmpty());
    }

    @Test
    void drainReleasesAClaimWhenQueuedExecutionFails() {
        FailureChatModel model = new FailureChatModel();
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued receipt = loop.enqueue(TurnRequest.of("session", "retry me"));

        StreamCapture capture = subscribe(loop.drain("session"));
        assertDoesNotThrow(capture::await);

        assertTrue(capture.error.get() instanceof TurnFailedException);
        assertTrue(capture.values.stream().noneMatch(TurnResponse.Outcome.class::isInstance));
        assertEquals(List.of(receipt.messageId()), queue.releasedMessageIds);
        assertEquals(List.of(receipt.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());
        assertTrue(queue.acknowledgedMessageIds.isEmpty());
    }

    @Test
    void drainStopsAfterQuestionsAndPendingRehydrationDoesNotClaimMoreWork() {
        ScriptedChatModel model = new ScriptedChatModel(List.of(
            askUserResponse("drain question"), response("after cancellation")));
        RecordingQueueStore queue = new RecordingQueueStore();
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            queue, new RecordingLifecycle());
        TurnResponse.Queued first = loop.enqueue(TurnRequest.of("session", "ask first"));
        TurnResponse.Queued second = loop.enqueue(TurnRequest.of("session", "run second"));

        List<TurnResponse> firstDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        TurnResponse.Questions pending = assertInstanceOf(TurnResponse.Questions.class,
            firstDrain.getLast());
        assertEquals(List.of(first.messageId()), queue.acknowledgedMessageIds);
        assertEquals(List.of(second.messageId()), queue.list("session").stream()
            .map(MessageQueueStore.QueuedRequest::messageId).toList());

        List<TurnResponse> blockedDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(2));
        assertEquals(List.of(pending), blockedDrain);
        assertEquals(1, queue.claimCalls);
        assertEquals(Optional.of(pending), loop.pendingQuestions("session"));

        loop.cancelQuestions("session", pending.interactionId(), "continue queue");
        List<TurnResponse> finalDrain = loop.drain("session")
            .collectList().block(Duration.ofSeconds(5));
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            finalDrain.getLast());
        assertEquals("after cancellation", message.text());
        assertEquals(new ExecutionOrigin.Queued(second.messageId()),
            message.execution().origin());
        assertEquals(List.of(first.messageId(), second.messageId()), queue.acknowledgedMessageIds);
        assertTrue(queue.list("session").isEmpty());
    }

    @Test
    void lifecycleListenerFailuresAreIsolatedAndUnexpectedStreamsErrorWithoutOutcome() {
        RecordingLifecycle successfulLifecycle = new RecordingLifecycle(true);
        DefaultTurnLoop successfulLoop = createLoop(
            new ScriptedChatModel(List.of(response("ok"))), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(), successfulLifecycle);
        TurnResponse.Message message = assertInstanceOf(TurnResponse.Message.class,
            successfulLoop.send(TurnRequest.of("success", "hello")));
        assertEquals("ok", message.text());
        assertEquals(1, successfulLifecycle.outcomes.size());
        assertTrue(successfulLifecycle.failures.isEmpty());

        RecordingLifecycle failureLifecycle = new RecordingLifecycle(true);
        DefaultTurnLoop failureLoop = createLoop(new FailureChatModel(), new Memory(),
            new InMemoryInteractionStore(), new RecordingQueueStore(), failureLifecycle);
        TurnFailedException sendFailure = assertThrows(TurnFailedException.class,
            () -> failureLoop.send(TurnRequest.of("failure", "hello")));
        assertEquals(TurnFailureCategory.MODEL, sendFailure.failure().category());
        assertEquals(new ExecutionOrigin.Direct(), sendFailure.failure().origin());
        assertTrue(failureLifecycle.outcomes.isEmpty());
        assertEquals(1, failureLifecycle.failures.size());

        StreamCapture streamFailure = subscribe(failureLoop.stream(
            TurnRequest.of("failure-stream", "hello")));
        assertDoesNotThrow(streamFailure::await);
        assertTrue(streamFailure.error.get() instanceof TurnFailedException);
        assertTrue(streamFailure.values.stream().noneMatch(
            TurnResponse.Outcome.class::isInstance));
        assertEquals(2, failureLifecycle.failures.size());
    }

    @Test
    void interruptionProducesTypedOutcome() throws Exception {
        BlockingStreamingChatModel model = new BlockingStreamingChatModel(response("late"));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(Duration.ofSeconds(30));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);

        StreamCapture capture = subscribe(loop.stream(TurnRequest.of("session", "interrupt")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));
        TurnResponse.Started started = capture.values.stream()
            .filter(TurnResponse.Started.class::isInstance)
            .map(TurnResponse.Started.class::cast)
            .findFirst().orElseThrow();
        assertTrue(loop.interrupt(started.turnId(), "user interrupted"));
        model.release.countDown();
        capture.await();

        assertNull(capture.error.get());
        TurnResponse.Interrupted interrupted = assertInstanceOf(TurnResponse.Interrupted.class,
            capture.values.getLast());
        assertEquals("user interrupted", interrupted.reason());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
    }

    @Test
    void timeoutTerminatesBlockingCallerAndRecordsOneCoherentOutcome() throws Exception {
        BlockingChatModel model = new BlockingChatModel(List.of(response("late")), 1);
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofMillis(250), Duration.ofMillis(5));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);
        CompletableFuture<TurnResponse.Outcome> caller = CompletableFuture.supplyAsync(
            () -> loop.send(TurnRequest.of("timeout-session", "block")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        TurnResponse.TimedOut timedOut;
        try {
            timedOut = assertInstanceOf(TurnResponse.TimedOut.class,
                caller.get(2, TimeUnit.SECONDS));
        } finally {
            model.release.countDown();
        }

        assertEquals("turn_timeout", timedOut.reason());
        assertEquals(1, lifecycle.outcomes.size());
        assertEquals(timedOut, lifecycle.outcomes.getFirst());
        assertTrue(lifecycle.failures.isEmpty());
        awaitCondition(() -> gate.state("timeout-session")
            == io.mindspice.magenta.core.spi.TurnGate.GateState.IDLE);
    }

    @Test
    void timeoutTerminatesStreamingCallerBeforeProviderUnwinds() throws Exception {
        BlockingStreamingChatModel model = new BlockingStreamingChatModel(response("late"));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofMillis(250), Duration.ofMillis(5));
        DefaultTurnLoop loop = createLoop(model, new Memory(), new InMemoryInteractionStore(),
            new RecordingQueueStore(), lifecycle, gate);
        StreamCapture capture = subscribe(loop.stream(
            TurnRequest.of("timeout-stream", "block")));
        assertTrue(model.entered.await(2, TimeUnit.SECONDS));

        try {
            capture.await();
        } finally {
            model.release.countDown();
        }

        assertNull(capture.error.get());
        TurnResponse.TimedOut timedOut = assertInstanceOf(TurnResponse.TimedOut.class,
            capture.values.getLast());
        assertEquals(1, capture.values.stream()
            .filter(TurnResponse.Outcome.class::isInstance).count());
        assertEquals(List.of(timedOut), lifecycle.outcomes);
        assertTrue(lifecycle.failures.isEmpty());
    }

    @Test
    void streamCancellationFencesLateInteractionPersistence() throws Exception {
        CancellationIgnoringStreamingChatModel model = new CancellationIgnoringStreamingChatModel();
        Memory memory = new Memory();
        InMemoryInteractionStore interactions = new InMemoryInteractionStore();
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        SerialTurnGate gate = new SerialTurnGate(Duration.ofSeconds(30));
        DefaultTurnLoop loop = createLoop(model, memory, interactions,
            new RecordingQueueStore(), lifecycle, gate);

        List<TurnResponse> firstResponses = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Disposable subscription = loop.stream(TurnRequest.of("session", "cancelled"))
            .subscribe(firstResponses::add, firstError::set);
        assertTrue(model.firstEntered.await(2, TimeUnit.SECONDS));
        subscription.dispose();
        awaitCondition(() -> gate.state("session") == io.mindspice.magenta.core.spi.TurnGate.GateState.IDLE);

        List<TurnResponse> successor = loop.stream(
            TurnRequest.of("session", "successor")).collectList().block(Duration.ofSeconds(5));
        assertNotNull(successor);
        assertInstanceOf(TurnResponse.Message.class, successor.getLast());

        model.releaseFirst.countDown();
        assertTrue(model.firstExited.await(2, TimeUnit.SECONDS));
        awaitCondition(() -> model.calls.get() == 2);
        assertNull(firstError.get());
        assertTrue(firstResponses.stream().noneMatch(TurnResponse.Outcome.class::isInstance));
        assertEquals(List.of("successor", "fresh"), memory.getMessages("session").stream()
            .filter(message -> "user".equals(message.role()) || "assistant".equals(message.role()))
            .map(TurnMessage::text).toList());
        assertTrue(interactions.pending("session").isEmpty());
        assertTrue(lifecycle.outcomes.stream().anyMatch(
            TurnResponse.TurnCancelled.class::isInstance));
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle) {
        return createLoop(model, memory, interactions, queue, lifecycle,
            new SerialTurnGate(Duration.ofSeconds(30)));
    }

    private DefaultTurnLoop createLoop(ChatModel model,
                                       ConversationMemory memory,
                                       InteractionStore interactions,
                                       RecordingQueueStore queue,
                                       TurnLifecycleListener lifecycle,
                                       SerialTurnGate gate) {
        ToolRegistry tools = new EmptyToolRegistry();
        TokenEstimator estimator = new SimpleEstimator();
        CompactionStrategy compaction = context -> new CompactionResult("summary", 0, 0);
        ContextManager manager = new ContextManager(memory, estimator, compaction, 0);
        ActiveTurnRegistry activeRegistry = new ConcurrentActiveTurnRegistry();
        TurnExecutor executor = new DefaultTurnExecutor();
        DefaultTurnEngine engine = new DefaultTurnEngine.Builder()
            .toolExecution(tools, ToolAccessPolicy.allowAll(), new DefaultToolResultSummarizer(),
                new ToolLoopGuard())
            .contextAssembly(memory, estimator, compaction)
            .contextManager(manager)
            .promptAssembler(new DefaultPromptAssembler("system", List.of()))
            .responseRepair(ctx -> false, (ctx, reason) -> List.of())
            .interactionStore(interactions)
            .infrastructure(new FakeRouter(model), executor, activeRegistry,
                new CompositeThinkingExtractor())
            .build();
        DefaultTurnLoop loop = new DefaultTurnLoop(
            engine, gate, queue, interactions, new Sessions(), memory, activeRegistry,
            executor, tools, alias -> Set.of(ModelCapability.STREAMING,
                ModelCapability.TOOL_CALLING), lifecycle, new RecordingMetrics(), null,
            "model", Duration.ofMinutes(5), 5, new RecordingAudit(), true);
        loops.add(loop);
        return loop;
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

    private static StreamCapture subscribe(Flux<TurnResponse> stream) {
        StreamCapture capture = new StreamCapture();
        stream.subscribe(capture.values::add, error -> {
            capture.error.set(error);
            capture.done.countDown();
        }, capture.done::countDown);
        return capture;
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "condition was not satisfied before timeout");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        for (;;) {
            try {
                latch.await();
                return;
            } catch (InterruptedException ignored) {
                // This fake deliberately models a provider that ignores cancellation.
            }
        }
    }

    private static final class StreamCapture {
        private final List<TurnResponse> values = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();
        private final CountDownLatch done = new CountDownLatch(1);

        private void await() throws InterruptedException {
            assertTrue(done.await(5, TimeUnit.SECONDS), "stream did not terminate");
        }
    }

    private static class ScriptedChatModel implements ChatModel {
        private final Deque<ChatResponse> responses;
        protected final List<Prompt> prompts = new CopyOnWriteArrayList<>();
        protected final java.util.concurrent.atomic.AtomicInteger calls =
            new java.util.concurrent.atomic.AtomicInteger();

        private ScriptedChatModel(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return nextResponse();
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.just(nextResponse());
        }

        protected synchronized ChatResponse nextResponse() {
            return responses.isEmpty() ? response("default") : responses.removeFirst();
        }
    }

    private static final class BlockingChatModel extends ScriptedChatModel {
        private final int blockingCall;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingChatModel(List<ChatResponse> responses, int blockingCall) {
            super(responses);
            this.blockingCall = blockingCall;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            int call = calls.incrementAndGet();
            if (call == blockingCall) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
            return nextResponse();
        }
    }

    private static final class BlockingStreamingChatModel extends ScriptedChatModel {
        private final ChatResponse response;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingStreamingChatModel(ChatResponse response) {
            super(List.of());
            this.response = response;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.create(sink -> {
                entered.countDown();
                awaitUninterruptibly(release);
                sink.next(response);
                sink.complete();
            });
        }
    }

    private static final class CancellationIgnoringStreamingChatModel extends ScriptedChatModel {
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final CountDownLatch firstExited = new CountDownLatch(1);

        private CancellationIgnoringStreamingChatModel() {
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

    private static final class FailureChatModel extends ScriptedChatModel {
        private FailureChatModel() {
            super(List.of());
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            throw new IllegalStateException("provider failure");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            prompts.add(prompt);
            calls.incrementAndGet();
            return Flux.error(new IllegalStateException("provider failure"));
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
            return requested != null && !requested.isBlank()
                ? requested : stored != null && !stored.isBlank() ? stored : "model";
        }
        @Override public int contextLength(String alias) { return 4096; }
        @Override public ToolCallingChatOptions toolCallingOptions(
            String alias, List<ToolCallback> tools) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public ToolCallingChatOptions basicChatOptions(String alias) {
            return ToolCallingChatOptions.builder().build();
        }
        @Override public boolean supportsStreaming(String alias) { return true; }
        @Override public void closeChatModels() { }
    }

    private static final class Memory implements ConversationMemory {
        private final Map<String, List<TurnMessage>> messages = new ConcurrentHashMap<>();

        @Override
        public List<TurnMessage> getMessages(String sessionId) {
            return List.copyOf(messages.getOrDefault(sessionId, List.of()));
        }

        @Override
        public synchronized void addMessages(String sessionId, List<TurnMessage> additions) {
            List<TurnMessage> existing = new ArrayList<>(messages.getOrDefault(sessionId, List.of()));
            existing.addAll(additions);
            messages.put(sessionId, List.copyOf(existing));
        }

        @Override
        public synchronized void replaceMessages(String sessionId, List<TurnMessage> replacement) {
            messages.put(sessionId, List.copyOf(replacement));
        }

        @Override
        public synchronized void clear(String sessionId) {
            messages.remove(sessionId);
        }
    }

    private static final class Sessions implements SessionStore {
        private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

        @Override public void save(ConversationSession session) { sessions.put(session.sessionId(), session); }
        @Override public ConversationSession findById(String id) { return sessions.get(id); }
        @Override public List<ConversationSession> list(SessionFilter filter) {
            return List.copyOf(sessions.values());
        }
        @Override public void delete(String id) { sessions.remove(id); }
        @Override public void updateTitle(String id, String title) { }
        @Override public void setFavorite(String id, boolean value) { }
        @Override public void setArchived(String id, boolean value) { }
        @Override public void updateModel(String id, String model) {
            ConversationSession current = sessions.get(id);
            if (current != null) {
                sessions.put(id, new ConversationSession(current.sessionId(), current.title(), model,
                    current.favorite(), current.archived(), current.origin(), current.createdAt(),
                    current.updatedAt(), current.metadata()));
            }
        }
    }

    private static final class EmptyToolRegistry implements ToolRegistry {
        @Override public void register(ToolCallback tool) { }
        @Override public void registerFrameworkTool(ToolCallback tool) { }
        @Override public void registerToolBean(Object bean) { }
        @Override public void unregister(String name) { }
        @Override public List<ToolDefinition> definitions() { return List.of(); }
        @Override public List<ToolCallback> resolveForTurn(TurnContext context) { return List.of(); }
    }

    private static final class RecordingQueueStore implements MessageQueueStore {
        private final List<QueuedRequest> requests = new ArrayList<>();
        private final Map<String, Claim> claimsByToken = new HashMap<>();
        private final Map<String, String> tokenByMessage = new HashMap<>();
        private final List<String> acknowledgedMessageIds = new ArrayList<>();
        private final List<String> releasedMessageIds = new ArrayList<>();
        private int claimCalls;
        private int failedAcknowledgements;

        @Override
        public synchronized void insert(QueuedRequest request) {
            requests.add(request);
        }

        @Override
        public synchronized List<QueuedRequest> list(String sessionId) {
            return requests.stream()
                .filter(request -> request.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(QueuedRequest::enqueuedAt)
                    .thenComparing(QueuedRequest::messageId))
                .toList();
        }

        @Override
        public synchronized Optional<Claim> claimOldest(String sessionId,
                                                         Instant claimedAt,
                                                         Duration lease) {
            claimCalls++;
            QueuedRequest request = list(sessionId).stream()
                .filter(candidate -> !tokenByMessage.containsKey(candidate.messageId()))
                .findFirst().orElse(null);
            if (request == null) return Optional.empty();
            String token = UUID.randomUUID().toString();
            Claim claim = new Claim(request, token, claimedAt, claimedAt.plus(lease));
            claimsByToken.put(token, claim);
            tokenByMessage.put(request.messageId(), token);
            return Optional.of(claim);
        }

        @Override
        public synchronized boolean ack(String claimToken) {
            if (failedAcknowledgements > 0) {
                failedAcknowledgements--;
                return false;
            }
            Claim claim = claimsByToken.remove(claimToken);
            if (claim == null) return false;
            tokenByMessage.remove(claim.request().messageId());
            requests.removeIf(request -> request.messageId().equals(claim.request().messageId()));
            acknowledgedMessageIds.add(claim.request().messageId());
            return true;
        }

        @Override
        public synchronized boolean release(String claimToken) {
            Claim claim = claimsByToken.remove(claimToken);
            if (claim == null) return false;
            tokenByMessage.remove(claim.request().messageId());
            releasedMessageIds.add(claim.request().messageId());
            return true;
        }

        @Override
        public synchronized int recoverExpiredClaims(Instant now) {
            List<String> expired = claimsByToken.values().stream()
                .filter(claim -> !claim.leaseExpiresAt().isAfter(now))
                .map(Claim::claimToken).toList();
            expired.forEach(token -> {
                Claim claim = claimsByToken.remove(token);
                if (claim != null) tokenByMessage.remove(claim.request().messageId());
            });
            return expired.size();
        }

        @Override
        public synchronized void deleteBySessionId(String sessionId) {
            requests.removeIf(request -> request.sessionId().equals(sessionId));
            claimsByToken.values().removeIf(claim -> claim.request().sessionId().equals(sessionId));
            tokenByMessage.entrySet().removeIf(entry -> requests.stream()
                .noneMatch(request -> request.messageId().equals(entry.getKey())));
        }
    }

    private static final class SimpleEstimator implements TokenEstimator {
        @Override public int estimateTokens(String text) {
            return text == null ? 0 : Math.max(1, text.length() / 4);
        }
        @Override public int estimateTokens(TurnMessage message) {
            return estimateTokens(message.text()) + 1;
        }
    }

    private static final class RecordingLifecycle implements TurnLifecycleListener {
        private final List<TurnResponse.Outcome> outcomes = new CopyOnWriteArrayList<>();
        private final List<TurnFailure> failures = new CopyOnWriteArrayList<>();
        private final boolean throwFromCallbacks;

        private RecordingLifecycle() {
            this(false);
        }

        private RecordingLifecycle(boolean throwFromCallbacks) {
            this.throwFromCallbacks = throwFromCallbacks;
        }

        @Override
        public void onOutcome(TurnResponse.Outcome outcome) {
            outcomes.add(outcome);
            if (throwFromCallbacks) throw new IllegalStateException("listener outcome failure");
        }

        @Override
        public void onFailure(TurnFailure failure) {
            failures.add(failure);
            if (throwFromCallbacks) throw new IllegalStateException("listener failure failure");
        }
    }

    private static final class RecordingAudit implements AuditWriter {
        @Override public void recordUserMessage(String sessionId, TurnMessage message) { }
        @Override public void recordAssistantMessage(String sessionId, TurnMessage message) { }
        @Override public void recordToolExecution(String sessionId, ToolExecutionRecord record) { }
        @Override public void recordContextUsage(String sessionId, ContextUsage usage, String model) { }
        @Override public void recordError(String sessionId, TurnError error) { }
    }

    private static final class RecordingMetrics implements TurnLoopMetrics {
        @Override public void recordTurnDuration(String model, String outcome, long durationMs) { }
        @Override public void recordToolCall(String toolName, String status) { }
        @Override public void setActiveTurns(long count) { }
        @Override public void setQueueDepth(String sessionId, long depth) { }
    }
}
