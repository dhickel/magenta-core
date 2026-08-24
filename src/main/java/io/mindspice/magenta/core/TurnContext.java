package io.mindspice.magenta.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Mutable turn-scoped carrier. One instance per turn, writes from the owning
 * turn thread. Streaming mode is execution state supplied by the internal
 * engine and is not part of {@link TurnRequest}.
 *
 * <p>The attributes map uses {@link ConcurrentHashMap} purely for safe reads
 * from asynchronous compaction or lifecycle-listener callbacks. Normal
 * turn-state writes are single-threaded.</p>
 */
public final class TurnContext {

    private final TurnRequest request;
    private final String turnId;
    private final String model;
    private final boolean streaming;

    static final AttributeKey<ExecutionOrigin> EXECUTION_ORIGIN =
        AttributeKey.of("magenta.execution.origin", ExecutionOrigin.class);

    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>();
    private final List<TurnMessage> messagesToPersist = new ArrayList<>();
    private ContextUsage currentContextUsage;
    private int toolRoundCount;
    private List<ToolCallback> activeTools = List.of();

    /**
     * Creates a non-streaming context for callers that need a turn-scoped
     * carrier outside the execution engine.
     */
    public TurnContext(@NonNull TurnRequest request,
                       @NonNull String turnId,
                       @NonNull String model) {
        this(request, turnId, model, false);
    }

    /** Creates a context with the execution mode selected by the internal engine. */
    TurnContext(@NonNull TurnRequest request,
                @NonNull String turnId,
                @NonNull String model,
                boolean streaming) {
        this.request = Objects.requireNonNull(request, "request");
        this.turnId = ResponseValidation.nonBlank(turnId, "turnId");
        this.model = ResponseValidation.nonBlank(model, "model");
        this.streaming = streaming;
    }

    // ── Immutable accessors ──

    @NonNull
    public TurnRequest request() {
        return request;
    }

    @NonNull
    public String sessionId() {
        return request.sessionId();
    }

    @NonNull
    public String turnId() {
        return turnId;
    }

    @NonNull
    public String model() {
        return model;
    }

    /** Internal engine accessor; streaming is not a public request property. */
    boolean streaming() {
        return streaming;
    }

    /** Returns the internal execution origin, defaulting to a direct turn. */
    ExecutionOrigin executionOrigin() {
        ExecutionOrigin origin = getAttribute(EXECUTION_ORIGIN);
        return origin == null ? new ExecutionOrigin.Direct() : origin;
    }

    /** Sets the internal execution origin for a continuation or queued turn. */
    void executionOrigin(ExecutionOrigin origin) {
        if (origin == null) {
            attributes.remove(EXECUTION_ORIGIN);
        } else {
            attributes.put(EXECUTION_ORIGIN, origin);
        }
    }

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
