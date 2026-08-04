package io.mindspice.magenta2.ai.chat.tool.orchestration;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRun;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentToolAuthorizationService {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
    private final AgentProfileService agentProfileService;
    private final ProjectService projectService;
    private final AssignmentService assignmentService;
    private final JobService jobService;

    public AgentToolAuthorizationService(
        AgentProfileService agentProfileService,
        @Lazy ProjectService projectService,
        @Lazy AssignmentService assignmentService,
        @Lazy JobService jobService
    ) {
        this.agentProfileService = agentProfileService;
        this.projectService = projectService;
        this.assignmentService = assignmentService;
        this.jobService = jobService;
    }

    AgentProfile requireCurrentAgent() {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context == null || !context.hasAgentContext()) {
            throw new IllegalStateException("Operational tools require an active orchestration agent context");
        }
        AgentProfile profile = agentProfileService.get(context.agentId());
        if (profile.status() == AgentProfileStatus.DISABLED) {
            throw new IllegalStateException("Agent is disabled and cannot use operational tools: " + profile.id());
        }
        return profile;
    }

    int boundLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(1, limit), MAX_LIMIT);
    }

    long boundReadBytes(Long maxBytes) {
        if (maxBytes == null || maxBytes <= 0) {
            return 65_536L;
        }
        return Math.min(maxBytes, 1_048_576L);
    }

    void requireConfirmation(String actual, String expected) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException("confirmation text must exactly match: " + expected);
        }
    }

    void requireProjectAccess(String agentId, String projectId) {
        requireText(projectId, "projectId");
        projectService.getProject(projectId);
        if (!projectService.isMember(projectId, agentId)) {
            throw new IllegalArgumentException("Agent is not a member of project: " + projectId);
        }
    }

    WorkAssignment requireAssignmentOwner(String agentId, String assignmentId) {
        requireText(assignmentId, "assignmentId");
        WorkAssignment assignment = assignmentService.get(assignmentId);
        if (!agentId.equals(assignment.agentId())) {
            throw new IllegalArgumentException("Assignment does not belong to current agent: " + assignmentId);
        }
        return assignment;
    }

    JobDefinition requireJobAccess(String agentId, String jobId) {
        requireText(jobId, "jobId");
        JobDefinition job = jobService.getDefinition(jobId);
        if (agentId.equals(job.ownerAgentId())) {
            return job;
        }
        if (StringUtils.hasText(job.projectId()) && projectService.isMember(job.projectId(), agentId)) {
            return job;
        }
        throw new IllegalArgumentException("Job is not visible to current agent: " + jobId);
    }

    JobRun requireJobRunAccess(String agentId, String runId) {
        requireText(runId, "runId");
        JobRun run = jobService.getRun(runId);
        if (StringUtils.hasText(run.jobAssignmentId())) {
            WorkAssignment assignment = assignmentService.get(run.jobAssignmentId());
            if (agentId.equals(assignment.agentId())) {
                return run;
            }
        }
        JobDefinition job = requireJobAccess(agentId, run.jobId());
        if (agentId.equals(job.ownerAgentId())) {
            return run;
        }
        if (StringUtils.hasText(job.projectId()) && projectService.isMember(job.projectId(), agentId)) {
            return run;
        }
        throw new IllegalArgumentException("Job run is not visible to current agent: " + runId);
    }

    void requireArtifactAccess(String agentId, RunOutputArtifact artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact is required");
        }
        if (agentId.equals(artifact.agentId())) {
            return;
        }
        if (StringUtils.hasText(artifact.projectId()) && projectService.isMember(artifact.projectId(), agentId)) {
            return;
        }
        if (StringUtils.hasText(artifact.jobAssignmentId())) {
            requireAssignmentOwner(agentId, artifact.jobAssignmentId());
            return;
        }
        if (StringUtils.hasText(artifact.jobId())) {
            requireJobAccess(agentId, artifact.jobId());
            return;
        }
        throw new IllegalArgumentException("Output artifact is not visible to current agent: " + artifact.id());
    }

    void requireText(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
