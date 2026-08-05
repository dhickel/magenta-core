package io.mindspice.core.persistence;

import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import io.mindspice.core.QuestionPendingContext;
import io.mindspice.core.spi.InteractionStore;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * In-memory {@link InteractionStore} backed by a {@link ConcurrentHashMap}.
 * Suitable for single-node deployments where restart resilience is not required.
 *
 * <p>Thread-safe by virtue of {@link ConcurrentHashMap}.</p>
 */
public final class InMemoryInteractionStore implements InteractionStore {

    private final Map<String, QuestionPendingContext> store = new ConcurrentHashMap<>();

    @Override
    public void save(@NonNull String sessionId, @NonNull QuestionPendingContext context) {
        store.put(sessionId, context);
    }

    @Override
    @Nullable
    public QuestionPendingContext load(@NonNull String sessionId) {
        return store.get(sessionId);
    }

    @Override
    public void delete(@NonNull String sessionId) {
        store.remove(sessionId);
    }
}
