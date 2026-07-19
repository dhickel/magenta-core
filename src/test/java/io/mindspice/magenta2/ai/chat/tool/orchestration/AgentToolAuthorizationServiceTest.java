package io.mindspice.magenta2.ai.chat.tool.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolAuthorizationServiceTest {
    static {
        System.setProperty("net.bytebuddy.experimental", "true");
    }

    private final AgentProfileService agentProfileService = mock(AgentProfileService.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private final AssignmentService assignmentService = mock(AssignmentService.class);
    private final JobService jobService = mock(JobService.class);
    private final AgentToolAuthorizationService authorization = new AgentToolAuthorizationService(
        agentProfileService, projectService, assignmentService, jobService);

    @AfterEach
    void clearContext() {
        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void rejectsMissingAgentContext() {
        assertThatThrownBy(authorization::requireCurrentAgent)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active orchestration agent context");
    }

    @Test
    void resolvesCurrentAgentFromThreadContextAndRejectsDisabledProfiles() {
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of(), AgentProfileStatus.DISABLED));

        assertThatThrownBy(authorization::requireCurrentAgent)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("disabled");
    }

    @Test
    void rejectsUnknownSurface() {
        OrchestrationTaskContextHolder.set(context("agent-1"));
        when(agentProfileService.get("agent-1")).thenReturn(profile("agent-1", List.of("*"), AgentProfileStatus.ACTIVE));

        assertThat(authorization.requireCurrentAgent().id()).isEqualTo("agent-1");
    }

    @Test
    void boundsLimitsAndRequiresExactConfirmation() {
        assertThat(authorization.boundLimit(null)).isEqualTo(50);
        assertThat(authorization.boundLimit(-1)).isEqualTo(50);
        assertThat(authorization.boundLimit(500)).isEqualTo(200);
        assertThat(authorization.boundLimit(2)).isEqualTo(2);

        assertThatThrownBy(() -> authorization.requireConfirmation("DELETE other", "DELETE assignment-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("DELETE assignment-1");
    }

    @Test
    void assignmentAndJobAccessStayScopedToOwnerOrProjectMembership() {
        WorkAssignment assignment = assignment("assignment-1", "agent-1", "job-1", "project-1");
        when(assignmentService.get("assignment-1")).thenReturn(assignment);

        assertThat(authorization.requireAssignmentOwner("agent-1", "assignment-1")).isEqualTo(assignment);
        assertThatThrownBy(() -> authorization.requireAssignmentOwner("agent-2", "assignment-1"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("current agent");

        JobDefinition ownerJob = new JobDefinition("job-owner", "agent-1", null, null, false, "ACTIVE",
            "Owned", null, List.of(), null, null, null, Instant.now(), Instant.now());
        JobDefinition projectJob = new JobDefinition("job-project", "agent-9", "project-1", null, false, "ACTIVE",
            "Project", null, List.of(), null, null, null, Instant.now(), Instant.now());
        when(jobService.getDefinition("job-owner")).thenReturn(ownerJob);
        when(jobService.getDefinition("job-project")).thenReturn(projectJob);
        when(projectService.isMember("project-1", "agent-1")).thenReturn(true);
        when(projectService.isMember("project-1", "agent-2")).thenReturn(false);

        assertThat(authorization.requireJobAccess("agent-1", "job-owner").id()).isEqualTo("job-owner");
        assertThat(authorization.requireJobAccess("agent-1", "job-project").id()).isEqualTo("job-project");
        assertThatThrownBy(() -> authorization.requireJobAccess("agent-2", "job-project"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not visible");
    }

    private OrchestrationTaskContext context(String agentId) {
        return new OrchestrationTaskContext(agentId, "Agent", null, null, null, "TASK_RUN", "/tmp/run", "/tmp/out");
    }

    private AgentProfile profile(String id, List<String> approvedTools, AgentProfileStatus status) {
        return new AgentProfile(id, id, status, "model", "prompt", approvedTools, List.of(), true, Instant.now(), Instant.now());
    }

    private WorkAssignment assignment(String id, String agentId, String jobId, String projectId) {
        return new WorkAssignment(id, agentId, jobId, null, AssignmentType.JOB_RUN, 0, OrchestrationStatus.QUEUED,
            null, null, projectId, null, null, 0, Map.of(), Map.of(), Map.of(), Map.of(), null, null, null,
            Instant.now(), Instant.now(), null, null);
    }
}
