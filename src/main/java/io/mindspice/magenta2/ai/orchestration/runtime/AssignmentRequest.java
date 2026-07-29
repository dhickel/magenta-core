package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Submission payload for an assignment. Immutable attribution fields
 * (agent, source, project, workspace, context) are captured at submission
 * and never rewritten. Contradictory {@code input.projectId} vs the
 * top-level {@code projectId} is rejected.
 */
public record AssignmentRequest(
    @NotBlank String agentId,
    String jobId,
    String jobItemId,
    @NotNull AssignmentType assignmentType,
    String runDisplayName,
    Integer priority,
    String modelOverride,
    String projectId,
    String workspaceType,
    String workspaceKey,
    Map<String, Object> input,
    String operationKey,
    // ── Phase 01: typed source and context binding ──
    String sourceDefinitionId,
    String contextBindingKey
) {
    /** 12-arg: all fields except sourceDefinitionId and contextBindingKey. */
    public AssignmentRequest(
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        String runDisplayName,
        Integer priority,
        String modelOverride,
        String projectId,
        String workspaceType,
        String workspaceKey,
        Map<String, Object> input,
        String operationKey
    ) {
        this(agentId, jobId, jobItemId, assignmentType, runDisplayName, priority, modelOverride,
            projectId, workspaceType, workspaceKey, input, operationKey, null, null);
    }

    public AssignmentRequest(
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        String runDisplayName,
        Integer priority,
        String modelOverride,
        String projectId,
        String workspaceType,
        String workspaceKey,
        Map<String, Object> input
    ) {
        this(agentId, jobId, jobItemId, assignmentType, runDisplayName, priority, modelOverride,
            projectId, workspaceType, workspaceKey, input, null, null, null);
    }
    public AssignmentRequest(
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        Integer priority,
        String modelOverride,
        String projectId,
        Map<String, Object> input
    ) {
        this(agentId, jobId, jobItemId, assignmentType, null, priority, modelOverride, projectId, null, null, input, null, null, null);
    }

    public AssignmentRequest(
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        Integer priority,
        String modelOverride,
        Map<String, Object> input
    ) {
        this(agentId, jobId, jobItemId, assignmentType, null, priority, modelOverride, null, null, null, input, null, null, null);
    }

    public AssignmentRequest(
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        Integer priority,
        String modelOverride,
        String projectId,
        String workspaceType,
        String workspaceKey,
        Map<String, Object> input
    ) {
        this(agentId, jobId, jobItemId, assignmentType, null, priority, modelOverride, projectId,
            workspaceType, workspaceKey, input, null, null, null);
    }
}
