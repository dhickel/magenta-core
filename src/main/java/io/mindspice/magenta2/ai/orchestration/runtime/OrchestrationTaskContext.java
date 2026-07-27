package io.mindspice.magenta2.ai.orchestration.runtime;

import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunWriteCapability;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import org.springframework.util.StringUtils;

/**
 * Task execution context set by the orchestration runner and visible to
 * tool implementations (shell, file) during model-backed task execution.
 *
 * <p>Contains agent, job, project identifiers plus one trusted descriptor root
 * and one direct workspace-relative active path.
 *
 * <p>The {@code taskRunLocation}, {@code runOutputLocation}, {@code attemptId},
 * and {@code writerCapability} fields were added in Phase 02 to carry trusted
 * lifecycle-provisioned locations and a write fence capability. Tools that
 * perform filesystem mutation must verify {@code writerCapability} before
 * writing. The legacy {@code hostDurableWorkspacePath} is now derived from
 * {@code taskRunLocation} when available.
 *
 * <p>The {@code contextBindingKey} field was added in Phase 03 to carry the
 * immutable context binding key through orchestration contexts.
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
    String activeRuntimePath,
    TaskRunLocation taskRunLocation,
    RunOutputLocation runOutputLocation,
    String attemptId,
    TaskRunWriteCapability writerCapability,
    WorkspaceDescriptor workspaceDescriptor,
    String contextBindingKey
) {
    public static final OrchestrationTaskContext EMPTY = new OrchestrationTaskContext(
        null, null, null, null, null, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);

    /** Compatibility constructor for contexts that predate trusted descriptor carriage. */
    public OrchestrationTaskContext(
        String agentId, String agentName, String jobId, String jobAssignmentId, String jobRunId,
        String projectId, String workspaceType, String workspaceKey, String workspaceIdentity,
        String runType, String runDisplayName, String hostDurableWorkspacePath, String activeRuntimePath,
        TaskRunLocation taskRunLocation, RunOutputLocation runOutputLocation, String attemptId,
        TaskRunWriteCapability writerCapability
    ) {
        this(agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId, workspaceType,
            workspaceKey, workspaceIdentity, runType, runDisplayName, hostDurableWorkspacePath,
            activeRuntimePath, taskRunLocation, runOutputLocation, attemptId, writerCapability, null, null);
    }

    /** Compatibility constructor for contexts that predate context bindings. */
    public OrchestrationTaskContext(
        String agentId, String agentName, String jobId, String jobAssignmentId, String jobRunId,
        String projectId, String workspaceType, String workspaceKey, String workspaceIdentity,
        String runType, String runDisplayName, String hostDurableWorkspacePath, String activeRuntimePath,
        TaskRunLocation taskRunLocation, RunOutputLocation runOutputLocation, String attemptId,
        TaskRunWriteCapability writerCapability, WorkspaceDescriptor workspaceDescriptor
    ) {
        this(agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId, workspaceType,
            workspaceKey, workspaceIdentity, runType, runDisplayName, hostDurableWorkspacePath,
            activeRuntimePath, taskRunLocation, runOutputLocation, attemptId, writerCapability,
            workspaceDescriptor, null);
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
        attemptId = normalize(attemptId);
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

    /** Derive the active execution root from trusted values. */
    public String resolvedHostPath() {
        if (taskRunLocation != null) return taskRunLocation.confinedPath().toString();
        if (workspaceDescriptor != null) return workspaceDescriptor.root().toString();
        return hostDurableWorkspacePath;
    }

    public String taskRunId() {
        return taskRunLocation == null ? null : taskRunLocation.taskRunId();
    }

    public int attemptNumber() {
        return writerCapability == null ? 0 : writerCapability.attemptNumber();
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public OrchestrationTaskContext withJobRun(String jobAssignmentId, String jobRunId) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, activeRuntimePath,
            taskRunLocation, runOutputLocation, attemptId, writerCapability, workspaceDescriptor, contextBindingKey
        );
    }

    public OrchestrationTaskContext withActiveRuntimePath(String activeRuntimePath) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, activeRuntimePath,
            taskRunLocation, runOutputLocation, attemptId, writerCapability, workspaceDescriptor, contextBindingKey
        );
    }

    public OrchestrationTaskContext withLifecycle(TaskRunLocation location, RunOutputLocation outputLocation,
                                                   String attempt, TaskRunWriteCapability capability) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            location != null ? location.confinedPath().toString() : hostDurableWorkspacePath,
            activeRuntimePath,
            location, outputLocation, attempt, capability, workspaceDescriptor, contextBindingKey
        );
    }

    public OrchestrationTaskContext withContextBindingKey(String contextBindingKey) {
        return new OrchestrationTaskContext(
            agentId, agentName, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity, runType, runDisplayName,
            hostDurableWorkspacePath, activeRuntimePath,
            taskRunLocation, runOutputLocation, attemptId, writerCapability, workspaceDescriptor, contextBindingKey
        );
    }
}
