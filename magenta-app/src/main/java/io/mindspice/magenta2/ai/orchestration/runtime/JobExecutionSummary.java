package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;

/** Stable read model for assignment-owned job execution. */
public record JobExecutionSummary(
    String jobId,
    String jobTitle,
    String jobStatus,
    String assignmentId,
    OrchestrationStatus assignmentStatus,
    AssignmentType assignmentType,
    int assignmentPriority,
    String modelOverride,
    String agentId,
    String agentName,
    String agentStatus,
    String projectId,
    String projectName,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String jobRunId,
    JobRunStatus jobRunStatus,
    List<String> childRunIds,
    int outputCount,
    Instant latestOutputAt,
    Instant queuedAt,
    Instant startedAt,
    Instant completedAt,
    Instant updatedAt
) {
}
