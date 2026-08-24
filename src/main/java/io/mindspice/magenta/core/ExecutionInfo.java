package io.mindspice.magenta.core;

import java.time.Instant;
import java.util.List;

/**
 * Immutable facts shared by outcomes produced by a model execution.
 *
 * @param sessionId       owning session
 * @param turnId          execution turn identifier
 * @param origin          typed source of the execution
 * @param startedAt       execution start time
 * @param completedAt     execution completion time
 * @param finalContextUsage context usage at completion
 * @param toolActivities  all tool activity summaries accumulated by the turn
 */
public record ExecutionInfo(
    @NonNull String sessionId,
    @NonNull String turnId,
    @NonNull ExecutionOrigin origin,
    @NonNull Instant startedAt,
    @NonNull Instant completedAt,
    @NonNull ContextUsage finalContextUsage,
    @NonNull List<ToolActivityData> toolActivities
) {
    public ExecutionInfo {
        sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
        turnId = ResponseValidation.nonBlank(turnId, "turnId");
        origin = ResponseValidation.required(origin, "origin");
        startedAt = ResponseValidation.timestamp(startedAt, "startedAt");
        completedAt = ResponseValidation.timestamp(completedAt, "completedAt");
        ResponseValidation.ordered(startedAt, completedAt, "completedAt");
        finalContextUsage = ResponseValidation.required(finalContextUsage,
            "finalContextUsage");
        toolActivities = ResponseValidation.required(toolActivities, "toolActivities");
        toolActivities = List.copyOf(toolActivities);
    }
}
