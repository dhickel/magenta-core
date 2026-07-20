package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
    String operationKey
) {
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
            projectId, workspaceType, workspaceKey, input, null);
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
        this(agentId, jobId, jobItemId, assignmentType, null, priority, modelOverride, projectId, null, null, input, null);
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
        this(agentId, jobId, jobItemId, assignmentType, null, priority, modelOverride, null, null, null, input, null);
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
            workspaceType, workspaceKey, input, null);
    }
}
