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
import java.util.Objects;
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
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
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
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Session session = sessions.computeIfAbsent(sessionId, ignored -> new Session());
        session.lock.lock();
        try {
            return switch (session.state) {
                case Idle ignored -> {
                    Executing executing = newExecutingState();
                    session.state = executing;
                    yield new AcquireResult.Acquired(executing.lockToken());
                }
                case Executing ignored -> new AcquireResult.Rejected(
                    "Session '" + sessionId + "' is already executing a turn");
                case TimingOut ignored -> new AcquireResult.Rejected(
                    "Session '" + sessionId + "' is already executing a turn");
                case AwaitingAnswer ignored -> {
                    if (mode == TurnGateMode.ANSWER) {
                        Executing executing = newExecutingState();
                        session.state = executing;
                        yield new AcquireResult.Acquired(executing.lockToken());
                    }
                    yield new AcquireResult.Rejected(
                        "Session '" + sessionId + "' is awaiting user answer");
                }
            };
        } finally {
            session.lock.unlock();
        }
    }

    @Override
    public void release(@NonNull String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) return;
        session.lock.lock();
        try {
            reset(session);
        } finally {
            session.lock.unlock();
        }
    }

    @Override
    public void transitionToAwaitingAnswer(@NonNull String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            logger.warn("transitionToAwaitingAnswer called for unknown session: {}", sessionId);
            return;
        }
        session.lock.lock();
        try {
            switch (session.state) {
                case Executing ignored -> session.state = new AwaitingAnswer();
                case Idle ignored -> logger.warn(
                    "transitionToAwaitingAnswer: session {} is in state {}, expected EXECUTING",
                    sessionId, GateState.IDLE);
                case TimingOut ignored -> logger.warn(
                    "transitionToAwaitingAnswer: session {} is in state {}, expected EXECUTING",
                    sessionId, GateState.EXECUTING);
                case AwaitingAnswer ignored -> logger.warn(
                    "transitionToAwaitingAnswer: session {} is in state {}, expected EXECUTING",
                    sessionId, GateState.AWAITING_ANSWER);
            }
        } finally {
            session.lock.unlock();
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

            Session session = sessions.get(sessionId);
            if (session == null) continue;
            String token = null;
            session.lock.lock();
            try {
                switch (session.state) {
                    case AwaitingAnswer ignored -> {
                        logger.info("Drain: releasing AWAITING_ANSWER session {}", sessionId);
                        reset(session);
                    }
                    case Executing ignored -> token = markTimingOut(session);
                    case TimingOut ignored -> { }
                    case Idle ignored -> { }
                }
            } finally {
                session.lock.unlock();
            }
            if (token != null) {
                logger.info("Drain: fencing EXECUTING session {}", sessionId);
                fenceAndRelease(sessionId, session, token);
            }
        }
    }

    @Override
    @NonNull
    public GateState state(@NonNull String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) return GateState.IDLE;
        session.lock.lock();
        try {
            return project(session.state);
        } finally {
            session.lock.unlock();
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
            Session session = sessions.get(sessionId);
            if (session == null) continue;
            String token = null;
            session.lock.lock();
            try {
                if (session.state instanceof Executing executing
                    && Duration.between(executing.startedAt(), now).compareTo(turnTimeout) >= 0) {
                    token = markTimingOut(session);
                }
            } finally {
                session.lock.unlock();
            }
            if (token != null) {
                logger.warn("Turn timeout for session {}: exceeded {}", sessionId, turnTimeout);
                fenceAndRelease(sessionId, session, token);
            }
        }
    }

    @Nullable
    private String markTimingOut(Session session) {
        if (!(session.state instanceof Executing executing)) return null;
        session.state = new TimingOut(executing.lockToken(), executing.startedAt());
        return executing.lockToken();
    }

    private void fenceAndRelease(String sessionId, Session session, String token) {
        boolean fenced = notifyTimeout(sessionId, token);
        session.lock.lock();
        try {
            if (!(session.state instanceof TimingOut timingOut)
                || !token.equals(timingOut.lockToken())) {
                return;
            }
            if (fenced) {
                reset(session);
            } else {
                session.state = new Executing(timingOut.lockToken(), timingOut.startedAt());
                logger.error("Turn timeout for session {} was not fenced; gate remains EXECUTING",
                    sessionId);
            }
        } finally {
            session.lock.unlock();
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

    private Executing newExecutingState() {
        return new Executing(UUID.randomUUID().toString(), clock.instant());
    }

    private static GateState project(SessionState state) {
        return switch (state) {
            case Idle ignored -> GateState.IDLE;
            case Executing ignored -> GateState.EXECUTING;
            case TimingOut ignored -> GateState.EXECUTING;
            case AwaitingAnswer ignored -> GateState.AWAITING_ANSWER;
        };
    }

    private static void reset(Session session) {
        session.state = new Idle();
    }

    private static final class Session {
        private final ReentrantLock lock = new ReentrantLock();
        private SessionState state = new Idle();
    }

    sealed interface SessionState permits Idle, Executing, TimingOut, AwaitingAnswer { }

    record Idle() implements SessionState { }

    record Executing(String lockToken, Instant startedAt) implements SessionState {
        Executing {
            Objects.requireNonNull(lockToken, "lockToken");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    record TimingOut(String lockToken, Instant startedAt) implements SessionState {
        TimingOut {
            Objects.requireNonNull(lockToken, "lockToken");
            Objects.requireNonNull(startedAt, "startedAt");
        }
    }

    record AwaitingAnswer() implements SessionState { }
}
