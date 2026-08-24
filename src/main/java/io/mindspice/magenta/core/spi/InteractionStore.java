package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.QuestionPendingContext;

/**
 * Persists pending question-user interaction state across restarts.
 */
public interface InteractionStore {

    void save(@NonNull String sessionId, @NonNull QuestionPendingContext context);

    @Nullable
    QuestionPendingContext load(@NonNull String sessionId);

    void delete(@NonNull String sessionId);

    /**
     * Records one combined answer and transitions the pending interaction to
     * its answered state. Implementations backed by durable storage should
     * perform this as an atomic compare-and-set from {@code PENDING}.
     *
     * @return {@code true} when an active interaction was answered
     */
    default boolean answer(@NonNull String sessionId, @NonNull String answer) {
        QuestionPendingContext pending = load(sessionId);
        if (pending == null) {
            return false;
        }
        save(sessionId, pending.answered(answer));
        return true;
    }

    /**
     * Records cancellation and transitions the interaction out of the active
     * pending state.
     *
     * @return {@code true} when an active interaction was cancelled
     */
    default boolean cancel(@NonNull String sessionId, @NonNull String reason) {
        QuestionPendingContext pending = load(sessionId);
        if (pending == null) {
            return false;
        }
        save(sessionId, pending.cancelled(reason));
        return true;
    }
}
