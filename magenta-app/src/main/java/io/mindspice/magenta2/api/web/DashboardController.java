package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DashboardController {
    private final ProjectService projectService;
    private final JobService jobService;
    private final AgentProfileService agentProfileService;
    private final InboxService inboxService;
    private final OutputArtifactService outputArtifactService;
    private final AssignmentService assignmentService;

    public DashboardController(ProjectService projectService,
                               JobService jobService,
                               AgentProfileService agentProfileService,
                               InboxService inboxService,
                               OutputArtifactService outputArtifactService) {
        this(projectService, jobService, agentProfileService, inboxService, outputArtifactService, null);
    }

    @Autowired
    public DashboardController(ProjectService projectService,
                               JobService jobService,
                               AgentProfileService agentProfileService,
                               InboxService inboxService,
                               OutputArtifactService outputArtifactService,
                               @Autowired(required = false) AssignmentService assignmentService) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.agentProfileService = agentProfileService;
        this.inboxService = inboxService;
        this.outputArtifactService = outputArtifactService;
        this.assignmentService = assignmentService;
    }

    @GetMapping("/api/dashboard/summary")
    public DashboardSummary summary() {
        List<ProjectSummary> projects = projectService.listProjects().stream()
            .map(project -> new ProjectSummary(project.id(), project.name(), project.ownerAgentId(), project.updatedAt()))
            .toList();
        List<JobDefinition> jobs = jobService.listDefinitions();
        List<WorkSummary> activeWork = new ArrayList<>(jobs.stream()
            .filter(job -> job.status() != null && !"COMPLETED".equals(job.status()) && !"CANCELLED".equals(job.status()))
            .map(job -> new WorkSummary(job.id(), "JOB", job.title(), job.status(), job.ownerAgentId(), job.projectId()))
            .toList());
        List<WorkAssignment> activeAssignments = activeAssignments();
        activeWork.addAll(activeAssignments.stream()
            .map(assignment -> new WorkSummary(
                assignment.id(),
                assignment.assignmentType() == null ? "ASSIGNMENT" : assignment.assignmentType().name(),
                assignment.id(),
                assignment.status() == null ? "UNKNOWN" : assignment.status().name(),
                assignment.agentId(),
                assignment.projectId()))
            .toList());
        List<AgentSummary> agents = agentProfileService.list().stream()
            .map(agent -> new AgentSummary(agent.id(), agent.name(),
                agent.status() == null ? "UNKNOWN" : agent.status().name(), agent.defaultModel()))
            .toList();
        long waitingApprovals = inboxService.userInbox().stream()
            .filter(message -> message.respondedAt() == null)
            .count();
        Map<String, Long> jobsByStatus = jobs.stream()
            .collect(Collectors.groupingBy(job -> job.status() == null ? "DRAFT" : job.status(), Collectors.counting()));
        Map<String, Long> agentsByStatus = agents.stream()
            .collect(Collectors.groupingBy(AgentSummary::status, Collectors.counting()));
        return new DashboardSummary(
            projects,
            activeWork,
            agents,
            new InboxSummary(waitingApprovals),
            outputArtifactService.query(null, null, null, 10).stream()
                .map(output -> new OutputSummary(output.id(), output.runId(), output.planId(), output.outputName(), output.artifactType(), output.createdAt()))
                .toList(),
            new SystemStats(
                jobsByStatus.getOrDefault(JobRunStatus.RUNNING.name(), 0L),
                jobsByStatus.getOrDefault(JobRunStatus.QUEUED.name(), 0L),
                0,
                activeAssignments.size(),
                waitingApprovals,
                jobsByStatus.getOrDefault(OrchestrationStatus.FAILED.name(), 0L),
                agentsByStatus
            ),
            Instant.now()
        );
    }

    private List<WorkAssignment> activeAssignments() {
        if (assignmentService == null) {
            return List.of();
        }
        return agentProfileService.list().stream()
            .flatMap(agent -> assignmentService.queueAssignments(agent.id()).stream())
            .filter(assignment -> assignment.status() == null || !assignment.status().isTerminal())
            .toList();
    }

    public record DashboardSummary(
        List<ProjectSummary> openProjects,
        List<WorkSummary> activeWork,
        List<AgentSummary> agents,
        InboxSummary userInbox,
        List<OutputSummary> recentOutputs,
        SystemStats stats,
        Instant generatedAt
    ) {}

    public record ProjectSummary(String id, String name, String ownerAgentId, Instant updatedAt) {}

    public record WorkSummary(String id, String type, String title, String status, String ownerAgentId, String projectId) {}

    public record AgentSummary(String id, String name, String status, String defaultModel) {}

    public record InboxSummary(long waitingApprovals) {}

    public record OutputSummary(String id, String runId, String planId, String outputName, String artifactType, Instant createdAt) {}

    public record SystemStats(
        long runningJobs,
        long pendingJobs,
        long runningWorkflows,
        long pendingAssignments,
        long waitingApprovals,
        long failedItems,
        Map<String, Long> agentsByStatus
    ) {}
}
