package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.ToolActivityData;

/**
 * Observability metrics for the turn loop engine.
 */
public interface TurnLoopMetrics {

    void recordTurnDuration(@NonNull String model,
                            @NonNull String outcome,
                            long durationMs);

    void recordToolCall(@NonNull ToolActivityData activity);

    void setActiveTurns(long count);

    void setQueueDepth(@NonNull String sessionId, long depth);
}
