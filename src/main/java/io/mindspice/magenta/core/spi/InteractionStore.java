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
}
