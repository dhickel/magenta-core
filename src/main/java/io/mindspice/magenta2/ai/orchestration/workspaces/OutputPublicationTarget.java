package io.mindspice.magenta2.ai.orchestration.workspaces;

import org.springframework.util.StringUtils;

/**
 * Simplified target descriptor for output publication.
 */
public record OutputPublicationTarget(
    String kind,
    String workUnitId,
    String runId,
    String agentId,
    String projectId,
    String jobId,
    String jobAssignmentId,
    String jobRunId
) {
    public static final String KIND_TASK = "TASK";
    public static final String KIND_WORKFLOW = "WORKFLOW";
    public static final String KIND_JOB = "JOB";

    public OutputPublicationTarget {
        if (!StringUtils.hasText(kind)) {
            throw new IllegalArgumentException("kind is required");
        }
        workUnitId = normalize(workUnitId);
        runId = normalize(runId);
        agentId = normalize(agentId);
        projectId = normalize(projectId);
        jobId = normalize(jobId);
        jobAssignmentId = normalize(jobAssignmentId);
        jobRunId = normalize(jobRunId);
    }

    public static OutputPublicationTarget task(
        String taskId,
        String runId,
        String agentId,
        String projectId
    ) {
        return new OutputPublicationTarget(
            KIND_TASK, taskId, runId, agentId, projectId, null, null, null);
    }

    public static OutputPublicationTarget workflow(
        String workflowId,
        String runId,
        String agentId,
        String projectId
    ) {
        return new OutputPublicationTarget(
            KIND_WORKFLOW, workflowId, runId, agentId, projectId, null, null, null);
    }

    public static OutputPublicationTarget job(
        String jobId,
        String jobAssignmentId,
        String jobRunId,
        String agentId,
        String projectId
    ) {
        return new OutputPublicationTarget(
            KIND_JOB, null, null, agentId, projectId, jobId, jobAssignmentId, jobRunId);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
