package io.mindspice.core.spi;

import io.mindspice.core.NonNull;

/**
 * Observability metrics for the turn loop engine.
 */
public interface TurnLoopMetrics {

    void recordTurnDuration(@NonNull String model,
                            @NonNull String outcome,
                            long durationMs);

    void recordToolCall(@NonNull String toolName, @NonNull String status);

    void setActiveTurns(long count);

    void setQueueDepth(@NonNull String sessionId, long depth);
}
