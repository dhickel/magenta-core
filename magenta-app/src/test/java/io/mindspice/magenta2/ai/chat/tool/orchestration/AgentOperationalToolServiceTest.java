package io.mindspice.magenta2.ai.chat.tool.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.AgentItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.AssignmentItem;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.PagedListResult;
import io.mindspice.magenta2.ai.chat.tool.orchestration.AgentOperationalToolResponses.ToolResult;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatusService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentOperationalToolServiceTest {
    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private final AgentProfileService agentProfileService = mock(AgentProfileService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final AssignmentService assignmentService = mock(AssignmentService.class);
    private final InboxService inboxService = mock(InboxService.class);
    private final ScheduleService scheduleService = mock(ScheduleService.class);
    private final JobService jobService = mock(JobService.class);
    private final AgentWorkspaceStatusService workspaceStatusService = mock(AgentWorkspaceStatusService.class);
    private final OutputArtifactService outputArtifactService = mock(OutputArtifactService.class);
    private final AgentToolAuthorizationService authorization = new AgentToolAuthorizationService(
        agentProfileService, projectService, assignmentService, jobService);

    @AfterEach
    void clearContext() {
        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void queueListUsesCurrentAgentAndBoundsLimit() {
        AgentOperationalToolService service = service(true);
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of(), AgentProfileStatus.ACTIVE));
        when(assignmentService.assignments("agent-1")).thenReturn(List.of(
            assignment("assignment-1", "agent-1", OrchestrationStatus.QUEUED),
            assignment("assignment-2", "agent-1", OrchestrationStatus.RUNNING)
        ));

        PagedListResult result = service.agentQueueList(1);

        assertThat(result.limit()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(((AssignmentItem) result.items().getFirst()).id()).isEqualTo("assignment-1");
    }

    @Test
    void destructiveAssignmentDeleteRequiresOwnedAssignmentAndExactConfirmation() {
        AgentOperationalToolService service = service(true);
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of(), AgentProfileStatus.ACTIVE));
        when(assignmentService.get("assignment-1")).thenReturn(assignment("assignment-1", "agent-1", OrchestrationStatus.QUEUED));

        assertThatThrownBy(() -> service.agentAssignmentDelete("assignment-1", "DELETE other"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DELETE assignment-1");

        ToolResult result = service.agentAssignmentDelete("assignment-1", "DELETE assignment-1");

        assertThat(result.ok()).isTrue();
        verify(assignmentService).delete("agent-1", "assignment-1");
    }

    @Test
    void inboxMarkReadRequiresMessageAddressedToCurrentAgent() {
        AgentOperationalToolService service = service(true);
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of(), AgentProfileStatus.ACTIVE));
        InboxMessage owned = new InboxMessage("msg-1", "agent-1", "agent-2", "update", "body", Map.of(), false, false, Instant.now(), Instant.now());
        when(inboxService.messages("agent-1")).thenReturn(List.of(owned));
        when(inboxService.markRead("msg-1")).thenReturn(new InboxMessage("msg-1", "agent-1", "agent-2", "update", "body", Map.of(), true, false, Instant.now(), Instant.now()));

        assertThatThrownBy(() -> service.agentInboxMarkRead("msg-2"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not addressed");

        assertThat(service.agentInboxMarkRead("msg-1").ok()).isTrue();
        verify(inboxService).markRead("msg-1");
    }

    @Test
    void schedulesReportDisabledStateBeforeMutating() {
        AgentOperationalToolService service = service(false);
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of(), AgentProfileStatus.ACTIVE));

        ToolResult result = service.agentScheduleSave(null, null, "0 0 * * * *", "UTC", true, Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.message()).contains("disabled");
    }

    private AgentOperationalToolService service(boolean schedulesEnabled) {
        return new AgentOperationalToolService(
            authorization,
            agentProfileService,
            assignmentService,
            inboxService,
            scheduleService,
            jobService,
            projectService,
            workspaceStatusService,
            outputArtifactService,
            schedulesEnabled
        );
    }

    private OrchestrationTaskContext context(String agentId) {
        return new OrchestrationTaskContext(agentId,
            "Agent",
            null,
            null,
            null,
            null,
            "agent",
            agentId,
            "agent/" + agentId,
            "TASK_RUN",
            null,
            "/tmp/run",
            null, null, null, null, null);
    }

    private AgentProfile profile(String id, List<String> approvedTools, AgentProfileStatus status) {
        return new AgentProfile(id, id, status, "model", "prompt", approvedTools, List.of(), true, Instant.now(), Instant.now());
    }

    private WorkAssignment assignment(String id, String agentId, OrchestrationStatus status) {
        return new WorkAssignment(id, agentId, "job-1", null, AssignmentType.JOB_RUN, 0, status,
            null, null, null, null, null, 0, Map.of(), Map.of(), Map.of(), Map.of(), null, null, null,
            Instant.now(), Instant.now(), null, null);
    }
}
