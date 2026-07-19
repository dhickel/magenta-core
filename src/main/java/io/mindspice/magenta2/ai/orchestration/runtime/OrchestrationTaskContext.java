package io.mindspice.magenta2.ai.orchestration.runtime;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import org.springframework.util.StringUtils;

/**
 * Task execution context set by the orchestration runner and visible to
 * tool implementations (shell, file) during model-backed task execution.
 *
 * <p>Contains agent, job, project identifiers plus a resolved workspace
 * descriptor and active confined path. Tool implementations use this
 * context to resolve working directories.
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
    String hostOutputPath,
    String hostRunPath,
    String hostRootPath,
    String activeRuntimePath
) {
    public static final OrchestrationTaskContext EMPTY = new OrchestrationTaskContext(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    public OrchestrationTaskContext(
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
        String hostOutputPath,
        String hostRunPath,
        String hostRootPath
    ) {
        this(agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, hostOutputPath, hostRunPath, hostRootPath, null);
    }

    public OrchestrationTaskContext(
        String agentId,
        String agentName,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String runType,
        String hostWorkspacePath,
        String hostOutputPath
    ) {
        this(agentId, agentName, jobId, null, null, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, null,
            hostWorkspacePath, hostOutputPath, hostWorkspacePath, hostWorkspacePath);
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
        hostOutputPath = normalize(hostOutputPath);
        hostRunPath = normalize(hostRunPath);
        hostRootPath = normalize(hostRootPath);
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

    public OrchestrationTaskContext withExecutionPaths(
        String hostDurableWorkspacePath,
        String hostOutputPath,
        String hostRunPath
    ) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, hostOutputPath, hostRunPath, hostDurableWorkspacePath, activeRuntimePath
        );
    }

    public OrchestrationTaskContext withExecutionPaths(
        String hostDurableWorkspacePath,
        String hostOutputPath,
        String hostRunPath,
        String hostRootPath
    ) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, hostOutputPath, hostRunPath, hostRootPath, activeRuntimePath
        );
    }

    public OrchestrationTaskContext withJobRun(String jobAssignmentId, String jobRunId) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, hostOutputPath, hostRunPath, hostRootPath, activeRuntimePath
        );
    }

    public OrchestrationTaskContext withActiveRuntimePath(String activeRuntimePath) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, hostOutputPath, hostRunPath, hostRootPath, activeRuntimePath
        );
    }
}
