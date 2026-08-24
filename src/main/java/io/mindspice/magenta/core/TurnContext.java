package io.mindspice.magenta.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Mutable turn-scoped carrier. One instance per turn, writes from the owning
 * turn thread. The attributes map uses {@link ConcurrentHashMap} purely for
 * safe reads from asynchronous compaction or lifecycle-listener callbacks —
 * normal turn-state writes are single-threaded.
 *
 * <p>Immutable fields set at construction time are exposed via accessors.
 * Mutable fields have getters and (where allowed) setters.</p>
 */
public final class TurnContext {

    private final TurnRequest request;
    private final String turnId;
    private final String model;
    private final boolean streaming;

    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final List<TurnMessage> messagesToPersist = new ArrayList<>();
    private ContextUsage currentContextUsage;
    private int toolRoundCount;
    private List<ToolCallback> activeTools = List.of();

    /**
     * Creates a new turn context.
     *
     * @param request the turn request
     * @param turnId  unique turn identifier
     * @param model   resolved model alias (never null)
     */
    public TurnContext(@NonNull TurnRequest request,
                       @NonNull String turnId,
                       @NonNull String model) {
        this.request = Objects.requireNonNull(request);
        this.turnId = Objects.requireNonNull(turnId);
        this.model = Objects.requireNonNull(model);
        this.streaming = request.stream();
    }

    // ── Immutable accessors ──

    @NonNull
    public TurnRequest request() { return request; }

    @NonNull
    public String sessionId() { return request.sessionId(); }

    @NonNull
    public String turnId() { return turnId; }

    @NonNull
    public String model() { return model; }

    public boolean streaming() { return streaming; }

    // ── Typed attribute access ──

    public <T> void setAttribute(@NonNull AttributeKey<T> key, @Nullable T value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public <T> T getAttribute(@NonNull AttributeKey<T> key) {
        return (T) attributes.get(key);
    }

    @NonNull
    public <T> T requireAttribute(@NonNull AttributeKey<T> key) {
        @SuppressWarnings("unchecked")
        T value = (T) attributes.get(key);
        if (value == null) {
            throw new IllegalStateException(
                "Required attribute '" + key.name() + "' is not set");
        }
        return value;
    }

    public <T> boolean hasAttribute(@NonNull AttributeKey<T> key) {
        return attributes.containsKey(key);
    }

    // ── Mutable turn state ──

    @NonNull
    public List<TurnMessage> messagesToPersist() {
        return messagesToPersist;
    }

    @Nullable
    public ContextUsage currentContextUsage() {
        return currentContextUsage;
    }

    public void currentContextUsage(@Nullable ContextUsage v) {
        this.currentContextUsage = v;
    }

    public int toolRoundCount() {
        return toolRoundCount;
    }

    public void incrementToolRoundCount() {
        this.toolRoundCount++;
    }

    // ── Tool-related ──

    @NonNull
    public List<ToolCallback> activeTools() {
        return activeTools;
    }

    public void activeTools(@NonNull List<ToolCallback> v) {
        this.activeTools = Objects.requireNonNull(v);
    }
}
