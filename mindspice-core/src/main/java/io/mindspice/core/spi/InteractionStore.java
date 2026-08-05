package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import io.mindspice.core.QuestionPendingContext;

/**
 * Persists pending question-user interaction state across restarts.
 */
public interface InteractionStore {

    void save(@NonNull String sessionId, @NonNull QuestionPendingContext context);

    @Nullable
    QuestionPendingContext load(@NonNull String sessionId);

    void delete(@NonNull String sessionId);
}
