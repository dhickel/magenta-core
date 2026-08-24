package io.mindspice.magenta.core;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

/**
 * Primary facade for embedding applications to interact with the turn-loop
 * framework. A single {@code TurnLoop} instance orchestrates execution,
 * streaming, durable questions, queued work, session management, tools,
 * memory, interruption, and lifecycle control.
 *
 * <h3>Thread safety</h3>
 * The implementation serializes execution-affecting operations per session.
 * Submissions made while a session is active or awaiting questions are queued
 * rather than rejected. The returned streams are cold: subscribing starts the
 * corresponding operation. Tool, memory, and session views are safe for
 * concurrent reads; callers should serialize their mutations. {@link #close()}
 * is idempotent and thread-safe.
 *
 * <h3>Exception contract</h3>
 * Every public method may throw {@link TurnLoopException} or one of its
 * documented subtypes. Provider-specific and other implementation exceptions
 * do not escape this interface. Unexpected execution failures use
 * {@link TurnFailedException} or terminate a stream with an error; they are
 * not emitted as a terminal {@link TurnResponse.Outcome}.
 *
 * @see DefaultTurnLoop
 * @see TurnLoopBuilder
 */
public interface TurnLoop extends AutoCloseable {

    // ── Turn execution ──

    /**
     * Executes a turn synchronously and blocks until its expected outcome is
     * available. A non-idle session receives a durable {@link TurnResponse.Queued}
     * outcome instead of starting a second provider execution.
     *
     * @param request caller turn intent; session ID and message must be nonblank
     * @return the expected terminal outcome
     * @throws TurnFailedException unexpected execution failure with a
     *                             {@link TurnFailure} payload
     * @throws ConfigurationException requested model/configuration is invalid
     * @throws ConversationNotFoundException the session does not exist when
     *                                       the operation requires an existing one
     */
    TurnResponse.Outcome send(@NonNull TurnRequest request);

    /**
     * Executes a turn as a cold stream. The stream emits zero or more
     * {@link TurnResponse.Progress} values, exactly one expected
     * {@link TurnResponse.Outcome}, and then completes. Subscriber cancellation
     * fences the execution and does not produce a contradictory terminal value
     * for that subscription.
     *
     * @param request caller turn intent
     * @return a cold stream of progress and one expected outcome
     * @throws ConfigurationException if the selected model cannot stream
     */
    Flux<TurnResponse> stream(@NonNull TurnRequest request);

    // ── Question interaction ──

    /**
     * Answers one durable pending interaction and blocks for its expected
     * continuation outcome. The answer list must contain exactly one nonblank
     * answer for every ordered question in the interaction.
     *
     * @param sessionId session awaiting answers
     * @param interactionId exact durable interaction identifier
     * @param answers ordered answers, one per pending question
     * @return the continuation outcome
     * @throws InteractionStateException if the interaction is missing or already
     *                                   consumed, answered, or cancelled
     * @throws IllegalArgumentException if answer cardinality or content is invalid
     */
    TurnResponse.Outcome answer(@NonNull String sessionId,
                                @NonNull String interactionId,
                                @NonNull List<String> answers);

    /**
     * Streams a durable question continuation using the same interaction CAS
     * and answer validation as {@link #answer(String, String, List)}.
     *
     * @param sessionId session awaiting answers
     * @param interactionId exact durable interaction identifier
     * @param answers ordered answers, one per pending question
     * @return a cold progress-and-outcome continuation stream
     */
    Flux<TurnResponse> streamAnswer(@NonNull String sessionId,
                                    @NonNull String interactionId,
                                    @NonNull List<String> answers);

    /**
     * Rehydrates the exact durable pending question response without changing
     * interaction state or marking delivery.
     *
     * @param sessionId session to inspect
     * @return the pending interaction, or empty when none is pending
     */
    Optional<TurnResponse.Questions> pendingQuestions(@NonNull String sessionId);

    /**
     * Consumes a pending question interaction by durable ID and records the
     * caller's cancellation reason. Already-consumed or stale interaction IDs
     * are rejected and do not affect a newer interaction.
     *
     * @param sessionId session awaiting answers
     * @param interactionId exact durable interaction identifier
     * @param reason nonblank caller cancellation reason
     * @return the typed cancellation outcome
     * @throws InteractionStateException if the interaction is not cancellable
     */
    TurnResponse.QuestionsCancelled cancelQuestions(@NonNull String sessionId,
                                                     @NonNull String interactionId,
                                                     @NonNull String reason);

    // ── Queue and drain ──

    /**
     * Persists caller intent as queued work and returns its durable receipt.
     * The receipt contains no processing claim or acknowledgement token.
     *
     * @param request request intent to persist
     * @return the durable queue receipt
     */
    TurnResponse.Queued enqueue(@NonNull TurnRequest request);

    /**
     * Starts a cold, framework-managed oldest-first drain for one session. Each
     * claimed request emits its normal progress sequence and one outcome with a
     * queued execution origin. The framework acknowledges successful message
     * or question outcomes internally and stops after questions; retryable
     * interruption, timeout, cancellation, or failure releases the claim.
     *
     * <p>If questions are already pending, the durable question outcome is
     * emitted and the stream completes without claiming queued work. If another
     * execution or drain owns the session, the stream terminates with
     * {@link TurnLoopBusyException}.</p>
     *
     * @param sessionId session whose queue should be drained
     * @return a cold stream of progress and outcomes for claimed executions
     */
    Flux<TurnResponse> drain(@NonNull String sessionId);

    // ── Session management ──

    /**
     * Clears conversation memory and pending interaction state for a session.
     * Session metadata is preserved. Callers should avoid concurrent execution
     * for the session while clearing it.
     *
     * @param sessionId session to clear
     * @throws TurnLoopException if persistence fails
     */
    void clearSession(@NonNull String sessionId);

    // ── Interrupt ──

    /**
     * Requests interruption of an active execution. The request is safe for
     * concurrent use and is observed at the engine's next interruption point.
     *
     * @param turnId active execution turn identifier, normally obtained from
     *              {@link TurnResponse.Started#turnId()}
     * @param reason nonblank interruption reason
     * @return {@code true} when an active turn accepted the request
     */
    boolean interrupt(@NonNull String turnId, @NonNull String reason);

    // ── Accessors ──

    /**
     * Returns the tool sub-interface for registering, unregistering, and
     * inspecting tools. The returned instance is stable for this loop.
     *
     * @return the tool sub-interface
     */
    ToolSubinterface tools();

    /**
     * Returns the memory sub-interface for inspecting and clearing conversation
     * history and context usage.
     *
     * @return the memory sub-interface
     */
    MemorySubinterface memory();

    /**
     * Returns the session sub-interface for listing, retrieving, updating, and
     * deleting session metadata.
     *
     * @return the session sub-interface
     */
    SessionSubinterface sessions();

    // ── Lifecycle ──

    /**
     * Gracefully shuts down the loop, fencing active work and releasing
     * framework-owned resources. This method is idempotent, thread-safe, and
     * does not expose queue claim mechanics to callers.
     */
    @Override
    void close();

    // ── Sub-interfaces ──

    /** View of the tool registry scoped to this {@code TurnLoop} instance. */
    interface ToolSubinterface {

        /** Registers a tool callback. */
        void register(@NonNull ToolCallback tool);

        /** Registers annotated tool methods from a caller-owned bean. */
        void registerToolBean(@NonNull Object bean);

        /** Removes a registered tool by name, if present. */
        void unregister(@NonNull String toolName);

        /** Returns immutable definitions for publicly registered tools. */
        List<ToolDefinition> definitions();
    }

    /** View of conversation memory scoped to this {@code TurnLoop} instance. */
    interface MemorySubinterface {

        /** Returns full session history in insertion order. */
        List<TurnMessage> history(@NonNull String sessionId);

        /** Clears conversation messages for a session. */
        void clear(@NonNull String sessionId);

        /** Computes current context usage for a session. */
        ContextUsage contextUsage(@NonNull String sessionId);
    }

    /** View of session metadata scoped to this {@code TurnLoop} instance. */
    interface SessionSubinterface {

        /** Lists sessions matching the supplied filter. */
        List<ConversationSession> list(@NonNull SessionFilter filter);

        /** Returns a session, or {@code null} when it does not exist. */
        @Nullable
        ConversationSession get(@NonNull String sessionId);

        /** Updates a session title. */
        void updateTitle(@NonNull String sessionId, @NonNull String title);

        /** Sets or clears the favorite flag. */
        void setFavorite(@NonNull String sessionId, boolean favorite);

        /** Sets or clears the archived flag. */
        void setArchived(@NonNull String sessionId, boolean archived);

        /** Deletes a session and its conversation memory. */
        void delete(@NonNull String sessionId);
    }
}
