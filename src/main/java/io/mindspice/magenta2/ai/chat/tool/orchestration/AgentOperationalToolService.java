package io.mindspice.magenta2.ai.chat.tool.orchestration;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.AssignmentItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.AuditEventItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.DiagnosticsItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.InboxItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.JobItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.JobRunItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.LinkedRunItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.OutputContentItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.OutputItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.PagedListResult;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ProjectEventItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ProjectItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ProjectMemberItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ProjectWorkspaceItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ScheduleItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ToolResult;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.TranscriptItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.WorkspaceLinkItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.WorkspaceStatusItem;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentSchedule;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxService;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRun;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatusService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactQuery;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.Workspace;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLink;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLease;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentOperationalToolService {
    private final AgentToolAuthorizationService authorization;
    private final AgentProfileService agentProfileService;
    private final AssignmentService assignmentService;
    private final InboxService inboxService;
    private final ScheduleService scheduleService;
    private final JobService jobService;
    private final ProjectService projectService;
    private final WorkspaceService workspaceService;
    private final AgentWorkspaceStatusService workspaceStatusService;
    private final OutputArtifactService outputArtifactService;
    private final boolean schedulesEnabled;

    public AgentOperationalToolService(
        AgentToolAuthorizationService authorization,
        AgentProfileService agentProfileService,
        @Lazy AssignmentService assignmentService,
        @Lazy InboxService inboxService,
        @Lazy ScheduleService scheduleService,
        @Lazy JobService jobService,
        @Lazy ProjectService projectService,
        @Lazy WorkspaceService workspaceService,
        @Lazy AgentWorkspaceStatusService workspaceStatusService,
        @Lazy OutputArtifactService outputArtifactService,
        @Value("${magenta.features.schedules-enabled:false}") boolean schedulesEnabled
    ) {
        this.authorization = authorization;
        this.agentProfileService = agentProfileService;
        this.assignmentService = assignmentService;
        this.inboxService = inboxService;
        this.scheduleService = scheduleService;
        this.jobService = jobService;
        this.projectService = projectService;
        this.workspaceService = workspaceService;
        this.workspaceStatusService = workspaceStatusService;
        this.outputArtifactService = outputArtifactService;
        this.schedulesEnabled = schedulesEnabled;
    }

    public ToolResult agentWorkspaceStatus() {
        AgentProfile agent = authorization.requireCurrentAgent();
        return ok("workspace status", statusItem(workspaceStatusService.statusFor(agent.id())));
    }

    public ToolResult agentWorkspaceLinks() {
        AgentProfile agent = authorization.requireCurrentAgent();
        Workspace workspace = workspaceService.agentWorkspace(agent.id(), agent.name());
        return ok("workspace links", list(workspaceService.links(workspace.id()).stream().map(this::linkItem).toList(), AgentToolAuthorizationService.MAX_LIMIT));
    }

    public ToolResult agentProjectReleaseWorkspace(String projectId, String confirmation) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireProjectAccess(agent.id(), projectId);
        authorization.requireConfirmation(confirmation, "REQUEST RELEASE " + projectId);
        return ok("project workspace release requested", leaseItem(projectService.requestWorkspaceRelease(projectId)));
    }

    public PagedListResult agentQueueList(Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        int bounded = authorization.boundLimit(limit);
        return list(assignmentService.assignments(agent.id()).stream().limit(bounded).map(this::assignmentItem).toList(), bounded);
    }

    public ToolResult agentAssignmentGet(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        return ok("assignment", assignmentItem(authorization.requireAssignmentOwner(agent.id(), assignmentId)));
    }

    public ToolResult agentAssignmentCancel(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        return ok("assignment cancelled", assignmentItem(assignmentService.cancel(agent.id(), assignmentId)));
    }

    public ToolResult agentAssignmentPause(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        return ok("assignment paused", assignmentItem(assignmentService.pause(agent.id(), assignmentId)));
    }

    public ToolResult agentAssignmentResume(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        return ok("assignment resumed", assignmentItem(assignmentService.resume(agent.id(), assignmentId)));
    }

    public ToolResult agentAssignmentDelete(String assignmentId, String confirmation) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        authorization.requireConfirmation(confirmation, "DELETE " + assignmentId);
        assignmentService.delete(agent.id(), assignmentId);
        return ok("assignment deleted", Map.of("assignmentId", assignmentId));
    }

    public ToolResult agentAssignmentRequeueWorkspaceBlocked(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        return ok("assignment requeued", assignmentItem(assignmentService.resume(agent.id(), assignmentId)));
    }

    public ToolResult agentAssignmentDiagnostics(String assignmentId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        return ok("assignment diagnostics", diagnosticsItem(assignmentService.diagnostics(assignmentId)));
    }

    public ToolResult agentAssignmentTranscript(String assignmentId, Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireAssignmentOwner(agent.id(), assignmentId);
        int bounded = authorization.boundLimit(limit);
        return ok("assignment transcript", transcriptItem(assignmentService.transcript(agent.id(), assignmentId), bounded));
    }

    public PagedListResult agentInboxList(Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        int bounded = authorization.boundLimit(limit);
        return list(inboxService.messages(agent.id()).stream().limit(bounded).map(this::inboxItem).toList(), bounded);
    }

    public ToolResult agentInboxSend(String toAgentId, String messageType, String body) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireText(toAgentId, "toAgentId");
        authorization.requireText(messageType, "messageType");
        authorization.requireText(body, "body");
        InboxMessage sent = inboxService.send(toAgentId, new InboxMessage(
            null, toAgentId, agent.id(), messageType, body, Map.of(), false, false, null, null));
        return ok("inbox message sent", inboxItem(sent));
    }

    public ToolResult agentInboxMarkRead(String messageId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        requireInboxOwner(agent.id(), messageId);
        return ok("inbox message marked read", inboxItem(inboxService.markRead(messageId)));
    }

    public ToolResult agentInboxMarkHandled(String messageId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        requireInboxOwner(agent.id(), messageId);
        return ok("inbox message marked handled", inboxItem(inboxService.markHandled(messageId)));
    }

    public Object agentScheduleList(Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        if (!schedulesEnabled) {
            return disabled("schedules are disabled");
        }
        int bounded = authorization.boundLimit(limit);
        return list(scheduleService.schedules(agent.id()).stream().limit(bounded).map(this::scheduleItem).toList(), bounded);
    }

    public ToolResult agentScheduleSave(String scheduleId, String jobId, String cronExpression, String timezone,
                                        Boolean enabled, Map<String, Object> assignmentTemplate) {
        AgentProfile agent = authorization.requireCurrentAgent();
        if (!schedulesEnabled) {
            return disabled("schedules are disabled");
        }
        AgentSchedule saved = scheduleService.save(agent.id(), new AgentSchedule(
            scheduleId, agent.id(), jobId, assignmentTemplate == null ? Map.of() : assignmentTemplate,
            cronExpression, timezone, enabled == null || enabled, null, null, null));
        return ok("schedule saved", scheduleItem(saved));
    }

    public ToolResult agentScheduleToggle(String scheduleId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        if (!schedulesEnabled) {
            return disabled("schedules are disabled");
        }
        return ok("schedule toggled", scheduleItem(scheduleService.toggle(agent.id(), scheduleId)));
    }

    public ToolResult agentScheduleDelete(String scheduleId, String confirmation) {
        AgentProfile agent = authorization.requireCurrentAgent();
        if (!schedulesEnabled) {
            return disabled("schedules are disabled");
        }
        authorization.requireConfirmation(confirmation, "DELETE " + scheduleId);
        scheduleService.delete(agent.id(), scheduleId);
        return ok("schedule deleted", Map.of("scheduleId", scheduleId));
    }

    public PagedListResult agentJobList(Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        int bounded = authorization.boundLimit(limit);
        return list(jobService.listDefinitions().stream()
            .filter(job -> canAccessJob(agent.id(), job))
            .limit(bounded)
            .map(this::jobItem)
            .toList(), bounded);
    }

    public ToolResult agentJobGet(String jobId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        return ok("job", jobItem(authorization.requireJobAccess(agent.id(), jobId)));
    }

    public ToolResult agentJobSubmitRun(String jobId, String projectId, String modelOverride, Integer priority, String instructions) {
        AgentProfile agent = authorization.requireCurrentAgent();
        JobDefinition job = authorization.requireJobAccess(agent.id(), jobId);
        String effectiveProjectId = StringUtils.hasText(projectId) ? projectId.trim() : job.projectId();
        if (StringUtils.hasText(effectiveProjectId)) {
            authorization.requireProjectAccess(agent.id(), effectiveProjectId);
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("jobId", jobId);
        if (StringUtils.hasText(instructions)) {
            input.put("instructions", instructions.trim());
        }
        WorkAssignment assignment = assignmentService.create(new AssignmentRequest(
            agent.id(), jobId, null, AssignmentType.JOB_RUN, priority, modelOverride, effectiveProjectId, null, null, input));
        return ok("job assignment submitted", assignmentItem(assignment));
    }

    public PagedListResult agentJobRunList(String jobId, Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireJobAccess(agent.id(), jobId);
        int bounded = authorization.boundLimit(limit);
        return list(jobService.listRuns(jobId).stream().limit(bounded).map(this::jobRunItem).toList(), bounded);
    }

    public ToolResult agentJobRunCancel(String runId, String confirmation) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireJobRunAccess(agent.id(), runId);
        authorization.requireConfirmation(confirmation, "CANCEL " + runId);
        return ok("job run cancelled", jobRunItem(jobService.cancelRun(runId)));
    }

    public PagedListResult agentJobOutputs(String jobId, Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireJobAccess(agent.id(), jobId);
        int bounded = authorization.boundLimit(limit);
        return list(outputArtifactService.query(OutputArtifactQuery.of(null, jobId, null, null, null, null, null, null, null, bounded))
            .stream()
            .filter(artifact -> canAccessArtifact(agent.id(), artifact))
            .limit(bounded)
            .map(this::outputItem)
            .toList(), bounded);
    }

    public PagedListResult agentProjectList(Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        int bounded = authorization.boundLimit(limit);
        return list(projectService.listAgentProjects(agent.id()).stream()
            .limit(bounded)
            .map(projectService::getProject)
            .map(this::projectItem)
            .toList(), bounded);
    }

    public ToolResult agentProjectGet(String projectId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireProjectAccess(agent.id(), projectId);
        return ok("project", projectItem(projectService.getProject(projectId)));
    }

    public PagedListResult agentProjectMembers(String projectId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireProjectAccess(agent.id(), projectId);
        return list(projectService.listMembers(projectId).stream().map(this::memberItem).toList(), AgentToolAuthorizationService.MAX_LIMIT);
    }

    public ToolResult agentProjectWorkspaceStatus(String projectId) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireProjectAccess(agent.id(), projectId);
        return ok("project workspace", projectWorkspaceItem(projectService.workspaceSummary(projectId)));
    }

    public PagedListResult agentProjectEvents(String projectId, Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        authorization.requireProjectAccess(agent.id(), projectId);
        int bounded = authorization.boundLimit(limit);
        return list(projectService.listEvents(projectId).stream().limit(bounded).map(this::eventItem).toList(), bounded);
    }

    public PagedListResult agentOutputList(String projectId, String jobId, String artifactType, Integer limit) {
        AgentProfile agent = authorization.requireCurrentAgent();
        int bounded = authorization.boundLimit(limit);
        if (StringUtils.hasText(projectId)) {
            authorization.requireProjectAccess(agent.id(), projectId);
            return list(outputArtifactService.query(OutputArtifactQuery.of(null, null, projectId, null, null, null, null, null, artifactType, bounded))
                .stream().limit(bounded).map(this::outputItem).toList(), bounded);
        }
        if (StringUtils.hasText(jobId)) {
            authorization.requireJobAccess(agent.id(), jobId);
            return agentJobOutputs(jobId, bounded);
        }
        Map<String, RunOutputArtifact> artifacts = new LinkedHashMap<>();
        outputArtifactService.query(OutputArtifactQuery.of(agent.id(), null, null, null, null, null, null, null, artifactType, bounded))
            .forEach(artifact -> artifacts.putIfAbsent(artifact.id(), artifact));
        for (String membershipProjectId : projectService.listAgentProjects(agent.id())) {
            outputArtifactService.query(OutputArtifactQuery.of(null, null, membershipProjectId, null, null, null, null, null, artifactType, bounded))
                .forEach(artifact -> artifacts.putIfAbsent(artifact.id(), artifact));
        }
        return list(artifacts.values().stream().limit(bounded).map(this::outputItem).toList(), bounded);
    }

    public ToolResult agentOutputRead(String artifactId, Long maxBytes) throws IOException {
        AgentProfile agent = authorization.requireCurrentAgent();
        RunOutputArtifact artifact = outputArtifactService.getArtifact(artifactId);
        authorization.requireArtifactAccess(agent.id(), artifact);
        String content = outputArtifactService.loadContent(artifactId, authorization.boundReadBytes(maxBytes));
        return ok("output content", new OutputContentItem(outputItem(artifact), content.length(), content));
    }

    private InboxMessage requireInboxOwner(String agentId, String messageId) {
        return inboxService.messages(agentId).stream()
            .filter(message -> message.id().equals(messageId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Inbox message is not addressed to current agent: " + messageId));
    }

    private boolean canAccessJob(String agentId, JobDefinition job) {
        if (agentId.equals(job.ownerAgentId())) {
            return true;
        }
        return StringUtils.hasText(job.projectId()) && projectService.isMember(job.projectId(), agentId);
    }

    private boolean canAccessArtifact(String agentId, RunOutputArtifact artifact) {
        try {
            authorization.requireArtifactAccess(agentId, artifact);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private WorkspaceStatusItem statusItem(AgentWorkspaceStatus status) {
        return new WorkspaceStatusItem(
            status.agentId(), status.workspaceRelativePath(), status.health().name(), status.exists(), status.writable(),
            status.activeRunCount(), status.activeLeaseCount(), status.linkedProjectIds(), status.outputArtifactCount(),
            status.outputBytes(), status.lastActivityAt(), status.message()
        );
    }

    private WorkspaceLinkItem linkItem(WorkspaceLink link) {
        return new WorkspaceLinkItem(
            link.id(), link.workspaceId(), link.label(), link.linkType() == null ? null : link.linkType().name(),
            link.target(), link.readable(), link.writable(), link.createdAt(), link.updatedAt()
        );
    }

    private AssignmentItem assignmentItem(WorkAssignment assignment) {
        return new AssignmentItem(
            assignment.id(), assignment.agentId(), assignment.jobId(), assignment.jobItemId(), assignment.assignmentType(),
            assignment.status(), assignment.priority(), assignment.modelOverride(), assignment.workspaceIdentity(),
            assignment.projectId(), assignment.workspaceKey(), assignment.workspaceType(),
            assignment.updatedAt() == null ? null : assignment.updatedAt().toString()
        );
    }

    private DiagnosticsItem diagnosticsItem(AssignmentService.AssignmentDiagnostics diagnostics) {
        Duration progressAge = diagnostics.progressAge();
        Duration heartbeatAge = diagnostics.heartbeatAge();
        return new DiagnosticsItem(
            assignmentItem(diagnostics.assignment()),
            diagnostics.lastProgressAt(),
            diagnostics.lastHeartbeatAt(),
            progressAge == null ? null : progressAge.toSeconds(),
            heartbeatAge == null ? null : heartbeatAge.toSeconds(),
            diagnostics.suspectedStuck(),
            diagnostics.linkedRuns().stream().map(run -> new LinkedRunItem(
                run.type(), run.id(), run.parentId(), run.status(), run.errorText())).toList(),
            diagnostics.auditEvents().stream().map(this::auditItem).toList(),
            diagnostics.conversationId(),
            diagnostics.buildCommit()
        );
    }

    private TranscriptItem transcriptItem(AssignmentService.AssignmentTranscript transcript, int limit) {
        return new TranscriptItem(
            assignmentItem(transcript.assignment()),
            transcript.conversationIds(),
            transcript.auditEvents().stream().limit(limit).map(this::auditItem).toList()
        );
    }

    private AuditEventItem auditItem(AuditRepository.AuditEvent event) {
        return new AuditEventItem(
            event.sequence(),
            event.eventType(),
            preview(event.messageText(), 240),
            event.toolName(),
            event.toolStatus(),
            preview(firstText(event.resultPreview(), event.resultSummary(), event.resultText()), 240),
            event.errorType(),
            event.recordedAt()
        );
    }

    private InboxItem inboxItem(InboxMessage message) {
        return new InboxItem(
            message.id(), message.toAgentId(), message.fromId(), message.messageType(), message.read(), message.handled(),
            message.createdAt(), message.updatedAt(), preview(message.body(), 240)
        );
    }

    private ScheduleItem scheduleItem(AgentSchedule schedule) {
        return new ScheduleItem(
            schedule.id(), schedule.agentId(), schedule.jobId(), schedule.cronExpression(), schedule.timezone(),
            schedule.enabled(), schedule.nextRunAt(), schedule.updatedAt()
        );
    }

    private JobItem jobItem(JobDefinition job) {
        return new JobItem(
            job.id(), job.title(), job.ownerAgentId(), job.projectId(), job.workspaceType(), job.workspaceKey(),
            job.workspaceIdentity(), job.status(), job.items().size(), job.updatedAt()
        );
    }

    private JobRunItem jobRunItem(JobRun run) {
        return new JobRunItem(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), run.status(), run.startedAt(), run.completedAt(), run.updatedAt()
        );
    }

    private ProjectItem projectItem(Project project) {
        return new ProjectItem(project.id(), project.name(), project.ownerAgentId(), project.model(), project.updatedAt());
    }

    private ProjectMemberItem memberItem(ProjectAgentMembership member) {
        return new ProjectMemberItem(member.id(), member.projectId(), member.agentId(), member.role(), member.joinedAt());
    }

    private ProjectEventItem eventItem(ProjectEvent event) {
        return new ProjectEventItem(event.id(), event.projectId(), event.type(), preview(event.payloadJson(), 320), event.createdAt());
    }

    private ProjectWorkspaceItem projectWorkspaceItem(ProjectService.ProjectWorkspaceSummary summary) {
        return new ProjectWorkspaceItem(
            summary.workspaceId(), summary.ownerAgentId(), summary.rootKind(), summary.displayPath(), summary.linkCount(),
            summary.leaseId(), summary.leaseHolderAssignmentId(), summary.mountedAgentId(), summary.releaseRequested()
        );
    }

    private OutputItem outputItem(RunOutputArtifact artifact) {
        return new OutputItem(
            artifact.id(), artifact.runId(), artifact.planId(), artifact.agentId(), artifact.jobId(),
            artifact.jobAssignmentId(), artifact.jobRunId(), artifact.projectId(), artifact.workspaceIdentity(),
            artifact.runType(), artifact.artifactType(), artifact.fileName(), artifact.createdAt()
        );
    }

    private Map<String, Object> leaseItem(WorkspaceLease lease) {
        return Map.of(
            "leaseId", lease.id(),
            "workspaceId", lease.workspaceId(),
            "holderId", lease.holderId(),
            "releaseRequested", lease.releaseRequested()
        );
    }

    private ToolResult ok(String message, Object data) {
        return new ToolResult(true, message, data);
    }

    private ToolResult disabled(String message) {
        return new ToolResult(false, message, null);
    }

    private PagedListResult list(List<?> items, int limit) {
        return new PagedListResult(items.size(), limit, items);
    }

    private String preview(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxChars ? trimmed : trimmed.substring(0, maxChars) + "...";
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }
}
