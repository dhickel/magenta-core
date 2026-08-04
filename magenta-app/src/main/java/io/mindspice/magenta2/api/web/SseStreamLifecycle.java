package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

/**
 * Standardized SSE lifecycle support for text/event-stream endpoints.
 *
 * <h3>Stream Outcome Table</h3>
 *
 * <pre>{@code
 *  Outcome             | Trigger                              | Domain Behavior
 * ---------------------+--------------------------------------+------------------------------------------
 *  COMPLETED           | Normal stream completion             | Subscription disposed, emitter completes.
 *                      | (Flux completes or sync work done)   | ActiveTurn completed if registered.
 * ---------------------+--------------------------------------+------------------------------------------
 *  CLIENT_DISCONNECTED | Client closes connection             | onCompletion fires: subscription
 *                      | (Tomcat/Netty connection close)      | disposed, ActiveTurn completed.
 *                      |                                      | No error recorded (clean client exit).
 * ---------------------+--------------------------------------+------------------------------------------
 *  TIMEOUT             | SseEmitter timeout fires             | Timeout handler invoked: subscription
 *                      | (server-side timeout only, not       | disposed, ActiveTurn completed.
 *                      | client idle timeout)                 | Plan execution: failure recorded.
 *                      |                                      | Non-plan: message discarded.
 * ---------------------+--------------------------------------+------------------------------------------
 *  USER_CANCELLED      | Turn interrupt or cancellation API  | Subscription disposed (via interrupted
 *                      | (POST /turns/{turnId}/interrupt)     | thread), ActiveTurn completes.
 *                      |                                      | No separate emitter signal; the
 *                      |                                      | cancellation causes an error or
 *                      |                                      | completion on the stream.
 * ---------------------+--------------------------------------+------------------------------------------
 *  MODEL_TOOL_FAILURE  | Model-level or tool-level error      | Error handler invoked: subscription
 *                      | during streaming                     | disposed, ActiveTurn completed.
 *                      |                                      | Plan execution: failure recorded.
 *                      |                                      | Non-plan: last user message discarded.
 * ---------------------+--------------------------------------+------------------------------------------
 *  VALIDATION_FAILURE  | Input validation fails before stream | Controller throws ResponseStatusException
 *                      | starts (invalid UUID, missing field, | before emitter is created, or an error
 *                      | conversation not found, etc.)        | event is sent and emitter completes
 *                      |                                      | without a subscription.
 * ---------------------+--------------------------------------+------------------------------------------
 *  INTERNAL_ERROR      | Unexpected exception during stream   | emitter.completeWithError called.
 *                      | setup or event sending               | Subscription disposed if created.
 *                      |                                      | Plan execution: failure recorded.
 * }</pre>
 *
 * <p>Each stream endpoint should use the same timeout, cancellation, and cleanup
 * policy unless there is a documented product reason to differ. Chat plan-execution
 * streams may opt into a configurable timeout, but use no server-side SSE timeout
 * by default because active saved-plan runs can exceed a short wall-clock window.
 *
 * <p>This class handles only the transport lifecycle. Domain transitions (turn
 * completion, execution failure recording, message discard) remain in the calling
 * controller or service as documented in the outcome table above.
 */
public final class SseStreamLifecycle {

    private SseStreamLifecycle() {}

    /**
     * Creates an SseEmitter with the given timeout in milliseconds.
     * Values less than or equal to zero mean no server-side timeout.
     */
    public static SseEmitter createEmitter(long timeoutMillis) {
        return new SseEmitter(Math.max(0L, timeoutMillis));
    }

    /**
     * Creates an SseEmitter with no server-side timeout.
     */
    public static SseEmitter createEmitter() {
        return new SseEmitter(0L);
    }

    /**
     * Guards a reactive subscription tied to an SseEmitter's lifecycle. Ensures the
     * subscription is disposed on every terminal path (completion, timeout, error,
     * client disconnect).
     *
     * <p>Usage:
     * <pre>{@code
     * SseEmitter emitter = SseStreamLifecycle.createEmitter(timeout);
     * SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
     *
     * // Wire up callbacks with domain logic inline:
     * emitter.onCompletion(() -> {
     *     guard.dispose();
     *     // domain cleanup...
     * });
     * emitter.onTimeout(() -> {
     *     guard.dispose();
     *     // domain timeout handling...
     * });
     * emitter.onError(error -> {
     *     guard.dispose();
     *     // domain error handling...
     * });
     *
     * Disposable sub = flux.subscribe(...);
     * guard.set(sub);
     * return emitter;
     * }</pre>
     *
     * <p>For simple cases where only subscription disposal is needed, use
     * {@link #registerCallbacks(SseEmitter, SubscriptionGuard, Runnable, Consumer)}.
     */
    public static SubscriptionGuard guardSubscription() {
        return new SubscriptionGuard();
    }

    /**
     * Convenience method that registers standard lifecycle callbacks on the emitter
     * using the given guard. Completion disposes the guard; timeout disposes and calls
     * {@code onTimeoutHandler}; error disposes and calls {@code onErrorHandler}.
     *
     * <p>Use this when no additional domain logic is needed on completion
     * (no ActiveTurn to complete, no failure to record). For controllers that need
     * domain transitions on terminal paths, set callbacks manually with
     * {@code emitter.onCompletion(guard::dispose)} then add domain cleanup afterward.
     */
    public static void registerCallbacks(
        SseEmitter emitter,
        SubscriptionGuard guard,
        Runnable onTimeoutHandler,
        Consumer<Throwable> onErrorHandler
    ) {
        LifecycleCallbacks callbacks = callbacks(guard, onTimeoutHandler, onErrorHandler);
        emitter.onCompletion(callbacks.onCompletion());
        emitter.onTimeout(callbacks.onTimeout());
        emitter.onError(callbacks.onError());
    }

    /**
     * Builds testable lifecycle callbacks from a guard and optional domain handlers.
     * Package-private so tests can invoke the returned runnables directly.
     */
    static LifecycleCallbacks callbacks(
        SubscriptionGuard guard,
        Runnable onTimeoutHandler,
        Consumer<Throwable> onErrorHandler
    ) {
        return new LifecycleCallbacks(
            guard::dispose,
            () -> {
                guard.dispose();
                if (onTimeoutHandler != null) onTimeoutHandler.run();
            },
            error -> {
                guard.dispose();
                if (onErrorHandler != null) onErrorHandler.accept(error);
            }
        );
    }

    record LifecycleCallbacks(
        Runnable onCompletion,
        Runnable onTimeout,
        Consumer<Throwable> onError
    ) {}

    /**
     * Sends an SSE event with the given name and data. Uses the default media type
     * extracted from the data object.
     */
    public static void sendSseEvent(SseEmitter emitter, String name, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(name).data(data));
    }

    /**
     * Attempts to send an SSE event and reports whether the transport accepted it.
     * A failed send means the client-side stream is already unusable, so callers
     * should treat it as terminal transport cleanup instead of a domain failure.
     */
    public static boolean trySendSseEvent(SseEmitter emitter, String name, Object data) {
        try {
            sendSseEvent(emitter, name, data);
            return true;
        } catch (IllegalStateException | IOException exception) {
            return false;
        }
    }

    public static void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // Ignored: emitter is already completed or response is committed
        }
    }

    /**
     * Sends an SSE event with the given name, data, and explicit media type.
     */
    public static void sendSseEvent(SseEmitter emitter, String name, Object data,
            org.springframework.http.MediaType mediaType) throws Exception {
        emitter.send(SseEmitter.event().name(name).data(data, mediaType));
    }

    /**
     * Tracks a reactive {@link Disposable} subscription and provides safe disposal.
     * Thread-safe.
     */
    public static final class SubscriptionGuard {
        private final AtomicReference<Disposable> subscriptionRef = new AtomicReference<>();

        SubscriptionGuard() {}

        /**
         * Sets the disposable subscription. Any previously-set subscription is disposed
         * first. Thread-safe via AtomicReference.
         */
        public void set(Disposable subscription) {
            Disposable previous = subscriptionRef.getAndSet(subscription);
            if (previous != null && !previous.isDisposed()) {
                previous.dispose();
            }
        }

        /**
         * Disposes the tracked subscription if it exists and is not already disposed.
         * Safe to call multiple times.
         */
        public void dispose() {
            Disposable subscription = subscriptionRef.get();
            if (subscription != null && !subscription.isDisposed()) {
                subscription.dispose();
            }
        }
    }
}
