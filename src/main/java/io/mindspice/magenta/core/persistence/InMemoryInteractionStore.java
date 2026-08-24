package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.QuestionPendingContext;
import io.mindspice.magenta.core.spi.InteractionStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory compatibility implementation of {@link InteractionStore}.
 *
 * <p>Use {@link SqliteInteractionStore} for restart-safe operation. This
 * implementation retains the same pending/answered/cancelled lifecycle for
 * callers that deliberately choose an ephemeral store.</p>
 */
public final class InMemoryInteractionStore implements InteractionStore {

    private final Map<String, QuestionPendingContext> store = new ConcurrentHashMap<>();

    @Override
    public void save(@NonNull String sessionId, @NonNull QuestionPendingContext context) {
        if (!sessionId.equals(context.sessionId())) {
            throw new IllegalArgumentException(
                "Interaction session does not match the persistence key");
        }
        store.put(sessionId, context);
    }

    @Override
    @Nullable
    public QuestionPendingContext load(@NonNull String sessionId) {
        QuestionPendingContext context = store.get(sessionId);
        return context != null && context.state() == QuestionPendingContext.State.PENDING
            ? context : null;
    }

    @Override
    public boolean answer(@NonNull String sessionId, @NonNull String answer) {
        AtomicBoolean changed = new AtomicBoolean();
        store.computeIfPresent(sessionId, (key, context) -> {
            if (context.state() != QuestionPendingContext.State.PENDING) {
                return context;
            }
            changed.set(true);
            return context.answered(answer);
        });
        return changed.get();
    }

    @Override
    public boolean cancel(@NonNull String sessionId, @NonNull String reason) {
        AtomicBoolean changed = new AtomicBoolean();
        store.computeIfPresent(sessionId, (key, context) -> {
            if (context.state() != QuestionPendingContext.State.PENDING) {
                return context;
            }
            changed.set(true);
            return context.cancelled(reason);
        });
        return changed.get();
    }

    @Override
    public void delete(@NonNull String sessionId) {
        store.remove(sessionId);
    }
}
