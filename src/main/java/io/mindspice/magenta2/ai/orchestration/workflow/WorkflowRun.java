package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Runtime state for a workflow v2 execution. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRun(
    String id,
    String workflowId,
    String runDisplayName,
    WorkflowRunStatus status,
    int currentNodeIndex,
    List<WorkflowNodeRun> nodeRuns,
    String agentId,
    String jobId,
    String jobAssignmentId,
    String jobRunId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String runType,
    WorkflowDefinition workflowSnapshot,
    Map<String, Object> finalOutputs,
    List<String> artifactIds,
    String finalMessage,
    String errorText,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt
) {
    public WorkflowRun {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("WorkflowRun id must not be blank");
        if (workflowId == null || workflowId.isBlank()) throw new IllegalArgumentException("WorkflowRun workflowId must not be blank");
        if (status == null) status = WorkflowRunStatus.QUEUED;
        if ((workspaceType == null) != (workspaceKey == null)) {
            throw new IllegalArgumentException("workspaceType and workspaceKey must be supplied together");
        }
        if (workspaceType != null) workspaceIdentity = workspaceType + "/" + workspaceKey;
        currentNodeIndex = Math.max(0, currentNodeIndex);
        nodeRuns = nodeRuns == null ? List.of() : List.copyOf(nodeRuns);
        finalOutputs = finalOutputs == null ? Map.of() : Map.copyOf(finalOutputs);
        artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
    }

    public WorkflowRun(
        String id,
        String workflowId,
        WorkflowRunStatus status,
        int currentNodeIndex,
        List<WorkflowNodeRun> nodeRuns,
        WorkflowDefinition workflowSnapshot,
        Map<String, Object> finalOutputs,
        List<String> artifactIds,
        String finalMessage,
        String errorText,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {
        this(id, workflowId, null, status, currentNodeIndex, nodeRuns,
            null, null, null, null, null, null, null, null, null,
            workflowSnapshot, finalOutputs, artifactIds, finalMessage, errorText,
            createdAt, updatedAt, startedAt, completedAt);
    }

    public WorkflowNodeRun currentNodeRun() {
        return currentNodeIndex < nodeRuns.size() ? nodeRuns.get(currentNodeIndex) : null;
    }

    public boolean isTerminal() {
        return status == WorkflowRunStatus.COMPLETED
            || status == WorkflowRunStatus.FAILED
            || status == WorkflowRunStatus.CANCELLED
            || status == WorkflowRunStatus.NEEDS_REVIEW;
    }
}
