package io.mindspice.magenta.core.internal;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.TurnPhase;

import java.util.List;
import java.util.Optional;

/**
 * Framework-internal registry tracking in-flight turns. The concrete
 * {@code ConcurrentHashMap}-backed implementation is created in a later phase;
 * this phase defines only the interface and its inner {@code ActiveTurn}
 * sub-interface.
 */
public interface ActiveTurnRegistry {

    @NonNull
    ActiveTurn register(@NonNull String turnId);

    @Nullable
    ActiveTurn get(@NonNull String turnId);

    void remove(@NonNull String turnId);

    @NonNull
    List<ActiveTurn> activeTurns();

    // ── Inner interface ──

    interface ActiveTurn {

        @NonNull
        String turnId();

        void phase(@NonNull TurnPhase phase);

        @NonNull
        TurnPhase phase();

        @NonNull
        Optional<String> pollInterrupt();

        void queueInterrupt(@NonNull String reason);
    }
}
