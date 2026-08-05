package io.mindspice.core.autoconfigure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.mindspice.core.spi.TurnLoopMetrics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer-based implementation of the {@link TurnLoopMetrics} SPI.
 *
 * <p>Registers four core metrics on construction:
 * <ul>
 *   <li>{@code turnloop.turn.duration} — {@link Timer} with percentile
 *       histogram, tagged {@code model} and {@code outcome}</li>
 *   <li>{@code turnloop.tool.calls} — {@link Counter}, tagged
 *       {@code tool_name} and {@code status}</li>
 *   <li>{@code turnloop.turns.active} — {@link Gauge} backed by an
 *       {@link AtomicLong}</li>
 *   <li>{@code turnloop.queue.depth} — per-session {@link Gauge}s
 *       registered dynamically via a {@link ConcurrentHashMap}</li>
 * </ul>
 *
 * <p>This bean is only created when {@code MeterRegistry} is on the
 * classpath (guarded by {@code @ConditionalOnClass} in
 * {@link TurnLoopAutoConfiguration}).</p>
 */
public class MicrometerTurnLoopMetrics implements TurnLoopMetrics {

    private final MeterRegistry registry;
    private final AtomicLong activeTurns = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> queueDepths = new ConcurrentHashMap<>();

    public MicrometerTurnLoopMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("turnloop.turns.active", activeTurns, AtomicLong::get)
            .description("Currently active turns")
            .register(registry);
    }

    @Override
    public void recordTurnDuration(String model, String outcome, long durationMs) {
        Timer.builder("turnloop.turn.duration")
            .description("Turn execution duration")
            .tags("model", model, "outcome", outcome)
            .publishPercentileHistogram()
            .register(registry)
            .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordToolCall(String toolName, String status) {
        Counter.builder("turnloop.tool.calls")
            .description("Tool call count")
            .tags("tool_name", toolName, "status", status)
            .register(registry)
            .increment();
    }

    @Override
    public void setActiveTurns(long count) {
        activeTurns.set(count);
    }

    @Override
    public void setQueueDepth(String sessionId, long depth) {
        queueDepths.computeIfAbsent(sessionId, k -> {
            var gauge = new AtomicLong();
            Gauge.builder("turnloop.queue.depth", gauge, AtomicLong::get)
                .description("Queue depth per session")
                .tags("session_id", sessionId)
                .register(registry);
            return gauge;
        }).set(depth);
    }
}
