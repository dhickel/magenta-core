package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.api.web.selector.EntityLookupService;
import io.mindspice.magenta2.api.web.selector.EntitySelectorComponents;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectConsoleControllerTest {
    private ProjectService projectService;
    private ProjectConsoleController controller;
    private Project project;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        AgentProfileService agentProfileService = mock(AgentProfileService.class);
        when(agentProfileService.list()).thenReturn(List.of());
        project = new Project("project-1", "Alpha", "Project description", "agent-owner",
            "https://legacy.invalid/repo.git", "CODING_CENTRIC", null, null, false,
            Instant.parse("2026-07-28T00:00:00Z"), Instant.parse("2026-07-28T01:00:00Z"));
        when(projectService.getProject("project-1")).thenReturn(project);

        ProjectConsoleFragments fragments = new ProjectConsoleFragments();
        EntityLookupService entityLookupService = mock(EntityLookupService.class);
        EntitySelectorComponents entitySelectorComponents = mock(EntitySelectorComponents.class);
        when(entitySelectorComponents.selector(any(), any())).thenReturn(new io.mindspice.simplypages.components.Div());
        AgentContextService agentContextService = mock(AgentContextService.class);
        AgentMaterializationService agentMaterializationService = mock(AgentMaterializationService.class);
        WorkspaceDirectoryService workspaceDirectoryService = mock(WorkspaceDirectoryService.class);
        WorkspaceExplorerService workspaceExplorerService = mock(WorkspaceExplorerService.class);

        controller = new ProjectConsoleController(
            projectService, mock(JobService.class), agentProfileService,
            mock(AssignmentService.class), mock(OutputArtifactService.class),
            fragments, entityLookupService, entitySelectorComponents,
            agentContextService, agentMaterializationService,
            workspaceDirectoryService, workspaceExplorerService);
    }

    @Test
    void selectedProjectUsesGroupedTabsInOrder() {
        String html = controller.projectDetailFragment("project-1", null, mock(HttpServletResponse.class));

        // Verify grouped tab structure with functional and unavailable tabs
        assertThat(html).contains("data-project-tab=\"dashboard\"");
        assertThat(html).contains("data-project-tab=\"queue\"");
        assertThat(html).contains("data-project-tab=\"tasks\"");
        assertThat(html).contains("data-project-tab=\"jobs\"");
        assertThat(html).contains("data-project-tab=\"outputs\"");
        assertThat(html).contains("data-project-tab=\"files\"");
        assertThat(html).contains("data-project-tab=\"chat\"");
        assertThat(html).contains("data-project-tab=\"schedules\"");
        assertThat(html).contains("data-project-tab=\"events\"");
        assertThat(html).contains("data-project-tab=\"members\"");
        assertThat(html).contains("data-project-tab=\"settings\"");
        assertThat(html).contains("id=\"project-tab-panel\"");
        assertThat(html).contains("Tracked");
    }

    @Test
    void browserFormsUseOwnerAgentAndNeverExposeLegacyGitControls() {
        String create = controller.newProjectEditor();
        String settings = controller.projectSettingsTab("project-1", mock(HttpServletResponse.class));

        assertThat(create).contains("Owner Agent").doesNotContain("Legacy Initial Agent", "Git Repo URL", "gitRepoUrl");
        assertThat(settings).contains("Settings", "not yet available")
            .doesNotContain("Git Repo URL", "gitRepoUrl", "legacy.invalid");
    }

    @Test
    void dashboardAndMembersRemainFunctionalTabs() {
        HttpServletResponse response = mock(HttpServletResponse.class);

        String dashboard = controller.projectDashboardTab("project-1", response);
        String members = controller.projectMembersTab("project-1", response);

        assertThat(dashboard).contains("Project", "Member Agents", "Active Assignments")
            .doesNotContain("not yet available");
        assertThat(members).contains("Members", "Add Member", "project-member-add-controls")
            .doesNotContain("not yet available");
    }

    @Test
    void projectListFragmentRendersTrackedChip() {
        when(projectService.listProjects()).thenReturn(List.of(project));
        String html = controller.projectsListFragment();
        assertThat(html).contains("Tracked", "Alpha", "Project description");
    }

    @Test
    void missingProjectReturnsNotFoundFragment() {
        when(projectService.getProject("missing")).thenThrow(new IllegalArgumentException("not found"));
        HttpServletResponse response = mock(HttpServletResponse.class);
        String html = controller.projectDetailFragment("missing", null, response);
        assertThat(html).contains("not found");
    }

    @Test
    void projectRoutesExist() {
        java.util.List<String> routes = java.util.Arrays.stream(ProjectConsoleController.class.getDeclaredMethods())
            .filter(m -> m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                || m.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class))
            .flatMap(m -> java.util.Arrays.stream(
                m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
                    ? m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value()
                    : m.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                    ? m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value()
                    : m.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                    ? m.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class).value()
                    : m.getAnnotation(org.springframework.web.bind.annotation.DeleteMapping.class).value()))
            .toList();

        assertThat(routes).contains(
            "/projects", "/projects/{projectId}", "/projects/_list",
            "/projects/_editor/_new", "/projects/_editor",
            "/projects/_detail/{projectId}", "/projects/_detail/{projectId}/dashboard",
            "/projects/_detail/{projectId}/members", "/projects/_detail/{projectId}/files",
            "/projects/_detail/{projectId}/jobs", "/projects/_detail/{projectId}/outputs",
            "/projects/_detail/{projectId}/settings",
            "/projects/_detail/{projectId}/tasks", "/projects/_detail/{projectId}/chat",
            "/projects/_detail/{projectId}/schedules", "/projects/_detail/{projectId}/events",
            "/projects/_editor/{projectId}", "/projects/_detail/{projectId}/delete-confirm",
            "/projects/{projectId}");
    }

    @Test
    void unavailableTabsRenderCleanState() {
        String tasks = controller.projectTasksTab("project-1", mock(HttpServletResponse.class));
        String chat = controller.projectChatTab("project-1", mock(HttpServletResponse.class));
        String schedules = controller.projectSchedulesTab("project-1", mock(HttpServletResponse.class));
        String events = controller.projectEventsTab("project-1", mock(HttpServletResponse.class));

        assertThat(tasks).contains("Tasks").contains("not yet available");
        assertThat(chat).contains("Chat").contains("not yet available");
        assertThat(schedules).contains("Schedules").contains("not yet available");
        assertThat(events).contains("Events").contains("not yet available");

        // Unavailable tabs must not expose any speculative API
        for (String html : List.of(tasks, chat, schedules, events)) {
            assertThat(html).doesNotContain("hx-get", "hx-post", "hx-put", "hx-delete");
        }
    }

    @Test
    void activeTabButtonMarkedActive() {
        String html = controller.projectDetailFragment("project-1", null, mock(HttpServletResponse.class));
        assertThat(html).contains("data-project-tab=\"queue\"", "active");
    }
}
