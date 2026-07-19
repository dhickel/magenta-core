package io.mindspice.magenta2.ai.orchestration.workspaces;

import org.springframework.util.StringUtils;

public record OutputArtifactQuery(
    String agentId,
    String jobId,
    String jobAssignmentId,
    String jobRunId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    String runId,
    String planId,
    String runType,
    String artifactType,
    int limit
) {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    public OutputArtifactQuery {
        agentId = normalize(agentId);
        jobId = normalize(jobId);
        jobAssignmentId = normalize(jobAssignmentId);
        jobRunId = normalize(jobRunId);
        projectId = normalize(projectId);
        workspaceType = normalize(workspaceType);
        workspaceKey = normalize(workspaceKey);
        workspaceIdentity = normalize(workspaceIdentity);
        runId = normalize(runId);
        planId = normalize(planId);
        runType = normalize(runType);
        artifactType = normalize(artifactType);
        limit = normalizeLimit(limit);
    }

    public static OutputArtifactQuery of(String agentId,
                                         String jobId,
                                         String projectId,
                                         String workspaceType,
                                         String workspaceKey,
                                         String workspaceIdentity,
                                         String runId,
                                         String planId,
                                         String artifactType,
                                         Integer limit) {
        return of(agentId, jobId, null, null, projectId, workspaceType, workspaceKey, workspaceIdentity, runId, planId, null, artifactType, limit);
    }

    /**
     * @deprecated Use the 10-arg of() with explicit workspaceType/workspaceKey/workspaceIdentity.
     */
    @Deprecated
    public static OutputArtifactQuery of(String agentId,
                                         String jobId,
                                         String projectId,
                                         String workspaceId,
                                         String runId,
                                         String planId,
                                         String artifactType,
                                         Integer limit) {
        return of(agentId, jobId, null, null, projectId,
            null, workspaceId, workspaceId, runId, planId, null, artifactType, limit);
    }

    /**
     * @deprecated Use the 13-arg of() with explicit workspaceType/workspaceKey/workspaceIdentity.
     */
    @Deprecated
    public static OutputArtifactQuery of(String agentId,
                                         String jobId,
                                         String jobAssignmentId,
                                         String jobRunId,
                                         String projectId,
                                         String workspaceId,
                                         String runId,
                                         String planId,
                                         String runType,
                                         String artifactType,
                                         Integer limit) {
        return of(agentId, jobId, jobAssignmentId, jobRunId, projectId,
            null, workspaceId, workspaceId, runId, planId, runType, artifactType, limit);
    }

    public static OutputArtifactQuery of(String agentId,
                                         String jobId,
                                         String jobAssignmentId,
                                         String jobRunId,
                                         String projectId,
                                         String workspaceType,
                                         String workspaceKey,
                                         String workspaceIdentity,
                                         String runId,
                                         String planId,
                                         String runType,
                                         String artifactType,
                                         Integer limit) {
        return new OutputArtifactQuery(agentId, jobId, jobAssignmentId, jobRunId, projectId,
            workspaceType, workspaceKey, workspaceIdentity,
            runId, planId, runType, artifactType,
            limit == null ? DEFAULT_LIMIT : limit);
    }

    private static int normalizeLimit(int value) {
        if (value <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(value, MAX_LIMIT);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
