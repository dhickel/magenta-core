package io.mindspice.magenta.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.persistence.SqliteAuditWriter;
import io.mindspice.magenta.core.spi.AuditWriter;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.InteractionStore;
import io.mindspice.magenta.core.spi.MessageQueueStore;
import io.mindspice.magenta.core.spi.ModelCapabilityProvider;
import io.mindspice.magenta.core.spi.SessionStore;
import io.mindspice.magenta.core.spi.TurnGate;
import io.mindspice.magenta.core.spi.TurnLifecycleListener;
import io.mindspice.magenta.core.spi.TurnLoopMetrics;
import io.mindspice.magenta.core.spi.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;

/** Package-private implementation of the public turn-loop facade. */
final class DefaultTurnLoop implements TurnLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnLoop.class);
    private static final String PENDING_TYPE = "turnloop.pending_interaction";
    private static final String PENDING_ANSWERS = "turnloop.pending_answers";
    private static final String PENDING_INTERACTION_ID = "turnloop.pending_interaction_id";
    private static final String CONTINUATION = "turnloop.continuation";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final ConcurrentHashMap<String, Boolean> ACTIVE_ANSWER_CLAIMS =
        new ConcurrentHashMap<>();

    private final DefaultTurnEngine engine;
    private final TurnGate gate;
    private final MessageQueueStore messageQueueStore;
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
    private final String defaultModel;
    private final Duration claimLease;
    private final long drainTimeoutSeconds;
    private final boolean ownsTurnGate;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeTurnIds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> activeGateTokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SessionAction> pendingSessionActions =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DrainState> drainOwners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ExecutionHandle> executionsByTurn =
        new ConcurrentHashMap<>();
    private final ToolSubinterface toolsView;
    private final MemorySubinterface memoryView;
    private final SessionSubinterface sessionsView;

    DefaultTurnLoop(
        DefaultTurnEngine engine,
        TurnGate gate,
        MessageQueueStore messageQueueStore,
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
        String defaultModel,
        Duration claimLease,
        long drainTimeoutSeconds,
        AuditWriter auditWriter,
        boolean ownsTurnGate
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.messageQueueStore = Objects.requireNonNull(messageQueueStore, "messageQueueStore");
        this.interactionStore = Objects.requireNonNull(interactionStore, "interactionStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.conversationMemory = Objects.requireNonNull(conversationMemory, "conversationMemory");
        this.activeTurnRegistry = Objects.requireNonNull(activeTurnRegistry, "activeTurnRegistry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.modelCapabilityProvider = Objects.requireNonNull(
            modelCapabilityProvider, "modelCapabilityProvider");
        this.lifecycleListener = lifecycleListener == null ? outcome -> { } : lifecycleListener;
        this.metrics = metrics == null ? new NoOpTurnLoopMetrics() : metrics;
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.objectMapper = mapper;
        this.auditWriter = auditWriter == null
            ? defaultAuditWriter(conversationMemory, mapper) : auditWriter;
        this.defaultModel = ResponseValidation.nonBlank(defaultModel, "defaultModel");
        this.claimLease = validClaimLease(claimLease);
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
    public TurnResponse.Outcome send(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        PreparedSubmission submission;
        try {
            submission = prepareDirect(request, false);
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Unable to start turn", e);
        }
        if (submission instanceof QueuedSubmission queued) {
            return queued.outcome();
        }
        return executeBlocking(((ExecutionSubmission) submission).handle());
    }

    @Override
    public Flux<TurnResponse> stream(@NonNull TurnRequest request) {
        validateRequest(request);
        return Flux.defer(() -> {
            validateClosed();
            try {
                PreparedSubmission submission = prepareDirect(request, true);
                if (submission instanceof QueuedSubmission queued) {
                    return Flux.just(queued.outcome());
                }
                return executionStream(((ExecutionSubmission) submission).handle());
            } catch (TurnLoopException e) {
                return Flux.error(e);
            } catch (Throwable e) {
                return Flux.error(publicFailure("Unable to start stream", e));
            }
        });
    }

    private PreparedSubmission prepareDirect(TurnRequest request, boolean streaming) {
        String sessionId = request.sessionId();
        synchronized (lockFor(sessionId)) {
            validateClosed();
            Optional<InteractionStore.Interaction> pending = pendingInteractionLocked(sessionId);
            TurnGate.GateState state = gate.state(sessionId);
            if (activeTurnIds.containsKey(sessionId)
                || state == TurnGate.GateState.EXECUTING) {
                return queuedSubmission(request, QueueReason.SESSION_ACTIVE);
            }
            if (pending.isPresent() || state == TurnGate.GateState.AWAITING_ANSWER) {
                return queuedSubmission(request, QueueReason.AWAITING_QUESTIONS);
            }
            if (drainOwners.containsKey(sessionId)) {
                return queuedSubmission(request, QueueReason.SESSION_ACTIVE);
            }

            Optional<InteractionStore.Interaction> recovery = answeredRecoveryLocked(sessionId);
            if (recovery.isPresent()) {
                InteractionStore.Interaction interaction = recovery.get();
                String interactionId = interaction.interactionId();
                if (ACTIVE_ANSWER_CLAIMS.containsKey(interactionId)
                    || ACTIVE_ANSWER_CLAIMS.putIfAbsent(interactionId, Boolean.TRUE) != null) {
                    return queuedSubmission(request, QueueReason.SESSION_ACTIVE);
                }
                try {
                    TurnRequest continuation = continuationRequest(interaction);
                    ExecutionHandle handle = startExecutionLocked(
                        continuation, new ExecutionOrigin.Answer(interactionId),
                        streaming, TurnGate.TurnGateMode.ANSWER);
                    handle.answerInteractionId = interactionId;
                    handle.answerClaimId = interactionId;
                    return new ExecutionSubmission(handle);
                } catch (Throwable failure) {
                    ACTIVE_ANSWER_CLAIMS.remove(interactionId, Boolean.TRUE);
                    throw failure;
                }
            }

            ExecutionHandle handle = startExecutionLocked(
                request, new ExecutionOrigin.Direct(), streaming, streaming
                    ? TurnGate.TurnGateMode.STREAM : TurnGate.TurnGateMode.SEND);
            return new ExecutionSubmission(handle);
        }
    }

    // ── question interaction ──

    @Override
    public TurnResponse.Outcome answer(@NonNull String sessionId,
                                       @NonNull String interactionId,
                                       @NonNull List<String> answers) {
        validateClosed();
        validateText(sessionId, "sessionId");
        validateText(interactionId, "interactionId");
        ExecutionHandle handle;
        try {
            synchronized (lockFor(sessionId)) {
                validateClosed();
                handle = prepareAnswerLocked(sessionId, interactionId, answers, false);
            }
        } catch (InteractionStateException | TurnLoopBusyException | ConfigurationException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Unable to start answer continuation", e);
        }
        return executeBlocking(handle);
    }

    @Override
    public Flux<TurnResponse> streamAnswer(@NonNull String sessionId,
                                            @NonNull String interactionId,
                                            @NonNull List<String> answers) {
        validateText(sessionId, "sessionId");
        validateText(interactionId, "interactionId");
        return Flux.defer(() -> {
            validateClosed();
            try {
                ExecutionHandle handle;
                synchronized (lockFor(sessionId)) {
                    handle = prepareAnswerLocked(sessionId, interactionId, answers, true);
                }
                return executionStream(handle);
            } catch (InteractionStateException | TurnLoopBusyException
                     | ConfigurationException | IllegalArgumentException e) {
                return Flux.error(e);
            } catch (TurnLoopException e) {
                return Flux.error(e);
            } catch (Throwable e) {
                return Flux.error(publicFailure("Unable to start answer stream", e));
            }
        });
    }

    @Override
    public Optional<TurnResponse.Questions> pendingQuestions(@NonNull String sessionId) {
        validateClosed();
        validateText(sessionId, "sessionId");
        try {
            synchronized (lockFor(sessionId)) {
                return pendingInteractionLocked(sessionId)
                    .map(InteractionStore.Interaction::questions);
            }
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Failed to read pending questions", e);
        }
    }

    @Override
    public TurnResponse.QuestionsCancelled cancelQuestions(@NonNull String sessionId,
                                                            @NonNull String interactionId,
                                                            @NonNull String reason) {
        validateClosed();
        validateText(sessionId, "sessionId");
        validateText(interactionId, "interactionId");
        validateText(reason, "reason");
        try {
            TurnResponse.QuestionsCancelled outcome;
            synchronized (lockFor(sessionId)) {
                Optional<InteractionStore.Interaction> target = interactionStore.find(
                    sessionId, interactionId);
                InteractionState state = target.map(InteractionStore.Interaction::state)
                    .orElse(InteractionState.MISSING);
                if (state != InteractionState.PENDING) {
                    throw new InteractionStateException(sessionId, interactionId, state);
                }
                if (activeTurnIds.containsKey(sessionId)
                    || gate.state(sessionId) == TurnGate.GateState.EXECUTING
                    || drainOwners.containsKey(sessionId)) {
                    throw new TurnLoopBusyException(sessionId);
                }
                InteractionStore.MutationResult mutation = interactionStore.cancel(
                    sessionId, interactionId, reason);
                if (!mutation.applied()) {
                    throw new InteractionStateException(
                        sessionId, interactionId, mutation.state());
                }
                removeInteractionMarker(sessionId, interactionId);
                gate.release(sessionId);
                auditError(sessionId, "Question cancelled: " + reason, null,
                    target.map(InteractionStore.Interaction::model).orElse(defaultModel));
                outcome = new TurnResponse.QuestionsCancelled(
                    sessionId, interactionId, Instant.now(), reason);
            }
            observeStandaloneOutcome(outcome, sessionId, defaultModel);
            setQueueMetric(sessionId);
            return outcome;
        } catch (InteractionStateException | TurnLoopBusyException e) {
            throw e;
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Failed to cancel questions", e);
        }
    }

    private ExecutionHandle prepareAnswerLocked(String sessionId, String interactionId,
                                                List<String> answers, boolean streaming) {
        if (answers == null) {
            throw new IllegalArgumentException("answers must not be null");
        }
        InteractionStore.Interaction interaction = interactionStore.find(sessionId, interactionId)
            .orElseThrow(() -> new InteractionStateException(
                sessionId, interactionId, InteractionState.MISSING));
        if (interaction.state() != InteractionState.PENDING) {
            throw new InteractionStateException(sessionId, interactionId, interaction.state());
        }
        validateAnswers(interaction.questions().questions(), answers);
        if (drainOwners.containsKey(sessionId)
            || activeTurnIds.containsKey(sessionId)
            || gate.state(sessionId) == TurnGate.GateState.EXECUTING) {
            throw new TurnLoopBusyException(sessionId);
        }
        Optional<InteractionStore.Interaction> pending = pendingInteractionLocked(sessionId);
        if (pending.isEmpty() || !interactionId.equals(pending.get().interactionId())) {
            throw new InteractionStateException(sessionId, interactionId, InteractionState.MISSING);
        }

        if (ACTIVE_ANSWER_CLAIMS.putIfAbsent(interactionId, Boolean.TRUE) != null) {
            throw new TurnLoopBusyException(sessionId);
        }
        try {
            // Record the ordered answer facts before the CAS. If the process dies
            // immediately after the CAS, the durable marker still lets a rebuilt
            // facade locate the ANSWERED interaction and resume exact answers.
            markAnsweredInteraction(sessionId, interaction, answers);
            InteractionStore.MutationResult mutation = interactionStore.answer(
                sessionId, interactionId, List.copyOf(answers));
            if (!mutation.applied()) {
                restorePendingMarker(sessionId, interaction);
                throw new InteractionStateException(sessionId, interactionId, mutation.state());
            }

            TurnRequest continuation = continuationRequest(interaction, answers);
            ExecutionHandle handle = startExecutionLocked(
                continuation, new ExecutionOrigin.Answer(interactionId), streaming,
                TurnGate.TurnGateMode.ANSWER);
            handle.answerInteractionId = interactionId;
            handle.answerClaimId = interactionId;
            return handle;
        } catch (Throwable failure) {
            ACTIVE_ANSWER_CLAIMS.remove(interactionId, Boolean.TRUE);
            throw failure;
        }
    }

    // ── queue and drain ──

    @Override
    public TurnResponse.Queued enqueue(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        try {
            TurnResponse.Queued outcome;
            synchronized (lockFor(request.sessionId())) {
                outcome = enqueueInternal(request, QueueReason.EXPLICIT);
            }
            observeQueued(outcome, request);
            return outcome;
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Failed to enqueue message", e);
        }
    }

    @Override
    public Flux<TurnResponse> drain(@NonNull String sessionId) {
        validateText(sessionId, "sessionId");
        return Flux.create(sink -> {
            validateClosed();
            DrainState state;
            try {
                state = acquireDrain(sessionId);
            } catch (Throwable e) {
                sink.error(e);
                return;
            }
            sink.onCancel(state::cancel);
            Thread.ofVirtual().name("magenta-turn-drain-" + sessionId).start(
                () -> runDrain(state, sink));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private DrainState acquireDrain(String sessionId) {
        DrainState state = new DrainState(sessionId);
        if (drainOwners.putIfAbsent(sessionId, state) != null) {
            throw new TurnLoopBusyException(sessionId);
        }
        synchronized (lockFor(sessionId)) {
            if (closed.get()) {
                drainOwners.remove(sessionId, state);
                throw new TurnLoopException("TurnLoop is closed");
            }
            if (activeTurnIds.containsKey(sessionId)
                || gate.state(sessionId) == TurnGate.GateState.EXECUTING) {
                drainOwners.remove(sessionId, state);
                throw new TurnLoopBusyException(sessionId);
            }
        }
        return state;
    }

    private void runDrain(DrainState state, FluxSink<TurnResponse> sink) {
        try {
            messageQueueStore.recoverExpiredClaims();
            while (!state.cancelled.get() && !sink.isCancelled()) {
                InteractionStore.Interaction pending;
                ExecutionHandle recoveryHandle = null;
                synchronized (lockFor(state.sessionId)) {
                    if (activeTurnIds.containsKey(state.sessionId)
                        || gate.state(state.sessionId) == TurnGate.GateState.EXECUTING) {
                        throw new TurnLoopBusyException(state.sessionId);
                    }
                    pending = pendingInteractionLocked(state.sessionId).orElse(null);
                    if (pending == null) {
                        InteractionStore.Interaction recovery = answeredRecoveryLocked(
                            state.sessionId).orElse(null);
                        if (recovery != null) {
                            if (ACTIVE_ANSWER_CLAIMS.putIfAbsent(
                                recovery.interactionId(), Boolean.TRUE) != null) {
                                throw new TurnLoopBusyException(state.sessionId);
                            }
                            try {
                                recoveryHandle = startExecutionLocked(
                                    continuationRequest(recovery),
                                    new ExecutionOrigin.Answer(recovery.interactionId()), true,
                                    TurnGate.TurnGateMode.ANSWER);
                                recoveryHandle.answerInteractionId = recovery.interactionId();
                                recoveryHandle.answerClaimId = recovery.interactionId();
                            } catch (Throwable failure) {
                                ACTIVE_ANSWER_CLAIMS.remove(
                                    recovery.interactionId(), Boolean.TRUE);
                                throw failure;
                            }
                        }
                    }
                }
                if (pending != null) {
                    TurnResponse.Questions outcome = pending.questions();
                    observeStandaloneOutcome(outcome, state.sessionId, pending.model());
                    if (!sink.isCancelled()) {
                        sink.next(outcome);
                    }
                    break;
                }
                if (recoveryHandle != null) {
                    state.handle.set(recoveryHandle);
                    Throwable recoveryFailure = awaitDrainExecution(
                        state, sink, recoveryHandle);
                    state.handle.compareAndSet(recoveryHandle, null);
                    if (state.cancelled.get() || sink.isCancelled()) {
                        break;
                    }
                    if (recoveryFailure != null) {
                        if (!sink.isCancelled()) sink.error(recoveryFailure);
                        break;
                    }
                    TurnResponse.Outcome recovered = recoveryHandle.accumulator.outcome();
                    if (recovered instanceof TurnResponse.Message) {
                        continue;
                    }
                    if (recovered == null) {
                        throw failureException(recoveryHandle, TurnFailureCategory.INTERNAL,
                            "Recovered answer completed without an outcome");
                    }
                    break;
                }

                Optional<MessageQueueStore.Claim> claimed = messageQueueStore.claimOldest(
                    state.sessionId, Instant.now(), claimLease);
                if (claimed.isEmpty()) {
                    break;
                }
                MessageQueueStore.Claim claim = claimed.get();
                state.claim.set(claim);
                if (state.cancelled.get() || sink.isCancelled()) {
                    releaseClaim(state);
                    break;
                }

                ExecutionHandle handle;
                try {
                    TurnRequest request = requestFromClaim(claim.request());
                    synchronized (lockFor(state.sessionId)) {
                        if (activeTurnIds.containsKey(state.sessionId)
                            || gate.state(state.sessionId) == TurnGate.GateState.EXECUTING) {
                            throw new TurnLoopBusyException(state.sessionId);
                        }
                        if (pendingInteractionLocked(state.sessionId).isPresent()) {
                            throw new TurnLoopBusyException(state.sessionId);
                        }
                        handle = startExecutionLocked(
                            request, new ExecutionOrigin.Queued(claim.request().messageId()), true,
                            TurnGate.TurnGateMode.STREAM);
                    }
                } catch (Throwable failure) {
                    releaseClaim(state);
                    throw failure;
                }
                state.handle.set(handle);
                Throwable executionFailure = awaitDrainExecution(state, sink, handle);
                state.handle.compareAndSet(handle, null);

                if (state.cancelled.get() || sink.isCancelled()) {
                    releaseClaim(state);
                    break;
                }
                if (executionFailure != null) {
                    releaseClaim(state);
                    if (!sink.isCancelled()) {
                        sink.error(executionFailure);
                    }
                    break;
                }

                TurnResponse.Outcome outcome = handle.accumulator.outcome();
                if (outcome == null) {
                    releaseClaim(state);
                    throw failureException(handle, TurnFailureCategory.INTERNAL,
                        "Queued execution completed without an outcome");
                }
                if (outcome instanceof TurnResponse.Message
                    || outcome instanceof TurnResponse.Questions) {
                    acknowledgeClaim(state);
                    if (outcome instanceof TurnResponse.Questions) {
                        break;
                    }
                } else {
                    releaseClaim(state);
                    break;
                }
            }
            if (!sink.isCancelled()) {
                sink.complete();
            }
        } catch (Throwable failure) {
            releaseClaim(state);
            if (!sink.isCancelled()) {
                sink.error(mapDrainFailure(state.sessionId, failure));
            }
        } finally {
            state.cancelled.set(true);
            releaseClaim(state);
            drainOwners.remove(state.sessionId, state);
            setQueueMetric(state.sessionId);
        }
    }

    private Throwable awaitDrainExecution(DrainState state, FluxSink<TurnResponse> sink,
                                          ExecutionHandle handle) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Disposable disposable = executionStream(handle).subscribe(
            response -> {
                if (!sink.isCancelled()) {
                    sink.next(response);
                }
            },
            error -> {
                failure.set(error);
                done.countDown();
            },
            done::countDown);
        state.disposable.set(disposable);
        if (state.cancelled.get() || sink.isCancelled()) {
            disposable.dispose();
        }
        try {
            while (!done.await(50, TimeUnit.MILLISECONDS)) {
                if (state.cancelled.get() || sink.isCancelled()) {
                    return null;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            state.cancel();
            return e;
        } finally {
            state.disposable.compareAndSet(disposable, null);
        }
        return failure.get();
    }

    private TurnLoopException mapDrainFailure(String sessionId, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TurnLoopException turnLoopException) {
            return turnLoopException;
        }
        return publicFailure("Queued drain failed for session " + sessionId, cause);
    }

    private TurnRequest requestFromClaim(MessageQueueStore.QueuedRequest queued) {
        return new TurnRequest(queued.sessionId(), queued.message(), queued.model(),
            queued.toolNames(), queued.metadata());
    }

    private void acknowledgeClaim(DrainState state) {
        MessageQueueStore.Claim claim = state.claim.get();
        if (claim == null) return;
        boolean acknowledged;
        try {
            acknowledged = messageQueueStore.ack(claim.claimToken());
        } catch (Throwable failure) {
            throw new TurnLoopException(
                "Unable to acknowledge queued claim " + claim.claimToken(), failure);
        }
        if (!acknowledged) {
            throw new TurnLoopException(
                "Queued claim was not acknowledged: " + claim.claimToken());
        }
        state.claim.compareAndSet(claim, null);
        setQueueMetric(state.sessionId);
    }

    private void releaseClaim(DrainState state) {
        MessageQueueStore.Claim claim = state.claim.getAndSet(null);
        if (claim == null) return;
        try {
            if (!messageQueueStore.release(claim.claimToken())) {
                log.debug("Queued claim {} was already released", claim.claimToken());
            }
        } catch (Throwable e) {
            log.warn("Unable to release queued claim {}: {}",
                claim.claimToken(), e.getMessage());
        }
        setQueueMetric(state.sessionId);
    }

    private TurnResponse.Queued enqueueInternal(TurnRequest request, QueueReason reason) {
        ensureSessionExists(request.sessionId());
        String messageId = UUID.randomUUID().toString();
        Instant enqueuedAt = Instant.now();
        MessageQueueStore.QueuedRequest queued = new MessageQueueStore.QueuedRequest(
            messageId, request.sessionId(), request.message(), request.model(),
            request.toolNames(), request.metadata(), reason, enqueuedAt);
        messageQueueStore.insert(queued);
        setQueueMetric(request.sessionId());
        return new TurnResponse.Queued(request.sessionId(), messageId, enqueuedAt, reason);
    }

    private PreparedSubmission queuedSubmission(TurnRequest request, QueueReason reason) {
        TurnResponse.Queued outcome = enqueueInternal(request, reason);
        observeQueued(outcome, request);
        return new QueuedSubmission(outcome);
    }

    // ── execution start, reduction, and cleanup ──

    private ExecutionHandle startExecutionLocked(TurnRequest request, ExecutionOrigin origin,
                                                  boolean streaming, TurnGate.TurnGateMode mode) {
        String sessionId = request.sessionId();
        String model = resolveModel(request);
        ensureSession(sessionId, model);
        if (streaming && !supportsStreaming(model)) {
            throw new ConfigurationException(
                "Model '" + model + "' does not support streaming",
                Map.of("error", "streaming_unsupported", "model", model));
        }

        TurnGate.AcquireResult acquired = gate.acquire(sessionId, mode);
        if (!(acquired instanceof TurnGate.AcquireResult.Acquired ownership)) {
            throw new TurnLoopBusyException(sessionId);
        }
        String gateToken = ownership.lockToken();
        String turnId = UUID.randomUUID().toString();
        try {
            TurnContext context = buildTurnContext(request, turnId, model, streaming, origin);
            registerExecution(sessionId, turnId, gateToken);
            List<TurnMessage> before = safeHistory(sessionId);
            auditUser(sessionId, currentUser(request));
            setActiveMetric();
            ExecutionHandle handle = new ExecutionHandle(
                context, gateToken, before, model, Instant.now());
            executionsByTurn.put(turnId, handle);
            return handle;
        } catch (TurnLoopException e) {
            cleanupFailedStart(sessionId, turnId, gateToken);
            throw e;
        } catch (Throwable e) {
            cleanupFailedStart(sessionId, turnId, gateToken);
            throw publicFailure("Unable to initialize turn execution", e);
        }
    }

    private TurnResponse.Outcome executeBlocking(ExecutionHandle handle) {
        try {
            executionStream(handle).collectList().block();
            return handle.accumulator.requireOutcome(handle);
        } catch (Throwable failure) {
            TurnLoopException mapped = mapExecutionFailure(handle, failure);
            finishUnexpected(handle, mapped);
            throw mapped;
        } finally {
            setActiveMetric();
        }
    }

    /**
     * The stream path and blocking path both use this response accumulator and
     * reduction contract. The facade never reconstructs a terminal meaning
     * from progress or from a diagnostic enum.
     */
    private Flux<TurnResponse> executionStream(ExecutionHandle handle) {
        Flux<TurnResponse> engineResponses = engine.stream(handle.context)
            .concatWith(Flux.defer(() -> Flux.error(failureException(
                handle, TurnFailureCategory.INTERNAL,
                "Turn completed without an outcome"))));
        return Flux.merge(engineResponses, handle.forcedOutcomes.asFlux())
            .takeUntil(TurnResponse.Outcome.class::isInstance)
            .doOnNext(handle.accumulator::accept)
            .doOnComplete(() -> finishExpected(handle,
                handle.accumulator.requireOutcome(handle)))
            .onErrorMap(failure -> mapExecutionFailure(handle, failure))
            .doOnError(failure -> finishUnexpected(handle, failure))
            .doFinally(signal -> {
                try {
                    if (signal == SignalType.CANCEL
                        && !handle.terminalObserved.get()) {
                        TurnResponse.Outcome outcome = handle.accumulator.outcome();
                        finishExpected(handle, outcome == null
                            ? cancelledOutcome(handle, "subscriber_cancelled") : outcome);
                    } else if (signal == SignalType.ON_COMPLETE
                        && !handle.terminalObserved.get()) {
                        finishExpected(handle, handle.accumulator.outcome());
                    } else if (signal == SignalType.ON_ERROR
                        && !handle.terminalObserved.get()) {
                        finishUnexpected(handle,
                            new TurnLoopException("Turn stream terminated unexpectedly"));
                    }
                } catch (Throwable cleanupFailure) {
                    log.warn("Turn stream finalization failed for {}: {}",
                        handle.context.turnId(), cleanupFailure.getMessage());
                } finally {
                    setActiveMetric();
                }
            });
    }

    private TurnLoopException mapExecutionFailure(ExecutionHandle handle, Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TurnFailedException turnFailed) {
            return turnFailed;
        }
        if (isSpecializedFailure(cause)) {
            return (TurnLoopException) cause;
        }
        TurnPhase phase = Optional.ofNullable(activeTurnRegistry.get(handle.context.turnId()))
            .map(ActiveTurnRegistry.ActiveTurn::phase)
            .orElse(TurnPhase.FINALIZE);
        TurnFailureCategory category = failureCategory(cause, phase);
        return failureException(handle, category, safeFailureMessage(category));
    }

    private TurnFailedException failureException(ExecutionHandle handle,
                                                  TurnFailureCategory category,
                                                  String message) {
        Instant failedAt = atOrAfter(handle.startedAt);
        TurnFailure failure = new TurnFailure(
            handle.context.sessionId(), handle.context.turnId(),
            handle.context.executionOrigin(),
            Optional.ofNullable(activeTurnRegistry.get(handle.context.turnId()))
                .map(ActiveTurnRegistry.ActiveTurn::phase)
                .orElse(TurnPhase.FINALIZE),
            category, handle.startedAt, failedAt,
            ResponseValidation.nonBlank(message, "message"));
        return new TurnFailedException(failure.message(), failure);
    }

    private TurnFailureCategory failureCategory(Throwable failure, TurnPhase phase) {
        if (failure instanceof ToolException) return TurnFailureCategory.TOOL;
        if (failure instanceof TurnFailedException.ContextExhaustedException) {
            return TurnFailureCategory.CONTEXT;
        }
        return switch (phase) {
            case INVOKE_MODEL -> TurnFailureCategory.MODEL;
            case EXECUTE_TOOLS -> TurnFailureCategory.TOOL;
            case FINALIZE -> TurnFailureCategory.PERSISTENCE;
            case PREPARE, EVALUATE, REPAIR, DONE -> TurnFailureCategory.EXECUTION;
        };
    }

    private String safeFailureMessage(TurnFailureCategory category) {
        return switch (category) {
            case MODEL -> "Model execution failed";
            case TOOL -> "Tool execution failed";
            case CONTEXT -> "Context processing failed";
            case PERSISTENCE -> "Turn state persistence failed";
            case EXECUTION -> "Turn execution failed";
            case INTERNAL -> "Internal turn execution failure";
        };
    }

    private boolean isSpecializedFailure(Throwable failure) {
        return failure instanceof ConfigurationException
            || failure instanceof ConversationNotFoundException
            || failure instanceof InteractionStateException
            || failure instanceof TurnLoopBusyException;
    }

    private void finishExpected(ExecutionHandle handle, TurnResponse.Outcome outcome) {
        if (outcome == null || !handle.terminalObserved.compareAndSet(false, true)) {
            return;
        }
        recordOutcomeObservability(handle, outcome);
        cleanupExecution(handle, outcome, false);
    }

    private void finishUnexpected(ExecutionHandle handle, Throwable failure) {
        if (!handle.terminalObserved.compareAndSet(false, true)) {
            return;
        }
        TurnLoopException mapped = mapExecutionFailure(handle, failure);
        if (mapped instanceof TurnFailedException turnFailed) {
            recordFailureObservability(handle, turnFailed.failure());
        }
        cleanupExecution(handle, null, true);
    }

    private TurnResponse.TurnCancelled cancelledOutcome(ExecutionHandle handle, String reason) {
        return new TurnResponse.TurnCancelled(executionInfo(handle),
            ResponseValidation.nonBlank(reason, "reason"));
    }

    private ExecutionInfo executionInfo(ExecutionHandle handle) {
        ContextUsage usage = handle.accumulator.contextUsage();
        if (usage == null) {
            usage = handle.context.currentContextUsage();
        }
        if (usage == null) {
            usage = safeUsage(handle.context.sessionId(), handle.model);
        }
        return new ExecutionInfo(
            handle.context.sessionId(), handle.context.turnId(), handle.context.executionOrigin(),
            handle.startedAt, atOrAfter(handle.startedAt), usage,
            handle.accumulator.toolActivities());
    }

    private void cleanupExecution(ExecutionHandle handle, TurnResponse.Outcome outcome,
                                  boolean failure) {
        String sessionId = handle.context.sessionId();
        try {
            ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(handle.context.turnId());
            if (active != null && active.revocationReason().isEmpty()) {
                active.revoke(failure ? "turn_failed" : "turn_completed");
            }
            synchronized (lockFor(sessionId)) {
                boolean owns = ownsExecution(sessionId, handle.context.turnId(), handle.gateToken);
                if (handle.answerInteractionId != null && !failure) {
                    if (outcome instanceof TurnResponse.Message
                        || outcome instanceof TurnResponse.Questions question
                            && !handle.answerInteractionId.equals(question.interactionId())) {
                        try {
                            interactionStore.delete(sessionId, handle.answerInteractionId);
                            removeInteractionMarker(sessionId, handle.answerInteractionId);
                        } catch (Throwable cleanupFailure) {
                            log.warn("Unable to consume answered interaction {}: {}",
                                handle.answerInteractionId, cleanupFailure.getMessage());
                        }
                    }
                }
                if (owns) {
                    try {
                        if (outcome instanceof TurnResponse.Questions) {
                            gate.transitionToAwaitingAnswer(sessionId);
                        } else {
                            gate.release(sessionId);
                        }
                    } finally {
                        finishActiveTurn(sessionId, handle.context.turnId(), handle.gateToken, true);
                    }
                } else {
                    activeTurnRegistry.remove(handle.context.turnId());
                }
            }
        } catch (Throwable cleanupFailure) {
            log.warn("Execution cleanup failed for {}: {}",
                handle.context.turnId(), cleanupFailure.getMessage());
            activeTurnRegistry.remove(handle.context.turnId());
        } finally {
            executionsByTurn.remove(handle.context.turnId(), handle);
            if (handle.answerClaimId != null) {
                ACTIVE_ANSWER_CLAIMS.remove(handle.answerClaimId, Boolean.TRUE);
            }
            setQueueMetric(sessionId);
        }
    }

    private void registerExecution(String sessionId, String turnId, String gateToken) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.register(turnId);
        String previous = activeTurnIds.putIfAbsent(sessionId, turnId);
        if (previous != null) {
            active.revoke("registration_conflict");
            activeTurnRegistry.remove(turnId);
            throw new TurnLoopBusyException(sessionId);
        }
        activeGateTokens.put(sessionId, gateToken);
    }

    private void cleanupFailedStart(String sessionId, String turnId, String gateToken) {
        if (turnId != null) {
            activeTurnIds.remove(sessionId, turnId);
            activeGateTokens.remove(sessionId, gateToken);
            activeTurnRegistry.remove(turnId);
        }
        if (gateToken != null) {
            gate.release(sessionId);
        }
    }

    private boolean ownsExecution(String sessionId, String turnId, String gateToken) {
        return turnId.equals(activeTurnIds.get(sessionId))
            && gateToken.equals(activeGateTokens.get(sessionId));
    }

    private void revokeExecution(String turnId, String reason) {
        ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
        if (active != null) {
            active.revoke(reason);
        }
    }

    private boolean fenceTimedOutTurn(String sessionId, String gateToken) {
        synchronized (lockFor(sessionId)) {
            String turnId = activeTurnIds.get(sessionId);
            if (turnId == null || !ownsExecution(sessionId, turnId, gateToken)) {
                return false;
            }
            ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
            ExecutionHandle handle = executionsByTurn.get(turnId);
            if (active == null || handle == null || !active.revoke("turn_timeout")) {
                return false;
            }
            Sinks.EmitResult emitted = handle.forcedOutcomes.tryEmitNext(
                new TurnResponse.TimedOut(executionInfo(handle), "turn_timeout"));
            if (emitted.isFailure()) {
                log.warn("Unable to terminate timed-out execution {}: {}", turnId, emitted);
                return handle.terminalObserved.get();
            }
            setActiveMetric();
            return true;
        }
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

    // ── session lifecycle ──

    @Override
    public void clearSession(@NonNull String sessionId) {
        validateClosed();
        validateText(sessionId, "sessionId");
        synchronized (lockFor(sessionId)) {
            validateClosed();
            try {
                DrainState drain = drainOwners.get(sessionId);
                if (drain != null) drain.cancel();
                interruptActiveSession(sessionId, "session_cleared");
                if (activeTurnIds.containsKey(sessionId)
                    || gate.state(sessionId) == TurnGate.GateState.EXECUTING) {
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
            boolean found = false;
            ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
            if (active != null) {
                active.queueInterrupt(reason);
                found = true;
            }
            for (Map.Entry<String, String> entry : activeTurnIds.entrySet()) {
                if (turnId.equals(entry.getValue())) {
                    queueInterruptOnRegistration(entry.getKey(), turnId, reason);
                    found = true;
                }
            }
            for (String sessionId : knownSessionIds()) {
                synchronized (lockFor(sessionId)) {
                    InteractionStore.Interaction pending = pendingInteractionLocked(sessionId)
                        .orElse(null);
                    if (pending != null
                        && turnId.equals(pending.questions().execution().turnId())) {
                        InteractionStore.MutationResult mutation = interactionStore.cancel(
                            sessionId, pending.interactionId(), reason);
                        if (mutation.applied()) {
                            removeInteractionMarker(sessionId, pending.interactionId());
                            gate.release(sessionId);
                            TurnResponse.QuestionsCancelled outcome =
                                new TurnResponse.QuestionsCancelled(
                                    sessionId, pending.interactionId(), Instant.now(), reason);
                            observeStandaloneOutcome(outcome, sessionId, pending.model());
                            found = true;
                        }
                    }
                }
            }
            return found;
        } catch (TurnLoopException e) {
            throw e;
        } catch (Throwable e) {
            throw publicFailure("Failed to interrupt turn", e);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        log.info("Closing TurnLoop");
        long drainNanos = TimeUnit.SECONDS.toNanos(drainTimeoutSeconds);
        long deadline;
        try {
            deadline = Math.addExact(System.nanoTime(), drainNanos);
        } catch (ArithmeticException e) {
            deadline = Long.MAX_VALUE;
        }

        for (DrainState state : drainOwners.values()) {
            state.cancel();
        }
        for (String sessionId : knownSessionIds()) {
            synchronized (lockFor(sessionId)) {
                try {
                    InteractionStore.Interaction pending = interactionStore.pending(sessionId)
                        .orElse(null);
                    if (pending != null) {
                        InteractionStore.MutationResult mutation = interactionStore.cancel(
                            sessionId, pending.interactionId(), "shutdown");
                        if (mutation.applied()) {
                            removeInteractionMarker(sessionId, pending.interactionId());
                        }
                    }
                    if (gate.state(sessionId) == TurnGate.GateState.AWAITING_ANSWER) {
                        gate.release(sessionId);
                    }
                } catch (Throwable e) {
                    log.warn("Failed to close pending interaction for {}: {}",
                        sessionId, e.getMessage());
                }
            }
        }
        try {
            for (ExecutionHandle handle : executionsByTurn.values()) {
                if (handle.answerClaimId != null) {
                    ACTIVE_ANSWER_CLAIMS.remove(handle.answerClaimId, Boolean.TRUE);
                }
            }
            for (ActiveTurnRegistry.ActiveTurn active : activeTurnRegistry.activeTurns()) {
                active.revoke("shutdown");
            }
            for (Map.Entry<String, String> entry : activeTurnIds.entrySet()) {
                queueInterruptOnRegistration(entry.getKey(), entry.getValue(), "shutdown");
            }
        } catch (Throwable e) {
            log.warn("Active-turn shutdown fencing failed: {}", e.getMessage());
        }

        Thread executorCloser = Thread.ofVirtual().name("magenta-turn-executor-close")
            .unstarted(() -> {
                try {
                    executor.close();
                } catch (Throwable e) {
                    log.error("Executor close failed", e);
                }
            });
        executorCloser.start();
        try {
            gate.drain(remainingDrainMillis(deadline));
        } catch (Throwable e) {
            log.warn("Turn gate drain failed: {}", e.getMessage());
        }
        finishExecutorClose(executorCloser, deadline);
        forceCloseExecutionRegistrations();
        try {
            engine.closeChatModels();
        } catch (Throwable e) {
            log.warn("Model close failed: {}", e.getMessage());
        }
        if (gate instanceof SerialTurnGate serialTurnGate) {
            serialTurnGate.setTimeoutHandler(null);
            if (ownsTurnGate) {
                try {
                    serialTurnGate.close();
                } catch (Throwable e) {
                    log.warn("Owned turn gate close failed: {}", e.getMessage());
                }
            }
        } else if (ownsTurnGate && gate instanceof AutoCloseable closeableGate) {
            try {
                closeableGate.close();
            } catch (Throwable e) {
                log.warn("Owned turn gate close failed: {}", e.getMessage());
            }
        }
        drainOwners.clear();
        sessionLocks.clear();
        log.info("TurnLoop closed");
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
    }

    private void forceCloseExecutionRegistrations() {
        for (Map.Entry<String, String> entry : activeTurnIds.entrySet()) {
            String sessionId = entry.getKey();
            String turnId = entry.getValue();
            synchronized (lockFor(sessionId)) {
                activeTurnIds.remove(sessionId, turnId);
                activeGateTokens.remove(sessionId);
                activeTurnRegistry.remove(turnId);
                try {
                    gate.release(sessionId);
                } catch (Throwable e) {
                    log.debug("Unable to release closed session gate {}: {}",
                        sessionId, e.getMessage());
                }
            }
        }
        for (ActiveTurnRegistry.ActiveTurn active : activeTurnRegistry.activeTurns()) {
            activeTurnRegistry.remove(active.turnId());
        }
        executionsByTurn.clear();
    }

    private List<String> knownSessionIds() {
        try {
            return sessionStore.list(SessionFilter.all()).stream()
                .map(ConversationSession::sessionId).toList();
        } catch (Throwable e) {
            log.warn("Unable to enumerate sessions during shutdown: {}", e.getMessage());
            return List.of();
        }
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
        if (removeRegistry) activeTurnRegistry.remove(turnId);
        SessionAction action = pendingSessionActions.remove(sessionId);
        if (action != null) applySessionAction(sessionId, action);
        return true;
    }

    private void applySessionAction(String sessionId, SessionAction action) {
        removePendingMarkers(sessionId);
        conversationMemory.clear(sessionId);
        interactionStore.deleteBySessionId(sessionId);
        if (action == SessionAction.DELETE) {
            messageQueueStore.deleteBySessionId(sessionId);
            sessionStore.delete(sessionId);
        }
        gate.release(sessionId);
    }

    private enum SessionAction { CLEAR, DELETE }

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
                    .filter(message -> !isPendingMarker(message)).toList();
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
                    DrainState drain = drainOwners.get(sessionId);
                    if (drain != null) drain.cancel();
                    interruptActiveSession(sessionId, "session_deleted");
                    if (activeTurnIds.containsKey(sessionId)
                        || gate.state(sessionId) == TurnGate.GateState.EXECUTING) {
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

    // ── interaction persistence and session setup ──

    private Optional<InteractionStore.Interaction> pendingInteractionLocked(String sessionId) {
        Optional<InteractionStore.Interaction> pending = interactionStore.pending(sessionId);
        pending.ifPresent(interaction -> discardSupersededAnsweredInteractions(
            sessionId, interaction.interactionId()));
        if (pending.isPresent() && gate.state(sessionId) == TurnGate.GateState.IDLE
            && !activeTurnIds.containsKey(sessionId)) {
            TurnGate.AcquireResult acquired = gate.acquire(sessionId, TurnGate.TurnGateMode.SEND);
            if (acquired instanceof TurnGate.AcquireResult.Acquired) {
                gate.transitionToAwaitingAnswer(sessionId);
            }
        }
        return pending;
    }

    private void discardSupersededAnsweredInteractions(String sessionId,
                                                        String pendingInteractionId) {
        List<String> staleIds = safeHistory(sessionId).stream()
            .filter(this::isPendingMarker)
            .map(message -> message.metadata().get(PENDING_INTERACTION_ID))
            .filter(Objects::nonNull)
            .filter(id -> !id.equals(pendingInteractionId))
            .filter(id -> !readJsonList(findMarker(sessionId, id)
                .map(message -> message.metadata().get(PENDING_ANSWERS)).orElse(null)).isEmpty())
            .distinct()
            .toList();
        for (String staleId : staleIds) {
            try {
                Optional<InteractionStore.Interaction> stale = interactionStore.find(
                    sessionId, staleId);
                if (stale.isPresent() && stale.get().state() == InteractionState.ANSWERED) {
                    interactionStore.delete(sessionId, staleId);
                    removeInteractionMarker(sessionId, staleId);
                }
            } catch (Throwable e) {
                log.debug("Unable to discard superseded interaction {}: {}",
                    staleId, e.getMessage());
            }
        }
    }

    private Optional<TurnMessage> findMarker(String sessionId, String interactionId) {
        return safeHistory(sessionId).stream()
            .filter(this::isPendingMarker)
            .filter(message -> interactionId.equals(
                message.metadata().get(PENDING_INTERACTION_ID)))
            .findFirst();
    }

    private Optional<InteractionStore.Interaction> answeredRecoveryLocked(String sessionId) {
        List<TurnMessage> messages = safeHistory(sessionId);
        for (int index = messages.size() - 1; index >= 0; index--) {
            TurnMessage message = messages.get(index);
            if (!isPendingMarker(message)) continue;
            String interactionId = message.metadata().get(PENDING_INTERACTION_ID);
            List<String> answers = readJsonList(message.metadata().get(PENDING_ANSWERS));
            if (interactionId == null || answers.isEmpty()) continue;
            Optional<InteractionStore.Interaction> found = interactionStore.find(
                sessionId, interactionId);
            if (found.isPresent() && found.get().state() == InteractionState.ANSWERED
                && found.get().answers().equals(answers)) {
                return found;
            }
        }
        return Optional.empty();
    }

    private TurnRequest continuationRequest(InteractionStore.Interaction interaction) {
        return continuationRequest(interaction, interaction.answers());
    }

    private TurnRequest continuationRequest(InteractionStore.Interaction interaction,
                                             List<String> answers) {
        Map<String, String> metadata = continuationMetadata(interaction, answers);
        String message = String.join("\n", answers);
        return new TurnRequest(interaction.sessionId(), message, interaction.model(),
            interaction.explicitToolNames(), metadata);
    }

    private Map<String, String> continuationMetadata(InteractionStore.Interaction interaction,
                                                       List<String> answers) {
        Map<String, String> metadata = new LinkedHashMap<>(interaction.continuationMetadata());
        metadata.put(CONTINUATION, "true");
        metadata.put(PENDING_INTERACTION_ID, interaction.interactionId());
        metadata.put("turnloop.pending_turn_id",
            interaction.questions().execution().turnId());
        metadata.put("turnloop.pending_model", interaction.model());
        metadata.put("turnloop.pending_questions", writeJson(interaction.questions().questions()));
        metadata.put("turnloop.pending_tool_names", writeJson(
            interaction.explicitToolNames() == null ? List.of() : interaction.explicitToolNames()));
        metadata.put("turnloop.pending_metadata", writeJson(interaction.continuationMetadata()));
        metadata.put(PENDING_ANSWERS, writeJson(answers));
        return Map.copyOf(metadata);
    }

    private void markAnsweredInteraction(String sessionId,
                                          InteractionStore.Interaction interaction,
                                          List<String> answers) {
        List<TurnMessage> messages = safeHistory(sessionId);
        List<TurnMessage> updated = new ArrayList<>(messages.size());
        boolean changed = false;
        for (TurnMessage message : messages) {
            if (!isPendingMarker(message)
                || !interaction.interactionId().equals(
                    message.metadata().get(PENDING_INTERACTION_ID))) {
                updated.add(message);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(message.metadata());
            metadata.put(PENDING_ANSWERS, writeJson(answers));
            updated.add(new TurnMessage(message.role(), message.text(), message.toolName(),
                message.toolCallId(), metadata));
            changed = true;
        }
        if (changed) {
            conversationMemory.replaceMessages(sessionId, List.copyOf(updated));
        } else {
            conversationMemory.addMessages(sessionId, List.of(answeredMarker(interaction, answers)));
        }
    }

    private void restorePendingMarker(String sessionId,
                                       InteractionStore.Interaction interaction) {
        List<TurnMessage> messages = safeHistory(sessionId);
        List<TurnMessage> updated = new ArrayList<>(messages.size());
        boolean changed = false;
        for (TurnMessage message : messages) {
            if (!isPendingMarker(message)
                || !interaction.interactionId().equals(
                    message.metadata().get(PENDING_INTERACTION_ID))) {
                updated.add(message);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(message.metadata());
            metadata.remove(PENDING_ANSWERS);
            updated.add(new TurnMessage(message.role(), message.text(), message.toolName(),
                message.toolCallId(), metadata));
            changed = true;
        }
        if (changed) {
            conversationMemory.replaceMessages(sessionId, List.copyOf(updated));
        }
    }

    private TurnMessage answeredMarker(InteractionStore.Interaction interaction,
                                       List<String> answers) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("turnloop.persistence_type", PENDING_TYPE);
        metadata.put("turnloop.pending_turn_id",
            interaction.questions().execution().turnId());
        metadata.put("turnloop.pending_model", interaction.model());
        metadata.put(PENDING_INTERACTION_ID, interaction.interactionId());
        metadata.put("turnloop.pending_questions", writeJson(interaction.questions().questions()));
        metadata.put("turnloop.pending_tool_names", writeJson(
            interaction.explicitToolNames() == null ? List.of() : interaction.explicitToolNames()));
        metadata.put("turnloop.pending_metadata", writeJson(interaction.continuationMetadata()));
        metadata.put(PENDING_ANSWERS, writeJson(answers));
        return new TurnMessage("system", "[Magenta interaction pending]", null, null, metadata);
    }

    private void removeInteractionMarker(String sessionId, String interactionId) {
        List<TurnMessage> messages = safeHistory(sessionId);
        List<TurnMessage> retained = messages.stream()
            .filter(message -> !isPendingMarker(message)
                || !interactionId.equals(message.metadata().get(PENDING_INTERACTION_ID)))
            .toList();
        if (retained.size() != messages.size()) {
            conversationMemory.replaceMessages(sessionId, retained);
        }
    }

    private void removePendingMarkers(String sessionId) {
        List<TurnMessage> messages = safeHistory(sessionId);
        List<TurnMessage> retained = messages.stream()
            .filter(message -> !isPendingMarker(message)).toList();
        if (retained.size() != messages.size()) {
            conversationMemory.replaceMessages(sessionId, retained);
        }
    }

    private boolean isPendingMarker(TurnMessage message) {
        return message != null && message.metadata() != null
            && PENDING_TYPE.equals(message.metadata().get("turnloop.persistence_type"));
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

    private void ensureSessionExists(String sessionId) {
        ConversationSession existing = sessionStore.findById(sessionId);
        if (existing == null) {
            Instant now = Instant.now();
            sessionStore.save(new ConversationSession(
                sessionId, null, defaultModel, false, false, "turnloop", now, now, Map.of()));
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

    private boolean supportsStreaming(String model) {
        try {
            return modelCapabilityProvider.capabilities(model)
                .contains(ModelCapability.STREAMING);
        } catch (Throwable e) {
            throw publicFailure("Failed to resolve model capabilities", e);
        }
    }

    private TurnContext buildTurnContext(TurnRequest request, String turnId, String model,
                                         boolean streaming, ExecutionOrigin origin) {
        TurnContext context = new TurnContext(request, turnId, model, streaming);
        context.executionOrigin(origin);
        return context;
    }

    // ── observability ──

    private void observeQueued(TurnResponse.Queued outcome, TurnRequest request) {
        safeMetric(() -> metrics.recordTurnDuration(
            request.model() == null ? defaultModel : request.model(), "queued", 0));
        safeLifecycleOutcome(outcome);
        setQueueMetric(request.sessionId());
    }

    private void observeStandaloneOutcome(TurnResponse.Outcome outcome,
                                           String sessionId, String model) {
        safeMetric(() -> metrics.recordTurnDuration(
            model == null ? defaultModel : model, outcomeName(outcome), 0));
        safeLifecycleOutcome(outcome);
        setQueueMetric(sessionId);
    }

    private void recordOutcomeObservability(ExecutionHandle handle,
                                            TurnResponse.Outcome outcome) {
        auditNewMessages(handle.context.sessionId(), handle.before);
        String outcomeName = outcomeName(outcome);
        String model = handle.model;
        long duration = Math.max(0, Duration.between(
            handle.startedAt, completionTime(outcome, handle.startedAt)).toMillis());
        ContextUsage usage = outcome instanceof TurnResponse.Message message
            ? message.execution().finalContextUsage()
            : outcome instanceof TurnResponse.Questions questions
                ? questions.execution().finalContextUsage()
                : outcome instanceof TurnResponse.Interrupted interrupted
                    ? interrupted.execution().finalContextUsage()
                    : outcome instanceof TurnResponse.TimedOut timedOut
                        ? timedOut.execution().finalContextUsage()
                        : outcome instanceof TurnResponse.TurnCancelled cancelled
                            ? cancelled.execution().finalContextUsage()
                            : safeUsage(handle.context.sessionId(), model);
        safeAuditContext(handle.context.sessionId(), usage, model);
        safeMetric(() -> metrics.recordTurnDuration(model, outcomeName, duration));
        List<ToolActivityData> activities = outcome instanceof TurnResponse.Message message
            ? message.execution().toolActivities()
            : outcome instanceof TurnResponse.Questions questions
                ? questions.execution().toolActivities()
                : outcome instanceof TurnResponse.Interrupted interrupted
                    ? interrupted.execution().toolActivities()
                    : outcome instanceof TurnResponse.TimedOut timedOut
                        ? timedOut.execution().toolActivities()
                        : outcome instanceof TurnResponse.TurnCancelled cancelled
                            ? cancelled.execution().toolActivities()
                            : handle.accumulator.toolActivities();
        activities.forEach(activity -> safeMetric(() -> metrics.recordToolCall(
            activity.toolName(), activity.status().name())));
        safeLifecycleOutcome(outcome);
        setQueueMetric(handle.context.sessionId());
    }

    private void recordFailureObservability(ExecutionHandle handle, TurnFailure failure) {
        auditNewMessages(handle.context.sessionId(), handle.before);
        auditError(handle.context.sessionId(), failure.message(), null, handle.model);
        safeAuditContext(handle.context.sessionId(),
            safeUsage(handle.context.sessionId(), handle.model), handle.model);
        safeMetric(() -> metrics.recordTurnDuration(handle.model, "failed",
            Math.max(0, Duration.between(handle.startedAt, failure.failedAt()).toMillis())));
        handle.accumulator.toolActivities().forEach(activity -> safeMetric(() ->
            metrics.recordToolCall(activity.toolName(), activity.status().name())));
        safeLifecycleFailure(failure);
        setQueueMetric(handle.context.sessionId());
    }

    private String outcomeName(TurnResponse.Outcome outcome) {
        if (outcome instanceof TurnResponse.Message) return "message";
        if (outcome instanceof TurnResponse.Questions) return "questions";
        if (outcome instanceof TurnResponse.Queued) return "queued";
        if (outcome instanceof TurnResponse.Interrupted) return "interrupted";
        if (outcome instanceof TurnResponse.TimedOut) return "timed_out";
        if (outcome instanceof TurnResponse.QuestionsCancelled) return "questions_cancelled";
        if (outcome instanceof TurnResponse.TurnCancelled) return "turn_cancelled";
        return "outcome";
    }

    private Instant completionTime(TurnResponse.Outcome outcome, Instant fallback) {
        if (outcome instanceof TurnResponse.Message message) return message.execution().completedAt();
        if (outcome instanceof TurnResponse.Questions questions) return questions.execution().completedAt();
        if (outcome instanceof TurnResponse.Interrupted interrupted) return interrupted.execution().completedAt();
        if (outcome instanceof TurnResponse.TimedOut timedOut) return timedOut.execution().completedAt();
        if (outcome instanceof TurnResponse.TurnCancelled cancelled) return cancelled.execution().completedAt();
        if (outcome instanceof TurnResponse.QuestionsCancelled cancelled) return cancelled.cancelledAt();
        if (outcome instanceof TurnResponse.Queued queued) return queued.enqueuedAt();
        return fallback;
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
                if ("user".equals(message.role())) continue;
                if ("assistant".equals(message.role())) {
                    auditWriter.recordAssistantMessage(sessionId, message);
                } else if ("tool".equals(message.role())) {
                    Map<String, String> metadata = message.metadata() == null
                        ? Map.of() : message.metadata();
                    auditWriter.recordToolExecution(sessionId,
                        new AuditWriter.ToolExecutionRecord(
                            nullToEmpty(message.toolName()), nullToEmpty(message.toolCallId()),
                            metadata.getOrDefault("tool.arguments", ""),
                            metadata.getOrDefault("tool.summary", message.text()),
                            Boolean.parseBoolean(metadata.getOrDefault("tool.success", "true")),
                            parseLong(metadata.get("tool.duration_ms"))));
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

    private void safeLifecycleOutcome(TurnResponse.Outcome outcome) {
        try {
            lifecycleListener.onOutcome(outcome);
        } catch (Throwable e) {
            log.warn("Turn lifecycle outcome listener failed: {}", e.getMessage());
        }
    }

    private void safeLifecycleFailure(TurnFailure failure) {
        try {
            lifecycleListener.onFailure(failure);
        } catch (Throwable e) {
            log.warn("Turn lifecycle failure listener failed: {}", e.getMessage());
        }
    }

    private void setActiveMetric() {
        safeMetric(() -> metrics.setActiveTurns(activeTurnRegistry.activeTurns().size()));
    }

    private void setQueueMetric(String sessionId) {
        safeMetric(() -> metrics.setQueueDepth(sessionId, messageQueueStore.list(sessionId).size()));
    }

    private void safeMetric(Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            log.debug("Turn metric failed: {}", e.getMessage());
        }
    }

    // ── validation and utility ──

    private void validateClosed() {
        if (closed.get()) throw new TurnLoopException("TurnLoop is closed");
    }

    private void validateRequest(TurnRequest request) {
        if (request == null) throw new TurnLoopException("request must not be null");
        validateText(request.sessionId(), "sessionId");
        validateText(request.message(), "message");
    }

    private void validateAnswersArgument(List<String> answers) {
        if (answers == null) throw new IllegalArgumentException("answers must not be null");
        for (int index = 0; index < answers.size(); index++) {
            validateText(answers.get(index), "answers[" + index + "]");
        }
    }

    private void validateAnswers(List<String> questions, List<String> answers) {
        if (answers.size() != questions.size()) {
            throw new IllegalArgumentException(
                "Exactly one answer is required for each ordered question");
        }
        validateAnswersArgument(answers);
    }

    private void validateText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
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
        while (true) {
            if ((current instanceof java.util.concurrent.CompletionException
                    || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            Throwable reactorCause = Exceptions.unwrap(current);
            if (reactorCause == current) return current;
            current = reactorCause;
        }
    }

    private String messageOf(Throwable error) {
        return error == null || error.getMessage() == null || error.getMessage().isBlank()
            ? "unknown failure" : error.getMessage();
    }

    private TurnMessage currentUser(TurnRequest request) {
        return TurnMessage.user(request.message());
    }

    private List<TurnMessage> safeHistory(String sessionId) {
        List<TurnMessage> history = conversationMemory.getMessages(sessionId);
        return history == null ? List.of() : List.copyOf(history);
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

    private long parseLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Object lockFor(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, ignored -> new Object());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new TurnLoopException("Unable to serialize continuation metadata", e);
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

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Instant atOrAfter(Instant baseline) {
        Instant now = Instant.now();
        return now.isBefore(baseline) ? baseline : now;
    }

    private static Duration validClaimLease(Duration lease) {
        Objects.requireNonNull(lease, "claimLease");
        if (lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
        if (lease.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("claimLease must not exceed one hour");
        }
        return lease;
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
        @Override public void recordUserMessage(String sessionId, TurnMessage message) { }
        @Override public void recordAssistantMessage(String sessionId, TurnMessage message) { }
        @Override public void recordToolExecution(String sessionId, ToolExecutionRecord record) { }
        @Override public void recordContextUsage(String sessionId, ContextUsage usage, String model) { }
        @Override public void recordError(String sessionId, TurnError error) { }
    }

    private static final class NoOpTurnLoopMetrics implements TurnLoopMetrics {
        @Override public void recordTurnDuration(String model, String outcome, long durationMs) { }
        @Override public void recordToolCall(String toolName, String status) { }
        @Override public void setActiveTurns(long count) { }
        @Override public void setQueueDepth(String sessionId, long depth) { }
    }

    // ── private carriers ──

    private sealed interface PreparedSubmission permits ExecutionSubmission, QueuedSubmission { }

    private record ExecutionSubmission(ExecutionHandle handle) implements PreparedSubmission { }

    private record QueuedSubmission(TurnResponse.Queued outcome) implements PreparedSubmission { }

    private static final class ExecutionHandle {
        private final TurnContext context;
        private final String gateToken;
        private final List<TurnMessage> before;
        private final String model;
        private final Instant startedAt;
        private final ResponseAccumulator accumulator = new ResponseAccumulator();
        private final Sinks.Many<TurnResponse> forcedOutcomes =
            Sinks.many().replay().limit(1);
        private final AtomicBoolean terminalObserved = new AtomicBoolean(false);
        private volatile String answerInteractionId;
        private volatile String answerClaimId;

        private ExecutionHandle(TurnContext context, String gateToken,
                                List<TurnMessage> before, String model, Instant startedAt) {
            this.context = context;
            this.gateToken = gateToken;
            this.before = List.copyOf(before);
            this.model = model;
            this.startedAt = startedAt;
        }
    }

    private static final class ResponseAccumulator {
        private final List<ToolActivityData> activities = new ArrayList<>();
        private TurnResponse.Outcome outcome;
        private ContextUsage contextUsage;

        private synchronized void accept(TurnResponse response) {
            if (response == null) {
                throw new TurnLoopException("Turn emitted a null response");
            }
            if (outcome != null) {
                throw new TurnLoopException("Turn emitted progress after its outcome");
            }
            if (response instanceof TurnResponse.Started started) {
                contextUsage = started.initialContextUsage();
            } else if (response instanceof TurnResponse.ToolActivity activity) {
                activities.add(activity.activity());
            } else if (response instanceof TurnResponse.Outcome terminal) {
                if (outcome != null) {
                    throw new TurnLoopException("Turn emitted multiple outcomes");
                }
                outcome = terminal;
                contextUsage = executionUsage(terminal, contextUsage);
            }
        }

        private synchronized TurnResponse.Outcome requireOutcome(ExecutionHandle handle) {
            if (outcome == null) {
                throw new TurnFailedException(
                    "Turn completed without an outcome",
                    new TurnFailure(handle.context.sessionId(), handle.context.turnId(),
                        handle.context.executionOrigin(), TurnPhase.FINALIZE,
                        TurnFailureCategory.INTERNAL, handle.startedAt,
                        Instant.now(), "Turn completed without an outcome"));
            }
            return outcome;
        }

        private synchronized TurnResponse.Outcome outcome() {
            return outcome;
        }

        private synchronized ContextUsage contextUsage() {
            return contextUsage;
        }

        private synchronized List<ToolActivityData> toolActivities() {
            return List.copyOf(activities);
        }

        private static ContextUsage executionUsage(TurnResponse.Outcome outcome,
                                                   ContextUsage fallback) {
            if (outcome instanceof TurnResponse.Message message) {
                return message.execution().finalContextUsage();
            }
            if (outcome instanceof TurnResponse.Questions questions) {
                return questions.execution().finalContextUsage();
            }
            if (outcome instanceof TurnResponse.Interrupted interrupted) {
                return interrupted.execution().finalContextUsage();
            }
            if (outcome instanceof TurnResponse.TimedOut timedOut) {
                return timedOut.execution().finalContextUsage();
            }
            if (outcome instanceof TurnResponse.TurnCancelled cancelled) {
                return cancelled.execution().finalContextUsage();
            }
            return fallback;
        }
    }

    private final class DrainState {
        private final String sessionId;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<MessageQueueStore.Claim> claim = new AtomicReference<>();
        private final AtomicReference<ExecutionHandle> handle = new AtomicReference<>();
        private final AtomicReference<Disposable> disposable = new AtomicReference<>();

        private DrainState(String sessionId) {
            this.sessionId = sessionId;
        }

        private void cancel() {
            cancelled.set(true);
            ExecutionHandle current = handle.get();
            if (current != null) {
                revokeExecution(current.context.turnId(), "subscriber_cancelled");
            }
            Disposable currentDisposable = disposable.get();
            if (currentDisposable != null) currentDisposable.dispose();
            releaseClaim(this);
        }
    }
}
