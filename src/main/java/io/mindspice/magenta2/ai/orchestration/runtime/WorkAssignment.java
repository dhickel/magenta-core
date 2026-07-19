package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

public record WorkAssignment(
    String id,
    String agentId,
    String jobId,
    String jobItemId,
    AssignmentType assignmentType,
    String runDisplayName,
    int priority,
    OrchestrationStatus status,
    String modelOverride,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String projectId,
    int currentItemIndex,
    Map<String, Object> checkpoint,
    Map<String, Object> input,
    Map<String, Object> output,
    Map<String, Object> evidence,
    String errorText,
    String leaseOwner,
    Instant leaseExpiresAt,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    Instant lastProgressAt,
    Instant lastHeartbeatAt
) {
    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        Instant lastProgressAt,
        Instant lastHeartbeatAt
    ) {
        this(
            id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, null, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt
        );
    }

    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt,
        Instant lastProgressAt,
        Instant lastHeartbeatAt
    ) {
        this(
            id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt
        );
    }

    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {
        this(
            id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, null, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            null, null
        );
    }

    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {
        this(
            id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            null, null
        );
    }

    /**
     * Backward-compatible constructor accepting a single workspaceId in place of
     * workspaceType/workspaceKey/workspaceIdentity. Maps workspaceId to both
     * workspaceKey and workspaceIdentity.
     * @deprecated Use the constructors with explicit workspaceType/workspaceKey/workspaceIdentity.
     */
    @Deprecated
    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceId,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            null, workspaceId, workspaceId, projectId, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            null, null);
    }

    /**
     * Backward-compatible constructor without completedAt.
     * @deprecated Use the constructors with explicit workspaceType/workspaceKey/workspaceIdentity.
     */
    @Deprecated
    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceId,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            null, workspaceId, workspaceId, projectId, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, null,
            null, null);
    }

    /**
     * Backward-compatible constructor with currentItemIndex directly after workspaceId (no projectId).
     * @deprecated Use the constructors with explicit workspaceType/workspaceKey/workspaceIdentity.
     */
    @Deprecated
    public WorkAssignment(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String modelOverride,
        String workspaceId,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> input,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            null, workspaceId, workspaceId, null, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, createdAt, updatedAt, startedAt, completedAt,
            null, null);
    }
}
