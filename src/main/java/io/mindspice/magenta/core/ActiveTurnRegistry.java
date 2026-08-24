package io.mindspice.magenta.core;

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
interface ActiveTurnRegistry {

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

        /**
         * Atomically performs a persistence-affecting action while this turn
         * still owns execution authority. Revocation waits for an action
         * already in progress, so no fenced write can finish after revoke
         * returns.
         */
        boolean runIfActive(@NonNull Runnable action);

        /** Revokes this turn's execution authority and queues the reason. */
        boolean revoke(@NonNull String reason);

        /** Returns the first reason that revoked this turn, if any. */
        @NonNull
        Optional<String> revocationReason();
    }
}
