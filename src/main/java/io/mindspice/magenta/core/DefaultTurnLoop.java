package io.mindspice.magenta.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.engine.DefaultTurnEngine;
import io.mindspice.magenta.core.internal.ActiveTurnRegistry;
import io.mindspice.magenta.core.internal.TurnExecutor;
import io.mindspice.magenta.core.spi.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Package-private implementation of {@link TurnLoop} that orchestrates
 * the {@link DefaultTurnEngine}, {@link TurnGate}, {@link MessageQueue},
 * {@link InteractionStore}, and all SPI wiring.
 *
 * <p>The only public construction path is {@link TurnLoopBuilder#build()}.
 * This class implements the full facade contract including send, stream,
 * answer, enqueue/claim/ack/release, session management, interrupt, and
 * the 7-step ordered {@link #close()} lifecycle.</p>
 *
 * <h3>Exception contract</h3>
 * Every public method catches internal exceptions and wraps them in
 * {@link TurnLoopException} or its documented subtypes. No raw
 * {@code RuntimeException} or Spring AI exception escapes through
 * any public method.
 */
final class DefaultTurnLoop implements TurnLoop {

    private static final Logger log = LoggerFactory.getLogger(DefaultTurnLoop.class);

    // ── Dependencies ──

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
    private final ObjectMapper objectMapper;
    private final String defaultSystemPrompt;
    private final String defaultModel;
    private final long drainTimeoutSeconds;

    // ── State ──

    private final AtomicBoolean closed = new AtomicBoolean(false);

    // ── Sub-interface instances (created once, returned on every call) ──

    private final ToolSubinterface toolsView;
    private final MemorySubinterface memoryView;
    private final SessionSubinterface sessionsView;

    // ── Constructor ──

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
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.messageQueue = Objects.requireNonNull(messageQueue, "messageQueue");
        this.interactionStore = Objects.requireNonNull(interactionStore, "interactionStore");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.conversationMemory = Objects.requireNonNull(conversationMemory, "conversationMemory");
        this.activeTurnRegistry = Objects.requireNonNull(activeTurnRegistry, "activeTurnRegistry");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.modelCapabilityProvider = Objects.requireNonNull(modelCapabilityProvider, "modelCapabilityProvider");
        this.lifecycleListener = lifecycleListener != null ? lifecycleListener : result -> {};
        this.metrics = metrics != null ? metrics : new NoOpTurnLoopMetrics();
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.defaultSystemPrompt = defaultSystemPrompt != null ? defaultSystemPrompt : "You are a helpful AI assistant.";
        this.defaultModel = Objects.requireNonNull(defaultModel, "defaultModel");
        this.drainTimeoutSeconds = Math.max(0, drainTimeoutSeconds);

        this.toolsView = new Tools();
        this.memoryView = new Memory();
        this.sessionsView = new Sessions();
    }

    // ── TurnLoop: send ──

    @Override
    public TurnResult send(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);

        String sessionId = request.sessionId();
        TurnGate.AcquireResult acquire = gate.acquire(sessionId, TurnGate.TurnGateMode.SEND);

        if (acquire instanceof TurnGate.AcquireResult.Rejected rej) {
            throw new TurnLoopException(
                "Turn already in progress for session " + sessionId + ": " + rej.reason());
        }

        // Ignore Queued — the SerialTurnGate never returns Queued for SEND mode.
        // If a future gate implementation returns Queued, treat as rejection.
        if (acquire instanceof TurnGate.AcquireResult.Queued q) {
            throw new TurnLoopException(
                "Turn queued but synchronous send requires immediate execution for session " + sessionId);
        }

        String model = resolveModel(request.model());
        TurnContext ctx = buildTurnContext(request, model, false);

        long startMs = System.currentTimeMillis();
        try {
            TurnResult result = engine.execute(ctx);
            recordSuccess(request, result, startMs);

            // If the turn suspended on ask_user, transition gate; otherwise release.
            if (interactionStore.load(sessionId) != null) {
                gate.transitionToAwaitingAnswer(sessionId);
            } else {
                gate.release(sessionId);
            }

            return result;
        } catch (TurnFailedException | ConfigurationException
                 | ConversationNotFoundException | ToolException e) {
            // Public exceptions — release gate and rethrow
            gate.release(sessionId);
            throw e;
        } catch (IllegalArgumentException e) {
            // ModelRouter throws IAE for unknown model alias
            gate.release(sessionId);
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown model";
            throw new ConfigurationException(msg, Map.of("error", "unknown_model"));
        } catch (RuntimeException e) {
            gate.release(sessionId);
            throw new TurnLoopException("Turn execution failed: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: stream ──

    @Override
    public Flux<TurnEvent> stream(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);

        String sessionId = request.sessionId();
        String model = resolveModel(request.model());

        // Fast-fail streaming capability check before gate acquisition
        if (!modelCapabilityProvider.capabilities(model).contains(ModelCapability.STREAMING)) {
            throw new TurnLoopException("Model '" + model + "' does not support streaming");
        }

        TurnGate.AcquireResult acquire = gate.acquire(sessionId, TurnGate.TurnGateMode.STREAM);
        if (acquire instanceof TurnGate.AcquireResult.Rejected rej) {
            throw new TurnLoopException(
                "Turn already in progress for session " + sessionId + ": " + rej.reason());
        }
        if (acquire instanceof TurnGate.AcquireResult.Queued) {
            throw new TurnLoopException(
                "Turn queued but streaming requires immediate execution for session " + sessionId);
        }

        TurnContext ctx = buildTurnContext(request, model, true);
        long startMs = System.currentTimeMillis();

        // Gate-release guard — ensures release happens at most once
        final AtomicBoolean gateReleased = new AtomicBoolean(false);

        return engine.stream(ctx)
            .doOnSubscribe(s -> {
                if (metrics != null) {
                    try {
                        metrics.setActiveTurns(activeTurnRegistry.activeTurns().size());
                    } catch (Exception ignored) { }
                }
            })
            .doFinally(signal -> {
                releaseGateOnce(sessionId, gateReleased);
                try {
                    if (signal == SignalType.ON_COMPLETE || signal == SignalType.ON_ERROR) {
                        long durationMs = System.currentTimeMillis() - startMs;
                        QuestionPendingContext pending = interactionStore.load(sessionId);
                        String outcome = pending != null ? "suspended" : "completed";
                        try {
                            metrics.recordTurnDuration(model, outcome, durationMs);
                        } catch (Exception ignored) { }
                    }
                } catch (Exception e) {
                    log.warn("Metrics recording failed for stream turn session={}: {}", sessionId, e.getMessage());
                }
            })
            .onErrorMap(e -> {
                // Release gate on error too (guard ensures once)
                releaseGateOnce(sessionId, gateReleased);
                return mapInternalException(e);
            });
    }

    // ── TurnLoop: answer ──

    @Override
    public TurnResult answer(@NonNull String sessionId, @NonNull String answer) {
        validateClosed();
        if (sessionId.isBlank()) {
            throw new TurnLoopException("sessionId must not be blank");
        }
        if (answer.isBlank()) {
            throw new TurnLoopException("answer must not be blank");
        }

        // Gate must be in AWAITING_ANSWER state
        if (gate.state(sessionId) != TurnGate.GateState.AWAITING_ANSWER) {
            throw new TurnLoopException("No question pending for session " + sessionId);
        }

        // Load the pending question context
        QuestionPendingContext pendingCtx = interactionStore.load(sessionId);
        if (pendingCtx == null) {
            throw new TurnLoopException("No pending question context for session " + sessionId);
        }

        // Resolve model — use the session's stored model, fall back to default
        String model = resolveSessionModel(sessionId);

        // Acquire gate in ANSWER mode (transitions AWAITING_ANSWER → EXECUTING)
        TurnGate.AcquireResult acquire = gate.acquire(sessionId, TurnGate.TurnGateMode.ANSWER);
        if (acquire instanceof TurnGate.AcquireResult.Rejected rej) {
            throw new TurnLoopException("Cannot resume turn for session " + sessionId + ": " + rej.reason());
        }

        // Build a continuation TurnRequest injecting the user's answer
        TurnRequest contRequest = new TurnRequest(
            sessionId, answer, model, null, false, Map.of("turnloop.continuation", "true"));

        TurnContext ctx = buildTurnContext(contRequest, model, false);

        try {
            TurnResult result = engine.execute(ctx);

            // On success: delete interaction store entry and release gate
            interactionStore.delete(sessionId);
            gate.release(sessionId);

            try {
                lifecycleListener.onTurnCompleted(result);
            } catch (Exception e) {
                log.warn("Lifecycle listener failed for answer session={}: {}", sessionId, e.getMessage());
            }

            return result;
        } catch (TurnFailedException | ConfigurationException
                 | ConversationNotFoundException | ToolException e) {
            gate.release(sessionId);
            throw e;
        } catch (IllegalArgumentException e) {
            gate.release(sessionId);
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown model";
            throw new ConfigurationException(msg, Map.of("error", "unknown_model"));
        } catch (RuntimeException e) {
            gate.release(sessionId);
            throw new TurnLoopException("Answer turn execution failed: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: enqueue ──

    @Override
    public PendingMessage enqueue(@NonNull TurnRequest request) {
        validateClosed();
        validateRequest(request);
        try {
            return messageQueue.enqueue(
                request.sessionId(),
                request.message(),
                request.metadata() != null ? request.metadata() : Map.of());
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to enqueue message: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: claim ──

    @Override
    @Nullable
    public ClaimedMessage claim(@NonNull String sessionId) {
        validateClosed();
        if (sessionId.isBlank()) {
            throw new TurnLoopException("sessionId must not be blank");
        }
        try {
            return messageQueue.claim(sessionId);
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to claim message: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: ack ──

    @Override
    public void ack(@NonNull String claimToken) {
        validateClosed();
        if (claimToken.isBlank()) {
            throw new TurnLoopException("claimToken must not be blank");
        }
        try {
            messageQueue.ack(claimToken);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Stale/already-acked claim — log and return normally per contract
            log.warn("ack on stale claim token: {}", claimToken);
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to ack message: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: release ──

    @Override
    public void release(@NonNull String claimToken) {
        validateClosed();
        if (claimToken.isBlank()) {
            throw new TurnLoopException("claimToken must not be blank");
        }
        try {
            messageQueue.release(claimToken);
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Stale/already-released claim — log and return normally per contract
            log.warn("release on stale claim token: {}", claimToken);
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to release message: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: clearSession ──

    @Override
    public void clearSession(@NonNull String sessionId) {
        validateClosed();
        if (sessionId.isBlank()) {
            throw new TurnLoopException("sessionId must not be blank");
        }
        try {
            conversationMemory.clear(sessionId);
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to clear conversation memory: " + e.getMessage(), e);
        }
        try {
            interactionStore.delete(sessionId);
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to clear interaction store: " + e.getMessage(), e);
        }
        // Session metadata is preserved (not deleted)
    }

    // ── TurnLoop: interrupt ──

    @Override
    public boolean interrupt(@NonNull String turnId, @NonNull String reason) {
        validateClosed();
        if (turnId.isBlank()) {
            throw new TurnLoopException("turnId must not be blank");
        }
        if (reason.isBlank()) {
            throw new TurnLoopException("reason must not be blank");
        }
        try {
            ActiveTurnRegistry.ActiveTurn active = activeTurnRegistry.get(turnId);
            if (active == null) {
                return false;
            }
            active.queueInterrupt(reason);
            return true;
        } catch (RuntimeException e) {
            throw new TurnLoopException("Failed to interrupt turn: " + e.getMessage(), e);
        }
    }

    // ── TurnLoop: close ──

    /**
     * Gracefully shuts down the turn loop.
     *
     * <h3>7-step ordered drain sequence</h3>
     * <ol>
     *   <li>CAS closed flag false→true. If already true, return immediately.</li>
     *   <li>Cancel pending questions: for each AWAITING_ANSWER session, queue
     *       interrupt (via gate drain which handles this internally).</li>
     *   <li>Wait for EXECUTING turns up to drain timeout.</li>
     *   <li>Force-release remaining EXECUTING turns after timeout.</li>
     *   <li>Release stream resources (subscriptions cancel when the sink
     *       completes; the executor shutdown in step 7 stops async work).</li>
     *   <li>Release pending claims: recover all stale claims with zero-age
     *       threshold and release each; log the count at INFO.</li>
     *   <li>Close ChatModel instances and shut down the
     *       {@link TurnExecutor}.</li>
     * </ol>
     *
     * <p>Every step is individually try-caught and logged at ERROR.
     * No step may throw out of close().</p>
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // idempotent
        }

        log.info("TurnLoop closing...");

        // Step 2-4: Drain the gate (SerialTurnGate.drain handles AWAITING_ANSWER
        // cancellation, EXECUTING wait, and force-release internally).
        try {
            gate.drain(drainTimeoutSeconds * 1000L);
        } catch (Exception e) {
            log.error("Gate drain failed during close: {}", e.getMessage(), e);
        }

        // Step 5: Release stream resources.
        // The facade does not hold direct Flux references; the engine owns
        // the sinks. Cancelling the scheduler below stops async work.

        // Step 6: Release pending claims.
        try {
            List<ClaimedMessage> stale = messageQueue.recoverStaleClaims(Duration.ZERO);
            int count = 0;
            for (ClaimedMessage cm : stale) {
                try {
                    messageQueue.release(cm.claimToken());
                    count++;
                } catch (Exception e) {
                    log.warn("Failed to release claim token {} during close: {}",
                        cm.claimToken(), e.getMessage());
                }
            }
            log.info("Released {} pending claims during close", count);
        } catch (Exception e) {
            log.error("Pending claim release failed during close: {}", e.getMessage(), e);
        }

        // Step 7: Close ChatModel instances and shut down executor.
        try {
            engine.closeChatModels();
        } catch (Exception e) {
            log.error("ChatModel close failed during shutdown: {}", e.getMessage(), e);
        }
        try {
            executor.close();
        } catch (Exception e) {
            log.error("TurnExecutor shutdown failed during close: {}", e.getMessage(), e);
        }

        log.info("TurnLoop closed");
    }

    // ── TurnLoop: accessors ──

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

    // ── Internal helpers ──

    private void validateClosed() {
        if (closed.get()) {
            throw new TurnLoopException("TurnLoop is closed");
        }
    }

    private void validateRequest(TurnRequest request) {
        if (request == null) {
            throw new TurnLoopException("request must not be null");
        }
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            throw new TurnLoopException("sessionId must not be blank");
        }
        if (request.message() == null || request.message().isBlank()) {
            throw new TurnLoopException("message must not be blank");
        }
    }

    private String resolveModel(@Nullable String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel;
        }
        return defaultModel;
    }

    private String resolveSessionModel(String sessionId) {
        try {
            ConversationSession session = sessionStore.findById(sessionId);
            if (session != null && session.model() != null && !session.model().isBlank()) {
                return session.model();
            }
        } catch (Exception e) {
            log.warn("Failed to resolve model from session store for {}: {}", sessionId, e.getMessage());
        }
        return defaultModel;
    }

    private TurnContext buildTurnContext(TurnRequest request, String model, boolean streaming) {
        TurnRequest effectiveRequest = streaming
            ? new TurnRequest(request.sessionId(), request.message(), model,
                request.toolNames(), true, request.metadata())
            : new TurnRequest(request.sessionId(), request.message(), model,
                request.toolNames(), false, request.metadata());

        String turnId = UUID.randomUUID().toString();
        return new TurnContext(effectiveRequest, turnId, model);
    }

    private void recordSuccess(TurnRequest request, TurnResult result, long startMs) {
        try {
            lifecycleListener.onTurnCompleted(result);
        } catch (Exception e) {
            log.warn("Lifecycle listener failed for session={}: {}", request.sessionId(), e.getMessage());
        }
        try {
            long durationMs = System.currentTimeMillis() - startMs;
            String outcome = result.diagnostic().outcomeKind().name().toLowerCase();
            metrics.recordTurnDuration(
                resolveModel(request.model()), outcome, durationMs);
        } catch (Exception e) {
            log.warn("Metrics recording failed for session={}: {}", request.sessionId(), e.getMessage());
        }
    }

    /**
     * Releases the gate exactly once. Safe for multiple calls.
     */
    private void releaseGateOnce(String sessionId, AtomicBoolean gateReleased) {
        if (gateReleased.compareAndSet(false, true)) {
            try {
                // Check if the turn was suspended — if so, transition instead of release.
                if (interactionStore.load(sessionId) != null) {
                    gate.transitionToAwaitingAnswer(sessionId);
                } else {
                    gate.release(sessionId);
                }
            } catch (Exception e) {
                log.warn("Gate release/transition failed for session={}: {}", sessionId, e.getMessage());
            }
        }
    }

    /**
     * Maps internal exceptions to public exception hierarchy.
     * TurnFailedException and known public types pass through;
     * IllegalArgumentException (model alias) becomes ConfigurationException;
     * everything else becomes TurnLoopException.
     */
    private Throwable mapInternalException(Throwable e) {
        if (e instanceof TurnFailedException
            || e instanceof ConfigurationException
            || e instanceof ConversationNotFoundException
            || e instanceof ToolException
            || e instanceof TurnLoopException) {
            return e; // already public
        }
        if (e instanceof IllegalArgumentException) {
            String msg = e.getMessage() != null ? e.getMessage() : "Invalid argument";
            return new ConfigurationException(msg, Map.of("error", "illegal_argument"));
        }
        return new TurnLoopException(
            e.getMessage() != null ? e.getMessage() : "Turn execution failed", e);
    }

    // ── Sub-interface: Tools ──

    private final class Tools implements ToolSubinterface {

        @Override
        public void register(@NonNull ToolCallback tool) {
            validateClosed();
            try {
                toolRegistry.register(tool);
            } catch (ToolException e) {
                throw e; // already public
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to register tool: " + e.getMessage(), e);
            }
        }

        @Override
        public void registerToolBean(@NonNull Object bean) {
            validateClosed();
            try {
                toolRegistry.registerToolBean(bean);
            } catch (ToolException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to register tool bean: " + e.getMessage(), e);
            }
        }

        @Override
        public void unregister(@NonNull String toolName) {
            validateClosed();
            try {
                toolRegistry.unregister(toolName);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to unregister tool: " + e.getMessage(), e);
            }
        }

        @Override
        public List<ToolDefinition> definitions() {
            validateClosed();
            try {
                return toolRegistry.definitions();
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to list tool definitions: " + e.getMessage(), e);
            }
        }
    }

    // ── Sub-interface: Memory ──

    private final class Memory implements MemorySubinterface {

        @Override
        public List<TurnMessage> history(@NonNull String sessionId) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                return conversationMemory.getMessages(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to get message history: " + e.getMessage(), e);
            }
        }

        @Override
        public void clear(@NonNull String sessionId) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                conversationMemory.clear(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to clear memory: " + e.getMessage(), e);
            }
        }

        @Override
        public ContextUsage contextUsage(@NonNull String sessionId) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                List<TurnMessage> messages = conversationMemory.getMessages(sessionId);
                int totalChars = 0;
                for (TurnMessage msg : messages) {
                    String text = msg.text();
                    if (text != null) {
                        totalChars += text.length();
                    }
                }
                // Rough estimate: ~4 chars per token, default max 128K
                int usedTokens = totalChars / 4;
                int maxTokens = 128_000;
                int triggerTokens = (int) (maxTokens * 0.85);
                return new ContextUsage(usedTokens, triggerTokens, maxTokens);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to compute context usage: " + e.getMessage(), e);
            }
        }
    }

    // ── Sub-interface: Sessions ──

    private final class Sessions implements SessionSubinterface {

        @Override
        public List<ConversationSession> list(@NonNull SessionFilter filter) {
            validateClosed();
            try {
                return sessionStore.list(filter);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to list sessions: " + e.getMessage(), e);
            }
        }

        @Override
        @Nullable
        public ConversationSession get(@NonNull String sessionId) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                return sessionStore.findById(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to get session: " + e.getMessage(), e);
            }
        }

        @Override
        public void updateTitle(@NonNull String sessionId, @NonNull String title) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                sessionStore.updateTitle(sessionId, title);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to update title: " + e.getMessage(), e);
            }
        }

        @Override
        public void setFavorite(@NonNull String sessionId, boolean favorite) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                sessionStore.setFavorite(sessionId, favorite);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to set favorite: " + e.getMessage(), e);
            }
        }

        @Override
        public void setArchived(@NonNull String sessionId, boolean archived) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                sessionStore.setArchived(sessionId, archived);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to set archived: " + e.getMessage(), e);
            }
        }

        @Override
        public void delete(@NonNull String sessionId) {
            validateClosed();
            if (sessionId.isBlank()) {
                throw new TurnLoopException("sessionId must not be blank");
            }
            try {
                // Clear conversation memory for this session
                conversationMemory.clear(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to clear memory during session delete: " + e.getMessage(), e);
            }
            try {
                interactionStore.delete(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to clear interactions during session delete: " + e.getMessage(), e);
            }
            try {
                sessionStore.delete(sessionId);
            } catch (RuntimeException e) {
                throw new TurnLoopException("Failed to delete session: " + e.getMessage(), e);
            }
        }
    }

    // ── No-op metrics (used when no TurnLoopMetrics is provided) ──

    private static final class NoOpTurnLoopMetrics implements TurnLoopMetrics {
        @Override
        public void recordTurnDuration(String model, String outcome, long durationMs) { }

        @Override
        public void recordToolCall(String toolName, String status) { }

        @Override
        public void setActiveTurns(long count) { }

        @Override
        public void setQueueDepth(String sessionId, long depth) { }
    }
}
