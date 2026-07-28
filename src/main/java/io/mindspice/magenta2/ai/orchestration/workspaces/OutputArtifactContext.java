package io.mindspice.magenta2.ai.orchestration.workspaces;

import org.springframework.util.StringUtils;

public record OutputArtifactContext(
    String agentId,
    String jobId,
    String jobAssignmentId,
    String jobRunId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String runType
) {
    public static final OutputArtifactContext EMPTY = new OutputArtifactContext(null, null, null, null, null, null, null, null, null);

    public OutputArtifactContext(
        String agentId,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String runType
    ) {
        this(agentId, jobId, null, null, projectId, workspaceType, workspaceKey, workspaceIdentity, runType);
    }

    public OutputArtifactContext {
        agentId = normalize(agentId);
        jobId = normalize(jobId);
        jobAssignmentId = normalize(jobAssignmentId);
        jobRunId = normalize(jobRunId);
        projectId = normalize(projectId);
        workspaceType = normalize(workspaceType);
        workspaceKey = normalize(workspaceKey);
        workspaceIdentity = normalize(workspaceIdentity);
        runType = normalize(runType);
    }

    public boolean isEmpty() {
        return !StringUtils.hasText(agentId)
            && !StringUtils.hasText(jobId)
            && !StringUtils.hasText(jobAssignmentId)
            && !StringUtils.hasText(jobRunId)
            && !StringUtils.hasText(projectId)
            && !StringUtils.hasText(workspaceType)
            && !StringUtils.hasText(workspaceKey)
            && !StringUtils.hasText(workspaceIdentity)
            && !StringUtils.hasText(runType);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
