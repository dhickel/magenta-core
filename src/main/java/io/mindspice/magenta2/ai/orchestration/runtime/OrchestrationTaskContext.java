package io.mindspice.magenta2.ai.orchestration.runtime;

import org.springframework.util.StringUtils;

/**
 * Task execution context set by the orchestration runner and visible to
 * tool implementations (shell, file) during model-backed task execution.
 *
 * <p>Contains agent, job, project identifiers plus one trusted descriptor root
 * and one direct workspace-relative active path.
 */
public record OrchestrationTaskContext(
    String agentId,
    String agentName,
    String jobId,
    String jobAssignmentId,
    String jobRunId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String runType,
    String runDisplayName,
    String hostDurableWorkspacePath,
    String activeRuntimePath
) {
    public static final OrchestrationTaskContext EMPTY = new OrchestrationTaskContext(
        null, null, null, null, null, null, null, null, null, null, null, null, null);

    /**
     * Convenience constructor for agent-scoped execution without an active runtime path.
     */
    public OrchestrationTaskContext(
        String agentId,
        String agentName,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String runType,
        String hostWorkspacePath
    ) {
        this(agentId, agentName, jobId, null, null, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, null,
            hostWorkspacePath, null);
    }

    public OrchestrationTaskContext {
        agentId = normalize(agentId);
        agentName = normalize(agentName);
        jobId = normalize(jobId);
        jobAssignmentId = normalize(jobAssignmentId);
        jobRunId = normalize(jobRunId);
        projectId = normalize(projectId);
        workspaceType = normalize(workspaceType);
        workspaceKey = normalize(workspaceKey);
        workspaceIdentity = normalize(workspaceIdentity);
        runType = normalize(runType);
        runDisplayName = normalize(runDisplayName);
        hostDurableWorkspacePath = normalize(hostDurableWorkspacePath);
        activeRuntimePath = normalize(activeRuntimePath);
    }

    public boolean hasAgentContext() {
        return StringUtils.hasText(agentId);
    }

    public boolean hasContext() {
        return StringUtils.hasText(agentId) || StringUtils.hasText(jobId)
            || StringUtils.hasText(jobAssignmentId)
            || StringUtils.hasText(jobRunId)
            || StringUtils.hasText(projectId)
            || StringUtils.hasText(workspaceType);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public OrchestrationTaskContext withJobRun(String jobAssignmentId, String jobRunId) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, activeRuntimePath
        );
    }

    public OrchestrationTaskContext withActiveRuntimePath(String activeRuntimePath) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, activeRuntimePath
        );
    }
}
