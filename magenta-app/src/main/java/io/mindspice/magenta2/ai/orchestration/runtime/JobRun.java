package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A single execution attempt for a {@link JobDefinition}. */
public record JobRun(
    String id,
    String jobId,
    String jobAssignmentId,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    JobRunStatus status,
    List<JobWorkItemRun> workItemRuns,
    String finalMessage,
    String errorText,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt
) {
    public JobRun {
        if ((workspaceType == null) != (workspaceKey == null)) {
            throw new IllegalArgumentException("workspaceType and workspaceKey must be supplied together");
        }
        if (workspaceType != null) {
            workspaceIdentity = workspaceType + "/" + workspaceKey;
        }
        workItemRuns = workItemRuns == null ? List.of() : List.copyOf(workItemRuns);
    }

    public boolean isTerminal() {
        return status != null && status.isTerminal();
    }

    public double progress() {
        if (workItemRuns.isEmpty()) return 0.0;
        long completed = workItemRuns.stream()
            .filter(wi -> "COMPLETED".equals(wi.status()) || "FAILED".equals(wi.status()))
            .count();
        return (double) completed / workItemRuns.size();
    }
}

record JobWorkItemRun(
    String key,
    String type,
    String planId,
    String workflowId,
    String status,
    String runId,
    Map<String, Object> inputValues,
    Map<String, Object> outputValues,
    String errorText,
    Instant startedAt,
    Instant completedAt
) {
    public JobWorkItemRun {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key is required");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type is required");
    }
}
