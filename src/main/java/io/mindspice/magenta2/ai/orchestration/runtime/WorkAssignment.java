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
    long leaseFence,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    Instant lastProgressAt,
    Instant lastHeartbeatAt
) {
    // Full constructor matching the old canonical shape (27 args without leaseFence,
    // defaults leaseFence to 0).
    public WorkAssignment(
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
        this(
            id, agentId, jobId, jobItemId, assignmentType, runDisplayName, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId, currentItemIndex, checkpoint, input, output,
            evidence, errorText, leaseOwner, leaseExpiresAt, 0L, createdAt, updatedAt, startedAt, completedAt,
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

}
