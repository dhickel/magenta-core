package io.mindspice.magenta2.ai.orchestration.runtime;

import org.springframework.util.StringUtils;

public record OrchestrationRunContext(
    String agentId,
    String jobId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    String modelOverride,
    Integer priority
) {
    public OrchestrationRunContext(
        String agentId,
        String jobId,
        String projectId,
        String modelOverride,
        Integer priority
    ) {
        this(agentId, jobId, projectId, null, null, modelOverride, priority);
    }

    public OrchestrationRunContext {
        agentId = normalize(agentId);
        jobId = normalize(jobId);
        projectId = normalize(projectId);
        workspaceType = normalize(workspaceType);
        workspaceKey = normalize(workspaceKey);
        modelOverride = normalize(modelOverride);
    }

    public boolean hasContext() {
        return StringUtils.hasText(agentId) || StringUtils.hasText(jobId) || StringUtils.hasText(projectId)
            || StringUtils.hasText(workspaceType) || StringUtils.hasText(modelOverride) || priority != null;
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
