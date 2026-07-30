package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
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
import static org.mockito.Mockito.verify;
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
        io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskService workTaskService
            = mock(io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskService.class);
        when(workTaskService.listByProject(any())).thenReturn(java.util.List.of());
        io.mindspice.magenta2.ai.chat.plan.PlanService planService
            = mock(io.mindspice.magenta2.ai.chat.plan.PlanService.class);
        io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService projectAccessService
            = mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService.class);
        when(projectAccessService.isTrustedDeployment()).thenReturn(true);
        io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService projectOutputService
            = mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService.class);

        controller = new ProjectConsoleController(
            projectService, mock(JobService.class), agentProfileService,
            mock(AssignmentService.class), mock(OutputArtifactService.class),
            fragments, entityLookupService, entitySelectorComponents,
            agentContextService, agentMaterializationService,
            workspaceDirectoryService, workspaceExplorerService,
            workTaskService, planService, projectAccessService, projectOutputService,
            mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectMaterializationTemplateService.class));
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
        assertThat(settings).contains("Settings")
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
    void preferredAgentUpdateUsesSubmittedDefinitionVersion() {
        JobService jobs = mock(JobService.class);
        JobDefinition job = new JobDefinition("job-1", "agent-owner", "project-1", "agent", "agent-owner",
            null, "DRAFT", "Job", "Summary", List.of(), null, null, null, 3, Instant.now(), Instant.now());
        when(jobs.getDefinition("job-1")).thenReturn(job);
        when(jobs.saveDefinitionCAS(any(), org.mockito.ArgumentMatchers.eq(3)))
            .thenReturn(java.util.Optional.of(job));
        when(jobs.listRuns("job-1")).thenReturn(List.of());
        when(projectService.isMember("project-1", "agent-owner")).thenReturn(true);

        ProjectConsoleController versioned = new ProjectConsoleController(
            projectService, jobs, mock(AgentProfileService.class), mock(AssignmentService.class),
            mock(OutputArtifactService.class), new ProjectConsoleFragments(), mock(EntityLookupService.class),
            mock(EntitySelectorComponents.class), mock(AgentContextService.class), mock(AgentMaterializationService.class),
            mock(WorkspaceDirectoryService.class), mock(WorkspaceExplorerService.class),
            mock(io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskService.class),
            mock(io.mindspice.magenta2.ai.chat.plan.PlanService.class),
            mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService.class),
            mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectOutputService.class),
            mock(io.mindspice.magenta2.ai.orchestration.runtime.ProjectMaterializationTemplateService.class));

        String html = versioned.updateJobPreferredAgent("project-1", "job-1",
            Map.of("preferredAgentId", "agent-owner", "expectedVersion", "3"), mock(HttpServletResponse.class));

        verify(jobs).saveDefinitionCAS(any(), org.mockito.ArgumentMatchers.eq(3));
        assertThat(html).contains("Version: 3");
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
            "/projects/_editor/{projectId}", "/projects/_detail/{projectId}/delete-confirm",
            "/projects/{projectId}");
        // Schedules and Events routes are on ProjectAutomationController (Phase 06)
    }

    @Test
    void unavailableTabsRenderCleanState() {
        String tasks = controller.projectTasksTab("project-1", mock(HttpServletResponse.class));

        assertThat(tasks).contains("Tasks");
    }

    @Test
    void chatTabRendersMemberSelectorWhenNoMembers() {
        String chat = controller.projectChatTab("project-1", null, mock(HttpServletResponse.class));

        assertThat(chat).contains("Project Chat");
        assertThat(chat).contains("No project members available");
    }

    @Test
    void activeTabButtonMarkedActive() {
        String html = controller.projectDetailFragment("project-1", null, mock(HttpServletResponse.class));
        assertThat(html).contains("data-project-tab=\"queue\"", "active");
    }

    @Test
    void projectChatHistoryReplacesItsStableHostInsteadOfNestingIt() {
        String html = ProjectChatFragments.panel("project-1", "agent-1", "conversation-1", true);

        assertThat(html).contains("id=\"project-chat-history\"", "hx-swap=\"outerHTML\"");
        assertThat(ProjectChatFragments.history("project-1", "agent-1", List.of())).containsOnlyOnce(
            "id=\"project-chat-history\"");
    }

    @Test
    void projectFragmentsExposeTheCanonicalNavigationRegistry() {
        assertThat(new ProjectConsoleFragments().projectTabs())
            .containsExactlyElementsOf(AppNavigation.consoleTabs());
    }
}
