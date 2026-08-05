package io.mindspice.core;

import io.mindspice.core.spi.TurnGate;
import io.mindspice.core.spi.TurnGate.AcquireResult;
import io.mindspice.core.spi.TurnGate.GateState;
import io.mindspice.core.spi.TurnGate.TurnGateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-session serial turn gate with a {@link ReentrantLock}-guarded state
 * machine, periodic turn-timeout scanning, and graceful drain.
 *
 * <h3>State machine</h3>
 * <pre>
 * IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE
 *         ↑              ↓
 *         └── ANSWER mode ──┘
 * </pre>
 *
 * <h3>Thread safety</h3>
 * All state transitions for a given session are guarded by that session's
 * {@link ReentrantLock}. Timeout scanning runs on a single daemon thread
 * and acquires the session lock before modifying state.
 */
public final class SerialTurnGate implements TurnGate {

    private static final Logger logger = LoggerFactory.getLogger(SerialTurnGate.class);

    private static final long SCAN_INTERVAL_SECONDS = 30;

    private final Duration turnTimeout;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScanner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "serial-turn-gate-timeout");
        t.setDaemon(true);
        return t;
    });

    @Nullable
    private volatile Consumer<String> timeoutCallback;

    /**
     * Creates a gate with the given default turn timeout.
     *
     * @param turnTimeout maximum duration a turn may remain in EXECUTING
     *                    before being forcibly released
     */
    public SerialTurnGate(@NonNull Duration turnTimeout) {
        this.turnTimeout = turnTimeout;
        this.timeoutScanner.scheduleAtFixedRate(
            this::scanForTimeouts,
            SCAN_INTERVAL_SECONDS,
            SCAN_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    /**
     * Registers a callback invoked when a turn times out. The callback
     * receives the session ID and is expected to emit a
     * {@link TurnEvent.Failed} event before the gate is released.
     *
     * <p>Typically set by the engine during construction.</p>
     */
    public void setTimeoutCallback(@NonNull Consumer<String> callback) {
        this.timeoutCallback = callback;
    }

    // ── TurnGate implementation ──

    @Override
    @NonNull
    public AcquireResult acquire(@NonNull String sessionId, @NonNull TurnGateMode mode) {
        SessionState state = sessions.computeIfAbsent(sessionId, k -> new SessionState());
        state.lock.lock();
        try {
            switch (state.gateState) {
                case IDLE -> {
                    state.gateState = GateState.EXECUTING;
                    state.lockToken = UUID.randomUUID().toString();
                    state.startedAt = Instant.now();
                    return new AcquireResult.Acquired(state.lockToken);
                }
                case EXECUTING -> {
                    if (mode == TurnGateMode.ANSWER && state.awaitingAnswer) {
                        state.awaitingAnswer = false;
                        state.lockToken = UUID.randomUUID().toString();
                        state.startedAt = Instant.now();
                        return new AcquireResult.Acquired(state.lockToken);
                    }
                    return new AcquireResult.Rejected(
                        "Session '" + sessionId + "' is already executing a turn");
                }
                case AWAITING_ANSWER -> {
                    if (mode == TurnGateMode.ANSWER) {
                        state.gateState = GateState.EXECUTING;
                        state.awaitingAnswer = false;
                        state.lockToken = UUID.randomUUID().toString();
                        state.startedAt = Instant.now();
                        return new AcquireResult.Acquired(state.lockToken);
                    }
                    return new AcquireResult.Rejected(
                        "Session '" + sessionId + "' is awaiting user answer");
                }
                default -> {
                    return new AcquireResult.Rejected(
                        "Session '" + sessionId + "' is in unexpected state: " + state.gateState);
                }
            }
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void release(@NonNull String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            state.gateState = GateState.IDLE;
            state.lockToken = null;
            state.startedAt = null;
            state.awaitingAnswer = false;
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<String> sessionIds;

        synchronized (sessions) {
            sessionIds = new ArrayList<>(sessions.keySet());
        }

        for (String sessionId : sessionIds) {
            SessionState state = sessions.get(sessionId);
            if (state == null) {
                continue;
            }

            state.lock.lock();
            try {
                switch (state.gateState) {
                    case AWAITING_ANSWER -> {
                        logger.info("Drain: cancelling AWAITING_ANSWER session {}", sessionId);
                        notifyTimeout(sessionId);
                        state.gateState = GateState.IDLE;
                        state.lockToken = null;
                        state.startedAt = null;
                        state.awaitingAnswer = false;
                    }
                    case EXECUTING -> {
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining > 0) {
                            logger.info("Drain: waiting for EXECUTING session {} ({}ms remaining)",
                                sessionId, remaining);
                            // Release the lock so the turn can progress; reacquire after wait
                            state.lock.unlock();
                            try {
                                // Wait with polling
                                long waitUntil = System.currentTimeMillis() + remaining;
                                while (System.currentTimeMillis() < waitUntil) {
                                    SessionState current = sessions.get(sessionId);
                                    if (current == null || current.gateState != GateState.EXECUTING) {
                                        break;
                                    }
                                    Thread.sleep(Math.min(100, waitUntil - System.currentTimeMillis()));
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            state.lock.lock();
                        }

                        // Force-release if still executing
                        if (state.gateState == GateState.EXECUTING) {
                            logger.info("Drain: force-releasing EXECUTING session {}", sessionId);
                            notifyTimeout(sessionId);
                            state.gateState = GateState.IDLE;
                            state.lockToken = null;
                            state.startedAt = null;
                            state.awaitingAnswer = false;
                        }
                    }
                    default -> {
                        // IDLE — nothing to do
                    }
                }
            } finally {
                // Ensure lock is released if we still hold it
                if (state.lock.isHeldByCurrentThread()) {
                    state.lock.unlock();
                }
            }
        }
    }

    @Override
    @NonNull
    public GateState state(@NonNull String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null ? state.gateState : GateState.IDLE;
    }

    // ── Internal ──

    /**
     * Per-session mutable state guarded by {@link #lock}.
     */
    private static final class SessionState {
        final ReentrantLock lock = new ReentrantLock();
        GateState gateState = GateState.IDLE;
        @Nullable
        volatile String lockToken;
        @Nullable
        volatile Instant startedAt;
        volatile boolean awaitingAnswer;
    }

    /**
     * Periodic scan: checks all EXECUTING sessions for timeout expiry.
     */
    private void scanForTimeouts() {
        List<String> sessionIds;
        synchronized (sessions) {
            sessionIds = new ArrayList<>(sessions.keySet());
        }

        Instant now = Instant.now();
        for (String sessionId : sessionIds) {
            SessionState state = sessions.get(sessionId);
            if (state == null || state.gateState != GateState.EXECUTING) {
                continue;
            }

            Instant startedAt = state.startedAt;
            if (startedAt == null) {
                continue;
            }

            if (Duration.between(startedAt, now).compareTo(turnTimeout) > 0) {
                state.lock.lock();
                try {
                    // Recheck after acquiring lock
                    if (state.gateState == GateState.EXECUTING
                        && state.startedAt != null
                        && Duration.between(state.startedAt, Instant.now()).compareTo(turnTimeout) > 0) {

                        logger.warn("Turn timeout for session {}: exceeded {}",
                            sessionId, turnTimeout);
                        notifyTimeout(sessionId);
                        state.gateState = GateState.IDLE;
                        state.lockToken = null;
                        state.startedAt = null;
                        state.awaitingAnswer = false;
                    }
                } finally {
                    state.lock.unlock();
                }
            }
        }
    }

    private void notifyTimeout(String sessionId) {
        Consumer<String> cb = timeoutCallback;
        if (cb != null) {
            try {
                cb.accept(sessionId);
            } catch (Exception e) {
                logger.error("Timeout callback failed for session {}: {}", sessionId, e.getMessage(), e);
            }
        }
    }
}
