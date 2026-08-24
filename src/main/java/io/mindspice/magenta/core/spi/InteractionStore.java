package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.QuestionPendingContext;

/**
 * Persists pending question-user interaction state across restarts.
 */
public interface InteractionStore {

    void save(@NonNull String sessionId, @NonNull QuestionPendingContext context);

    /**
     * Loads the latest interaction snapshot, including terminal ANSWERED or
     * CANCELLED state when retained by the store. Terminal snapshots let
     * callers distinguish an already-claimed interaction from an absent row.
     */
    @Nullable
    QuestionPendingContext load(@NonNull String sessionId);

    void delete(@NonNull String sessionId);

    /**
     * Records one combined answer and transitions the pending interaction to
     * its answered state. The compatibility default serializes callers of the
     * same store instance; durable or multi-instance stores must override it
     * with a storage-level compare-and-set from {@code PENDING}.
     *
     * @return {@code true} when an active interaction was answered
     */
    default boolean answer(@NonNull String sessionId, @NonNull String answer) {
        synchronized (this) {
            QuestionPendingContext pending = load(sessionId);
            if (pending == null || pending.state() != QuestionPendingContext.State.PENDING) {
                return false;
            }
            save(sessionId, pending.answered(answer));
            return true;
        }
    }

    /**
     * Records cancellation and transitions the interaction out of the active
     * pending state. The compatibility default serializes only callers of the
     * same store instance; durable stores must override it with storage CAS.
     *
     * @return {@code true} when an active interaction was cancelled
     */
    default boolean cancel(@NonNull String sessionId, @NonNull String reason) {
        synchronized (this) {
            QuestionPendingContext pending = load(sessionId);
            if (pending == null || pending.state() != QuestionPendingContext.State.PENDING) {
                return false;
            }
            save(sessionId, pending.cancelled(reason));
            return true;
        }
    }
}
