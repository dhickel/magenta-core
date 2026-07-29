package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record AgentSchedule(
    String id,
    String agentId,
    String jobId,
    Map<String, Object> assignmentTemplate,
    ScheduleMode mode,
    @NotBlank String cronExpression,
    String timezone,
    Instant fireAt,
    boolean enabled,
    String projectId,
    Instant nextRunAt,
    Instant createdAt,
    Instant updatedAt
) {
    /** Backward-compatible constructor without project/mode fields. */
    public AgentSchedule(
        String id, String agentId, String jobId,
        Map<String, Object> assignmentTemplate,
        String cronExpression, String timezone,
        boolean enabled, Instant nextRunAt,
        Instant createdAt, Instant updatedAt
    ) {
        this(id, agentId, jobId, assignmentTemplate, ScheduleMode.CRON,
            cronExpression, timezone, null, enabled, null,
            nextRunAt, createdAt, updatedAt);
    }
}
