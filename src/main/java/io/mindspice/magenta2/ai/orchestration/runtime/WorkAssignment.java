package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * Canonical identity for submitted work.
 * <p>
 * Immutable attribution (agent, job, assignment type, source definition,
 * workspace selection, project, context binding) is captured at submission
 * and never rewritten. Runner-owned fields (status, lease, checkpoint,
 * output, evidence, error, timing) use separate fenced write paths.
 * The business {@code rowVersion} is a CAS guard for operators and control
 * services; the lease fence is a runner-owned monotonic counter and lease
 * heartbeat does not advance rowVersion.
 */
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
    // ── Immutable attribution added in Phase 01 ──
    String sourceDefinitionId,
    String contextBindingKey,
    // ── Business CAS version ──
    int rowVersion,
    int currentItemIndex,
    Map<String, Object> checkpoint,
    Map<String, Object> input,
    Map<String, Object> output,
    Map<String, Object> evidence,
    String errorText,
    @com.fasterxml.jackson.annotation.JsonIgnore
    String leaseOwner,
    Instant leaseExpiresAt,
    @com.fasterxml.jackson.annotation.JsonIgnore
    long leaseFence,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt,
    Instant lastProgressAt,
    Instant lastHeartbeatAt
) {

    // ── Construction with all fields including new Phase 01 columns ──

    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType, String runDisplayName, int priority,
        OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        String projectId,
        String sourceDefinitionId, String contextBindingKey, int rowVersion,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt, long leaseFence,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt,
        Instant lastProgressAt, Instant lastHeartbeatAt
    ) {
        this.id = id;
        this.agentId = agentId;
        this.jobId = jobId;
        this.jobItemId = jobItemId;
        this.assignmentType = assignmentType;
        this.runDisplayName = runDisplayName;
        this.priority = priority;
        this.status = status;
        this.modelOverride = modelOverride;
        this.workspaceType = workspaceType;
        this.workspaceKey = workspaceKey;
        this.workspaceIdentity = workspaceIdentity;
        this.projectId = projectId;
        this.sourceDefinitionId = sourceDefinitionId;
        this.contextBindingKey = contextBindingKey;
        this.rowVersion = rowVersion;
        this.currentItemIndex = currentItemIndex;
        this.checkpoint = checkpoint == null ? Map.of() : checkpoint;
        this.input = input == null ? Map.of() : input;
        this.output = output == null ? Map.of() : output;
        this.evidence = evidence == null ? Map.of() : evidence;
        this.errorText = errorText;
        this.leaseOwner = leaseOwner;
        this.leaseExpiresAt = leaseExpiresAt;
        this.leaseFence = leaseFence;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.lastProgressAt = lastProgressAt;
        this.lastHeartbeatAt = lastHeartbeatAt;
    }

    // ── Compatibility constructors used by existing callers. ──
    // Each defaults the new Phase 01 columns (sourceDefinitionId,
    // contextBindingKey, rowVersion) to null/0 so existing compilation
    // units do not break. New submission paths use the full constructor.

    /** Full canonical shape before Phase 01 (28-arg, without new cols). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType, String runDisplayName, int priority,
        OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt, long leaseFence,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt,
        Instant lastProgressAt, Instant lastHeartbeatAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, runDisplayName, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId,
            null, null, 0,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, leaseFence,
            createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt);
    }

    /** 27-arg (no leaseFence). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType, String runDisplayName, int priority,
        OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt,
        Instant lastProgressAt, Instant lastHeartbeatAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, runDisplayName, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, 0L,
            createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt);
    }

    /** 25-arg (no runDisplayName, no projectId, no lastProgressAt, no lastHeartbeatAt). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType,
        int priority, OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt,
        Instant lastProgressAt, Instant lastHeartbeatAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, null,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, 0L,
            createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt);
    }

    /** 25-arg with projectId (no runDisplayName, no lastProgressAt, no lastHeartbeatAt). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType,
        int priority, OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt,
        Instant lastProgressAt, Instant lastHeartbeatAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, 0L,
            createdAt, updatedAt, startedAt, completedAt,
            lastProgressAt, lastHeartbeatAt);
    }

    /** 23-arg (no lastProgressAt, no lastHeartbeatAt). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType,
        int priority, OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, null,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, 0L,
            createdAt, updatedAt, startedAt, completedAt,
            null, null);
    }

    /** 24-arg with projectId (no lastProgressAt, no lastHeartbeatAt). */
    public WorkAssignment(
        String id, String agentId, String jobId, String jobItemId,
        AssignmentType assignmentType,
        int priority, OrchestrationStatus status, String modelOverride,
        String workspaceType, String workspaceKey, String workspaceIdentity,
        String projectId,
        int currentItemIndex,
        Map<String, Object> checkpoint, Map<String, Object> input,
        Map<String, Object> output, Map<String, Object> evidence,
        String errorText,
        String leaseOwner, Instant leaseExpiresAt,
        Instant createdAt, Instant updatedAt,
        Instant startedAt, Instant completedAt
    ) {
        this(id, agentId, jobId, jobItemId, assignmentType, null, priority, status, modelOverride,
            workspaceType, workspaceKey, workspaceIdentity, projectId,
            currentItemIndex, checkpoint, input, output, evidence, errorText,
            leaseOwner, leaseExpiresAt, 0L,
            createdAt, updatedAt, startedAt, completedAt,
            null, null);
    }
}
