package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

public record OrchestrationEvent(
    String id,
    EventType eventType,
    String sourceType,
    String sourceId,
    String projectId,
    Map<String, Object> payload,
    Instant createdAt,
    Instant handledAt
) {
    /** Backward-compatible constructor without projectId. */
    public OrchestrationEvent(
            String id, EventType eventType, String sourceType, String sourceId,
            Map<String, Object> payload, Instant createdAt, Instant handledAt) {
        this(id, eventType, sourceType, sourceId, null, payload, createdAt, handledAt);
    }
}
