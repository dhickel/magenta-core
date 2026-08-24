package io.mindspice.magenta.core;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Primary facade for embedding applications to interact with the turn-loop
 * framework. A single {@code TurnLoop} instance orchestrates turn execution,
 * streaming, question-user interaction, message queuing, session management,
 * tool registration, memory access, and lifecycle control.
 *
 * <h3>Thread safety</h3>
 * Callers must serialize {@link #send(TurnRequest) send},
 * {@link #stream(TurnRequest) stream}, and {@link #answer(String, String) answer}
 * per session (these methods contend on a per-session {@code TurnGate}).
 * The sub-interfaces returned by {@link #tools()}, {@link #memory()}, and
 * {@link #sessions()} are safe for concurrent read but mutations through
 * them should be serialized externally. {@link #enqueue(TurnRequest) enqueue},
 * {@link #claim(String) claim}, {@link #ack(String) ack},
 * {@link #release(String) release}, {@link #interrupt(String, String) interrupt},
 * and {@link #cancelQuestion(String, String) cancelQuestion} are safe for
 * concurrent use. {@link #close()} is idempotent and thread-safe.
 *
 * <h3>Exception contract</h3>
 * Every public method may throw {@link TurnLoopException} or one of its
 * documented subtypes. No internal exception type (including Spring AI
 * exceptions) escapes through this interface.
 *
 * @see DefaultTurnLoop
 * @see TurnLoopBuilder
 */
public interface TurnLoop extends AutoCloseable {

    // ── Turn execution ──

    /**
     * Executes a turn synchronously and blocks until the full turn lifecycle
     * completes. The result includes the final assistant message, turn
     * diagnostic, tool activity summaries, and context usage.
     *
     * <p>Callers must serialize per-session calls to {@code send},
     * {@code stream}, and {@code answer}.</p>
     *
     * @param request the turn request (sessionId and message must be non-blank)
     * @return the completed turn result
     * @throws TurnLoopException          if the gate rejects the turn (e.g. a
     *                                    turn is already in progress for the session)
     * @throws TurnFailedException        if the turn completes with a non-success
     *                                    outcome (FAILED, ABORTED, TIMED_OUT)
     * @throws ConfigurationException     if the requested model is not configured
     * @throws ConversationNotFoundException if the session does not exist
     * @throws ToolException              if a tool fails during the turn (wrapped
     *                                    by the engine and surfaced here)
     */
    TurnResult send(@NonNull TurnRequest request);

    /**
     * Executes a turn and returns a reactive stream of {@link TurnEvent}
     * lifecycle events. The returned {@link Flux} is cold; subscribing starts
     * the turn. Cancelling the subscription cancels the turn.
     *
     * <p>Callers must serialize per-session calls to {@code send},
     * {@code stream}, and {@code answer}. The returned {@code Flux} may
     * complete with a {@code TurnEvent.Completed}, error with a
     * {@code TurnEvent.Failed}, or be cancelled by the subscriber.</p>
     *
     * @param request the turn request ({@code stream} should be {@code true})
     * @return a cold {@code Flux} of turn lifecycle events
     * @throws TurnLoopException      if the gate rejects the turn
     * @throws ConfigurationException if the model does not support streaming
     */
    Flux<TurnEvent> stream(@NonNull TurnRequest request);

    // ── Question-user loop ──

    /**
     * Resumes a suspended turn that is waiting for a user answer. After a
     * turn emits {@link TurnEvent.QuestionPending}, the embedding application
     * collects the user's answer and calls this method to continue.
     *
     * <p>Callers must serialize per-session calls to {@code send},
     * {@code stream}, and {@code answer}.</p>
     *
     * @param sessionId the session awaiting an answer
     * @param answer    the user's answer text (must be non-blank)
     * @return the completed turn result
     * @throws TurnLoopException if no question is pending for the session
     *                           or the stored context is stale
     * @throws TurnFailedException if the resumed turn fails
     */
    TurnResult answer(@NonNull String sessionId, @NonNull String answer);

    /**
     * Cancels the pending question interaction for a session, releases its
     * gate, and leaves already queued messages untouched.
     *
     * @param sessionId session awaiting an answer
     * @param reason durable cancellation reason
     * @return {@code true} when an active pending interaction was cancelled
     */
    boolean cancelQuestion(@NonNull String sessionId, @NonNull String reason);

    // ── Queue ──

    /**
     * Enqueues a turn request for asynchronous processing. The message is
     * stored in the {@code MessageQueue} and can later be claimed and
     * executed via {@link #claim(String)} and {@link #send(TurnRequest)}.
     *
     * <p>This method is safe for concurrent use.</p>
     *
     * @param request the turn request to enqueue (message content is extracted)
     * @return a {@code PendingMessage} with a unique message ID
     * @throws TurnLoopException if the queue operation fails
     */
    PendingMessage enqueue(@NonNull TurnRequest request);

    /**
     * Claims the oldest pending message for the given session. Returns
     * {@code null} if no messages are queued for the session.
     *
     * <p>This method is safe for concurrent use.</p>
     *
     * @param sessionId the session to claim a message from
     * @return the claimed message with a claim token, or {@code null}
     * @throws TurnLoopException if the claim operation fails at the storage level
     */
    @Nullable
    ClaimedMessage claim(@NonNull String sessionId);

    /**
     * Acknowledges a claimed message, removing it from the queue permanently.
     * If the claim token is stale or already acknowledged, a warning is
     * logged and the call returns normally (idempotent-tolerant).
     *
     * <p>This method is safe for concurrent use.</p>
     *
     * @param claimToken the claim token returned by {@link #claim(String)}
     * @throws TurnLoopException if storage fails during acknowledgement
     */
    void ack(@NonNull String claimToken);

    /**
     * Releases a claimed message back to the queue so it can be claimed
     * again. If the claim token is stale or already released, a warning
     * is logged and the call returns normally (idempotent-tolerant).
     *
     * <p>This method is safe for concurrent use.</p>
     *
     * @param claimToken the claim token returned by {@link #claim(String)}
     * @throws TurnLoopException if storage fails during release
     */
    void release(@NonNull String claimToken);

    // ── Session management ──

    /**
     * Clears all conversation memory and pending interactions for a session.
     * Session metadata (title, favorites, etc.) is preserved.
     *
     * <p>Callers should ensure no turn is in progress for the session
     * before calling this method.</p>
     *
     * @param sessionId the session to clear
     * @throws TurnLoopException if persistence operations fail
     */
    void clearSession(@NonNull String sessionId);

    // ── Interrupt ──

    /**
     * Interrupts an in-progress turn identified by {@code turnId}. If the
     * turn is found and active, an interrupt signal is queued and the
     * engine will terminate the turn at its next polling point.
     *
     * <p>This method is safe for concurrent use.</p>
     *
     * @param turnId the turn identifier (from {@link TurnEvent.Started#turnId()})
     * @param reason a human-readable reason for the interrupt
     * @return {@code true} if the turn was found and interrupted,
     *         {@code false} if the turn is already completed or unknown
     */
    boolean interrupt(@NonNull String turnId, @NonNull String reason);

    // ── Accessors ──

    /**
     * Returns the tool sub-interface for registering, unregistering, and
     * inspecting tools. The returned instance is the same on every call
     * and is safe for concurrent read.
     *
     * @return the tool sub-interface
     */
    ToolSubinterface tools();

    /**
     * Returns the memory sub-interface for inspecting and clearing
     * conversation history and context usage. The returned instance is
     * the same on every call and is safe for concurrent read.
     *
     * @return the memory sub-interface
     */
    MemorySubinterface memory();

    /**
     * Returns the session sub-interface for listing, retrieving, updating,
     * and deleting session metadata. The returned instance is the same on
     * every call and is safe for concurrent read.
     *
     * @return the session sub-interface
     */
    SessionSubinterface sessions();

    // ── Lifecycle ──

    /**
     * Gracefully shuts down the turn loop. The shutdown sequence drains
     * the gate (cancelling pending questions, waiting for executing turns
     * up to a configurable timeout, then force-releasing), releases stream
     * resources, releases pending claims, closes model instances, and
     * shuts down the turn executor.
     *
     * <p>This method is idempotent and thread-safe. It must never throw.
     * Repeated calls after the first are no-ops.</p>
     */
    @Override
    void close();

    // ── Sub-interfaces ──

    /**
     * View of the tool registry scoped to this {@code TurnLoop} instance.
     * Provides methods to register tools from callbacks or annotated beans,
     * unregister tools by name, and list public tool definitions.
     *
     * <p>Safe for concurrent read. Mutations ({@code register},
     * {@code registerToolBean}, {@code unregister}) should be serialized
     * externally.</p>
     */
    interface ToolSubinterface {

        /**
         * Registers a tool callback.
         *
         * @param tool the tool to register
         * @throws ToolException if a tool with the same name is already
         *                       registered (including framework-internal tools)
         */
        void register(@NonNull ToolCallback tool);

        /**
         * Registers a bean whose methods annotated with a framework
         * {@code @Tool} annotation are converted to tool callbacks.
         *
         * @param bean the annotated object
         * @throws ToolException if a discovered tool name conflicts with an
         *                       already-registered tool
         */
        void registerToolBean(@NonNull Object bean);

        /**
         * Unregisters a tool by name. Has no effect if no tool with the
         * given name is registered.
         *
         * @param toolName the tool name
         */
        void unregister(@NonNull String toolName);

        /**
         * Returns the definitions of all publicly registered tools.
         * Framework-internal tools (e.g. {@code ask_user}) are excluded.
         *
         * @return immutable list of tool definitions
         */
        List<ToolDefinition> definitions();
    }

    /**
     * View of conversation memory scoped to this {@code TurnLoop} instance.
     * Provides access to message history, clearing, and context usage
     * estimation.
     *
     * <p>Safe for concurrent read.</p>
     */
    interface MemorySubinterface {

        /**
         * Returns the full message history for a session in insertion order
         * (oldest first).
         *
         * @param sessionId the session identifier
         * @return immutable list of messages (empty if the session has no history)
         * @throws TurnLoopException if storage access fails
         */
        List<TurnMessage> history(@NonNull String sessionId);

        /**
         * Clears all messages from a session's conversation memory.
         *
         * @param sessionId the session identifier
         * @throws TurnLoopException if storage access fails
         */
        void clear(@NonNull String sessionId);

        /**
         * Estimates the token usage of the current conversation context
         * for a session.
         *
         * @param sessionId the session identifier
         * @return the context usage snapshot
         * @throws TurnLoopException if calculation or storage access fails
         */
        ContextUsage contextUsage(@NonNull String sessionId);
    }

    /**
     * View of session metadata scoped to this {@code TurnLoop} instance.
     * Provides listing, retrieval, update, and deletion of
     * {@link ConversationSession} records.
     *
     * <p>Safe for concurrent read.</p>
     */
    interface SessionSubinterface {

        /**
         * Lists sessions matching the given filter criteria.
         *
         * @param filter the filter (use {@link SessionFilter#all()} for
         *               unfiltered listing)
         * @return immutable list of matching sessions
         * @throws TurnLoopException if storage access fails
         */
        List<ConversationSession> list(@NonNull SessionFilter filter);

        /**
         * Retrieves a single session by its identifier.
         *
         * @param sessionId the session identifier
         * @return the session, or {@code null} if not found
         * @throws TurnLoopException if storage access fails
         */
        @Nullable
        ConversationSession get(@NonNull String sessionId);

        /**
         * Updates the title of a session.
         *
         * @param sessionId the session identifier
         * @param title     the new title (may be blank)
         * @throws TurnLoopException if storage access fails
         */
        void updateTitle(@NonNull String sessionId, @NonNull String title);

        /**
         * Sets or clears the favorite flag on a session.
         *
         * @param sessionId the session identifier
         * @param favorite  {@code true} to mark as favorite, {@code false} to clear
         * @throws TurnLoopException if storage access fails
         */
        void setFavorite(@NonNull String sessionId, boolean favorite);

        /**
         * Sets or clears the archived flag on a session.
         *
         * @param sessionId the session identifier
         * @param archived  {@code true} to mark as archived, {@code false} to clear
         * @throws TurnLoopException if storage access fails
         */
        void setArchived(@NonNull String sessionId, boolean archived);

        /**
         * Deletes a session and its associated conversation memory.
         *
         * @param sessionId the session identifier
         * @throws TurnLoopException if storage access fails
         */
        void delete(@NonNull String sessionId);
    }
}
