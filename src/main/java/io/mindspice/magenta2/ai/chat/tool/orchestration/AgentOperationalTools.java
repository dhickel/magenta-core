package io.mindspice.magenta2.ai.chat.tool.orchestration;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentOperationalTools {
    private final AgentOperationalToolService service;
    private final ObjectMapper objectMapper;

    public AgentOperationalTools(AgentOperationalToolService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "agent_workspace_status", description = "Inspect the current agent workspace health, active run count, and output activity from the active orchestration context.")
    public String agentWorkspaceStatus() {
        return json(service.agentWorkspaceStatus());
    }

    @Tool(name = "agent_queue_list", description = "List queued and active assignments for the current agent. Results are compact and capped.")
    public String agentQueueList(@ToolParam(required = false, description = "Maximum items to return, bounded to 1..200.") Integer limit) {
        return json(service.agentQueueList(limit));
    }

    @Tool(name = "agent_assignment_get", description = "Get one assignment owned by the current agent.")
    public String agentAssignmentGet(@ToolParam(description = "Assignment id to inspect.") String assignmentId) {
        return json(service.agentAssignmentGet(assignmentId));
    }

    @Tool(name = "agent_assignment_cancel", description = "Cancel an assignment owned by the current agent.")
    public String agentAssignmentCancel(@ToolParam(description = "Assignment id to cancel.") String assignmentId) {
        return json(service.agentAssignmentCancel(assignmentId));
    }

    @Tool(name = "agent_assignment_pause", description = "Pause an assignment owned by the current agent.")
    public String agentAssignmentPause(@ToolParam(description = "Assignment id to pause.") String assignmentId) {
        return json(service.agentAssignmentPause(assignmentId));
    }

    @Tool(name = "agent_assignment_resume", description = "Resume a paused, interrupted, or waiting assignment owned by the current agent.")
    public String agentAssignmentResume(@ToolParam(description = "Assignment id to resume.") String assignmentId) {
        return json(service.agentAssignmentResume(assignmentId));
    }

    @Tool(name = "agent_assignment_delete", description = "Delete a non-running assignment owned by the current agent. Requires confirmation text DELETE <assignmentId>.")
    public String agentAssignmentDelete(
        @ToolParam(description = "Assignment id to delete.") String assignmentId,
        @ToolParam(description = "Exact confirmation text: DELETE <assignmentId>.") String confirmation
    ) {
        return json(service.agentAssignmentDelete(assignmentId, confirmation));
    }

    @Tool(name = "agent_assignment_requeue_workspace_blocked", description = "Requeue a workspace-blocked assignment owned by the current agent.")
    public String agentAssignmentRequeueWorkspaceBlocked(@ToolParam(description = "Assignment id to requeue.") String assignmentId) {
        return json(service.agentAssignmentRequeueWorkspaceBlocked(assignmentId));
    }

    @Tool(name = "agent_assignment_diagnostics", description = "Inspect compact diagnostics for an assignment owned by the current agent.")
    public String agentAssignmentDiagnostics(@ToolParam(description = "Assignment id to diagnose.") String assignmentId) {
        return json(service.agentAssignmentDiagnostics(assignmentId));
    }

    @Tool(name = "agent_assignment_transcript", description = "Read a compact retained transcript summary for an assignment owned by the current agent.")
    public String agentAssignmentTranscript(
        @ToolParam(description = "Assignment id whose transcript should be read.") String assignmentId,
        @ToolParam(required = false, description = "Maximum transcript events to return, bounded to 1..200.") Integer limit
    ) {
        return json(service.agentAssignmentTranscript(assignmentId, limit));
    }

    @Tool(name = "agent_inbox_list", description = "List inbox messages addressed to the current agent.")
    public String agentInboxList(@ToolParam(required = false, description = "Maximum messages to return, bounded to 1..200.") Integer limit) {
        return json(service.agentInboxList(limit));
    }

    @Tool(name = "agent_inbox_send", description = "Send a direct-line inbox message from the current agent to another agent.")
    public String agentInboxSend(
        @ToolParam(description = "Recipient agent id.") String toAgentId,
        @ToolParam(description = "Short message type such as question, update, or handoff.") String messageType,
        @ToolParam(description = "Message body.") String body
    ) {
        return json(service.agentInboxSend(toAgentId, messageType, body));
    }

    @Tool(name = "agent_inbox_mark_read", description = "Mark a message addressed to the current agent as read.")
    public String agentInboxMarkRead(@ToolParam(description = "Inbox message id.") String messageId) {
        return json(service.agentInboxMarkRead(messageId));
    }

    @Tool(name = "agent_inbox_mark_handled", description = "Mark a message addressed to the current agent as read and handled.")
    public String agentInboxMarkHandled(@ToolParam(description = "Inbox message id.") String messageId) {
        return json(service.agentInboxMarkHandled(messageId));
    }

    @Tool(name = "agent_schedule_list", description = "List schedules for the current agent, or report that schedules are disabled.")
    public String agentScheduleList(@ToolParam(required = false, description = "Maximum schedules to return, bounded to 1..200.") Integer limit) {
        return json(service.agentScheduleList(limit));
    }

    @Tool(name = "agent_schedule_save", description = "Create or update a schedule for the current agent when schedules are enabled.")
    public String agentScheduleSave(
        @ToolParam(required = false, description = "Existing schedule id to update, or omit to create.") String scheduleId,
        @ToolParam(required = false, description = "Optional job id this schedule dispatches.") String jobId,
        @ToolParam(description = "Spring cron expression.") String cronExpression,
        @ToolParam(required = false, description = "Timezone id such as America/New_York. Defaults to system timezone.") String timezone,
        @ToolParam(required = false, description = "Whether the schedule is enabled. Defaults to true.") Boolean enabled,
        @ToolParam(required = false, description = "Assignment template map consumed by existing schedule validation.") Map<String, Object> assignmentTemplate
    ) {
        return json(service.agentScheduleSave(scheduleId, jobId, cronExpression, timezone, enabled, assignmentTemplate));
    }

    @Tool(name = "agent_schedule_toggle", description = "Toggle a schedule owned by the current agent when schedules are enabled.")
    public String agentScheduleToggle(@ToolParam(description = "Schedule id to toggle.") String scheduleId) {
        return json(service.agentScheduleToggle(scheduleId));
    }

    @Tool(name = "agent_schedule_delete", description = "Delete a schedule owned by the current agent. Requires confirmation text DELETE <scheduleId>.")
    public String agentScheduleDelete(
        @ToolParam(description = "Schedule id to delete.") String scheduleId,
        @ToolParam(description = "Exact confirmation text: DELETE <scheduleId>.") String confirmation
    ) {
        return json(service.agentScheduleDelete(scheduleId, confirmation));
    }

    @Tool(name = "agent_job_list", description = "List jobs owned by or visible to the current agent and accessible project memberships.")
    public String agentJobList(@ToolParam(required = false, description = "Maximum jobs to return, bounded to 1..200.") Integer limit) {
        return json(service.agentJobList(limit));
    }

    @Tool(name = "agent_job_get", description = "Get one job definition visible to the current agent.")
    public String agentJobGet(@ToolParam(description = "Job id to inspect.") String jobId) {
        return json(service.agentJobGet(jobId));
    }

    @Tool(name = "agent_job_submit_run", description = "Submit a JOB_RUN assignment for a job visible to the current agent. The actual job run is allocated by the assignment runner.")
    public String agentJobSubmitRun(
        @ToolParam(description = "Job id to submit.") String jobId,
        @ToolParam(required = false, description = "Optional project id. The current agent must be a project member.") String projectId,
        @ToolParam(required = false, description = "Optional model override key.") String modelOverride,
        @ToolParam(required = false, description = "Optional priority. Defaults to 0.") Integer priority,
        @ToolParam(required = false, description = "Optional short instructions attached to the assignment input.") String instructions
    ) {
        return json(service.agentJobSubmitRun(jobId, projectId, modelOverride, priority, instructions));
    }

    @Tool(name = "agent_job_run_list", description = "List runs for a job visible to the current agent.")
    public String agentJobRunList(
        @ToolParam(description = "Job id whose runs should be listed.") String jobId,
        @ToolParam(required = false, description = "Maximum runs to return, bounded to 1..200.") Integer limit
    ) {
        return json(service.agentJobRunList(jobId, limit));
    }

    @Tool(name = "agent_job_run_cancel", description = "Cancel a non-terminal job run visible to the current agent. Requires confirmation text CANCEL <runId>.")
    public String agentJobRunCancel(
        @ToolParam(description = "Job run id to cancel.") String runId,
        @ToolParam(description = "Exact confirmation text: CANCEL <runId>.") String confirmation
    ) {
        return json(service.agentJobRunCancel(runId, confirmation));
    }

    @Tool(name = "agent_job_outputs", description = "List output artifacts for a job visible to the current agent.")
    public String agentJobOutputs(
        @ToolParam(description = "Job id whose outputs should be listed.") String jobId,
        @ToolParam(required = false, description = "Maximum outputs to return, bounded to 1..200.") Integer limit
    ) {
        return json(service.agentJobOutputs(jobId, limit));
    }

    @Tool(name = "agent_project_list", description = "List projects where the current agent is a member.")
    public String agentProjectList(@ToolParam(required = false, description = "Maximum projects to return, bounded to 1..200.") Integer limit) {
        return json(service.agentProjectList(limit));
    }

    @Tool(name = "agent_project_get", description = "Get a project where the current agent is a member.")
    public String agentProjectGet(@ToolParam(description = "Project id to inspect.") String projectId) {
        return json(service.agentProjectGet(projectId));
    }

    @Tool(name = "agent_project_members", description = "List members of a project where the current agent is a member.")
    public String agentProjectMembers(@ToolParam(description = "Project id whose members should be listed.") String projectId) {
        return json(service.agentProjectMembers(projectId));
    }

    @Tool(name = "agent_project_events", description = "List recent events for a project where the current agent is a member.")
    public String agentProjectEvents(
        @ToolParam(description = "Project id whose events should be listed.") String projectId,
        @ToolParam(required = false, description = "Maximum events to return, bounded to 1..200.") Integer limit
    ) {
        return json(service.agentProjectEvents(projectId, limit));
    }

    @Tool(name = "agent_output_list", description = "List output artifacts attributable to the current agent or its project memberships.")
    public String agentOutputList(
        @ToolParam(required = false, description = "Optional project id. Current agent must be a member.") String projectId,
        @ToolParam(required = false, description = "Optional job id visible to the current agent.") String jobId,
        @ToolParam(required = false, description = "Optional artifact type filter.") String artifactType,
        @ToolParam(required = false, description = "Maximum artifacts to return, bounded to 1..200.") Integer limit
    ) {
        return json(service.agentOutputList(projectId, jobId, artifactType, limit));
    }

    @Tool(name = "agent_output_read", description = "Read bounded UTF-8 content for one output artifact visible to the current agent.")
    public String agentOutputRead(
        @ToolParam(description = "Output artifact id to read.") String artifactId,
        @ToolParam(required = false, description = "Maximum bytes to read. Defaults to 65536 and is capped by the server.") Long maxBytes
    ) throws Exception {
        return json(service.agentOutputRead(artifactId, maxBytes));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize operational tool result", exception);
        }
    }
}
