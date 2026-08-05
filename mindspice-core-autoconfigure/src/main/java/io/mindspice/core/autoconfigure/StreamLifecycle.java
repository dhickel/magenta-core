package io.mindspice.core.autoconfigure;

import io.mindspice.core.TurnEvent;
import io.mindspice.core.TurnEventRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Adapts a reactive {@link Flux}{@code <TurnEvent>} stream into a Spring
 * {@link SseEmitter} suitable for direct return from an SSE controller
 * endpoint.
 *
 * <h3>Backpressure</h3>
 * An {@code onBackpressureBuffer} of configurable capacity (default 256)
 * is placed before the SSE mapping. When the buffer overflows the oldest
 * events are dropped and a warning is logged.
 *
 * <h3>Client disconnect</h3>
 * The returned {@link SseEmitter} registers {@code onCompletion},
 * {@code onError}, and {@code onTimeout} callbacks that propagate
 * termination signals to the upstream {@link Flux} subscription.
 *
 * <h3>Error recovery</h3>
 * If the upstream {@link Flux} errors, a terminal
 * {@link TurnEvent.Failed} event is emitted before the emitter completes.
 *
 * <p>This class is a simple Spring bean. It requires {@code SseEmitter}
 * (from {@code spring-boot-starter-web}) on the classpath at call time.</p>
 */
public class StreamLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StreamLifecycle.class);
    private static final Duration SSE_TIMEOUT = Duration.ofMillis(Long.MAX_VALUE);

    /**
     * Wraps a {@link Flux}{@code <TurnEvent>} in an {@link SseEmitter}.
     *
     * @param events         the turn event stream
     * @param bufferCapacity maximum number of events to buffer before
     *                       dropping oldest
     * @return a long-lived SSE emitter ready for the response
     */
    public SseEmitter toSseEmitter(Flux<TurnEvent> events, long bufferCapacity) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT.toMillis());
        int capacity = (int) Math.min(bufferCapacity, Integer.MAX_VALUE);

        events
            .onBackpressureBuffer(capacity, discarded -> log.warn("Stream buffer overflow for turn {}",
                    discarded.turnId()), BufferOverflowStrategy.DROP_OLDEST)
            .map(event -> SseEmitter.event()
                .name(event.getClass().getSimpleName())
                .data(TurnEventRenderer.sse().apply(event)))
            .doOnComplete(emitter::complete)
            .doOnError(emitter::completeWithError)
            .onErrorResume(throwable -> {
                log.error("Turn event stream error — emitting Failed event", throwable);
                var failed = new TurnEvent.Failed(
                    "unknown", Instant.now(),
                    new io.mindspice.core.TurnDiagnostic(
                        "unknown", "unknown",
                        Instant.now(), Instant.now(),
                        io.mindspice.core.TurnPhase.DONE,
                        io.mindspice.core.TurnDiagnostic.OutcomeKind.FAILED,
                        java.util.Map.of("error", throwable.getMessage() != null
                            ? throwable.getMessage() : "stream error")),
                    null);
                return Flux.just(SseEmitter.event()
                    .name("Failed")
                    .data(TurnEventRenderer.sse().apply(failed)));
            })
            .subscribe(
                sseEvent -> {
                    try {
                        emitter.send(sseEvent);
                    } catch (IOException e) {
                        // Client disconnected — this is normal
                        log.debug("SSE client disconnected");
                    }
                },
                error -> {
                    log.warn("SSE flux subscription error — completing emitter", error);
                    emitter.completeWithError(error);
                },
                emitter::complete
            );

        emitter.onCompletion(() -> log.debug("SSE emitter completed (client close)"));
        emitter.onError(throwable -> log.debug("SSE emitter error: {}", throwable.getMessage()));
        emitter.onTimeout(emitter::complete);

        return emitter;
    }
}
