package io.mindspice.magenta2.api.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectOperationsAccessService.ProjectAccessDeniedException;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskAttempt;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskDefinition;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskService;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.api.web.ProjectConsoleFragments.ProjectTab;
import io.mindspice.magenta2.api.web.selector.EntityKind;
import io.mindspice.magenta2.api.web.selector.EntityLookupService;
import io.mindspice.magenta2.api.web.selector.EntitySelectorComponents;
import io.mindspice.magenta2.api.web.selector.EntitySelectorConfig;
import io.mindspice.simplypages.builders.ShellBuilder;
import io.mindspice.simplypages.builders.ShellTemplate;
import io.mindspice.simplypages.components.Div;
import io.mindspice.simplypages.components.Header;
import io.mindspice.simplypages.components.Paragraph;
import io.mindspice.simplypages.components.RawHtml;
import io.mindspice.simplypages.components.display.Table;
import io.mindspice.simplypages.components.forms.Button;
import io.mindspice.simplypages.components.forms.Form;
import io.mindspice.simplypages.components.forms.Select;
import io.mindspice.simplypages.components.forms.TextArea;
import io.mindspice.simplypages.components.forms.TextInput;
import io.mindspice.simplypages.core.Component;
import io.mindspice.simplypages.core.HtmlTag;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Project console controller: chooser, create, archive, detail shell with
 * grouped tabs, and existing functional tabs (dashboard, members, files, jobs,
 * outputs, settings).
 *
 * <p>Controllers stay thin: parse transport input, obtain scope, delegate to
 * services, map results. No repository, lifecycle, or authorization logic in
 * web code.</p>
 */
@Controller
public class ProjectConsoleController {
    private static final Logger log = LoggerFactory.getLogger(ProjectConsoleController.class);
    private static final String PROJECTS_JS = "/js/orchestration/projects.js?v=5";
    private static final String HTMX_ERRORS_JS = "/js/orchestration/htmx-errors.js?v=1";
    private static final String DASHBOARD_CSS = "/css/orchestration.css?v=14";

    private final ProjectService projectService;
    private final JobService jobService;
    private final AgentProfileService agentProfileService;
    private final AssignmentService assignmentService;
    private final OutputArtifactService outputArtifactService;
    private final ShellTemplate dashboardShell;
    private final ProjectConsoleFragments fragments;
    private final EntityLookupService entityLookupService;
    private final EntitySelectorComponents entitySelectorComponents;
    private final AgentContextService agentContextService;
    private final AgentMaterializationService agentMaterializationService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final WorkspaceExplorerService workspaceExplorerService;
    private final WorkTaskService workTaskService;
    private final PlanService planService;
    private final ProjectOperationsAccessService projectAccessService;

    public ProjectConsoleController(ProjectService projectService,
                                    JobService jobService,
                                    AgentProfileService agentProfileService,
                                    AssignmentService assignmentService,
                                    OutputArtifactService outputArtifactService,
                                    ProjectConsoleFragments fragments,
                                    EntityLookupService entityLookupService,
                                    EntitySelectorComponents entitySelectorComponents,
                                    AgentContextService agentContextService,
                                    AgentMaterializationService agentMaterializationService,
                                    WorkspaceDirectoryService workspaceDirectoryService,
                                    WorkspaceExplorerService workspaceExplorerService,
                                    WorkTaskService workTaskService,
                                    PlanService planService,
                                    ProjectOperationsAccessService projectAccessService) {
        this.projectService = projectService;
        this.jobService = jobService;
        this.agentProfileService = agentProfileService;
        this.assignmentService = assignmentService;
        this.outputArtifactService = outputArtifactService;
        this.fragments = fragments;
        this.entityLookupService = entityLookupService;
        this.entitySelectorComponents = entitySelectorComponents;
        this.agentContextService = agentContextService;
        this.agentMaterializationService = agentMaterializationService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.workspaceExplorerService = workspaceExplorerService;
        this.workTaskService = workTaskService;
        this.planService = planService;
        this.projectAccessService = projectAccessService;
        this.dashboardShell = createShell(null);
    }

    private ShellTemplate createShell(String activePath) {
        return ShellBuilder.create()
            .withPageTitle("Magenta Projects")
            .withCustomCss(DASHBOARD_CSS)
            .withTopNav(AppNavigation.primaryTopNav())
            .withSideNav(AppNavigation.operationalSideNav(activePath), true)
            .buildTemplate();
    }

    private String renderPage(Component content, String activePath) {
        return createShell(activePath).renderWithContent(content);
    }

    // ════════════════════════════════════════════════════════════════
    //  Project pages
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects")
    @ResponseBody
    public String projects() {
        return renderPage(projectsPage(null), "/projects");
    }

    @GetMapping("/projects/{projectId}")
    @ResponseBody
    public String projectDetail(@PathVariable String projectId,
                                @RequestParam(value = "tab", required = false) String tab,
                                HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            return renderPage(projectsPage(projectId, selectedTabKey(tab)), "/projects/" + projectId);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return renderPage(projectsPage(null), "/projects");
        }
    }

    private Component projectsPage(String selectedProjectId) {
        return projectsPage(selectedProjectId, null);
    }

    private Component projectsPage(String selectedProjectId, String selectedTab) {
        Div detail = new Div().withId("project-detail-container");
        if (StringUtils.hasText(selectedProjectId)) {
            String detailUrl = "/projects/_detail/" + e(selectedProjectId)
                + (StringUtils.hasText(selectedTab) ? "?tab=" + e(selectedTab) : "");
            detail.withAttribute("hx-get", detailUrl)
                .withAttribute("hx-trigger", "load")
                .withAttribute("hx-swap", "innerHTML")
                .withChild(new Div().withClass("dashboard-empty").withInnerText("Loading..."));
        } else {
            detail.withChild(new Div().withClass("orch-panel")
                .withChild(new Div().withClass("dashboard-empty")
                    .withInnerText("Select a project from the list or create a new one.")));
        }
        return new Div()
            .withId("projects-page")
            .withAttribute("data-orchestration-page", "projects")
            .withAttribute("data-selected-project-id", nn(selectedProjectId))
            .withChild(Header.H1("Projects"))
            .withChild(new Paragraph("Project metadata, membership, materialized context, jobs, and outputs."))
            .withChild(new Div().withClass("browser-layout browser-layout-wide project-console-layout")
                .withChild(new Div().withClass("browser-sidebar project-chooser")
                    .withChild(new Div().withClass("browser-sidebar-header")
                        .withChild(Button.create("New Project").withClass("orch-primary")
                            .withAttribute("data-htmx-error-swap", "true")
                            .hxGet("/projects/_editor/_new")
                            .hxTarget("#project-detail-container").hxSwap("innerHTML")))
                    .withChild(new Div().withId("project-list").withClass("entity-list")
                        .hxGet("/projects/_list").hxTrigger("load").hxSwap("innerHTML")
                        .withChild(new Div().withClass("dashboard-empty").withInnerText("Loading..."))))
                .withChild(new Div().withClass("browser-detail").withChild(detail)))
            .withChild(moduleScript(HTMX_ERRORS_JS))
            .withChild(moduleScript(PROJECTS_JS));
    }

    // ════════════════════════════════════════════════════════════════
    //  Chooser list
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_list")
    @ResponseBody
    public String projectsListFragment() {
        return projectsList().render();
    }

    private Component projectsList() {
        List<Project> projects = projectService.listProjects();
        if (projects.isEmpty()) {
            return new Div().withClass("dashboard-empty").withInnerText("No projects.");
        }
        Div list = new Div();
        for (Project project : projects) {
            list.withChild(new HtmlTag("button").withClass("tool-item project-list-item")
                .withAttribute("data-project-id", project.id())
                .hxGet("/projects/_detail/" + e(project.id()))
                .hxTarget("#project-detail-container").hxSwap("innerHTML")
                .withAttribute("data-htmx-error-swap", "true")
                .withChild(new HtmlTag("strong").withInnerText(dv(project.name())))
                .withChild(new HtmlTag("span").withClass("orch-status-chip tracked").withInnerText("Tracked"))
                .withChild(new Paragraph(StringUtils.hasText(project.description())
                    ? project.description() : "No description")));
        }
        return list;
    }

    private String projectListOobFragment() {
        return new Div().withId("project-list").withAttribute("hx-swap-oob", "innerHTML")
            .withChild(projectsList()).render();
    }

    // ════════════════════════════════════════════════════════════════
    //  Create
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_editor/_new")
    @ResponseBody
    public String newProjectEditor() {
        return projectCreateFragment(Map.of(), null).render();
    }

    @PostMapping("/projects/_editor")
    @ResponseBody
    public String createProject(@RequestParam Map<String, String> params, HttpServletResponse response) {
        String name = params.getOrDefault("name", "").trim();
        if (name.isBlank()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return projectCreateFragment(params, "Name is required.").render();
        }
        try {
            Project created = projectService.createProject(name, nn(params.get("description")),
                nn(params.get("ownerAgentId")), null);
            return fragments.projectDetailShell(created).render() + projectListOobFragment();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return projectCreateFragment(params, e.getMessage()).render();
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return projectCreateFragment(params, e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Project creation failed", e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return projectCreateFragment(params, "Project creation is temporarily unavailable.").render();
        }
    }

    private Component projectCreateFragment(Map<String, String> values, String error) {
        Div container = new Div().withClass("orch-panel project-create-form");
        container.withChild(Header.H2("New Project"));
        if (StringUtils.hasText(error)) {
            container.withChild(new Div().withClass("orch-error").withInnerText(error));
        }
        Form form = Form.create().withHxPost("/projects/_editor");
        form.withClass("orch-form-stack").withAttribute("data-htmx-error-swap", "true");
        form.withHxTarget("#project-detail-container").withHxSwap("innerHTML")
            .withChild(label("Name", TextInput.create("name").withId("project-name")
                .withValue(values.getOrDefault("name", ""))))
            .withChild(label("Description", TextArea.create("description").withId("project-description")
                .withRows(4).withValue(values.getOrDefault("description", ""))))
            .withChild(label("Owner Agent", agentSelect("ownerAgentId",
                values.getOrDefault("ownerAgentId", "")).withId("project-owner-agent")))
            .withChild(new Div().withClass("tool-actions")
                .withChild(Button.create("Create Project").withClass("orch-primary")
                    .withAttribute("type", "submit")));
        return container.withChild(form);
    }

    // ════════════════════════════════════════════════════════════════
    //  Detail shell (replaces old flat detail)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}")
    @ResponseBody
    public String projectDetailFragment(@PathVariable String projectId,
                                        @RequestParam(value = "tab", required = false) String tab,
                                        HttpServletResponse response) {
        try {
            ProjectTab selected = fragments.findTab(selectedTabKey(tab)).orElseGet(fragments::defaultTab);
            return fragments.projectDetailShell(projectService.getProject(projectId), selected).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Dashboard (overview)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/dashboard")
    @ResponseBody
    public String projectDashboardTab(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("dashboard").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Dashboard", "overview", response);
        }
        try {
            Project project = projectService.getProject(projectId);
            List<ProjectAgentMembership> members = sortedProjectMembers(projectId);
            List<AssignmentService.AssignmentSummary> assignments
                = assignmentService.activeSummariesForProject(projectId);
            List<JobDefinition> jobs = projectJobs(projectId);
            String materializationCount = availableMaterializationCount(members);

            Div panel = new Div().withClass("project-dashboard");
            panel.withChild(new Div().withClass("project-dashboard-identity")
                .withChild(metaItem("Project", dv(project.name())))
                .withChild(metaItem("Record State", "Tracked"))
                .withChild(metaItem("Owner Agent", dv(project.ownerAgentId())))
                .withChild(metaItem("ID", project.id())));
            Div counters = new Div().withClass("agent-dashboard-counters project-dashboard-counters");
            counters.withChild(projectCounterCard("Members", String.valueOf(members.size()), projectId, "members"));
            counters.withChild(projectCounterCard("Active Assignments", String.valueOf(assignments.size()), projectId, "dashboard"));
            counters.withChild(projectCounterCard("Jobs", String.valueOf(jobs.size()), projectId, "jobs"));
            counters.withChild(projectCounterCard("Available Materializations", materializationCount, projectId, "files"));
            panel.withChild(counters);

            panel.withChild(sectionHeader("Member Agents", "Current project members and agent-local materialization health."));
            panel.withChild(projectMemberCards(projectId, members, false));
            panel.withChild(sectionHeader("Active Assignments", "Current assignments explicitly attributed to this project."));
            panel.withChild(projectAssignments(assignments));
            panel.withChild(sectionHeader("Recent Jobs", "Most recently updated project-attributed job definitions."));
            panel.withChild(projectJobRows(jobs.stream().limit(5).toList(), false));
            return panel.render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        } catch (RuntimeException e) {
            log.warn("Project dashboard unavailable for {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.errorUnavailable("Project dashboard is temporarily unavailable.");
        }
    }

    private Component projectCounterCard(String label, String value, String projectId, String tab) {
        return new Div().withClass("agent-counter-card")
            .withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/" + tab)
            .withAttribute("hx-target", "#project-tab-panel").withAttribute("hx-swap", "innerHTML")
            .withChild(new HtmlTag("strong").withInnerText(value))
            .withChild(new HtmlTag("span").withInnerText(label));
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Members (admin)
    // ════════════════════════════════════════════════════════════════

    @GetMapping({"/projects/_detail/{projectId}/members", "/projects/_detail/{projectId}/agents"})
    @ResponseBody
    public String projectMembersTab(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("members").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Members", "admin", response);
        }
        return projectMembersTabInternal(projectId, Map.of(), null, response);
    }

    private String projectMembersTabInternal(String projectId, Map<String, String> values, String error,
                                             HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            Div panel = new Div().withClass("project-members-tab");
            panel.withChild(Header.H2("Members"));
            if (StringUtils.hasText(error)) panel.withChild(new Div().withClass("orch-error").withInnerText(error));
            Div controls = new Div().withId("project-member-add-controls").withClass("orch-form-inline");
            controls.withChild(entitySelector("agentId", EntityKind.AGENT, values.get("agentId"),
                "Agent", "agent", true, Map.of("projectId", projectId)));
            Select role = Select.create("role");
            String selectedRole = values.getOrDefault("role", "member");
            role.addOption("member", "Member", "member".equals(selectedRole));
            role.addOption("maintainer", "Maintainer", "maintainer".equals(selectedRole));
            role.addOption("viewer", "Viewer", "viewer".equals(selectedRole));
            controls.withChild(role).withChild(Button.create("Add Member").withClass("orch-primary")
                .withAttribute("type", "button")
                .withAttribute("hx-post", "/projects/_detail/" + projectId + "/members")
                .withAttribute("hx-target", "#project-tab-panel").withAttribute("hx-swap", "innerHTML")
                .withAttribute("hx-include", "#project-member-add-controls")
                .withAttribute("data-htmx-error-swap", "true"));
            panel.withChild(controls);
            panel.withChild(projectMemberCards(projectId, sortedProjectMembers(projectId), true));
            return panel.render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    @PostMapping({"/projects/_detail/{projectId}/members", "/projects/_detail/{projectId}/agents"})
    @ResponseBody
    public String addProjectAgent(@PathVariable String projectId, @RequestParam Map<String, String> params,
                                  HttpServletResponse response) {
        try {
            validateSelectedEntity(EntityKind.AGENT, params.get("agentId"), true, "Agent");
            projectService.addAgent(projectId, params.get("agentId"), nn(params.get("role")));
            return projectMembersTabInternal(projectId, Map.of(), null, response);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return projectMembersTabInternal(projectId, params, e.getMessage(), response);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return projectMembersTabInternal(projectId, params, e.getMessage(), response);
        }
    }

    @DeleteMapping({"/projects/_detail/{projectId}/members/{agentId}",
        "/projects/_detail/{projectId}/agents/{agentId}"})
    @ResponseBody
    public String removeProjectAgent(@PathVariable String projectId, @PathVariable String agentId,
                                     HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            if (!projectService.isMember(projectId, agentId)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return projectMembersTabInternal(projectId, Map.of(), "Project member not found.", response);
            }
            projectService.removeAgent(projectId, agentId);
            return projectMembersTabInternal(projectId, Map.of(), null, response);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return projectMembersTabInternal(projectId, Map.of(), e.getMessage(), response);
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return projectMembersTabInternal(projectId, Map.of(), e.getMessage(), response);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Files (context)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/files")
    @ResponseBody
    public String projectFilesTab(@PathVariable String projectId,
                                  @RequestParam(required = false) String agentId,
                                  @RequestParam(required = false) Integer generation,
                                  HttpServletResponse response) {
        if (!fragments.findTab("files").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Files", "context", response);
        }
        try {
            projectService.getProject(projectId);
            List<ProjectAgentMembership> members = sortedProjectMembers(projectId);
            Div panel = new Div().withClass("project-files-tab").withChild(Header.H2("Files"))
                .withChild(new Paragraph("Read-only agent-local project materializations. Lifecycle actions remain on the owning agent Context tab."));
            if (members.isEmpty()) {
                return panel.withChild(new Div().withClass("dashboard-empty")
                    .withInnerText("No project members are available.")).render();
            }
            if (agentContextService == null || agentMaterializationService == null) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return panel.withChild(new Div().withClass("orch-error")
                    .withInnerText("Materialization facts are unavailable.")).render();
            }
            List<MaterializationRecord> records = members.stream()
                .map(member -> exactProjectMaterialization(member.agentId(), projectId))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(MaterializationRecord::agentId)).toList();
            if (records.isEmpty()) {
                return panel.withChild(new Div().withClass("project-files-state")
                    .withChild(Header.H3("No materialization record"))
                    .withChild(new Paragraph("No current member has materialized this project. Use an agent Context tab to provision one."))).render();
            }
            MaterializationRecord selected = records.stream()
                .filter(r -> r.agentId().equals(agentId)).findFirst().orElse(null);
            if (StringUtils.hasText(agentId) && selected == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return panel.withChild(new Div().withClass("orch-error")
                    .withInnerText("Selected member materialization was not found.")).render();
            }
            if (selected == null) selected = records.get(0);
            panel.withChild(projectMaterializationSelector(projectId, records, selected.agentId()));
            panel.withChild(new RawHtml(projectMaterializationContent(projectId, selected, generation, ".", response)));
            return panel.render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        } catch (RuntimeException e) {
            log.warn("Project files unavailable for {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.errorUnavailable("Project files are temporarily unavailable.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/files/list")
    @ResponseBody
    public String projectMaterializationList(@PathVariable String projectId,
                                             @RequestParam String agentId,
                                             @RequestParam int generation,
                                             @RequestParam(defaultValue = ".") String path,
                                             HttpServletResponse response) {
        if (!fragments.findTab("files").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Files", "context", response);
        }
        try {
            projectService.getProject(projectId);
            if (!projectService.isMember(projectId, agentId)) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return new Div().withClass("orch-error").withInnerText("Project member not found.").render();
            }
            MaterializationRecord record = exactProjectMaterialization(agentId, projectId);
            if (record == null) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return new Div().withClass("orch-error").withInnerText("Materialization record not found.").render();
            }
            if (record.generation() != generation) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return WorkspaceExplorerFragments.projectMaterializationState("Stale generation",
                    "The selected materialization changed. Refresh the Files tab before reading it.", "orch-error");
            }
            if (record.state() != MaterializationState.READY) {
                response.setStatus(HttpServletResponse.SC_CONFLICT);
                return WorkspaceExplorerFragments.projectMaterializationState("Materialization unavailable",
                    "The selected materialization is " + record.state().name() + ".", "orch-error");
            }
            MaterializationLocation location = resolveProjectMaterialization(record, generation);
            WorkspaceExplorerService.DirectoryListing listing = workspaceExplorerService.list(location,
                workspaceExplorerService.normalizeRelativePath(path));
            return WorkspaceExplorerFragments.projectMaterializationList(projectId, agentId, generation, listing);
        } catch (WorkspaceFileException e) {
            response.setStatus(fileErrorStatus(e));
            return new Div().withClass("orch-error").withInnerText(e.getMessage()).render();
        } catch (RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return new Div().withClass("orch-error").withInnerText("Materialization read is unavailable.").render();
        }
    }

    @GetMapping("/projects/_detail/{projectId}/files/preview")
    @ResponseBody
    public String projectMaterializationPreview(@PathVariable String projectId,
                                                @RequestParam String agentId,
                                                @RequestParam int generation,
                                                @RequestParam String path,
                                                HttpServletResponse response) {
        if (!fragments.findTab("files").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Files", "context", response);
        }
        try {
            projectService.getProject(projectId);
            if (!projectService.isMember(projectId, agentId))
                throw new IllegalArgumentException("Project member not found");
            MaterializationRecord record = exactProjectMaterialization(agentId, projectId);
            if (record == null) throw new IllegalArgumentException("Materialization not found");
            MaterializationLocation location = resolveProjectMaterialization(record, generation);
            WorkspaceExplorerService.FilePreview preview = workspaceExplorerService.preview(location,
                workspaceExplorerService.normalizeRelativePath(path));
            return WorkspaceExplorerFragments.projectMaterializationInspector(preview, null);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return WorkspaceExplorerFragments.projectMaterializationInspector(null,
                "Project member, materialization, or file was not found.");
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return new Div().withClass("orch-error").withInnerText(e.getMessage()).render();
        } catch (WorkspaceFileException e) {
            response.setStatus(fileErrorStatus(e));
            return new Div().withClass("orch-error").withInnerText(e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Project materialization preview unavailable for {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkspaceExplorerFragments.projectMaterializationInspector(null,
                "Materialization preview is temporarily unavailable.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Jobs (work)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/jobs")
    @ResponseBody
    public String projectJobsFragment(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("jobs").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Jobs", "work", response);
        }
        try {
            projectService.getProject(projectId);
            Div panel = new Div().withClass("project-jobs-tab").withChild(Header.H2("Jobs"));
            return panel.withChild(projectJobRows(projectJobs(projectId), true)).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        } catch (RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.errorUnavailable("Project jobs are unavailable.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Outputs (work)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/outputs")
    @ResponseBody
    public String projectOutputsFragment(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("outputs").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Outputs", "work", response);
        }
        try {
            projectService.getProject(projectId);
            List<RunOutputArtifact> artifacts = queryOutputs(projectId, 40);
            Div panel = new Div().withClass("project-outputs-tab").withChild(Header.H2("Outputs"));
            if (artifacts.isEmpty()) {
                return panel.withChild(new Div().withClass("dashboard-empty")
                    .withInnerText("No project-attributed outputs.")).render();
            }
            Div rows = new Div().withClass("project-operational-list");
            for (RunOutputArtifact artifact : artifacts) {
                Div row = new Div().withClass("orch-row")
                    .withChild(new HtmlTag("strong").withInnerText(dv(artifact.outputName())))
                    .withChild(new HtmlTag("span").withInnerText(outputCompactContext(artifact)));
                if (StringUtils.hasText(artifact.workspaceType()) && StringUtils.hasText(artifact.workspaceKey())) {
                    row.withChild(new HtmlTag("a").withClass("button button-secondary small")
                        .withAttribute("href", "/api/artifacts/" + e(artifact.id()) + "/download?workspaceKind="
                            + URLEncoder.encode(artifact.workspaceType(), StandardCharsets.UTF_8) + "&workspaceKey="
                            + URLEncoder.encode(artifact.workspaceKey(), StandardCharsets.UTF_8))
                        .withInnerText("Download"));
                } else {
                    row.withChild(new HtmlTag("span").withClass("orch-chip").withInnerText("Download unavailable"));
                }
                rows.withChild(row);
            }
            return panel.withChild(rows).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        } catch (RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.errorUnavailable("Project outputs are unavailable.");
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Settings (admin)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/settings")
    @ResponseBody
    public String projectSettingsTab(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("settings").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Settings", "admin", response);
        }
        try {
            return projectSettingsFragment(projectService.getProject(projectId), Map.of(), null);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    @PutMapping("/projects/_editor/{projectId}")
    @ResponseBody
    public String updateProject(@PathVariable String projectId, @RequestParam Map<String, String> params,
                                HttpServletResponse response) {
        Project existing;
        try {
            existing = projectService.getProject(projectId);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
        if (!StringUtils.hasText(params.get("name"))) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return projectSettingsFragment(existing, params, "Name is required.");
        }
        try {
            Project updated = projectService.updateProject(projectId, params.get("name"),
                params.get("description"), null, params.get("workTypeProfile"), params.get("model"), null);
            return projectSettingsFragment(updated, Map.of(), "Project saved.") + projectListOobFragment();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return projectSettingsFragment(existing, params, e.getMessage());
        } catch (RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return projectSettingsFragment(existing, params, "Project settings are temporarily unavailable.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/delete-confirm")
    @ResponseBody
    public String projectDeleteConfirmation(@PathVariable String projectId, HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            return new Div().withId("project-settings-delete").withClass("project-delete-confirm")
                .withChild(new Paragraph("Delete " + dv(project.name()) + "? This removes project metadata and memberships."))
                .withChild(new Div().withId("project-delete-status"))
                .withChild(Button.create("Confirm Delete").withClass("orch-danger")
                    .withAttribute("hx-delete", "/projects/" + project.id())
                    .withAttribute("hx-target", "#project-delete-status").withAttribute("hx-swap", "innerHTML")
                    .withAttribute("data-htmx-error-swap", "true"))
                .render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    @DeleteMapping("/projects/{projectId}")
    @ResponseBody
    public String deleteProject(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.deleteProject(projectId);
            return new Div().withClass("orch-status").withInnerText("Project deleted.").render()
                + new Div().withId("project-detail-container").withAttribute("hx-swap-oob", "innerHTML").render()
                + projectListOobFragment();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return new Div().withClass("orch-error").withInnerText(e.getMessage()).render();
        } catch (IllegalStateException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return new Div().withClass("orch-error").withInnerText(e.getMessage()).render();
        } catch (RuntimeException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return new Div().withClass("orch-error").withInnerText("Project deletion is temporarily unavailable.").render();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Tab: Tasks (work)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/tasks")
    @ResponseBody
    public String projectTasksTab(@PathVariable String projectId, HttpServletResponse response) {
        if (!fragments.findTab("tasks").orElseThrow().available()) {
            return unavailableProjectTab(projectId, "Tasks", "work", response);
        }
        try {
            Project project = projectService.getProject(projectId);
            if (projectAccessService == null || !projectAccessService.isTrustedDeployment()) {
                return WorkTaskFragments.errorUnavailable(
                    "Task controls are unavailable until trusted deployment capability is enabled.").render();
            }
            List<WorkTaskDefinition> tasks = workTaskService.listByProject(projectId);
            List<WorkTaskFragments.WorkTaskRow> rows = tasks.stream()
                .map(t -> taskToRow(t, project.archived()))
                .toList();
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            Div panel = new Div().withClass("project-tasks-tab");
            panel.withChild(WorkTaskFragments.taskListPanel(projectId, rows, agentOptions));
            panel.withChild(WorkTaskFragments.editorHost());
            panel.withChild(WorkTaskFragments.submitHost());
            panel.withChild(WorkTaskFragments.detailHost());
            return panel.render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        } catch (RuntimeException e) {
            log.warn("Project tasks unavailable for {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return fragments.errorUnavailable("Project tasks are temporarily unavailable.");
        }
    }

    // ── Task editor (create) ──

    @GetMapping("/projects/_detail/{projectId}/tasks/_editor/_new")
    @ResponseBody
    public String taskCreateForm(@PathVariable String projectId, HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.createForm(projectId, Map.of(), null, agentOptions).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    @GetMapping("/projects/_detail/{projectId}/tasks/_editor/_cancel")
    @ResponseBody
    public String taskEditorCancel(@PathVariable String projectId,
                                   @RequestParam(defaultValue = "editor") String host) {
        return "submit".equals(host)
            ? WorkTaskFragments.submitHost().render()
            : WorkTaskFragments.editorHost().render();
    }

    @PostMapping("/projects/_detail/{projectId}/tasks/_editor")
    @ResponseBody
    public String createTask(@PathVariable String projectId,
                              @RequestParam Map<String, String> params,
                              HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            String title = params.getOrDefault("title", "").trim();
            String description = params.get("description");
            int priority = parseIntOrDefault(params.get("defaultPriority"), 0);
            boolean pin = "true".equals(params.get("pin"));
            String preferredAgentId = cleanNullable(params.get("preferredAgentId"));

            workTaskService.createOperation(requireOperationKey(params), title, description, projectId,
                pin, priority, preferredAgentId);

            // Re-render the task list with OOB swap
            List<WorkTaskDefinition> tasks = workTaskService.listByProject(projectId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskFragments.WorkTaskRow> rows = tasks.stream()
                .map(t -> taskToRow(t, project.archived()))
                .toList();
            return WorkTaskFragments.editorHost().render()
                + WorkTaskFragments.taskList(projectId, rows).render()
                + WorkTaskFragments.statusMessage("Task created.", false).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.createForm(projectId, params, e.getMessage(), agentOptions).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.createForm(projectId, params, e.getMessage(), agentOptions).render();
        } catch (RuntimeException e) {
            log.warn("Task creation failed for project {}", projectId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.createForm(projectId, params,
                "Task creation is temporarily unavailable.", agentOptions).render();
        }
    }

    // ── Task detail ──

    @GetMapping("/projects/_detail/{projectId}/tasks/{taskId}")
    @ResponseBody
    public String taskDetail(@PathVariable String projectId, @PathVariable String taskId,
                              HttpServletResponse response) {
        try {
            Project project = projectService.getProject(projectId);
            WorkTaskDefinition task = validateProjectTask(taskId, projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(a -> attemptToDisplay(a))
                .toList();
            boolean planAvailable = task.promotedPlanId() != null
                && planService != null && isPlanExecutable(task.promotedPlanId());
            return WorkTaskFragments.taskDetail(projectId, task, attempts, displays,
                project.archived(), planAvailable).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return WorkTaskFragments.errorNotFound(e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Task detail unavailable for {} / {}", projectId, taskId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkTaskFragments.errorUnavailable("Task detail is temporarily unavailable.").render();
        }
    }

    // ── Task edit ──

    @GetMapping("/projects/_detail/{projectId}/tasks/{taskId}/edit")
    @ResponseBody
    public String taskEditForm(@PathVariable String projectId, @PathVariable String taskId,
                                HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            WorkTaskDefinition task = validateProjectTask(taskId, projectId);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.editForm(projectId, task, Map.of(), null, agentOptions).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return WorkTaskFragments.errorNotFound(e.getMessage()).render();
        }
    }

    @PutMapping("/projects/_detail/{projectId}/tasks/{taskId}")
    @ResponseBody
    public String updateTask(@PathVariable String projectId, @PathVariable String taskId,
                              @RequestParam Map<String, String> params,
                              HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            int expectedVersion = parseIntOrDefault(params.get("expectedVersion"), 0);
            String title = cleanNullable(params.get("title"));
            String description = params.get("description");
            Integer priority = params.containsKey("defaultPriority")
                ? parseIntOrDefault(params.get("defaultPriority"), 0) : null;
            Boolean pin = params.containsKey("pin") ? "true".equals(params.get("pin")) : null;
            String preferredAgentId = params.containsKey("preferredAgentId")
                ? cleanNullable(params.get("preferredAgentId")) : null;

            workTaskService.editOperation(requireOperationKey(params), taskId, expectedVersion,
                title, description, priority, pin, preferredAgentId);

            List<WorkTaskDefinition> tasks = workTaskService.listByProject(projectId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskFragments.WorkTaskRow> rows = tasks.stream()
                .map(t -> taskToRow(t, project.archived()))
                .toList();
            return WorkTaskFragments.editorHost().render()
                + WorkTaskFragments.taskList(projectId, rows).render()
                + WorkTaskFragments.statusMessage("Task saved.", false).render();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int status = msg.contains("version mismatch") || msg.contains("promoted")
                ? HttpServletResponse.SC_CONFLICT : HttpServletResponse.SC_BAD_REQUEST;
            response.setStatus(status);
            return editErrorFragment(projectId, taskId, params, e.getMessage());
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return editErrorFragment(projectId, taskId, params, e.getMessage());
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return editErrorFragment(projectId, taskId, params, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Task edit failed for {} / {}", projectId, taskId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return editErrorFragment(projectId, taskId, params,
                "Task editing is temporarily unavailable.");
        }
    }

    private String editErrorFragment(String projectId, String taskId,
                                      Map<String, String> params, String error) {
        try {
            WorkTaskDefinition task = workTaskService.getById(taskId);
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.editForm(projectId, task, params, error, agentOptions).render();
        } catch (RuntimeException ex) {
            return WorkTaskFragments.errorBadRequest(error).render();
        }
    }

    // ── Task promote ──

    @PostMapping("/projects/_detail/{projectId}/tasks/{taskId}/promote")
    @ResponseBody
    public String promoteTask(@PathVariable String projectId, @PathVariable String taskId,
                               @RequestParam int expectedVersion,
                               @RequestParam(required = false) String operationKey,
                               HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            validateProjectTask(taskId, projectId);
            workTaskService.promoteOperation(requireOperationKey(operationKey), taskId, expectedVersion);

            // Re-render detail
            WorkTaskDefinition task = workTaskService.getById(taskId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(a -> attemptToDisplay(a))
                .toList();
            boolean planAvailable = isPlanExecutable(task.promotedPlanId());
            return WorkTaskFragments.taskDetail(projectId, task, attempts, displays,
                project.archived(), planAvailable).render()
                + WorkTaskFragments.statusMessage("Task promoted to Plan.", false).render();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int status = msg.contains("already been promoted") || msg.contains("version mismatch")
                || msg.contains("contradictory")
                ? HttpServletResponse.SC_CONFLICT : HttpServletResponse.SC_BAD_REQUEST;
            response.setStatus(status);
            return renderTaskDetailSafely(projectId, taskId)
                + WorkTaskFragments.statusMessage(e.getMessage(), true).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return WorkTaskFragments.errorBadRequest(e.getMessage()).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkTaskFragments.errorUnavailable(e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Task promote failed for {} / {}", projectId, taskId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return renderTaskDetailSafely(projectId, taskId)
                + WorkTaskFragments.statusMessage("Promotion is temporarily unavailable.", true).render();
        }
    }

    // ── Task submit ──

    @GetMapping("/projects/_detail/{projectId}/tasks/{taskId}/submit")
    @ResponseBody
    public String taskSubmitForm(@PathVariable String projectId, @PathVariable String taskId,
                                  HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            WorkTaskDefinition task = validateProjectTask(taskId, projectId);
            if (task.promotedPlanId() == null) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return WorkTaskFragments.errorBadRequest("Task must be promoted before submission.").render();
            }
            List<WorkTaskFragments.AgentOption> agentOptions = buildAgentOptions(projectId);
            return WorkTaskFragments.submitForm(projectId, task, agentOptions, Map.of(), null).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return WorkTaskFragments.errorNotFound(e.getMessage()).render();
        }
    }

    @PostMapping("/projects/_detail/{projectId}/tasks/{taskId}/submit")
    @ResponseBody
    public String submitTask(@PathVariable String projectId, @PathVariable String taskId,
                              @RequestParam Map<String, String> params,
                              HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            WorkTaskDefinition task = validateProjectTask(taskId, projectId);
            String agentId = params.getOrDefault("agentId", "").trim();
            String runDisplayName = cleanNullable(params.get("runDisplayName"));
            String workspaceType = cleanNullable(params.get("workspaceType"));
            String workspaceKey = cleanNullable(params.get("workspaceKey"));
            String operationKey = cleanNullable(params.get("operationKey"));

            workTaskService.submitOperation(requireOperationKey(operationKey), taskId, agentId,
                runDisplayName, workspaceType, workspaceKey);

            // Re-render detail
            WorkTaskDefinition updated = workTaskService.getById(taskId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(a -> attemptToDisplay(a))
                .toList();
            boolean planAvailable = isPlanExecutable(task.promotedPlanId());
            return WorkTaskFragments.submitHost().render()
                + WorkTaskFragments.taskDetail(projectId, updated, attempts, displays,
                    project.archived(), planAvailable)
                    .withAttribute("hx-swap-oob", "outerHTML").render()
                + WorkTaskFragments.statusMessage("Task submitted for execution.", false).render();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int status = msg.contains("not promoted") ? HttpServletResponse.SC_BAD_REQUEST
                : msg.contains("already has an active assignment") ? HttpServletResponse.SC_CONFLICT
                : HttpServletResponse.SC_BAD_REQUEST;
            response.setStatus(status);
            return WorkTaskFragments.submitHost().render()
                + renderTaskDetailOob(projectId, taskId)
                + WorkTaskFragments.statusMessage(e.getMessage(), true).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            try {
                WorkTaskDefinition task = workTaskService.getById(taskId);
                var opts = buildAgentOptions(projectId);
                return WorkTaskFragments.submitForm(projectId, task, opts, params, e.getMessage()).render();
            } catch (RuntimeException ex) {
                return WorkTaskFragments.errorBadRequest(e.getMessage()).render();
            }
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkTaskFragments.errorUnavailable(e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Task submit failed for {} / {}", projectId, taskId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkTaskFragments.submitHost().render()
                + renderTaskDetailOob(projectId, taskId)
                + WorkTaskFragments.statusMessage("Submission is temporarily unavailable.", true).render();
        }
    }

    // ── Task retry ──

    @PostMapping("/projects/_detail/{projectId}/tasks/{taskId}/retry")
    @ResponseBody
    public String retryTask(@PathVariable String projectId, @PathVariable String taskId,
                             @RequestParam(required = false) String operationKey,
                             HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            validateProjectTask(taskId, projectId);
            workTaskService.retryOperation(requireOperationKey(operationKey), taskId);

            WorkTaskDefinition task = workTaskService.getById(taskId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(a -> attemptToDisplay(a))
                .toList();
            boolean planAvailable = isPlanExecutable(task.promotedPlanId());
            return WorkTaskFragments.taskDetail(projectId, task, attempts, displays,
                project.archived(), planAvailable).render()
                + WorkTaskFragments.statusMessage("Retry submitted.", false).render();
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            int status = msg.contains("No attempts exist") ? HttpServletResponse.SC_NOT_FOUND
                : msg.contains("not terminal") ? HttpServletResponse.SC_CONFLICT
                : HttpServletResponse.SC_BAD_REQUEST;
            response.setStatus(status);
            return renderTaskDetailSafely(projectId, taskId)
                + WorkTaskFragments.statusMessage(e.getMessage(), true).render();
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return renderTaskDetailSafely(projectId, taskId)
                + WorkTaskFragments.statusMessage(e.getMessage(), true).render();
        } catch (ProjectAccessDeniedException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return WorkTaskFragments.errorUnavailable(e.getMessage()).render();
        } catch (RuntimeException e) {
            log.warn("Task retry failed for {} / {}", projectId, taskId, e);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return renderTaskDetailSafely(projectId, taskId)
                + WorkTaskFragments.statusMessage("Retry is temporarily unavailable.", true).render();
        }
    }

    // ── Task helper methods ──

    private WorkTaskDefinition validateProjectTask(String taskId, String projectId) {
        WorkTaskDefinition task = workTaskService.getById(taskId);
        if (task.projectId() == null || !task.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Task " + taskId + " is not in project " + projectId);
        }
        return task;
    }

    private WorkTaskFragments.WorkTaskRow taskToRow(WorkTaskDefinition t, boolean archived) {
        String status = null;
        try {
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(t.id());
            if (t.designatedAttemptId() != null) {
                WorkTaskAttempt designated = attempts.stream()
                    .filter(attempt -> t.designatedAttemptId().equals(attempt.id()))
                    .findFirst().orElse(null);
                try {
                    var assignment = designated == null ? null : assignmentService.get(designated.assignmentId());
                    if (assignment == null) return new WorkTaskFragments.WorkTaskRow(
                        t.id(), t.title(), t.description(), t.projectId(), t.pin(),
                        t.defaultPriority(), t.preferredAgentId(), t.promotedPlanId(),
                        t.definitionVersion(), null, archived, t.updatedAt());
                    status = assignment.status() == null ? null : assignment.status().name();
                } catch (RuntimeException ignored) {}
            }
        } catch (RuntimeException ignored) {}
        return new WorkTaskFragments.WorkTaskRow(
            t.id(), t.title(), t.description(), t.projectId(), t.pin(),
            t.defaultPriority(), t.preferredAgentId(), t.promotedPlanId(),
            t.definitionVersion(), status, archived, t.updatedAt());
    }

    private WorkTaskFragments.AttemptDisplay attemptToDisplay(WorkTaskAttempt a) {
        String assignmentStatus = null;
        try {
            var assignment = assignmentService.get(a.assignmentId());
            assignmentStatus = assignment.status() == null ? null : assignment.status().name();
        } catch (RuntimeException ignored) {}
        return new WorkTaskFragments.AttemptDisplay(
            a.assignmentId(), a.attemptOrder(), assignmentStatus);
    }

    private boolean isPlanExecutable(String planId) {
        if (planService == null || planId == null) return false;
        try {
            PlanDefinition plan = planService.getTask(planId);
            return plan.status() == PlanStatus.APPROVED;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private List<WorkTaskFragments.AgentOption> buildAgentOptions(String projectId) {
        return projectService.listMembers(projectId).stream()
            .map(m -> new WorkTaskFragments.AgentOption(m.agentId(), m.agentId()))
            .toList();
    }

    private String renderTaskDetailOob(String projectId, String taskId) {
        try {
            WorkTaskDefinition task = workTaskService.getById(taskId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(this::attemptToDisplay).toList();
            return WorkTaskFragments.taskDetail(projectId, task, attempts, displays,
                project.archived(), isPlanExecutable(task.promotedPlanId()))
                .withAttribute("hx-swap-oob", "outerHTML").render();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String renderTaskDetailSafely(String projectId, String taskId) {
        try {
            WorkTaskDefinition task = workTaskService.getById(taskId);
            Project project = projectService.getProject(projectId);
            List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
            List<WorkTaskFragments.AttemptDisplay> displays = attempts.stream()
                .map(a -> attemptToDisplay(a))
                .toList();
            boolean planAvailable = isPlanExecutable(task.promotedPlanId());
            return WorkTaskFragments.taskDetail(projectId, task, attempts, displays,
                project.archived(), planAvailable).render();
        } catch (RuntimeException ex) {
            return WorkTaskFragments.errorUnavailable("Unable to render task detail.").render();
        }
    }

    private String requireOperationKey(Map<String, String> params) {
        return requireOperationKey(params.get("operationKey"));
    }

    private String requireOperationKey(String operationKey) {
        if (!StringUtils.hasText(operationKey)) {
            throw new IllegalArgumentException("operationKey is required");
        }
        return operationKey.trim();
    }

    private String cleanNullable(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int parseIntOrDefault(String value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Unavailable tabs (future phases)
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/projects/_detail/{projectId}/chat")
    @ResponseBody
    public String projectChatTab(@PathVariable String projectId, HttpServletResponse response) {
        return unavailableProjectTab(projectId, "Chat", "context", response);
    }

    @GetMapping("/projects/_detail/{projectId}/schedules")
    @ResponseBody
    public String projectSchedulesTab(@PathVariable String projectId, HttpServletResponse response) {
        return unavailableProjectTab(projectId, "Schedules", "automation", response);
    }

    @GetMapping("/projects/_detail/{projectId}/events")
    @ResponseBody
    public String projectEventsTab(@PathVariable String projectId, HttpServletResponse response) {
        return unavailableProjectTab(projectId, "Events", "automation", response);
    }

    // ════════════════════════════════════════════════════════════════
    //  Shared rendering helpers
    // ════════════════════════════════════════════════════════════════

    private String selectedTabKey(String requestedTab) {
        return fragments.findTab(requestedTab).map(ProjectTab::key)
            .orElseGet(() -> fragments.defaultTab().key());
    }

    private String unavailableProjectTab(String projectId, String label, String group,
                                         HttpServletResponse response) {
        try {
            projectService.getProject(projectId);
            return fragments.unavailableTab(label, group);
        } catch (IllegalArgumentException e) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return fragments.errorNotFound("Project not found.");
        }
    }

    private List<ProjectAgentMembership> sortedProjectMembers(String projectId) {
        return projectService.listMembers(projectId).stream()
            .sorted(Comparator.comparing(ProjectAgentMembership::agentId,
                Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    private List<JobDefinition> projectJobs(String projectId) {
        return jobService.listDefinitions(null, projectId, null).stream()
            .sorted(Comparator.comparing(JobDefinition::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(JobDefinition::id))
            .limit(40).toList();
    }

    private Component projectMemberCards(String projectId, List<ProjectAgentMembership> members, boolean removable) {
        if (members.isEmpty()) return new Div().withClass("dashboard-empty").withInnerText("No project members yet.");
        Div cards = new Div().withClass("project-member-grid");
        for (ProjectAgentMembership member : members) {
            AgentProfile profile = null;
            try { profile = agentProfileService.get(member.agentId()); } catch (RuntimeException ignored) { }
            String state = materializationState(member.agentId(), projectId);
            Div card = new Div().withClass("project-member-card")
                .withChild(new Div().withClass("project-member-card-heading")
                    .withChild(new HtmlTag("a").withAttribute("href", "/agents/" + e(member.agentId()))
                        .withInnerText(profile != null ? dv(profile.name()) : dv(member.agentId())))
                    .withChild(statusBadgeHtml(profile != null && profile.status() != null
                        ? profile.status().name() : "unavailable")))
                .withChild(new Div().withClass("orch-meta")
                    .withChild(new HtmlTag("span").withInnerText("ID: " + dv(member.agentId())))
                    .withChild(new HtmlTag("span").withInnerText("Role: " + dv(member.role())))
                    .withChild(new HtmlTag("span").withInnerText("Materialization: " + state)));
            if (removable) {
                card.withChild(Button.create("Remove").withClass("orch-danger")
                    .withAttribute("hx-delete", "/projects/_detail/" + e(projectId)
                        + "/members/" + e(member.agentId()))
                    .withAttribute("hx-confirm", "Remove this project member?")
                    .withAttribute("hx-target", "#project-tab-panel")
                    .withAttribute("hx-swap", "innerHTML")
                    .withAttribute("data-htmx-error-swap", "true"));
            }
            cards.withChild(card);
        }
        return cards;
    }

    private String materializationState(String agentId, String projectId) {
        if (agentContextService == null) return "Unavailable";
        try {
            MaterializationRecord record = exactProjectMaterialization(agentId, projectId);
            return record == null ? "Not provisioned" : record.state().name();
        } catch (RuntimeException e) {
            return "Unavailable";
        }
    }

    private String availableMaterializationCount(List<ProjectAgentMembership> members) {
        if (agentContextService == null || agentMaterializationService == null) return "Unavailable";
        int count = 0;
        try {
            for (ProjectAgentMembership member : members) {
                MaterializationRecord record = exactProjectMaterialization(member.agentId(), member.projectId());
                if (record == null || record.state() != MaterializationState.READY) continue;
                WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, member.agentId());
                agentMaterializationService.resolveReadyLocation(descriptor, record.materializationKey(), record.generation());
                count++;
            }
            return String.valueOf(count);
        } catch (RuntimeException e) {
            return "Unavailable";
        }
    }

    private Component projectAssignments(List<AssignmentService.AssignmentSummary> assignments) {
        if (assignments.isEmpty()) return new Div().withClass("dashboard-empty").withInnerText("No active assignments.");
        Div rows = new Div().withClass("project-operational-list");
        assignments.stream().sorted(Comparator.comparing(AssignmentService.AssignmentSummary::updatedAt,
            Comparator.nullsLast(Comparator.reverseOrder()))).limit(10).forEach(assignment ->
            rows.withChild(new Div().withClass("orch-row")
                .withChild(new HtmlTag("strong").withInnerText(dv(assignment.id())))
                .withChild(new HtmlTag("span").withInnerText(dv(assignment.agentId())))
                .withChild(statusBadgeHtml(assignment.status() == null ? null : assignment.status().name()))));
        return rows;
    }

    private Component projectJobRows(List<JobDefinition> jobs, boolean includeRuns) {
        if (jobs.isEmpty()) return new Div().withClass("dashboard-empty").withInnerText("No project-attributed jobs.");
        Div rows = new Div().withClass("project-operational-list");
        for (JobDefinition job : jobs) {
            Div row = new Div().withClass("project-job-row")
                .withChild(new Div().withClass("project-job-main")
                    .withChild(new HtmlTag("a").withAttribute("href", "/jobs/" + e(job.id()))
                        .withInnerText(StringUtils.hasText(job.title()) ? job.title() : job.id()))
                    .withChild(statusBadgeHtml(job.status()))
                    .withChild(new HtmlTag("span").withClass("workspace-file-muted")
                        .withInnerText(job.updatedAt() == null ? "Updated unavailable" : formatSince(job.updatedAt()))));
            if (includeRuns) {
                row.withChild(new Div().withClass("project-job-runs")
                    .withChild(new HtmlTag("span")
                        .withInnerText("Bounded project run facts are unavailable from the current job query contract.")));
            }
            rows.withChild(row);
        }
        return rows;
    }

    private Component projectMaterializationSelector(String projectId, List<MaterializationRecord> records,
                                                      String selectedAgentId) {
        Div selector = new Div().withClass("project-materialization-selector");
        for (MaterializationRecord record : records) {
            Button button = Button.create(record.agentId() + " · " + record.state().name());
            if (record.agentId().equals(selectedAgentId)) button.withClass("active");
            button.withAttribute("hx-get", "/projects/_detail/" + e(projectId) + "/files?agentId="
                    + URLEncoder.encode(record.agentId(), StandardCharsets.UTF_8)
                    + "&generation=" + record.generation())
                .withAttribute("hx-target", "#project-tab-panel").withAttribute("hx-swap", "innerHTML")
                .withAttribute("data-htmx-error-swap", "true");
            selector.withChild(button);
        }
        return selector;
    }

    private String projectMaterializationContent(String projectId, MaterializationRecord record,
                                                 Integer requestedGeneration, String path,
                                                 HttpServletResponse response) {
        if (requestedGeneration != null && requestedGeneration != record.generation()) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            return WorkspaceExplorerFragments.projectMaterializationState("Stale generation",
                "The selected materialization changed. Refresh the Files tab before reading it.", "orch-error");
        }
        return switch (record.state()) {
            case PROVISIONING -> WorkspaceExplorerFragments.projectMaterializationState("Not provisioned",
                "Lifecycle state: PROVISIONING. The local container is not ready; continue from the owning agent Context tab.", "provisioning");
            case FAILED -> WorkspaceExplorerFragments.projectMaterializationState("Failed",
                "Materialization provisioning failed. Review the owning agent Context tab.", "failed");
            case REVOKED -> WorkspaceExplorerFragments.projectMaterializationState("Revoked",
                "This materialization is revoked and cannot be browsed.", "revoked");
            case READY -> {
                try {
                    MaterializationLocation location = resolveProjectMaterialization(record, record.generation());
                    WorkspaceExplorerService.DirectoryListing listing = workspaceExplorerService.list(location,
                        workspaceExplorerService.normalizeRelativePath(path));
                    yield WorkspaceExplorerFragments.projectMaterializationShell(projectId, record.agentId(),
                        record.generation(), listing);
                } catch (WorkspaceFileException e) {
                    if (e.reason() == WorkspaceFileException.Reason.NOT_FOUND) {
                        yield WorkspaceExplorerFragments.projectMaterializationState("Ready location missing",
                            "The materialization record is READY but its verified directory is unavailable.", "missing");
                    }
                    throw e;
                } catch (IllegalArgumentException e) {
                    yield WorkspaceExplorerFragments.projectMaterializationState("Ready location missing",
                        "The materialization record is READY but its verified directory is unavailable.", "missing");
                }
            }
        };
    }

    private MaterializationRecord exactProjectMaterialization(String agentId, String projectId) {
        if (agentContextService == null) return null;
        return agentContextService.materializations(agentId).stream()
            .filter(record -> "project".equals(record.sourceKind()) && projectId.equals(record.sourceId())
                && agentId.equals(record.agentId()))
            .findFirst().orElse(null);
    }

    private MaterializationLocation resolveProjectMaterialization(MaterializationRecord record, int generation) {
        if (agentMaterializationService == null)
            throw new IllegalStateException("Materialization authority is unavailable");
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, record.agentId());
        return agentMaterializationService.resolveReadyLocation(descriptor, record.materializationKey(), generation);
    }

    private String projectSettingsFragment(Project project, Map<String, String> values, String message) {
        boolean submitted = !values.isEmpty();
        Div panel = new Div().withClass("project-settings-tab").withChild(Header.H2("Settings"));
        if (StringUtils.hasText(message)) {
            panel.withChild(new Div().withClass(message.endsWith("saved.") ? "orch-status" : "orch-error")
                .withInnerText(message));
        }
        Form form = Form.create().withHxPut("/projects/_editor/" + project.id());
        form.withClass("orch-form-stack").withAttribute("data-htmx-error-swap", "true");
        form.withHxTarget("#project-tab-panel").withHxSwap("innerHTML")
            .withChild(label("Name", TextInput.create("name").withId("project-name")
                .withValue(submitted ? values.getOrDefault("name", "") : nn(project.name()))))
            .withChild(label("Description", TextArea.create("description").withId("project-description")
                .withRows(4).withValue(submitted ? values.getOrDefault("description", "") : nn(project.description()))))
            .withChild(new Div().withClass("orch-form-grid")
                .withChild(label("Owner Agent", TextInput.create("ownerAgentDisplay")
                    .withValue(dv(project.ownerAgentId())).withAttribute("disabled", "disabled")))
                .withChild(label("Manager Type", worktypeSelect(submitted
                    ? values.get("workTypeProfile") : project.promptProfile())))
                .withChild(label("Default Model", modelSelect("model", submitted
                    ? values.get("model") : project.model()))))
            .withChild(new Div().withClass("tool-actions")
                .withChild(Button.create("Save Settings").withClass("orch-primary").withAttribute("type", "submit")));
        panel.withChild(form);
        panel.withChild(new HtmlTag("details").withClass("project-metadata")
            .withChild(new HtmlTag("summary").withInnerText("Metadata"))
            .withChild(new Div().withClass("orch-meta")
                .withChild(new HtmlTag("span").withInnerText("ID: " + project.id()))
                .withChild(new HtmlTag("span").withInnerText("Created: " + (project.createdAt() == null ? "—" : project.createdAt().toString())))
                .withChild(new HtmlTag("span").withInnerText("Updated: " + (project.updatedAt() == null ? "—" : project.updatedAt().toString())))));
        panel.withChild(new Div().withId("project-settings-delete")
            .withChild(Button.create("Delete Project").withClass("orch-danger")
                .withAttribute("hx-get", "/projects/_detail/" + project.id() + "/delete-confirm")
                .withAttribute("hx-target", "#project-settings-delete").withAttribute("hx-swap", "innerHTML")));
        return panel.render();
    }

    // ── Select helpers ──
    private Select agentSelect(String name, String selected) {
        Select select = Select.create(name);
        select.addOption("", "-- none --", !StringUtils.hasText(selected));
        agentProfileService.list().forEach(agent ->
            select.addOption(agent.id(), dv(agent.name()), agent.id().equals(selected)));
        return select;
    }

    private Select worktypeSelect(String selected) {
        Select select = Select.create("workTypeProfile");
        select.addOption("", "-- none --", !StringUtils.hasText(selected));
        for (String wt : List.of("CODING_CENTRIC", "CREATIVE_CENTRIC", "ANALYTICAL_CENTRIC", "GENERAL_PURPOSE")) {
            select.addOption(wt, wt, wt.equals(selected));
        }
        return select;
    }

    private Select modelSelect(String name, String selected) {
        Select select = Select.create(name);
        select.addOption("", "-- none --", !StringUtils.hasText(selected));
        // Model list is deferred; show selected if present
        if (StringUtils.hasText(selected)) {
            select.addOption(selected, selected, true);
        }
        return select;
    }

    // ── Entity selector adapter ──
    private Component entitySelector(String paramName, EntityKind kind, String value,
                                      String label, String placeholder, boolean required) {
        return entitySelector(paramName, kind, value, label, placeholder, required, Map.of());
    }

    private Component entitySelector(String paramName, EntityKind kind, String value,
                                      String label, String placeholder, boolean required,
                                      Map<String, String> context) {
        EntitySelectorConfig config = new EntitySelectorConfig(paramName, kind, value, label, placeholder, required, context);
        return entitySelectorComponents.selector(config, entityLookupService.currentOption(kind, value));
    }

    private void validateSelectedEntity(EntityKind kind, String entityId, boolean required, String label) {
        var validation = entityLookupService.validate(kind, entityId, required);
        if (!validation.exists()) {
            String suffix = StringUtils.hasText(validation.message()) ? ": " + validation.message() : "";
            throw new IllegalArgumentException(label + suffix);
        }
    }

    private List<RunOutputArtifact> queryOutputs(String projectId, int limit) {
        return outputArtifactService.query(new io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactQuery(
            null, null, null, null, projectId, null, null, null, null, null, null, null, limit));
    }

    private String outputCompactContext(RunOutputArtifact artifact) {
        return java.util.stream.Stream.of(
                lab("run", artifact.runId()),
                lab("project", artifact.projectId()),
                lab("workspace", artifact.workspaceIdentity()),
                lab("job", artifact.jobId()),
                lab("assignment", artifact.jobAssignmentId()),
                lab("job run", artifact.jobRunId()))
            .filter(StringUtils::hasText)
            .collect(java.util.stream.Collectors.joining(" / "));
    }

    private String lab(String label, String value) {
        return StringUtils.hasText(value) ? label + ": " + value : null;
    }

    // ── Generic widget helpers ──
    private Component sectionHeader(String title, String subtitle) {
        return new Div().withClass("section-header")
            .withChild(Header.H3(title))
            .withChild(new Paragraph(subtitle).withClass("orch-muted-note"));
    }

    private Component metaItem(String label, String value) {
        return new Div().withClass("agent-meta-item")
            .withChild(new HtmlTag("span").withClass("agent-meta-label").withInnerText(label))
            .withChild(new HtmlTag("span").withClass("agent-meta-value").withInnerText(value));
    }

    private Component label(String text, Component control) {
        return new HtmlTag("label")
            .withChild(new HtmlTag("span").withInnerText(text))
            .withChild(control);
    }

    private Component moduleScript(String src) {
        return new HtmlTag("script").withAttribute("type", "module").withAttribute("src", src);
    }

    private HtmlTag statusBadgeHtml(String status) {
        String s = (status != null ? status : "").toLowerCase();
        String css;
        if (s.equals("active") || s.equals("running") || s.equals("completed")) {
            css = "orch-status-chip active";
        } else if (s.equals("disabled") || s.equals("failed") || s.equals("cancelled")) {
            css = "orch-status-chip disabled";
        } else {
            css = "orch-chip";
        }
        return new HtmlTag("span").withClass(css).withInnerText(status != null ? status : "unknown");
    }

    private int fileErrorStatus(WorkspaceFileException e) {
        return e != null && e.reason() == WorkspaceFileException.Reason.NOT_FOUND ? 404 : 400;
    }

    private String formatSince(java.time.Instant iso) {
        if (iso == null) return "—";
        long diff = java.time.Instant.now().toEpochMilli() - iso.toEpochMilli();
        long seconds = diff / 1000;
        if (seconds < 60) return seconds + "s ago";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        return (hours / 24) + "d ago";
    }

    private String dv(String value) {
        return ProjectConsoleFragments.displayValue(value);
    }

    private String e(String value) {
        return ProjectConsoleFragments.e(value);
    }

    private String nn(String value) {
        return ProjectConsoleFragments.nn(value);
    }
}
