package io.mindspice.magenta.core.internal;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.TurnPhase;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link ConcurrentHashMap}-backed implementation of {@link ActiveTurnRegistry}.
 *
 * <p>Thread-safe by virtue of {@link ConcurrentHashMap} and
 * {@link ConcurrentLinkedQueue}. Registering the same turn ID a second
 * time replaces the previous registration (no duplicate detection —
 * the engine ensures uniqueness).</p>
 */
public final class ConcurrentActiveTurnRegistry implements ActiveTurnRegistry {

    private final ConcurrentMap<String, ActiveTurnImpl> turns = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public ActiveTurn register(@NonNull String turnId) {
        ActiveTurnImpl active = new ActiveTurnImpl(turnId);
        turns.put(turnId, active);
        return active;
    }

    @Override
    @Nullable
    public ActiveTurn get(@NonNull String turnId) {
        return turns.get(turnId);
    }

    @Override
    public void remove(@NonNull String turnId) {
        turns.remove(turnId);
    }

    @Override
    @NonNull
    public List<ActiveTurn> activeTurns() {
        return List.copyOf(turns.values());
    }

    // ── Inner implementation ──

    private static final class ActiveTurnImpl implements ActiveTurn {

        private final String turnId;
        private volatile TurnPhase phase = TurnPhase.PREPARE;
        private final ConcurrentLinkedQueue<String> interruptQueue = new ConcurrentLinkedQueue<>();

        ActiveTurnImpl(@NonNull String turnId) {
            this.turnId = turnId;
        }

        @Override
        @NonNull
        public String turnId() {
            return turnId;
        }

        @Override
        public void phase(@NonNull TurnPhase phase) {
            this.phase = phase;
        }

        @Override
        @NonNull
        public TurnPhase phase() {
            return phase;
        }

        @Override
        @NonNull
        public Optional<String> pollInterrupt() {
            return Optional.ofNullable(interruptQueue.poll());
        }

        @Override
        public void queueInterrupt(@NonNull String reason) {
            interruptQueue.add(reason);
        }
    }
}
