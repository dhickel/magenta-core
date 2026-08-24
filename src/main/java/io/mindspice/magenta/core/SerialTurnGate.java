package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.TurnGate;
import io.mindspice.magenta.core.spi.TurnGate.AcquireResult;
import io.mindspice.magenta.core.spi.TurnGate.GateState;
import io.mindspice.magenta.core.spi.TurnGate.TurnGateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * Per-session serial turn gate with token-qualified timeout release.
 *
 * <p>A timed-out executing turn is never released until the owning turn loop
 * confirms that the matching execution token has been interrupted and fenced.
 * This prevents the scanner from creating a second authorized writer while
 * stale provider work is still able to persist.</p>
 */
public final class SerialTurnGate implements TurnGate, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SerialTurnGate.class);
    private static final Duration DEFAULT_SCAN_INTERVAL = Duration.ofSeconds(30);

    private final Duration turnTimeout;
    private final Clock clock;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScanner;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @Nullable
    private volatile Consumer<String> timeoutCallback;
    @Nullable
    private volatile BiPredicate<String, String> timeoutHandler;

    /** Creates a gate with the given default turn timeout. */
    public SerialTurnGate(@NonNull Duration turnTimeout) {
        this(turnTimeout, DEFAULT_SCAN_INTERVAL);
    }

    SerialTurnGate(Duration turnTimeout, Duration scanInterval) {
        this(turnTimeout, scanInterval, Clock.systemUTC());
    }

    SerialTurnGate(Duration turnTimeout, Duration scanInterval, Clock clock) {
        if (turnTimeout == null || turnTimeout.isNegative()) {
            throw new IllegalArgumentException("turnTimeout must not be negative");
        }
        if (scanInterval == null || scanInterval.isZero() || scanInterval.isNegative()) {
            throw new IllegalArgumentException("scanInterval must be positive");
        }
        this.turnTimeout = turnTimeout;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.timeoutScanner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "serial-turn-gate-timeout");
            thread.setDaemon(true);
            return thread;
        });
        long scanNanos = Math.max(1, scanInterval.toNanos());
        timeoutScanner.scheduleAtFixedRate(
            this::scanForTimeouts, scanNanos, scanNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Registers an observation callback invoked when an executing turn reaches
     * its timeout. Release still requires the framework's token-aware fencing
     * handler; this callback alone never authorizes a force release.
     */
    public void setTimeoutCallback(@NonNull Consumer<String> callback) {
        this.timeoutCallback = callback;
    }

    /** Installs the token-aware execution fence used by the owning turn loop. */
    void setTimeoutHandler(@Nullable BiPredicate<String, String> handler) {
        this.timeoutHandler = handler;
    }

    @Override
    @NonNull
    public AcquireResult acquire(@NonNull String sessionId, @NonNull TurnGateMode mode) {
        if (closed.get()) {
            return new AcquireResult.Rejected("Turn gate is closed");
        }
        SessionState state = sessions.computeIfAbsent(sessionId, ignored -> new SessionState());
        state.lock.lock();
        try {
            return switch (state.gateState) {
                case IDLE -> {
                    state.gateState = GateState.EXECUTING;
                    state.lockToken = UUID.randomUUID().toString();
                    state.startedAt = clock.instant();
                    state.awaitingAnswer = false;
                    state.timingOutToken = null;
                    yield new AcquireResult.Acquired(state.lockToken);
                }
                case EXECUTING -> new AcquireResult.Rejected(
                    "Session '" + sessionId + "' is already executing a turn");
                case AWAITING_ANSWER -> {
                    if (mode == TurnGateMode.ANSWER) {
                        state.gateState = GateState.EXECUTING;
                        state.awaitingAnswer = false;
                        state.lockToken = UUID.randomUUID().toString();
                        state.startedAt = clock.instant();
                        state.timingOutToken = null;
                        yield new AcquireResult.Acquired(state.lockToken);
                    }
                    yield new AcquireResult.Rejected(
                        "Session '" + sessionId + "' is awaiting user answer");
                }
            };
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void release(@NonNull String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return;
        state.lock.lock();
        try {
            reset(state);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void transitionToAwaitingAnswer(@NonNull String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            logger.warn("transitionToAwaitingAnswer called for unknown session: {}", sessionId);
            return;
        }
        state.lock.lock();
        try {
            if (state.gateState == GateState.EXECUTING) {
                state.gateState = GateState.AWAITING_ANSWER;
                state.awaitingAnswer = true;
                state.startedAt = null;
                state.timingOutToken = null;
            } else {
                logger.warn("transitionToAwaitingAnswer: session {} is in state {}, expected EXECUTING",
                    sessionId, state.gateState);
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void drain(long timeoutMs) {
        long boundedTimeout = Math.max(0, timeoutMs);
        long deadline;
        try {
            deadline = Math.addExact(System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(boundedTimeout));
        } catch (ArithmeticException ignored) {
            deadline = Long.MAX_VALUE;
        }

        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            while (state(sessionId) == GateState.EXECUTING && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(Math.min(10, Math.max(1,
                        TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            SessionState state = sessions.get(sessionId);
            if (state == null) continue;
            String token = null;
            state.lock.lock();
            try {
                if (state.gateState == GateState.AWAITING_ANSWER) {
                    logger.info("Drain: releasing AWAITING_ANSWER session {}", sessionId);
                    reset(state);
                } else if (state.gateState == GateState.EXECUTING) {
                    token = markTimingOut(state);
                }
            } finally {
                state.lock.unlock();
            }
            if (token != null) {
                logger.info("Drain: fencing EXECUTING session {}", sessionId);
                fenceAndRelease(sessionId, state, token);
            }
        }
    }

    @Override
    @NonNull
    public GateState state(@NonNull String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) return GateState.IDLE;
        state.lock.lock();
        try {
            return state.gateState;
        } finally {
            state.lock.unlock();
        }
    }

    /** Stops the daemon timeout scanner. Gate shutdown is idempotent. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            timeoutScanner.shutdownNow();
        }
    }

    void scanForTimeouts() {
        if (closed.get()) return;
        Instant now = clock.instant();
        for (String sessionId : new ArrayList<>(sessions.keySet())) {
            SessionState state = sessions.get(sessionId);
            if (state == null) continue;
            String token = null;
            state.lock.lock();
            try {
                if (state.gateState == GateState.EXECUTING
                    && state.startedAt != null
                    && Duration.between(state.startedAt, now).compareTo(turnTimeout) >= 0) {
                    token = markTimingOut(state);
                }
            } finally {
                state.lock.unlock();
            }
            if (token != null) {
                logger.warn("Turn timeout for session {}: exceeded {}", sessionId, turnTimeout);
                fenceAndRelease(sessionId, state, token);
            }
        }
    }

    @Nullable
    private String markTimingOut(SessionState state) {
        if (state.lockToken == null || state.timingOutToken != null) return null;
        state.timingOutToken = state.lockToken;
        return state.lockToken;
    }

    private void fenceAndRelease(String sessionId, SessionState state, String token) {
        boolean fenced = notifyTimeout(sessionId, token);
        state.lock.lock();
        try {
            if (token.equals(state.timingOutToken)) {
                state.timingOutToken = null;
            }
            if (fenced && state.gateState == GateState.EXECUTING
                && token.equals(state.lockToken)) {
                reset(state);
            } else if (!fenced && state.gateState == GateState.EXECUTING
                && token.equals(state.lockToken)) {
                logger.error("Turn timeout for session {} was not fenced; gate remains EXECUTING",
                    sessionId);
            }
        } finally {
            state.lock.unlock();
        }
    }

    private boolean notifyTimeout(String sessionId, String token) {
        boolean fenced = false;
        BiPredicate<String, String> handler = timeoutHandler;
        if (handler != null) {
            try {
                fenced = handler.test(sessionId, token);
            } catch (Throwable e) {
                logger.error("Timeout fence failed for session {}: {}", sessionId, e.getMessage(), e);
            }
        }

        Consumer<String> callback = timeoutCallback;
        if (callback != null) {
            try {
                callback.accept(sessionId);
            } catch (Throwable e) {
                logger.error("Timeout callback failed for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
        return fenced;
    }

    private void reset(SessionState state) {
        state.gateState = GateState.IDLE;
        state.lockToken = null;
        state.startedAt = null;
        state.awaitingAnswer = false;
        state.timingOutToken = null;
    }

    private static final class SessionState {
        private final ReentrantLock lock = new ReentrantLock();
        private GateState gateState = GateState.IDLE;
        private String lockToken;
        private Instant startedAt;
        private boolean awaitingAnswer;
        private String timingOutToken;
    }
}
