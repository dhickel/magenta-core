package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.chat.tool.shell.AgentShellToolService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatusService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.api.web.selector.EntityLookupService;
import io.mindspice.magenta2.api.web.selector.EntitySelectorComponents;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectConsoleControllerTest {
    private ProjectService projectService;
    private OrchestrationController controller;
    private Project project;

    @BeforeEach
    void setUp() {
        ChatService chatService = mock(ChatService.class);
        when(chatService.availableModelOptions()).thenReturn(List.of());
        projectService = mock(ProjectService.class);
        AgentProfileService agentProfileService = mock(AgentProfileService.class);
        when(agentProfileService.list()).thenReturn(List.of());
        project = new Project("project-1", "Alpha", "Project description", "agent-owner",
            "https://legacy.invalid/repo.git", "CODING_CENTRIC", null, null,
            Instant.parse("2026-07-28T00:00:00Z"), Instant.parse("2026-07-28T01:00:00Z"));
        when(projectService.getProject("project-1")).thenReturn(project);

        controller = new OrchestrationController(
            chatService, projectService, mock(JobService.class), agentProfileService,
            mock(InboxService.class), mock(io.mindspice.magenta2.ai.orchestration.runtime.InboxService.class),
            mock(OutputArtifactService.class), mock(RuntimeSettingsService.class), provider(), provider(), provider(),
            mock(PlanService.class), mock(AssignmentService.class), mock(ScheduleService.class),
            mock(EventReactionService.class), mock(WorkflowService.class), mock(EntityLookupService.class),
            mock(EntitySelectorComponents.class), provider(), false, false);
    }

    @Test
    void selectedProjectUsesSixStableTabsInOrder() {
        String html = controller.projectDetailFragment("project-1", mock(HttpServletResponse.class));

        assertThat(html).containsSubsequence(
            "data-project-tab=\"dashboard\"", "data-project-tab=\"members\"",
            "data-project-tab=\"files\"", "data-project-tab=\"jobs\"",
            "data-project-tab=\"outputs\"", "data-project-tab=\"settings\"");
        assertThat(html).contains("id=\"project-tab-panel\"", "hx-target=\"#project-tab-panel\"")
            .contains("Tracked");
    }

    @Test
    void browserFormsUseOwnerAgentAndNeverExposeLegacyGitControls() {
        String create = controller.newProjectEditor();
        String settings = controller.projectSettingsTab("project-1", mock(HttpServletResponse.class));

        assertThat(create).contains("Owner Agent").doesNotContain("Legacy Initial Agent", "Git Repo URL", "gitRepoUrl");
        assertThat(settings).contains("Owner Agent", "Manager Type", "Default Model")
            .doesNotContain("Legacy Initial Agent", "Git Repo URL", "gitRepoUrl", "legacy.invalid");
    }

    @Test
    void invalidCreateAndSettingsRemainLocalAndPreserveSubmittedValues() {
        HttpServletResponse createResponse = mock(HttpServletResponse.class);
        String create = controller.createProject(Map.of("name", "", "description", "keep this"), createResponse);
        HttpServletResponse settingsResponse = mock(HttpServletResponse.class);
        String settings = controller.updateProject("project-1",
            Map.of("name", "", "description", "preserved description", "workTypeProfile", "RESEARCH_CENTRIC"),
            settingsResponse);

        assertThat(create).contains("Name is required.", "keep this", "data-htmx-error-swap=\"true\"")
            .doesNotContain("hx-swap-oob");
        assertThat(settings).contains("Name is required.", "preserved description", "data-htmx-error-swap=\"true\"")
            .doesNotContain("hx-swap-oob");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() {
        return mock(ObjectProvider.class);
    }
}
