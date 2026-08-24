package io.mindspice.magenta.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.engine.DefaultTurnEngine;
import io.mindspice.magenta.core.internal.ActiveTurnRegistry;
import io.mindspice.magenta.core.internal.TurnExecutor;
import io.mindspice.magenta.core.persistence.SqliteAuditWriter;
import io.mindspice.magenta.core.persistence.SqliteConversationMemory;
import io.mindspice.magenta.core.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Package-private facade implementation. It owns the public state-machine
 * boundary: gate acquisition, durable session setup, queue outcomes,
 * interaction lifecycle, observation hooks, and exception translation.
 */
final class DefaultTurnLoop implements TurnLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnLoop.class);
    private static final String PENDING_TYPE = "turnloop.pending_interaction";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final DefaultTurnEngine engine;
    private final TurnGate gate;
    private final MessageQueue messageQueue;
    private final InteractionStore interactionStore;
    private final SessionStore sessionStore;
    private final ConversationMemory conversationMemory;
    private final ActiveTurnRegistry activeTurnRegistry;
    private final TurnExecutor executor;
    private final ToolRegistry toolRegistry;
    private final ModelCapabilityProvider modelCapabilityProvider;
    private final TurnLifecycleListener lifecycleListener;
    private final TurnLoopMetrics metrics;
    private final AuditWriter auditWriter;
    private final ObjectMapper objectMapper;
    private final String defaultSystemPrompt;
    private final String defaultModel;
    private final long drainTimeoutSeconds;
    private final boolean ownsTurnGate;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeTurnIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeGateTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionAction> pendingSessionActions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> claimSessions = new ConcurrentHashMap<>();
    private final ToolSubinterface toolsView;
    private final MemorySubinterface memoryView;
    private final SessionSubinterface sessionsView;

    DefaultTurnLoop(
        DefaultTurnEngine engine,
        TurnGate gate,
        MessageQueue messageQueue,
        InteractionStore interactionStore,
        SessionStore sessionStore,
        ConversationMemory conversationMemory,
        ActiveTurnRegistry activeTurnRegistry,
        TurnExecutor executor,
        ToolRegistry toolRegistry,
        ModelCapabilityProvider modelCapabilityProvider,
        TurnLifecycleListener lifecycleListener,
        TurnLoopMetrics metrics,
        ObjectMapper objectMapper,
        String defaultSystemPrompt,
        String defaultModel,
        long drainTimeoutSeconds
    ) {
        this(engine, gate, messageQueue, interactionStore, sessionStore, conversationMemory,
            activeTurnRegistry, executor, toolRegistry, modelCapabilityProvider,
            lifecycleListener, metrics, objectMapper, defaultSystemPrompt, defaultModel,
            drainTimeoutSeconds, null, false);
    }

    DefaultTurnLoop(
        DefaultTurnEngine engine,
        TurnGate gate,
        MessageQueue messageQueue,
        InteractionStore interactionStore,
        SessionStore sessionStore,
        ConversationMemory conversationMemory,
        ActiveTurnRegistry activeTurnRegistry,
        TurnExecutor executor,
        ToolRegistry toolRegistry,
        ModelCapabilityProvider modelCapabilityProvider,
        TurnLifecycleListener lifecycleListener,
        TurnLoopMetrics metrics,
        ObjectMapper objectMapper,
        String defaultSystemPrompt,
        String defaultModel,
        long drainTimeoutSeconds,
        AuditWriter auditWriter,
        boolean ownsTurnGate
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.messageQueue = Objects.requireNonNull(messageQueue, "messageQueue");
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper = mapper;
        this.conversationMemory = Objects.requireNonNull(conversationMemory, "conversationMemory");
        this.interactionStore = Objects.requireNonNull(interactionStore, "interactionStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.activeTurnRegistry = Objects.requireNonNull(activeTurnRegistry, "activeTurnRegistry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.modelCapabilityProvider = Objects.requireNonNull(modelCapabilityProvider, "modelCapabilityProvider");
        this.lifecycleListener = lifecycleListener == null ? result -> { } : lifecycleListener;
        this.metrics = metrics == null ? new NoOpTurnLoopMetrics() : metrics;
        this.auditWriter = auditWriter == null
            ? defaultAuditWriter(conversationMemory, mapper) : auditWriter;
        this.defaultSystemPrompt = defaultSystemPrompt == null
            ? "You are a helpful AI assistant." : defaultSystemPrompt;
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.drainTimeoutSeconds = Math.max(0, drainTimeoutSeconds);
        this.ownsTurnGate = ownsTurnGate;
        this.toolsView = new Tools();
        this.memoryView = new Memory();
        this.sessionsView = new Sessions();
        if (gate instanceof SerialTurnGate serialTurnGate) {
            serialTurnGate.setTimeoutHandler(this::fenceTimedOutTurn);
        }
    }

    // ── turn execution ──

    @Override
    public TurnResult send(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        String sessionId = request.sessionId();
        TurnContext context = null;
        List<TurnMessage> before;
        String model;
        long started;
        String gateToken = null;

        synchronized (lockFor(sessionId)) {
            validateClosed();
            try {
                QuestionPendingContext pending = restorePending(sessionId);
                if (pending != null || gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                    return queueResult(request, enqueueInternal(request));
                }

                model = resolveModel(request);
                ensureSession(sessionId, model);
                TurnGate.AcquireResult acquired = gate.acquire(sessionId, TurnGate.TurnGateMode.SEND);
                if (!(acquired instanceof TurnGate.AcquireResult.Acquired ownership)) {
                    if (gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                        return queueResult(request, enqueueInternal(request));
                    }
                    String reason = acquired instanceof TurnGate.AcquireResult.Rejected rejected
                        ? rejected.reason() : "turn was queued by the gate";
                    throw new TurnLoopException("Turn already in progress for session "
                        + sessionId + ": " + reason);
                }
                gateToken = ownership.lockToken();

                context = buildTurnContext(request, model, false);
                registerExecution(sessionId, context.turnId(), gateToken);
                before = safeHistory(sessionId);
                auditUser(sessionId, currentUser(request));
                started = System.currentTimeMillis();
                setActiveMetric();
            } catch (TurnLoopException e) {
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                throw e;
            } catch (Throwable e) {
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                throw publicFailure("Turn execution failed", e);
            }
        }

        String turnId = context.turnId();
        String ownershipToken = gateToken;
        try {
            TurnResult result = engine.execute(context);
            runIfAuthorized(turnId,
                () -> recordTurnObservability(request, result, before, model, started, true));
            revokeExecution(turnId, "turn_completed");
            synchronized (lockFor(sessionId)) {
                if (ownsExecution(sessionId, turnId, ownershipToken)) {
                    finishSuccessfulTurn(sessionId, result);
                    finishActiveTurn(sessionId, turnId, ownershipToken, true);
                }
            }
            return result;
        } catch (Throwable failure) {
            runIfAuthorized(turnId,
                () -> recordFailureObservability(sessionId, model, before, started, failure));
            revokeExecution(turnId, "turn_failed");
            synchronized (lockFor(sessionId)) {
                if (ownsExecution(sessionId, turnId, ownershipToken)) {
                    clearPendingAfterFailure(sessionId);
                    gate.release(sessionId);
                    finishActiveTurn(sessionId, turnId, ownershipToken, true);
                }
            }
            throw publicFailure("Turn execution failed", failure);
        } finally {
            setActiveMetric();
        }
    }

    @Override
    public Flux<TurnEvent> stream(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        return Flux.defer(() -> startStream(request))
            .onErrorMap(this::mapInternalException);
    }

    private Flux<TurnEvent> startStream(TurnRequest request) {
        validateClosed();
        String sessionId = request.sessionId();
        TurnContext context = null;
        String gateToken = null;
        synchronized (lockFor(sessionId)) {
            validateClosed();
            try {
                QuestionPendingContext pending = restorePending(sessionId);
                if (pending != null || gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                    return Flux.just(queuedEvent(request, enqueueInternal(request)));
                }

                String model = resolveModel(request);
                ensureSession(sessionId, model);
                if (!modelCapabilityProvider.capabilities(model)
                    .contains(ModelCapability.STREAMING)) {
                    throw new TurnLoopException("Model '" + model + "' does not support streaming");
                }

                TurnGate.AcquireResult acquired = gate.acquire(sessionId, TurnGate.TurnGateMode.STREAM);
                if (!(acquired instanceof TurnGate.AcquireResult.Acquired ownership)) {
                    if (gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                        return Flux.just(queuedEvent(request, enqueueInternal(request)));
                    }
                    String reason = acquired instanceof TurnGate.AcquireResult.Rejected rejected
                        ? rejected.reason() : "turn was queued by the gate";
                    throw new TurnLoopException("Turn already in progress for session "
                        + sessionId + ": " + reason);
                }
                gateToken = ownership.lockToken();

                context = buildTurnContext(request, model, true);
                registerExecution(sessionId, context.turnId(), gateToken);
                List<TurnMessage> before = safeHistory(sessionId);
                auditUser(sessionId, currentUser(request));
                long started = System.currentTimeMillis();
                AtomicBoolean gateFinished = new AtomicBoolean(false);
                AtomicBoolean lifecycleRecorded = new AtomicBoolean(false);
                CopyOnWriteArrayList<TurnEvent> events = new CopyOnWriteArrayList<>();
                TurnContext streamContext = context;
                String ownershipToken = gateToken;

                return engine.stream(streamContext)
                    .doOnSubscribe(subscription -> setActiveMetric())
                    .doOnNext(event -> {
                        events.add(event);
                        if (event instanceof TurnEvent.Started) {
                            setActiveMetric();
                        }
                        if (event instanceof TurnEvent.ToolActivity activity) {
                            safeMetric(() -> metrics.recordToolCall(
                                activity.toolName(), activity.status().name()));
                        }
                    })
                    .doFinally(signal -> {
                        try {
                            if (signal == SignalType.CANCEL) {
                                revokeExecution(streamContext.turnId(), "subscriber_cancelled");
                            } else {
                                runIfAuthorized(streamContext.turnId(), () ->
                                    recordStreamObservability(request, streamContext, before, events,
                                        model, started, signal, lifecycleRecorded));
                                revokeExecution(streamContext.turnId(), "turn_completed");
                            }
                            synchronized (lockFor(sessionId)) {
                                if (!ownsExecution(sessionId, streamContext.turnId(), ownershipToken)) {
                                    return;
                                }
                                if (signal == SignalType.CANCEL) {
                                    recordStreamObservability(request, streamContext, before, events,
                                        model, started, signal, lifecycleRecorded);
                                    clearPendingAfterInterruption(sessionId, "subscriber_cancelled");
                                } else {
                                    if (signal == SignalType.ON_ERROR) {
                                        clearPendingAfterFailure(sessionId);
                                    }
                                    if (signal == SignalType.ON_COMPLETE
                                        && events.stream().anyMatch(event -> event instanceof TurnEvent.Failed failed
                                            && isInterruptedOutcome(failed.diagnostic().outcomeKind()))) {
                                        clearPendingAfterInterruption(sessionId, "interrupted");
                                    }
                                }
                                finishGate(sessionId, signal, gateFinished);
                                finishActiveTurn(sessionId, streamContext.turnId(), ownershipToken, true);
                            }
                        } catch (Throwable e) {
                            log.warn("Stream cleanup failed for session {}: {}", sessionId, e.getMessage());
                        } finally {
                            setActiveMetric();
                        }
                    });
            } catch (TurnLoopException e) {
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                throw e;
            } catch (Throwable e) {
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                throw publicFailure("Unable to start stream", e);
            }
        }
    }

    @Override
    public TurnResult answer(@NonNull String sessionId, @NonNull String answer) {
        validateClosed();
        validateText(sessionId, "sessionId");
        validateText(answer, "answer");
        TurnRequest request;
        TurnContext context = null;
        List<TurnMessage> before;
        String model;
        long started;
        boolean answerClaimed = false;
        String gateToken = null;

        synchronized (lockFor(sessionId)) {
            validateClosed();
            try {
                QuestionPendingContext pending = restorePending(sessionId);
                if (pending == null || pending.state() != QuestionPendingContext.State.PENDING
                    || gate.state(sessionId) != TurnGate.GateState.AWAITING_ANSWER) {
                    throw new TurnLoopException("No question pending for session " + sessionId);
                }
                model = pending.model() == null || pending.model().isBlank()
                    ? resolveModel(new TurnRequest(sessionId, answer, null, null, false, Map.of()))
                    : pending.model();
                if (!interactionStore.answer(sessionId, answer)) {
                    gate.release(sessionId);
                    throw new TurnLoopException("Question was already answered or cancelled for session "
                        + sessionId);
                }
                answerClaimed = true;

                TurnGate.AcquireResult acquired = gate.acquire(
                    sessionId, TurnGate.TurnGateMode.ANSWER);
                if (!(acquired instanceof TurnGate.AcquireResult.Acquired ownership)) {
                    clearPendingState(sessionId);
                    gate.release(sessionId);
                    String reason = acquired instanceof TurnGate.AcquireResult.Rejected rejected
                        ? rejected.reason() : "answer was queued by the gate";
                    throw new TurnLoopException("Cannot resume turn for session "
                        + sessionId + ": " + reason);
                }
                gateToken = ownership.lockToken();

                List<String> toolNames = readJsonList(
                    pending.continuationContext().get("turnloop.pending_tool_names"));
                Map<String, String> continuation = continuationMetadata(pending);
                request = new TurnRequest(
                    sessionId, answer, model, toolNames, false, continuation);
                context = buildTurnContext(request, model, false);
                registerExecution(sessionId, context.turnId(), gateToken);
                before = safeHistory(sessionId);
                auditUser(sessionId, currentUser(request));
                started = System.currentTimeMillis();
                setActiveMetric();
            } catch (TurnLoopException e) {
                if (answerClaimed) clearPendingState(sessionId);
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                if (answerClaimed && gateToken == null) gate.release(sessionId);
                throw e;
            } catch (Throwable e) {
                if (answerClaimed) clearPendingState(sessionId);
                cleanupFailedStart(sessionId, context == null ? null : context.turnId(), gateToken);
                if (answerClaimed && gateToken == null) gate.release(sessionId);
                throw publicFailure("Answer turn execution failed", e);
            }
        }

        String turnId = context.turnId();
        String ownershipToken = gateToken;
        try {
            TurnResult result = engine.execute(context);
            runIfAuthorized(turnId,
                () -> recordTurnObservability(request, result, before, model, started, true));
            revokeExecution(turnId, "turn_completed");
            synchronized (lockFor(sessionId)) {
                if (ownsExecution(sessionId, turnId, ownershipToken)) {
                    if (result.diagnostic().outcomeKind() != TurnDiagnostic.OutcomeKind.SUSPENDED) {
                        clearPendingState(sessionId);
                    }
                    finishSuccessfulTurn(sessionId, result);
                    finishActiveTurn(sessionId, turnId, ownershipToken, true);
                }
            }
            return result;
        } catch (Throwable failure) {
            runIfAuthorized(turnId,
                () -> recordFailureObservability(sessionId, model, before, started, failure));
            revokeExecution(turnId, "turn_failed");
            synchronized (lockFor(sessionId)) {
                if (ownsExecution(sessionId, turnId, ownershipToken)) {
                    clearPendingState(sessionId);
                    gate.release(sessionId);
                    finishActiveTurn(sessionId, turnId, ownershipToken, true);
                }
            }
            throw publicFailure("Answer turn execution failed", failure);
        } finally {
            setActiveMetric();
        }
    }

    @Override
    public boolean cancelQuestion(@NonNull String sessionId, @NonNull String reason) {
        validateClosed();
        validateText(sessionId, "sessionId");
        validateText(reason, "reason");
        synchronized (lockFor(sessionId)) {
            try {
                QuestionPendingContext pending = restorePending(sessionId);
                if (pending == null || pending.state() != QuestionPendingContext.State.PENDING) {
                    if (gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                        gate.release(sessionId);
                    }
                    return false;
                }
                if (!cancelPendingInternal(sessionId, reason)) {
                    gate.release(sessionId);
                    return false;
                }
                gate.release(sessionId);
                auditError(sessionId, "Question cancelled: " + reason, null, pending.model());
                return true;
            } catch (Throwable e) {
                throw publicFailure("Failed to cancel question", e);
            }
        }
    }

    // ── queue ──

    @Override
    public PendingMessage enqueue(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        try {
            return enqueueInternal(request);
        } catch (Throwable e) {
            throw publicFailure("Failed to enqueue message", e);
        }
    }

    @Override
    @Nullable
    public ClaimedMessage claim(@NonNull String sessionId) {
        validateClosed();
        validateText(sessionId, "sessionId");
        try {
            ClaimedMessage claimed = messageQueue.claim(sessionId);
            if (claimed != null) {
                claimSessions.put(claimed.claimToken(), sessionId);
            }
            setQueueMetric(sessionId);
            return claimed;
        } catch (Throwable e) {
            throw publicFailure("Failed to claim message", e);
        }
    }

    @Override
    public void ack(@NonNull String claimToken) {
        validateClosed();
        validateText(claimToken, "claimToken");
        try {
            messageQueue.ack(claimToken);
            String sessionId = claimSessions.remove(claimToken);
            if (sessionId != null) setQueueMetric(sessionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Ignoring stale queue claim {}", claimToken);
        } catch (Throwable e) {
            throw publicFailure("Failed to acknowledge message", e);
        }
    }

    @Override
    public void release(@NonNull String claimToken) {
        validateClosed();
        validateText(claimToken, "claimToken");
        try {
            messageQueue.release(claimToken);
            String sessionId = claimSessions.remove(claimToken);
            if (sessionId != null) setQueueMetric(sessionId);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("Ignoring stale queue claim {}", claimToken);
        } catch (Throwable e) {
            throw publicFailure("Failed to release message", e);
        }
    }

    // ── session lifecycle ──

    @Override
    public void clearSession(@NonNull String sessionId) {
        validateClosed();
        validateText(sessionId, "sessionId");
        synchronized (lockFor(sessionId)) {
            validateClosed();
            try {
                interruptActiveSession(sessionId, "session_cleared");
                if (activeTurnIds.containsKey(sessionId)) {
                    scheduleSessionAction(sessionId, SessionAction.CLEAR);
                    return;
                }
                applySessionAction(sessionId, SessionAction.CLEAR);
            } catch (Throwable e) {
                throw publicFailure("Failed to clear session", e);
            }
        }
    }

    @Override
    public boolean interrupt(@NonNull String turnId, @NonNull String reason) {
        validateClosed();
        validateText(turnId, "turnId");
        validateText(reason, "reason");
        try {
            ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
            boolean found = active != null;
            if (active != null) {
                active.queueInterrupt(reason);
            } else {
                for (Map.Entry<String, String> entry : activeTurnIds.entrySet()) {
                    if (turnId.equals(entry.getValue())) {
                        queueInterruptOnRegistration(entry.getKey(), turnId, reason);
                        found = true;
                        break;
                    }
                }
            }
            for (String sessionId : knownSessionIds()) {
                synchronized (lockFor(sessionId)) {
                    QuestionPendingContext pending = restorePending(sessionId);
                    if (pending != null && turnId.equals(pending.turnId())
                        && cancelPendingInternal(sessionId, reason)) {
                        gate.release(sessionId);
                        found = true;
                    }
                }
            }
            return found;
        } catch (Throwable e) {
            throw publicFailure("Failed to interrupt turn", e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        log.info("Closing TurnLoop");
        long drainNanos = TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
        long drainDeadline;
        try {
            drainDeadline = Math.addExact(System.nanoTime(), drainNanos);
        } catch (ArithmeticException ignored) {
            drainDeadline = Long.MAX_VALUE;
        }

        try {
            for (String sessionId : knownSessionIds()) {
                synchronized (lockFor(sessionId)) {
                    try {
                        QuestionPendingContext pending = restorePending(sessionId);
                        if (pending != null && pending.state() == QuestionPendingContext.State.PENDING) {
                            cancelPendingInternal(sessionId, "shutdown");
                            gate.release(sessionId);
                        }
                    } catch (Throwable e) {
                        log.warn("Failed to cancel pending question for {}: {}",
                            sessionId, e.getMessage());
                    }
                }
            }
        } catch (Throwable e) {
            log.error("Pending-question shutdown failed", e);
        }
        try {
            for (ActiveTurnRegistry.ActiveTurn active : activeTurnRegistry.activeTurns()) {
                active.queueInterrupt("shutdown");
            }
            for (Map.Entry<String, String> entry : activeTurnIds.entrySet()) {
                if (activeTurnRegistry.get(entry.getValue()) == null) {
                    queueInterruptOnRegistration(
                        entry.getKey(), entry.getValue(), "shutdown");
                }
            }
        } catch (Throwable e) {
            log.error("Active-turn interruption during close failed", e);
        }
        Thread executorCloser = Thread.ofVirtual()
            .name("magenta-turn-executor-close")
            .unstarted(() -> {
                try {
                    executor.close();
                } catch (Throwable e) {
                    log.error("Executor close failed", e);
                }
            });
        executorCloser.start();
        try {
            gate.drain(remainingDrainMillis(drainDeadline));
        } catch (Throwable e) {
            log.error("Turn gate drain failed", e);
        }
        finishExecutorClose(executorCloser, drainDeadline);
        try {
            List<ClaimedMessage> stale = messageQueue.recoverStaleClaims(Duration.ZERO);
            for (ClaimedMessage claim : stale) {
                try {
                    messageQueue.release(claim.claimToken());
                } catch (Throwable ignored) {
                    // recovery already returned the row to pending in the default store
                }
            }
        } catch (Throwable e) {
            log.error("Pending queue recovery during close failed", e);
        }
        try {
            engine.closeChatModels();
        } catch (Throwable e) {
            log.error("Model close failed", e);
        }
        if (gate instanceof SerialTurnGate serialTurnGate) {
            if (ownsTurnGate) {
                try {
                    serialTurnGate.close();
                } catch (Throwable e) {
                    log.error("Owned turn gate close failed", e);
                }
            } else {
                serialTurnGate.setTimeoutHandler(null);
            }
        } else if (ownsTurnGate && gate instanceof AutoCloseable closeableGate) {
            try {
                closeableGate.close();
            } catch (Throwable e) {
                log.error("Owned turn gate close failed", e);
            }
        }
        log.info("TurnLoop closed");
    }

    // ── views ──

    @Override
    public ToolSubinterface tools() {
        return toolsView;
    }

    @Override
    public MemorySubinterface memory() {
        return memoryView;
    }

    @Override
    public SessionSubinterface sessions() {
        return sessionsView;
    }

    private final class Tools implements ToolSubinterface {
        @Override
        public void register(@NonNull ToolCallback tool) {
            validateClosed();
            try {
                toolRegistry.register(tool);
            } catch (ToolException e) {
                throw e;
            } catch (Throwable e) {
                throw publicFailure("Failed to register tool", e);
            }
        }

        @Override
        public void registerToolBean(@NonNull Object bean) {
            validateClosed();
            try {
                toolRegistry.registerToolBean(bean);
            } catch (ToolException e) {
                throw e;
            } catch (Throwable e) {
                throw publicFailure("Failed to register tool bean", e);
            }
        }

        @Override
        public void unregister(@NonNull String toolName) {
            validateClosed();
            validateText(toolName, "toolName");
            try {
                toolRegistry.unregister(toolName);
            } catch (Throwable e) {
                throw publicFailure("Failed to unregister tool", e);
            }
        }

        @Override
        public List<ToolDefinition> definitions() {
            validateClosed();
            try {
                return toolRegistry.definitions();
            } catch (Throwable e) {
                throw publicFailure("Failed to list tools", e);
            }
        }
    }

    private final class Memory implements MemorySubinterface {
        @Override
        public List<TurnMessage> history(@NonNull String sessionId) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                return safeHistory(sessionId).stream()
                    .filter(message -> !isPendingMarker(message))
                    .toList();
            } catch (Throwable e) {
                throw publicFailure("Failed to read conversation history", e);
            }
        }

        @Override
        public void clear(@NonNull String sessionId) {
            clearSession(sessionId);
        }

        @Override
        public ContextUsage contextUsage(@NonNull String sessionId) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                ConversationSession session = sessionStore.findById(sessionId);
                String alias = session != null && session.model() != null
                    ? session.model() : engine.firstRegisteredModel(defaultModel);
                return engine.contextUsage(sessionId, alias);
            } catch (Throwable e) {
                throw publicFailure("Failed to compute context usage", e);
            }
        }
    }

    private final class Sessions implements SessionSubinterface {
        @Override
        public List<ConversationSession> list(@NonNull SessionFilter filter) {
            validateClosed();
            try {
                return sessionStore.list(filter);
            } catch (Throwable e) {
                throw publicFailure("Failed to list sessions", e);
            }
        }

        @Override
        @Nullable
        public ConversationSession get(@NonNull String sessionId) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                return sessionStore.findById(sessionId);
            } catch (Throwable e) {
                throw publicFailure("Failed to get session", e);
            }
        }

        @Override
        public void updateTitle(@NonNull String sessionId, @NonNull String title) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                sessionStore.updateTitle(sessionId, title);
            } catch (Throwable e) {
                throw publicFailure("Failed to update session title", e);
            }
        }

        @Override
        public void setFavorite(@NonNull String sessionId, boolean favorite) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                sessionStore.setFavorite(sessionId, favorite);
            } catch (Throwable e) {
                throw publicFailure("Failed to update session favorite", e);
            }
        }

        @Override
        public void setArchived(@NonNull String sessionId, boolean archived) {
            validateClosed();
            validateText(sessionId, "sessionId");
            try {
                sessionStore.setArchived(sessionId, archived);
            } catch (Throwable e) {
                throw publicFailure("Failed to update session archive state", e);
            }
        }

        @Override
        public void delete(@NonNull String sessionId) {
            validateClosed();
            validateText(sessionId, "sessionId");
            synchronized (lockFor(sessionId)) {
                validateClosed();
                try {
                    interruptActiveSession(sessionId, "session_deleted");
                    if (activeTurnIds.containsKey(sessionId)) {
                        scheduleSessionAction(sessionId, SessionAction.DELETE);
                        return;
                    }
                    applySessionAction(sessionId, SessionAction.DELETE);
                } catch (Throwable e) {
                    throw publicFailure("Failed to delete session", e);
                }
            }
        }
    }

    // ── gate/queue/session helpers ──

    private PendingMessage enqueueInternal(TurnRequest request) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (request.metadata() != null) metadata.putAll(request.metadata());
        metadata.put("turnloop.queued", "true");
        if (request.model() != null && !request.model().isBlank()) {
            metadata.put("turnloop.queued_model", request.model());
        }
        PendingMessage pending = messageQueue.enqueue(
            request.sessionId(), request.message(), metadata);
        setQueueMetric(request.sessionId());
        return pending;
    }

    private TurnResult queueResult(TurnRequest request, PendingMessage pending) {
        String turnId = UUID.randomUUID().toString();
        ContextUsage usage = safeUsage(request.sessionId(), request.model());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("queuedMessageId", pending.messageId());
        details.put("messageId", pending.messageId());
        TurnDiagnostic diagnostic = new TurnDiagnostic(
            turnId, request.sessionId(), Instant.now(), Instant.now(), TurnPhase.PREPARE,
            TurnDiagnostic.OutcomeKind.QUEUED, details);
        safeMetric(() -> metrics.recordTurnDuration(
            request.model() == null ? defaultModel : request.model(), "queued", 0));
        return new TurnResult(request.sessionId(), turnId,
            "Queued message " + pending.messageId(), usage, diagnostic, List.of());
    }

    private TurnEvent queuedEvent(TurnRequest request, PendingMessage pending) {
        TurnResult result = queueResult(request, pending);
        return new TurnEvent.Queued(result.turnId(), Instant.now(), pending.messageId(),
            result.diagnostic(), result.contextUsage());
    }

    private void registerExecution(String sessionId, String turnId, String gateToken) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.register(turnId);
        String previousTurn = activeTurnIds.putIfAbsent(sessionId, turnId);
        if (previousTurn != null) {
            active.revoke("registration_conflict");
            activeTurnRegistry.remove(turnId);
            throw new TurnLoopException("Turn already registered for session " + sessionId);
        }
        activeGateTokens.put(sessionId, gateToken);
    }

    private void cleanupFailedStart(String sessionId, String turnId, String gateToken) {
        if (turnId != null) {
            revokeExecution(turnId, "turn_setup_failed");
            activeTurnIds.remove(sessionId, turnId);
            activeTurnRegistry.remove(turnId);
        }
        if (gateToken != null) {
            activeGateTokens.remove(sessionId, gateToken);
            gate.release(sessionId);
        }
    }

    private boolean ownsExecution(String sessionId, String turnId, String gateToken) {
        return turnId.equals(activeTurnIds.get(sessionId))
            && gateToken.equals(activeGateTokens.get(sessionId));
    }

    private boolean runIfAuthorized(String turnId, Runnable action) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
        return active != null && active.runIfActive(action);
    }

    private void revokeExecution(String turnId, String reason) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
        if (active != null) active.revoke(reason);
    }

    private boolean fenceTimedOutTurn(String sessionId, String gateToken) {
        String turnId;
        ActiveTurnRegistry.ActiveTurn active;
        synchronized (lockFor(sessionId)) {
            turnId = activeTurnIds.get(sessionId);
            if (turnId == null) {
                return activeGateTokens.get(sessionId) == null;
            }
            if (!ownsExecution(sessionId, turnId, gateToken)) {
                return false;
            }
            active = activeTurnRegistry.get(turnId);
            if (active == null) {
                return false;
            }
        }

        active.revoke("turn_timeout");
        synchronized (lockFor(sessionId)) {
            if (ownsExecution(sessionId, turnId, gateToken)) {
                clearPendingAfterInterruption(sessionId, "turn_timeout");
                finishActiveTurn(sessionId, turnId, gateToken, true);
            }
        }
        setActiveMetric();
        return true;
    }

    private boolean isInterruptedOutcome(TurnDiagnostic.OutcomeKind outcome) {
        return outcome == TurnDiagnostic.OutcomeKind.INTERRUPTED
            || outcome == TurnDiagnostic.OutcomeKind.CANCELLED
            || outcome == TurnDiagnostic.OutcomeKind.TIMED_OUT
            || outcome == TurnDiagnostic.OutcomeKind.ABORTED;
    }

    private void finishSuccessfulTurn(String sessionId, TurnResult result) {
        try {
            TurnDiagnostic.OutcomeKind outcome = result.diagnostic().outcomeKind();
            if (isInterruptedOutcome(outcome) || outcome == TurnDiagnostic.OutcomeKind.FAILED) {
                clearPendingAfterInterruption(sessionId, outcome.name().toLowerCase());
                gate.release(sessionId);
                return;
            }
            QuestionPendingContext pending = activePending(sessionId);
            if (pending != null && pending.state() == QuestionPendingContext.State.PENDING) {
                gate.transitionToAwaitingAnswer(sessionId);
            } else {
                gate.release(sessionId);
            }
        } catch (Throwable e) {
            throw publicFailure("Failed to finalize session gate", e);
        }
    }

    private void finishGate(String sessionId, SignalType signal, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) return;
        if (signal == SignalType.CANCEL || signal == SignalType.ON_ERROR) {
            gate.release(sessionId);
            return;
        }
        if (activePending(sessionId) != null) {
            gate.transitionToAwaitingAnswer(sessionId);
        } else {
            gate.release(sessionId);
        }
    }

    private String resolveModel(TurnRequest request) {
        try {
            ConversationSession session = sessionStore.findById(request.sessionId());
            String stored = session == null ? null : session.model();
            return engine.selectModelAlias(request.model(), stored);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                e.getMessage() == null ? "Unknown model alias" : e.getMessage(),
                Map.of("error", "unknown_model"));
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Failed to resolve model", e);
        }
    }

    private void ensureSession(String sessionId, String model) {
        ConversationSession existing = sessionStore.findById(sessionId);
        Instant now = Instant.now();
        if (existing == null) {
            sessionStore.save(new ConversationSession(
                sessionId, null, model, false, false, "turnloop", now, now, Map.of()));
        } else if (model != null && !model.equals(existing.model())) {
            sessionStore.updateModel(sessionId, model);
        }
    }

    private TurnContext buildTurnContext(TurnRequest request, String model, boolean streaming) {
        TurnRequest effective = new TurnRequest(
            request.sessionId(), request.message(), model, request.toolNames(), streaming,
            request.metadata() == null ? Map.of() : Map.copyOf(request.metadata()));
        return new TurnContext(effective, UUID.randomUUID().toString(), model);
    }

    private QuestionPendingContext restorePending(String sessionId) {
        QuestionPendingContext pending = interactionStore.load(sessionId);
        if (pending == null) {
            pending = pendingFromMemory(sessionId);
            if (pending != null) {
                interactionStore.save(sessionId, pending);
            }
        }
        if (pending == null) return null;
        if (pending.state() != QuestionPendingContext.State.PENDING) {
            // ANSWERED is a durable in-flight answer claim. Fail closed by
            // keeping later submissions queued until the owner clears it.
            return pending;
        }
        if (gate.state(sessionId) == TurnGate.GateState.IDLE) {
            TurnGate.AcquireResult acquired = gate.acquire(sessionId, TurnGate.TurnGateMode.SEND);
            if (acquired instanceof TurnGate.AcquireResult.Acquired) {
                gate.transitionToAwaitingAnswer(sessionId);
            }
        }
        return pending;
    }

    private QuestionPendingContext activePending(String sessionId) {
        QuestionPendingContext pending = interactionStore.load(sessionId);
        return pending != null && pending.state() == QuestionPendingContext.State.PENDING
            ? pending : null;
    }

    private QuestionPendingContext pendingFromMemory(String sessionId) {
        List<TurnMessage> messages = conversationMemory.getMessages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            TurnMessage message = messages.get(i);
            if (!isPendingMarker(message)) continue;
            Map<String, String> metadata = message.metadata();
            List<String> questions = readJsonList(metadata.get("turnloop.pending_questions"));
            if (questions.isEmpty()) continue;
            Map<String, String> continuation = readJsonMap(metadata.get("turnloop.pending_metadata"));
            return new QuestionPendingContext(
                sessionId, metadata.get("turnloop.pending_turn_id"), questions,
                emptyToNull(metadata.get("turnloop.pending_model")), continuation);
        }
        return null;
    }

    private void clearPendingState(String sessionId) {
        removePendingMarkers(sessionId);
        interactionStore.delete(sessionId);
    }

    private void clearPendingAfterFailure(String sessionId) {
        try {
            QuestionPendingContext persisted = interactionStore.load(sessionId);
            if ((persisted != null && persisted.state() == QuestionPendingContext.State.PENDING)
                || pendingFromMemory(sessionId) != null) {
                clearPendingState(sessionId);
            }
        } catch (Throwable e) {
            log.warn("Failed to clear pending interaction for {}: {}", sessionId, e.getMessage());
        }
    }

    private void clearPendingAfterInterruption(String sessionId, String reason) {
        try {
            QuestionPendingContext pending = activePending(sessionId);
            if (pending != null && cancelPendingInternal(sessionId, reason)) {
                auditError(sessionId, "Question cancelled: " + reason, null, pending.model());
            }
        } catch (Throwable e) {
            log.warn("Failed to clear interrupted interaction for {}: {}", sessionId, e.getMessage());
        }
    }

    private boolean cancelPendingInternal(String sessionId, String reason) {
        if (!interactionStore.cancel(sessionId, reason)) return false;
        finalizePendingCancellation(sessionId, reason);
        return true;
    }

    private void finalizePendingCancellation(String sessionId, String reason) {
        removePendingMarkers(sessionId);
        interactionStore.delete(sessionId);
        conversationMemory.addMessages(sessionId, List.of(new TurnMessage(
            "system", "Question cancelled: " + reason, null, null,
            Map.of("turnloop.persistence_type", "interaction_cancelled"))));
    }

    private void removePendingMarkers(String sessionId) {
        List<TurnMessage> all = conversationMemory.getMessages(sessionId);
        List<TurnMessage> retained = all.stream()
            .filter(message -> !isPendingMarker(message))
            .toList();
        if (retained.size() == all.size()) return;
        conversationMemory.clear(sessionId);
        if (!retained.isEmpty()) conversationMemory.addMessages(sessionId, retained);
    }

    private boolean isPendingMarker(TurnMessage message) {
        return message != null && message.metadata() != null
            && PENDING_TYPE.equals(message.metadata().get("turnloop.persistence_type"));
    }

    private Map<String, String> continuationMetadata(QuestionPendingContext pending) {
        Map<String, String> metadata = new LinkedHashMap<>(pending.continuationContext());
        metadata.put("turnloop.continuation", "true");
        metadata.put("turnloop.pending_turn_id", pending.turnId());
        metadata.put("turnloop.pending_model", nullToEmpty(pending.model()));
        metadata.put("turnloop.pending_questions", writeJson(pending.questions()));
        metadata.put("turnloop.pending_tool_names",
            pending.continuationContext().getOrDefault("turnloop.pending_tool_names", "[]"));
        metadata.put("turnloop.pending_metadata", writeJson(pending.continuationContext()));
        return metadata;
    }

    private void interruptActiveSession(String sessionId, String reason) {
        String turnId = activeTurnIds.get(sessionId);
        if (turnId == null) return;
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
        if (active != null) {
            active.queueInterrupt(reason);
        } else {
            queueInterruptOnRegistration(sessionId, turnId, reason);
        }
    }

    private void queueInterruptOnRegistration(String sessionId, String turnId, String reason) {
        Thread.ofVirtual().name("magenta-turn-interrupt-wait").start(() -> {
            while (turnId.equals(activeTurnIds.get(sessionId))) {
                ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
                if (active != null) {
                    active.queueInterrupt(reason);
                    return;
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
        });
    }

    private void scheduleSessionAction(String sessionId, SessionAction action) {
        pendingSessionActions.merge(sessionId, action,
            (current, requested) -> current == SessionAction.DELETE ? current : requested);
    }

    private boolean finishActiveTurn(String sessionId, String turnId, String gateToken,
                                     boolean removeRegistry) {
        if (!ownsExecution(sessionId, turnId, gateToken)
            || !activeTurnIds.remove(sessionId, turnId)) {
            return false;
        }
        activeGateTokens.remove(sessionId, gateToken);
        if (removeRegistry) {
            activeTurnRegistry.remove(turnId);
        }
        SessionAction action = pendingSessionActions.get(sessionId);
        if (action != null) {
            applySessionAction(sessionId, action);
            pendingSessionActions.remove(sessionId, action);
        }
        return true;
    }

    private void applySessionAction(String sessionId, SessionAction action) {
        conversationMemory.clear(sessionId);
        interactionStore.delete(sessionId);
        if (action == SessionAction.DELETE) {
            messageQueue.deleteBySessionId(sessionId);
            sessionStore.delete(sessionId);
        }
        gate.release(sessionId);
    }

    private long remainingDrainMillis(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) return 0;
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private void finishExecutorClose(Thread closer, long deadlineNanos) {
        try {
            long remaining = remainingDrainMillis(deadlineNanos);
            if (remaining > 0) closer.join(remaining);
            if (closer.isAlive()) {
                closer.interrupt();
                closer.join(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closer.interrupt();
        }
        if (closer.isAlive()) {
            log.warn("Turn executor close did not stop after interruption");
        }
    }

    private List<String> knownSessionIds() {
        List<String> ids = new ArrayList<>();
        try {
            sessionStore.list(SessionFilter.all()).forEach(session -> ids.add(session.sessionId()));
        } catch (Throwable e) {
            log.warn("Unable to enumerate sessions during lifecycle cleanup: {}", e.getMessage());
        }
        return List.copyOf(ids);
    }

    private enum SessionAction {
        CLEAR,
        DELETE
    }

    // ── observations ──

    private void recordTurnObservability(TurnRequest request, TurnResult result,
                                         List<TurnMessage> before, String model,
                                         long started, boolean lifecycle) {
        auditNewMessages(request.sessionId(), before);
        safeAuditContext(request.sessionId(), result.contextUsage(), model);
        long duration = Math.max(0, System.currentTimeMillis() - started);
        safeMetric(() -> metrics.recordTurnDuration(model,
            result.diagnostic().outcomeKind().name().toLowerCase(), duration));
        result.toolActivities().forEach(activity -> safeMetric(() -> metrics.recordToolCall(
            activity.toolName(), activity.status().name())));
        if (lifecycle && result.diagnostic().outcomeKind() != TurnDiagnostic.OutcomeKind.QUEUED) {
            safeLifecycle(result);
        }
        setQueueMetric(request.sessionId());
    }

    private void recordStreamObservability(TurnRequest request, TurnContext context,
                                            List<TurnMessage> before,
                                            List<TurnEvent> events, String model,
                                            long started, SignalType signal,
                                            AtomicBoolean lifecycleRecorded) {
        if (events.isEmpty()) return;
        TurnResult reduced = reduceEvents(context, events);
        TurnResult observed = signal == SignalType.CANCEL
            ? withOutcome(reduced, TurnDiagnostic.OutcomeKind.CANCELLED,
                Map.of("reason", "subscriber_cancelled"))
            : reduced;
        auditNewMessages(request.sessionId(), before);
        safeAuditContext(request.sessionId(), observed.contextUsage(), model);
        long duration = Math.max(0, System.currentTimeMillis() - started);
        String outcome = observed.diagnostic().outcomeKind().name().toLowerCase();
        safeMetric(() -> metrics.recordTurnDuration(model, outcome, duration));
        observed.toolActivities().forEach(activity -> safeMetric(() -> metrics.recordToolCall(
            activity.toolName(), activity.status().name())));
        if (lifecycleRecorded.compareAndSet(false, true)
            && observed.diagnostic().outcomeKind() != TurnDiagnostic.OutcomeKind.QUEUED) {
            safeLifecycle(observed);
        }
        setQueueMetric(request.sessionId());
    }

    private void recordFailureObservability(String sessionId, String model,
                                            List<TurnMessage> before, long started,
                                            Throwable failure) {
        auditNewMessages(sessionId, before);
        auditError(sessionId, messageOf(failure), null, model);
        safeMetric(() -> metrics.recordTurnDuration(model, "failed",
            Math.max(0, System.currentTimeMillis() - started)));
    }

    private void auditNewMessages(String sessionId, List<TurnMessage> before) {
        try {
            List<TurnMessage> after = conversationMemory.getMessages(sessionId);
            List<TurnMessage> visibleBefore = before.stream()
                .filter(message -> !isPendingMarker(message)).toList();
            List<TurnMessage> visibleAfter = after.stream()
                .filter(message -> !isPendingMarker(message)).toList();
            int index = commonPrefix(visibleBefore, visibleAfter);
            for (; index < visibleAfter.size(); index++) {
                TurnMessage message = visibleAfter.get(index);
                if (isPendingMarker(message) || "user".equals(message.role())) continue;
                if ("assistant".equals(message.role())) {
                    auditWriter.recordAssistantMessage(sessionId, message);
                } else if ("tool".equals(message.role())) {
                    auditWriter.recordToolExecution(sessionId,
                        new AuditWriter.ToolExecutionRecord(
                            nullToEmpty(message.toolName()), nullToEmpty(message.toolCallId()),
                            "", message.text(), true, 0));
                }
            }
        } catch (Throwable e) {
            log.debug("Audit history capture failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void auditUser(String sessionId, TurnMessage message) {
        try {
            auditWriter.recordUserMessage(sessionId, message);
        } catch (Throwable e) {
            log.debug("User audit failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void auditError(String sessionId, String summary, String stack, String model) {
        try {
            auditWriter.recordError(sessionId, new AuditWriter.TurnError(summary, stack, model));
        } catch (Throwable e) {
            log.debug("Error audit failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void safeAuditContext(String sessionId, ContextUsage usage, String model) {
        try {
            auditWriter.recordContextUsage(sessionId, usage, model);
        } catch (Throwable e) {
            log.debug("Context audit failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    private void safeLifecycle(TurnResult result) {
        try {
            lifecycleListener.onTurnCompleted(result);
        } catch (Throwable e) {
            log.warn("Turn lifecycle listener failed: {}", e.getMessage());
        }
    }

    private void setActiveMetric() {
        safeMetric(() -> metrics.setActiveTurns(activeTurnRegistry.activeTurns().size()));
    }

    private void setQueueMetric(String sessionId) {
        safeMetric(() -> metrics.setQueueDepth(sessionId, messageQueue.list(sessionId).size()));
    }

    private void safeMetric(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.debug("Turn metric failed: {}", e.getMessage());
        }
    }

    // ── result reduction ──

    private TurnResult withOutcome(TurnResult result, TurnDiagnostic.OutcomeKind outcome,
                                   Map<String, Object> details) {
        TurnDiagnostic current = result.diagnostic();
        TurnDiagnostic diagnostic = new TurnDiagnostic(
            current.turnId(), current.sessionId(), current.startedAt(), Instant.now(),
            current.finalPhase(), outcome, Map.copyOf(details));
        return new TurnResult(result.sessionId(), result.turnId(), result.message(),
            result.contextUsage(), diagnostic, result.toolActivities());
    }

    private TurnResult reduceEvents(TurnContext context, List<TurnEvent> events) {
        TurnDiagnostic diagnostic = null;
        ContextUsage usage = null;
        StringBuilder message = new StringBuilder();
        List<ToolActivityData> activities = new ArrayList<>();
        for (TurnEvent event : events) {
            if (event instanceof TurnEvent.Completed completed) diagnostic = completed.diagnostic();
            if (event instanceof TurnEvent.Failed failed) diagnostic = failed.diagnostic();
            if (event instanceof TurnEvent.Queued queued) diagnostic = queued.diagnostic();
            if (event instanceof TurnEvent.QuestionPending pending) {
                diagnostic = new TurnDiagnostic(context.turnId(), context.sessionId(),
                    Instant.now(), Instant.now(), TurnPhase.FINALIZE,
                    TurnDiagnostic.OutcomeKind.SUSPENDED,
                    Map.of("questions", pending.questions()));
                if (message.isEmpty()) message.append(String.join("\n", pending.questions()));
            }
            if (event instanceof TurnEvent.Interrupt interrupt) {
                diagnostic = new TurnDiagnostic(context.turnId(), context.sessionId(),
                    Instant.now(), Instant.now(), TurnPhase.FINALIZE,
                    TurnDiagnostic.OutcomeKind.INTERRUPTED,
                    Map.of("reason", interrupt.reason()));
            }
            if (event instanceof TurnEvent.Chunk chunk && !chunk.thinking()) message.append(chunk.text());
            if (event instanceof TurnEvent.ToolActivity activity) {
                activities.add(new ToolActivityData(activity.toolName(), activity.toolCallId(),
                    activity.status(), activity.summary()));
            }
            if (event.contextUsage() != null) usage = event.contextUsage();
        }
        if (diagnostic == null) {
            diagnostic = new TurnDiagnostic(context.turnId(), context.sessionId(),
                Instant.now(), Instant.now(), TurnPhase.FINALIZE,
                TurnDiagnostic.OutcomeKind.COMPLETED, Map.of());
        }
        if (usage == null) usage = safeUsage(context.sessionId(), context.model());
        return new TurnResult(context.sessionId(), context.turnId(),
            message.isEmpty() ? null : message.toString(), usage, diagnostic, List.copyOf(activities));
    }

    // ── validation and exception boundary ──

    private void validateClosed() {
        if (closed.get()) throw new TurnLoopException("TurnLoop is closed");
    }

    private void validateRequest(TurnRequest request) {
        if (request == null) throw new TurnLoopException("request must not be null");
        validateText(request.sessionId(), "sessionId");
        validateText(request.message(), "message");
    }

    private void validateText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new TurnLoopException(name + " must not be blank");
        }
    }

    private Throwable mapInternalException(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TurnLoopException) return cause;
        return publicFailure("Turn execution failed", cause);
    }

    private TurnLoopException publicFailure(String prefix, Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof TurnLoopException turnLoopException) return turnLoopException;
        if (cause instanceof IllegalArgumentException) {
            return new ConfigurationException(
                cause.getMessage() == null ? prefix : cause.getMessage(),
                Map.of("error", "invalid_configuration"));
        }
        return new TurnLoopException(prefix + ": " + messageOf(cause), cause);
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private String messageOf(Throwable error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
            ? "unknown failure" : error.getMessage();
    }

    private TurnMessage currentUser(TurnRequest request) {
        return TurnMessage.user(request.message());
    }

    private List<TurnMessage> safeHistory(String sessionId) {
        return conversationMemory.getMessages(sessionId);
    }

    private ContextUsage safeUsage(String sessionId, String requestedModel) {
        try {
            String model = requestedModel == null || requestedModel.isBlank()
                ? engine.firstRegisteredModel(defaultModel) : requestedModel;
            return engine.contextUsage(sessionId, model);
        } catch (Throwable e) {
            return new ContextUsage(0, 0, 0);
        }
    }

    private int commonPrefix(List<TurnMessage> before, List<TurnMessage> after) {
        int limit = Math.min(before.size(), after.size());
        int index = 0;
        while (index < limit && before.get(index).equals(after.get(index))) index++;
        return index;
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new TurnLoopException("Unable to serialize interaction continuation", e);
        }
    }

    private List<String> readJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, String> readJsonMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return Map.copyOf(objectMapper.readValue(json, STRING_MAP));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static AuditWriter defaultAuditWriter(ConversationMemory memory, ObjectMapper mapper) {
        DataSource dataSource = dataSourceOf(memory);
        if (dataSource == null) return new NoOpTurnLoopAuditWriter();
        try {
            return new SqliteAuditWriter(dataSource, mapper);
        } catch (Throwable e) {
            log.warn("Durable audit writer unavailable: {}", e.getMessage());
            return new NoOpTurnLoopAuditWriter();
        }
    }

    private static DataSource dataSourceOf(Object repository) {
        if (repository == null) return null;
        for (String fieldName : List.of("dataSource", "jdbc")) {
            try {
                Field field = repository.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(repository);
                if (value instanceof DataSource dataSource) return dataSource;
                if (value != null) {
                    try {
                        Object nested = value.getClass().getMethod("getDataSource").invoke(value);
                        if (nested instanceof DataSource dataSource) return dataSource;
                    } catch (ReflectiveOperationException ignored) { }
                }
            } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }

    private static final class NoOpTurnLoopAuditWriter implements AuditWriter {
        @Override public void recordUserMessage(String s, TurnMessage m) { }
        @Override public void recordAssistantMessage(String s, TurnMessage m) { }
        @Override public void recordToolExecution(String s, ToolExecutionRecord r) { }
        @Override public void recordContextUsage(String s, ContextUsage u, String m) { }
        @Override public void recordError(String s, TurnError e) { }
    }

    private static final class NoOpTurnLoopMetrics implements TurnLoopMetrics {
        @Override public void recordTurnDuration(String model, String outcome, long durationMs) { }
        @Override public void recordToolCall(String toolName, String status) { }
        @Override public void setActiveTurns(long count) { }
        @Override public void setQueueDepth(String sessionId, long depth) { }
    }
}
