package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.TurnGate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialTurnGateTest {

    @Test
    void internalStateFamilyIsSealedAndStructurallyRepresentsEachState() {
        assertTrue(SerialTurnGate.SessionState.class.isSealed());
        assertEquals(Set.of(
                SerialTurnGate.Idle.class,
                SerialTurnGate.Executing.class,
                SerialTurnGate.TimingOut.class,
                SerialTurnGate.AwaitingAnswer.class),
            Set.of(SerialTurnGate.SessionState.class.getPermittedSubclasses()));
        assertEquals(0, SerialTurnGate.Idle.class.getRecordComponents().length);
        assertEquals(2, SerialTurnGate.Executing.class.getRecordComponents().length);
        assertEquals(2, SerialTurnGate.TimingOut.class.getRecordComponents().length);
        assertEquals(0, SerialTurnGate.AwaitingAnswer.class.getRecordComponents().length);
    }

    @Test
    void transitionsThroughExecutionAnswerReacquisitionAndRelease() {
        SerialTurnGate gate = new SerialTurnGate(Duration.ofMinutes(1));
        try {
            TurnGate.AcquireResult.Acquired first = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("session", TurnGate.TurnGateMode.SEND));
            assertTrue(!first.lockToken().isBlank());
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("session"));
            assertInstanceOf(TurnGate.AcquireResult.Rejected.class,
                gate.acquire("session", TurnGate.TurnGateMode.STREAM));

            gate.transitionToAwaitingAnswer("session");
            assertEquals(TurnGate.GateState.AWAITING_ANSWER, gate.state("session"));
            assertInstanceOf(TurnGate.AcquireResult.Rejected.class,
                gate.acquire("session", TurnGate.TurnGateMode.SEND));

            TurnGate.AcquireResult.Acquired answer = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("session", TurnGate.TurnGateMode.ANSWER));
            assertNotEquals(first.lockToken(), answer.lockToken());
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("session"));

            gate.release("session");
            assertEquals(TurnGate.GateState.IDLE, gate.state("session"));
        } finally {
            gate.close();
        }
    }

    @Test
    void concurrentAcquisitionAllowsExactlyOneOwner() throws Exception {
        SerialTurnGate gate = new SerialTurnGate(Duration.ofMinutes(1));
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            CyclicBarrier ready = new CyclicBarrier(12);
            List<Future<TurnGate.AcquireResult>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    return gate.acquire("same-session", TurnGate.TurnGateMode.SEND);
                }));
            }

            List<TurnGate.AcquireResult> results = new ArrayList<>();
            for (Future<TurnGate.AcquireResult> future : futures) {
                results.add(future.get(2, TimeUnit.SECONDS));
            }
            assertEquals(1, results.stream()
                .filter(TurnGate.AcquireResult.Acquired.class::isInstance)
                .count());
            assertEquals(11, results.stream()
                .filter(TurnGate.AcquireResult.Rejected.class::isInstance)
                .count());
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("same-session"));
            gate.release("same-session");
            assertEquals(TurnGate.GateState.IDLE, gate.state("same-session"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            gate.close();
        }
    }

    @Test
    void successfulTimeoutFenceProjectsTimingOutAsExecutingUntilFenced() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T15:00:00Z"));
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofSeconds(5), Duration.ofHours(1), clock);
        AtomicReference<String> fencedToken = new AtomicReference<>();
        AtomicReference<String> callbackSession = new AtomicReference<>();
        java.util.concurrent.CountDownLatch handlerEntered =
            new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowFence =
            new java.util.concurrent.CountDownLatch(1);
        Thread scanner = null;
        try {
            TurnGate.AcquireResult.Acquired acquired = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("timeout-session", TurnGate.TurnGateMode.SEND));
            gate.setTimeoutHandler((sessionId, token) -> {
                fencedToken.set(token);
                handlerEntered.countDown();
                try {
                    allowFence.await(2, TimeUnit.SECONDS);
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });
            gate.setTimeoutCallback(callbackSession::set);
            clock.advance(Duration.ofSeconds(5));

            scanner = Thread.ofVirtual().start(gate::scanForTimeouts);
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("timeout-session"));
            assertInstanceOf(TurnGate.AcquireResult.Rejected.class,
                gate.acquire("timeout-session", TurnGate.TurnGateMode.SEND));

            allowFence.countDown();
            scanner.join(2_000);
            assertEquals(TurnGate.GateState.IDLE, gate.state("timeout-session"));
            assertEquals(acquired.lockToken(), fencedToken.get());
            assertEquals("timeout-session", callbackSession.get());

            TurnGate.AcquireResult.Acquired successor = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("timeout-session", TurnGate.TurnGateMode.SEND));
            assertNotEquals(acquired.lockToken(), successor.lockToken());
            gate.release("timeout-session");
        } finally {
            allowFence.countDown();
            if (scanner != null) scanner.join(2_000);
            gate.close();
        }
    }

    @Test
    void staleTimeoutFenceCannotReleaseAReacquiredSession() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T15:00:00Z"));
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofSeconds(5), Duration.ofHours(1), clock);
        java.util.concurrent.CountDownLatch handlerEntered =
            new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowFence =
            new java.util.concurrent.CountDownLatch(1);
        Thread scanner = null;
        try {
            TurnGate.AcquireResult.Acquired original = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("reused-session", TurnGate.TurnGateMode.SEND));
            gate.setTimeoutHandler((sessionId, token) -> {
                handlerEntered.countDown();
                try {
                    allowFence.await(2, TimeUnit.SECONDS);
                    return true;
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });
            clock.advance(Duration.ofSeconds(5));
            scanner = Thread.ofVirtual().start(gate::scanForTimeouts);
            assertTrue(handlerEntered.await(2, TimeUnit.SECONDS));

            gate.release("reused-session");
            TurnGate.AcquireResult.Acquired successor = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("reused-session", TurnGate.TurnGateMode.SEND));
            assertNotEquals(original.lockToken(), successor.lockToken());

            allowFence.countDown();
            scanner.join(2_000);
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("reused-session"));
            assertInstanceOf(TurnGate.AcquireResult.Rejected.class,
                gate.acquire("reused-session", TurnGate.TurnGateMode.SEND));
            gate.release("reused-session");
        } finally {
            allowFence.countDown();
            if (scanner != null) scanner.join(2_000);
            gate.close();
        }
    }

    @Test
    void failedTimeoutFenceRetainsOwnershipAndRetriesTheSameToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T15:00:00Z"));
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofSeconds(5), Duration.ofHours(1), clock);
        List<String> tokens = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        try {
            TurnGate.AcquireResult.Acquired acquired = assertInstanceOf(
                TurnGate.AcquireResult.Acquired.class,
                gate.acquire("fenced-session", TurnGate.TurnGateMode.SEND));
            gate.setTimeoutHandler((sessionId, token) -> {
                tokens.add(token);
                return attempts.incrementAndGet() > 1;
            });
            clock.advance(Duration.ofSeconds(5));

            gate.scanForTimeouts();
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("fenced-session"));
            assertInstanceOf(TurnGate.AcquireResult.Rejected.class,
                gate.acquire("fenced-session", TurnGate.TurnGateMode.SEND));

            gate.scanForTimeouts();
            assertEquals(TurnGate.GateState.IDLE, gate.state("fenced-session"));
            assertEquals(List.of(acquired.lockToken(), acquired.lockToken()), tokens);
        } finally {
            gate.close();
        }
    }

    @Test
    void timeoutHandlerAndCallbackFailuresAreIsolated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-24T15:00:00Z"));
        SerialTurnGate gate = new SerialTurnGate(
            Duration.ofSeconds(5), Duration.ofHours(1), clock);
        AtomicInteger callbacks = new AtomicInteger();
        try {
            gate.acquire("isolated-session", TurnGate.TurnGateMode.SEND);
            gate.setTimeoutHandler((sessionId, token) -> {
                throw new AssertionError("handler failure");
            });
            gate.setTimeoutCallback(sessionId -> {
                callbacks.incrementAndGet();
                throw new AssertionError("callback failure");
            });
            clock.advance(Duration.ofSeconds(5));

            gate.scanForTimeouts();
            assertEquals(1, callbacks.get());
            assertEquals(TurnGate.GateState.EXECUTING, gate.state("isolated-session"));
        } finally {
            gate.close();
        }
    }

    @Test
    void drainReleasesAwaitingAnswerAndFencedExecutingSessions() {
        SerialTurnGate gate = new SerialTurnGate(Duration.ofMinutes(1));
        List<String> fencedSessions = new ArrayList<>();
        try {
            gate.acquire("awaiting", TurnGate.TurnGateMode.SEND);
            gate.transitionToAwaitingAnswer("awaiting");
            gate.acquire("executing", TurnGate.TurnGateMode.SEND);
            gate.setTimeoutHandler((sessionId, token) -> {
                fencedSessions.add(sessionId);
                return true;
            });

            gate.drain(0);

            assertEquals(TurnGate.GateState.IDLE, gate.state("awaiting"));
            assertEquals(TurnGate.GateState.IDLE, gate.state("executing"));
            assertEquals(List.of("executing"), fencedSessions);
        } finally {
            gate.close();
        }
    }

    @Test
    void closeRejectsNewAcquisitionAndIsIdempotent() {
        SerialTurnGate gate = new SerialTurnGate(Duration.ofMinutes(1));
        gate.acquire("session", TurnGate.TurnGateMode.SEND);
        gate.close();
        gate.close();

        TurnGate.AcquireResult.Rejected rejected = assertInstanceOf(
            TurnGate.AcquireResult.Rejected.class,
            gate.acquire("session", TurnGate.TurnGateMode.SEND));
        assertEquals("Turn gate is closed", rejected.reason());
        assertEquals(TurnGate.GateState.EXECUTING, gate.state("session"));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }

        private void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }
    }
}
