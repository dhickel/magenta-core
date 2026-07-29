package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

public record AgentEventReaction(
    String id,
    String agentId,
    @NotNull EventType eventType,
    Map<String, Object> filter,
    @NotNull ReactionActionType actionType,
    Map<String, Object> assignmentTemplate,
    boolean enabled,
    String projectId,
    Instant createdAt,
    Instant updatedAt
) {
    /** Backward-compatible constructor without projectId. */
    public AgentEventReaction(
        String id, String agentId, EventType eventType,
        Map<String, Object> filter, ReactionActionType actionType,
        Map<String, Object> assignmentTemplate, boolean enabled,
        Instant createdAt, Instant updatedAt
    ) {
        this(id, agentId, eventType, filter, actionType, assignmentTemplate,
            enabled, null, createdAt, updatedAt);
    }
}
