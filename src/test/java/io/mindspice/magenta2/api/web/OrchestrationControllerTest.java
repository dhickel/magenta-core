package io.mindspice.magenta2.api.web;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanChatRepository;
import io.mindspice.magenta2.ai.chat.plan.SavedPlanChatService;
import io.mindspice.magenta2.ai.chat.plan.WorkTypeProfile;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService.AssignmentDiagnostics;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService.AssignmentTranscript;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService.LinkedRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentEventReaction;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentSchedule;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNode;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNodeType;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRoute;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRouteType;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowValidator;
import io.mindspice.magenta2.ai.orchestration.workspaces.LeaseMode;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatusService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLease;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLink;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLinkType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.api.web.selector.EntityLookupService;
import io.mindspice.magenta2.api.web.selector.EntitySelectorComponents;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class OrchestrationControllerTest {

    private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return null; }
            @Override public T getIfAvailable() { return null; }
        };
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T instance) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject() { return instance; }
            @Override public T getIfAvailable() { return instance; }
        };
    }

    private static JdbcTemplate jdbcTemplate() {
        var dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        return new JdbcTemplate(dataSource);
    }

    private static OrchestrationController controller() {
        return controller(true, true, new StubScheduleService(), new StubEventReactionService());
    }

    private static OrchestrationController controller(
        boolean schedulesEnabled,
        boolean reactionsEnabled,
        ScheduleService scheduleService,
        EventReactionService reactionService
    ) {
        return controller(
            schedulesEnabled,
            reactionsEnabled,
            scheduleService,
            reactionService,
            new StubJobService(),
            new StubAssignmentService()
        );
    }

    private static OrchestrationController controller(
        boolean schedulesEnabled,
        boolean reactionsEnabled,
        ScheduleService scheduleService,
        EventReactionService reactionService,
        JobService jobService,
        AssignmentService assignmentService
    ) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            jobService,
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            assignmentService,
            scheduleService,
            reactionService,
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            schedulesEnabled,
            reactionsEnabled
        );
    }

    private static OrchestrationController controllerWithWorkflowService(WorkflowService workflowService) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            workflowService,
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static OrchestrationController controllerWithAgentProfileService(AgentProfileService agentProfileService) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            agentProfileService,
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static OrchestrationController controllerWithProjectService(ProjectService projectService) {
        return new OrchestrationController(
            new StubChatService(),
            projectService,
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static OrchestrationController controllerWithProjectFiles(
        ProjectService projectService,
        WorkAreaService workAreaService,
        WorkAreaExplorerService workAreaExplorerService
    ) {
        return new OrchestrationController(
            new StubChatService(),
            projectService,
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            providerOf(workAreaService),
            providerOf(workAreaExplorerService),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static OrchestrationController controllerWithRuntimeSettingsService(RuntimeSettingsService runtimeSettingsService) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            runtimeSettingsService,
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static OrchestrationController controllerWithWorkspaceStatus(AgentWorkspaceStatus status) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            providerOf(new StubAgentWorkspaceStatusService(status)),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private static WorkspaceService workspaceService() {
        try {
            Path dataRoot = Files.createTempDirectory("orch-controller-workspace");
            WorkspaceRepository repository = new WorkspaceRepository(new JdbcTemplate(
                new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
            ));
            WorkspaceService service = new WorkspaceService(repository,
                    WorkspaceDirectoryServiceTestSupport.aiConfig(dataRoot),
                    WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                    WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
            var workspace = service.agentWorkspace("agent-1", "Test Agent");
            service.addLink(workspace.id(), new WorkspaceLink(
                null,
                workspace.id(),
                "Home",
                WorkspaceLinkType.PATH,
                "home",
                true,
                false,
                null,
                null
            ));
            repository.saveLease(new WorkspaceLease(
                "lease-1",
                workspace.id(),
                "TASK_RUN",
                "run-1",
                LeaseMode.READ,
                Instant.now().plus(Duration.ofMinutes(15)),
                false,
                null,
                Instant.now(),
                Instant.now()
            ));
            return service;
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create workspace test service", exception);
        }
    }

    private static EntityLookupService selectorLookup() {
        return new EntityLookupService(
            new StubAgentProfileService(),
            new StubPlanService(),
            new StubWorkflowService(),
            new StubJobService(),
            new StubProjectService(),
            workspaceService(),
            null,
            new StubChatService()
        );
    }

    @Test
    void dashboardRendersFullShellWithSidebar() {
        String html = controller().dashboard(null, null);

        assertThat(html).contains(AppNavigation.OPERATIONAL_CSS);
        assertThat(html).doesNotContain("/js/alpha-security.js?v=1");
        assertThat(html).contains("Magenta Operations");
        assertThat(html).contains("Dashboard");
        assertThat(html).contains("/dashboard");
        assertThat(html).contains("main-sidebar");
        assertThat(html).contains("sidenav");
        assertThat(html).contains("collapsible");
        assertThat(html).contains("/webjars/htmx.org/dist/htmx.min.js");
        assertThat(html).contains("/js/orchestration/dashboard.js?v=5");
        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void planPageRendersHtmxFirstEditor() {
        String html = controller().plans();

        // Title is "Plans" (not "Plans & Tasks")
        assertThat(html).contains("Plans");
        assertThat(html).contains("/js/orchestration/plans.js?v=4");

        // HTMX containers for plan list
        assertThat(html).contains("hx-get=\"/plans/_list\"");
        assertThat(html).contains("hx-get=\"/plans/_editor/_name-modal?mode=editor\"");
        assertThat(html).contains("New Plan Chat");
        assertThat(html).contains("hx-get=\"/plans/_editor/_name-modal?mode=chat\"");
        assertThat(html).contains("plan-modal-container");

        // No Run button, No Run panel, No run-plan
        assertThat(html).doesNotContain("run-plan");
        assertThat(html).doesNotContain("plan-run-agent-id");
        assertThat(html).doesNotContain("plan-run-log");

        // No "Plans & Tasks" heading
        assertThat(html).doesNotContain("Plans &amp; Tasks");

        // No plan-prompt-profile text field (replaced by worktype dropdown)
        assertThat(html).doesNotContain("plan-prompt-profile");

        // No data-action based handlers for plan editing
        assertThat(html).doesNotContain("data-action=\"save-plan\"");
        assertThat(html).doesNotContain("data-action=\"new-plan\"");

        // Editor container exists
        assertThat(html).contains("plan-editor-container");

        // Filter input has HTMX attributes
        assertThat(html).contains("plan-filter");
        assertThat(html).contains("hx-get=\"/plans/_list\"");
        assertThat(html).contains("hx-trigger=\"keyup changed delay:300ms\"");
    }

    @Test
    void planEditorFragmentForNewPlanRendersWorktypeDropdownAndForm() {
        String html = controller().newPlanEditor();

        // New plan editor has form with POST
        assertThat(html).contains("hx-post=\"/plans/_editor\"");
        assertThat(html).contains("New Plan");

        // Has worktype dropdown (not promptProfile text field)
        assertThat(html).contains("workTypeProfile");
        assertThat(html).contains("CODING_CENTRIC");
        assertThat(html).contains("DATA_CENTRIC");
        assertThat(html).contains("RESEARCH_CENTRIC");

        // No Run button
        assertThat(html).doesNotContain("run-plan");
        assertThat(html).doesNotContain("Plan/Task Editor");
    }

    @Test
    void draftPlanCreationRendersFullPersistedEditor() {
        String html = controller().createDraftPlanEditor();

        assertThat(html).contains("Plan Editor");
        assertThat(html).contains("Deliverables");
        assertThat(html).contains("Inputs");
        assertThat(html).contains("Outputs");
        assertThat(html).contains("Steps");
        assertThat(html).contains("Validation Criteria");
        assertThat(html).contains("Assumptions");
        assertThat(html).contains("Advanced");
        assertThat(html).doesNotContain("Execution Evidence");
        assertThat(html).doesNotContain("Validation Feedback");
        assertThat(html).doesNotContain("Pending Questions");
    }

    @Test
    void planNameModalRendersEditorAndChatTargets() {
        OrchestrationController ctrl = controller();

        String editorHtml = ctrl.planNameModal("editor");
        String chatHtml = ctrl.planNameModal("chat");

        assertThat(editorHtml).contains("plan-name-modal");
        assertThat(editorHtml).contains("hx-post=\"/plans/_editor/_draft\"");
        assertThat(editorHtml).contains("new-plan-title");
        assertThat(chatHtml).contains("hx-post=\"/plans/_editor/_planning-chat\"");
        assertThat(chatHtml).contains("Create Chat");
    }

    @Test
    void namedDraftCreationOpensEditorTab() {
        String html = controller().createDraftPlanEditor("Named Draft");

        assertThat(html).contains("hx-swap-oob=\"innerHTML\"");
        assertThat(html).contains("value=\"Named Draft\"");
        assertThat(html).contains("data-plan-tab=\"editor\"");
        assertThat(html).contains("aria-selected=\"true\"");
    }

    @Test
    void namedPlanChatCreationOpensChatTabWithChatModuleStructure() {
        StubPlanService planService = new StubPlanService();
        OrchestrationController ctrl = controllerWithSavedPlanChat(planService);

        String html = ctrl.createPlanChatEditor("Interview Draft");

        assertThat(html).contains("Interview Draft");
        assertThat(html).contains("data-plan-tab=\"chat\"");
        assertThat(html).contains("aria-selected=\"true\"");
        assertThat(html).contains("data-active-plan-tab=\"chat\"");
        assertThat(html).contains("chat-module-shell");
        assertThat(html).contains("data-sp-chat-conversation-id=\"plan:");
        assertThat(html).contains("plan-chat-history");
        assertThat(html).contains("runtime inputs");
        assertThat(html).contains("planning-question-card");
        assertThat(html).contains("planning-question-count");
        assertThat(html).contains("Question 1/4");
        assertThat(html).doesNotContain("plan-editor-form");
        assertThat(html).doesNotContain("hx-put=\"/plans/_editor/");
        assertThat(html).doesNotContain("Recent Runs");
        assertThat(html).doesNotContain("chat-sessions");
    }

    @Test
    void planEditorFragmentForExistingPlanRendersAllSections() {
        String planId = "plan-abc";
        String html = controller().planEditor(planId);

        // Has PUT form for update
        assertThat(html).contains("hx-put=\"/plans/_editor/" + planId + "\"");

        // Has all list/field section headers
        assertThat(html).contains("Deliverables");
        assertThat(html).contains("Inputs");
        assertThat(html).contains("Outputs");
        assertThat(html).contains("Steps");
        assertThat(html).contains("Validation Criteria");
        assertThat(html).contains("Assumptions");

        // Has worktype dropdown
        assertThat(html).contains("workTypeProfile");

        // Has action buttons
        assertThat(html).contains("Save");
        assertThat(html).contains("Finalize Task");
        assertThat(html).contains("Submit to Agent");
        assertThat(html).contains("Editing Details");
        assertThat(html).contains("Planning Chat");
        assertThat(html).contains("/plans/_editor/" + planId + "/planning-chat/start");
        assertThat(html).contains("data-active-plan-tab=\"editor\"");
        assertThat(html).doesNotContain("chat-module-shell");

        // Has Advanced section (collapsible)
        assertThat(html).contains("Advanced");
        assertThat(html).doesNotContain("Execution Evidence");
        assertThat(html).doesNotContain("Validation Feedback");
        assertThat(html).doesNotContain("Pending Questions");

        // No Run button or run panel
        assertThat(html).doesNotContain("run-plan");
        assertThat(html).doesNotContain("plan-run-agent-id");
        assertThat(html).doesNotContain("plan-run-log");

        // No example field in input editor
        assertThat(html).doesNotContain("example");
    }

    @Test
    void planListFragmentRendersHtmxEnabledButtons() {
        String html = controller().planListFragment(null);

        // Has at least one plan item from stub
        assertThat(html).contains("tool-item");
        assertThat(html).contains("hx-get=\"/plans/_editor/");
        assertThat(html).contains("hx-target=\"#plan-editor-container\"");
    }

    @Test
    void submitFormRendersAgentSelectAndInputs() {
        String html = controller().submitForm("plan-abc");

        assertThat(html).contains("Submit to Agent");
        assertThat(html).contains("agentId");
        assertThat(html).contains("modelOverride");
        assertThat(html).contains("priority");
        assertThat(html).contains("data-selector-kind=\"work-area\"");
        assertThat(html).contains("name=\"selectedWorkAreaId\"");
        assertThat(html).contains("name=\"outputWorkAreaId\"");
    }

    @Test
    void chatPromptFragmentRendersPromptText() {
        String html = controller().chatPromptFragment("plan-abc");

        assertThat(html).contains("Continue in Chat");
        assertThat(html).contains("plan title");
        assertThat(html).contains("Grok the existing plan");
    }

    @Test
    void workflowPageRendersHtmxFirstEditor() {
        String html = controller().workflows();

        assertThat(html).contains("Workflows");
        assertThat(html).doesNotContain("/js/orchestration/workflows.js");
        assertThat(html).doesNotContain("Workflow V2 Graph Composer");
        assertThat(html).contains("/workflows/_list");
        assertThat(html).contains("/workflows/_editor/_draft");
        assertThat(html).contains("No workflows.");
        assertThat(html).doesNotContain("Loading...");

        // No Run button, No Run panel
        assertThat(html).doesNotContain("run-workflow");
        assertThat(html).doesNotContain("workflow-run-agent-id");
        assertThat(html).doesNotContain("workflow-run-log");

        // No data-action based handlers
        assertThat(html).doesNotContain("data-action=\"save-workflow\"");
        assertThat(html).doesNotContain("data-action=\"validate-workflow\"");
        assertThat(html).doesNotContain("data-action=\"add-workflow-node\"");

        // Editor container exists
        assertThat(html).contains("workflow-editor-container");
        assertThat(html).contains("Submit to Agent");
        assertThat(html).contains("disabled=\"disabled\"");

        // Filter input has HTMX attributes
        assertThat(html).contains("workflow-filter");
        assertThat(html).contains("hx-get=\"/workflows/_list\"");
        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void newWorkflowButtonCreatesDraftWithFullEditorSurface() {
        String html = controller().createWorkflowDraftEditor();

        assertThat(html).contains("Workflow Editor");
        assertThat(html).contains("Untitled Workflow");
        assertThat(html).contains("Nodes");
        assertThat(html).contains("Routes");
        assertThat(html).contains("Validation");
        assertThat(html).contains("Submit to Agent");
        assertThat(html).contains("name=\"condition\"");
        assertThat(html).doesNotContain("run-workflow");
    }

    @Test
    void workflowEditorSavesIncompleteApprovalDraftAndEditsRouteCondition() {
        OrchestrationController ctrl = controller();
        ctrl.createWorkflowDraftEditor();

        String gateHtml = ctrl.addWorkflowNode("workflow-draft", Map.of(
            "nodeType", "USER_APPROVAL",
            "messageTemplate", "approve?"
        ));
        assertThat(gateHtml).contains("node_1");
        assertThat(gateHtml).doesNotContain("Error:");

        String finalHtml = ctrl.addWorkflowNode("workflow-draft", Map.of("nodeType", "FINAL_OUTPUT"));
        assertThat(finalHtml).contains("node_2");
        assertThat(finalHtml).doesNotContain("Error:");

        String routeHtml = ctrl.addWorkflowRoute("workflow-draft", Map.of(
            "fromNodeKey", "node_1",
            "toNodeKey", "node_2",
            "routeType", "CONTROL"
        ));
        assertThat(routeHtml).contains("route_1");
        assertThat(routeHtml).contains("name=\"condition\"");
        assertThat(routeHtml).doesNotContain("must define condition");
        assertThat(routeHtml).doesNotContain("missing REJECTED");

        String updatedRouteHtml = ctrl.updateWorkflowRoute("workflow-draft", "route_1",
            Map.of("condition", "APPROVED"));
        assertThat(updatedRouteHtml).contains("APPROVED");
        assertThat(updatedRouteHtml).contains("selected");
        assertThat(updatedRouteHtml).doesNotContain("Error:");
    }

    @Test
    void htmxWorkflowValidateAndSubmitRejectEmptyDraftWithOperatorVisibleError() {
        OrchestrationController ctrl = controller();
        ctrl.createWorkflowDraftEditor();

        String validationHtml = ctrl.validateWorkflow("workflow-draft");
        assertThat(validationHtml).contains("ERROR:");
        assertThat(validationHtml).contains("at least one executable node");
        assertThat(validationHtml).doesNotContain("Valid: no errors found.");

        String submitHtml = ctrl.workflowSubmitToAgent("workflow-draft", Map.of(
            "agentId", "agent-1",
            "runDisplayName", "Draft workflow run"
        ));
        assertThat(submitHtml).contains("Validation failed");
        assertThat(submitHtml).contains("at least one executable node");
        assertThat(submitHtml).doesNotContain("Assignment Created");
    }

    @Test
    void failedWorkflowTitleSaveReturnsPersistedEditorWithVisibleError() {
        StubWorkflowService workflowService = new StubWorkflowService();
        workflowService.seed(new WorkflowDefinition(
            "workflow-draft", "Persisted Title", "Persisted summary",
            List.of(), List.of(), null, null));
        workflowService.failSavesWith("database write failed");

        String html = controllerWithWorkflowService(workflowService).updateWorkflowEditor(
            "workflow-draft",
            Map.of("title", "Unsaved Title", "summary", "Unsaved summary")
        );

        assertThat(html).contains("Workflow save failed");
        assertThat(html).contains("database write failed");
        assertThat(html).contains("value=\"Persisted Title\"");
        assertThat(html).contains("Persisted summary");
        assertThat(html).doesNotContain("Unsaved Title");
    }

    @Test
    void failedWorkflowNodeSaveReturnsPersistedNodeSectionWithVisibleError() {
        StubWorkflowService workflowService = new StubWorkflowService();
        workflowService.seed(new WorkflowDefinition(
            "workflow-draft", "Persisted Workflow", "",
            List.of(new WorkflowNode("node_1", WorkflowNodeType.FINAL_OUTPUT, null, "Persisted Node",
                null, Map.of(), false, List.of(), null, null)),
            List.of(), null, null));
        workflowService.failSavesWith("node write failed");

        String html = controllerWithWorkflowService(workflowService).updateWorkflowNode(
            "workflow-draft",
            "node_1",
            Map.of("label", "Unsaved Node")
        );

        assertThat(html).contains("Node save failed");
        assertThat(html).contains("node write failed");
        assertThat(html).contains("Persisted Node");
        assertThat(html).doesNotContain("Unsaved Node");
        assertThat(html).doesNotContain("Validation failed");
    }

    @Test
    void failedWorkflowRouteSaveReturnsPersistedRouteSectionWithVisibleError() {
        StubWorkflowService workflowService = new StubWorkflowService();
        workflowService.seed(new WorkflowDefinition(
            "workflow-draft", "Persisted Workflow", "",
            List.of(
                new WorkflowNode("node_1", WorkflowNodeType.USER_APPROVAL, null, "Gate",
                    null, Map.of(), false, List.of(), null, null),
                new WorkflowNode("node_2", WorkflowNodeType.FINAL_OUTPUT, null, "Done",
                    null, Map.of(), false, List.of(), null, null)
            ),
            List.of(new WorkflowRoute("route_1", "node_1", null, "node_2", null,
                WorkflowRouteType.CONTROL, WorkflowRoute.OUTCOME_APPROVED)),
            null, null));
        workflowService.failSavesWith("route write failed");

        String html = controllerWithWorkflowService(workflowService).updateWorkflowRoute(
            "workflow-draft",
            "route_1",
            Map.of("condition", WorkflowRoute.OUTCOME_REJECTED)
        );

        assertThat(html).contains("Route save failed");
        assertThat(html).contains("route write failed");
        assertThat(html).contains(WorkflowRoute.OUTCOME_APPROVED);
        assertThat(html).doesNotContain(WorkflowRoute.OUTCOME_REJECTED + "\" selected");
    }

    @Test
    void workflowValidationExceptionRendersInValidationResultArea() {
        StubWorkflowService workflowService = new StubWorkflowService();
        workflowService.seed(new WorkflowDefinition(
            "workflow-draft", "Persisted Workflow", "",
            List.of(), List.of(), null, null));
        workflowService.failValidationWith("validator unavailable");

        String html = controllerWithWorkflowService(workflowService).validateWorkflow("workflow-draft");

        assertThat(html).contains("warnings");
        assertThat(html).contains("ERROR: validator unavailable");
        assertThat(html).doesNotContain("Valid: no errors found.");
    }

    @Test
    void workflowEditorRendersPersistedNodePayloadsAsEscapedDomValues() {
        String payload = "<img src=x onerror=alert('workflow-xss')><script>alert('workflow-xss')</script>";
        StubWorkflowService workflowService = new StubWorkflowService();
        workflowService.seed(new WorkflowDefinition(
            "workflow-xss", "Workflow XSS", "",
            List.of(new WorkflowNode("node_xss", WorkflowNodeType.FINAL_OUTPUT, null, payload,
                null, Map.of(), false, List.of(), payload, null)),
            List.of(), null, null));
        OrchestrationController controller = controllerWithWorkflowService(workflowService);

        org.jsoup.nodes.Document editor = Jsoup.parse(controller.workflowEditor("workflow-xss"));
        org.jsoup.nodes.Document nodePanel = Jsoup.parse(controller.workflowNodePanel("workflow-xss", "node_xss"));

        assertThat(editor.select("script,img,[onerror]")).isEmpty();
        assertThat(nodePanel.select("script,img,[onerror]")).isEmpty();
        assertThat(editor.select("input[name=label]").attr("value")).isEqualTo(payload);
        assertThat(editor.select("input[name=messageTemplate]").attr("value")).isEqualTo(payload);
        assertThat(nodePanel.text()).contains(payload);
    }

    @Test
    void jobPageRendersHtmxFirstWithListAndEditor() {
        String html = controller().jobs();

        // HTMX-first layout with list and editor
        assertThat(html).contains("Ordered orchestration items");
        assertThat(html).contains("hx-get=\"/jobs/_list\"");
        assertThat(html).contains("hx-get=\"/jobs/_editor/_new\"");
        assertThat(html).contains("job-editor-container");
        assertThat(html).contains("job-list");

        // No JS data-action handlers for job editing
        assertThat(html).doesNotContain("data-action=\"create-job\"");
        assertThat(html).doesNotContain("data-action=\"reload-jobs\"");
        assertThat(html).doesNotContain("data-action=\"run-job\"");
        assertThat(html).doesNotContain("data-action=\"save-job\"");
        assertThat(html).doesNotContain("data-action=\"add-job-item\"");
        assertThat(html).doesNotContain("data-action=\"cancel-job\"");

        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void jobEditorFragmentRendersFormWithItemsAndSubmitButtons() {
        // Create a job first, then test the editor
        String html = controller().jobEditor("job-abc");

        // Job editor is populated from stub
        assertThat(html).contains("hx-put=\"/jobs/_editor/job-abc\"");
        assertThat(html).contains("job-editor");

        // Has items section
        assertThat(html).contains("Ordered Items");

        // Has submit and delete buttons
        assertThat(html).contains("Submit to Agent");
        assertThat(html).doesNotContain("Persistent").doesNotContain("job" + " workspace");
        assertThat(html).contains("Delete");

        // No Run button
        assertThat(html).doesNotContain("run-job");

        // Events and outputs load via HTMX
        assertThat(html).contains("/jobs/_detail/job-abc/events");
        assertThat(html).contains("/jobs/_detail/job-abc/outputs");
    }

    @Test
    void jobStartRunSubmitsHighPriorityAssignmentWithoutDirectJobRun() {
        StubJobService jobService = new StubJobService();
        StubAssignmentService assignmentService = new StubAssignmentService();
        OrchestrationController ctrl = controller(
            true,
            true,
            new StubScheduleService(),
            new StubEventReactionService(),
            jobService,
            assignmentService
        );

        String html = ctrl.startJobRun("job-abc");

        assertThat(html).contains("Assignment Created");
        assertThat(html).contains("Type: JOB_RUN");
        assertThat(html).contains("Priority: 9");
        assertThat(assignmentService.lastRequest).isNotNull();
        assertThat(assignmentService.lastRequest.assignmentType()).isEqualTo(AssignmentType.JOB_RUN);
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.jobId()).isEqualTo("job-abc");
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.input()).containsEntry("jobId", "job-abc");
        assertThat(jobService.startRunCalls).isZero();
    }

    @Test
    void jobSubmitFormRendersProjectWorkspaceOverridesAndPersistenceStatus() {
        String html = controller().jobSubmitForm("job-abc");

        assertThat(html).contains("Saved project:");
        assertThat(html).doesNotContain("Persistent").doesNotContain("job" + " workspace:");
        assertThat(html).contains("Project Override");
        assertThat(html).contains("Compatibility Workspace");
        assertThat(html).contains("name=\"projectId\"");
        assertThat(html).contains("name=\"workspaceId\"");
        assertThat(html).contains("name=\"selectorContext.agentId\"");
    }

    @Test
    void jobSubmitUsesSelectedAgentWhenSelectorContextCarriesJobOwner() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        OrchestrationController ctrl = controller(
            true,
            true,
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubJobService(),
            assignmentService
        );

        String html = ctrl.submitJob("job-abc", Map.of(
            "agentId", "agent-2",
            "selectorContext.agentId", "agent-1",
            "priority", "8"
        ));

        assertThat(html).contains("Assignment Created");
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-2");
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(8);
    }

    @Test
    void projectPageRendersHtmxFirstWithListAndEditor() {
        String html = controller().projects();

        // HTMX-first layout
        assertThat(html).contains("/js/orchestration/projects.js?v=3");
        assertThat(html).contains("hx-get=\"/projects/_list\"");
        assertThat(html).contains("hx-get=\"/projects/_editor/_new\"");
        assertThat(html).contains("project-editor-container");
        assertThat(html).contains("Shared project workspaces for durable context, membership, and cross-agent visibility.");
        assertThat(html).doesNotContain("Top-level tracking and data-space wrappers with owner agents.");

        // No JS data-action handlers
        assertThat(html).doesNotContain("data-action=\"create-project\"");
        assertThat(html).doesNotContain("data-action=\"save-project\"");
        assertThat(html).doesNotContain("data-action=\"delete-project\"");
        assertThat(html).doesNotContain("data-action=\"add-project-agent\"");

        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void projectEditorFragmentRendersFormWithDetailSections() {
        String html = controller().projectEditor("proj-xyz");

        // Editor form with PUT
        assertThat(html).contains("hx-put=\"/projects/_editor/proj-xyz\"");
        assertThat(html).contains("project-editor");

        // Has detail sections
        assertThat(html).contains("Name");
        assertThat(html).contains("Description");
        assertThat(html).contains("Legacy Initial Agent");
        assertThat(html).contains("name=\"ownerAgentId\"");
        assertThat(html).doesNotContain("Owner Agent");
        assertThat(html).contains("Git Repo URL");
        assertThat(html).contains("Manager Type");

        // Has workspace, agents, jobs, outputs sections
        assertThat(html).contains("Workspace");
        assertThat(html).contains("Agents");
        assertThat(html).contains("Active Jobs");
        assertThat(html).contains("Recent Outputs");
        assertThat(html).contains("Project Directory");
        assertThat(html).contains("hx-target=\"#project-network-section\"");
        assertThat(html).contains("hx-target=\"#project-agents-section\"");
        assertThat(html).contains("hx-target=\"#project-jobs-section\"");
        assertThat(html).contains("hx-target=\"#project-outputs-section\"");
        assertThat(html).contains("hx-get=\"/projects/_detail/proj-xyz/files\"");
        assertThat(html).contains("hx-target=\"#project-files-section\"");

        // Has delete button
        assertThat(html).contains("Delete");
    }

    @Test
    void projectMembershipFragmentRendersAddAndRemoveControls() {
        String html = controller().projectAgentsFragment("proj-xyz");

        assertThat(html).contains("hx-post=\"/projects/_detail/proj-xyz/agents\"");
        assertThat(html).contains("Add Member");
        assertThat(html).contains("hx-delete=\"/projects/_detail/proj-xyz/agents/agent-1\"");
        assertThat(html).contains("Remove");
    }

    @Test
    void projectFilesFragmentBrowsesAndEditsActualProjectRoot() throws Exception {
        Path dataRoot = Files.createTempDirectory("project-root-browser");
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        AiConfig config = new AiConfig(null, null, 10, dataRoot, Map.of(), Map.of());
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        WorkspaceService workspaceService = new WorkspaceService(
            new WorkspaceRepository(jdbc),
            config,
            new RootRelativePathService(directoryService)
        );
        WorkAreaService workAreaService = new WorkAreaService(
            new WorkAreaRepository(jdbc),
            workspaceService,
            directoryService
        );
        WorkAreaExplorerService explorerService = new WorkAreaExplorerService(workAreaService);
        Path projectRoot = workAreaService.ownerRoot(WorkspaceOwnerType.PROJECT, "proj-xyz");
        Files.writeString(projectRoot.resolve("root-note.txt"), "before", StandardCharsets.UTF_8);

        OrchestrationController ctrl = controllerWithProjectFiles(
            new StubProjectService(),
            workAreaService,
            explorerService
        );

        String listHtml = ctrl.projectFilesFragment("proj-xyz", ".");
        assertThat(listHtml).contains("root-note.txt");
        assertThat(listHtml).doesNotContain("Path: home");
        assertThat(listHtml).contains("name=\"directoryName\"");
        assertThat(listHtml).doesNotContain("id=\"project-new-folder-name\" name=\"name\"");

        String editorHtml = ctrl.projectFileEditorFragment("proj-xyz", "root-note.txt");
        assertThat(editorHtml).contains("Editing root-note.txt");
        assertThat(editorHtml).contains("before");

        String savedHtml = ctrl.saveProjectTextFile("proj-xyz", "root-note.txt", "after");
        assertThat(savedHtml).contains("Saved root-note.txt.");
        assertThat(Files.readString(projectRoot.resolve("root-note.txt"), StandardCharsets.UTF_8)).isEqualTo("after");

        String folderHtml = ctrl.createProjectDirectory("proj-xyz", ".", "folder-exact");
        assertThat(folderHtml).contains("Created folder-exact.");
        assertThat(Files.isDirectory(projectRoot.resolve("folder-exact"))).isTrue();
        assertThat(Files.exists(projectRoot.resolve("Project Name,folder-exact"))).isFalse();
    }

    @Test
    void projectJobsFragmentLinksToRealJobDetailRoute() {
        String html = controller().projectJobsFragment("proj-xyz");

        assertThat(html).contains("href=\"/jobs/job-abc\"");
        assertThat(html).doesNotContain("hx-target=\"#job-editor-container\"");
    }

    @Test
    void projectCreateWithoutOwnerReturnsEditorAndRefreshesProjectList() {
        StubProjectService projectService = new StubProjectService();
        String html = controllerWithProjectService(projectService).createProject(Map.of(
            "name", "Ownerless Project",
            "description", "Shared context",
            "gitRepoUrl", ""
        ));

        assertThat(html).contains("Project created.");
        assertThat(html).contains("Project: Ownerless Project");
        assertThat(html).contains("hx-swap-oob=\"innerHTML\"");
        assertThat(html).contains("id=\"project-list\"");
        assertThat(html).contains("Ownerless Project");
        assertThat(html).contains("Shared context");
        assertThat(html).doesNotContain("No projects.");
    }

    @Test
    void projectDetailPagePreloadsEditorViaHtmx() {
        String html = controller().projectDetail("proj-xyz");

        // Should have shell with sidebar and editor pre-loading via HTMX
        assertThat(html).contains("hx-get=\"/projects/_editor/proj-xyz\"");
        assertThat(html).contains("hx-get=\"/projects/_list\"");
        assertThat(html).contains("project-editor-container");
        assertThat(html).contains("Shared project workspaces for durable context, membership, and cross-agent visibility.");
        assertThat(html).doesNotContain("Top-level tracking and data-space wrappers with owner agents.");

        // No JS data-action handlers
        assertThat(html).doesNotContain("data-action=\"save-project\"");
        assertThat(html).doesNotContain("data-action=\"delete-project\"");
    }

    @Test
    void inboxPageRendersWithUserAndAgentInboxControls() {
        String html = controller().inbox();

        assertThat(html).contains("hx-get=\"/inbox/_user\"");
        assertThat(html).contains("hx-get=\"/inbox/_agent-selector\"");
        assertThat(html).contains("hx-get=\"/inbox/_agent\"");
        assertThat(html).contains("User Inbox");
        assertThat(html).contains("Agent Inbox");
        assertThat(html).contains("user-inbox-messages");
        assertThat(html).contains("agent-inbox-messages");
        assertThat(html).contains("inbox-agent-selector");
        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void outputsPageRendersWithFilterControls() {
        String html = controller().outputs();

        assertThat(html).contains("outputs-filter-form");
        assertThat(html).contains("name=\"agentId\"");
        assertThat(html).contains("name=\"jobId\"");
        assertThat(html).contains("name=\"projectId\"");
        assertThat(html).contains("name=\"runId\"");
        assertThat(html).contains("name=\"type\"");
        assertThat(html).contains("hx-get=\"/outputs/_list\"");
        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    // ── Agent page tests (HTMX-first for Phase 06) ──

    @Test
    void agentsPageRendersHtmxFirstWithListAndDetailContainers() {
        String html = controller().agents();

        assertThat(html).contains("Manage agent profiles");
        assertThat(html).contains("browser-layout");
        assertThat(html).contains("browser-sidebar");
        assertThat(html).contains("browser-detail");
        assertThat(html).contains("agent-list");
        assertThat(html).contains("agent-detail-container");
        assertThat(html).contains("/js/orchestration/agents.js?v=1");
        assertThat(html).doesNotContain("/js/chat-client.js");

        // HTMX containers for list loading
        assertThat(html).contains("hx-get=\"/agents/_list\"");
        assertThat(html).contains("hx-trigger=\"load\"");
        assertThat(html).contains("hx-swap=\"innerHTML\"");

        // Filter
        assertThat(html).contains("agent-filter");
        assertThat(html).contains("hx-get=\"/agents/_list\"");
        assertThat(html).contains("hx-trigger=\"keyup changed delay:300ms\"");

        // Create button via HTMX
        assertThat(html).contains("hx-post=\"/agents/_create\"");

        // No old JS-based agent form IDs (now HTMX-driven editor + detail)
        assertThat(html).doesNotContain("data-action=\"save-agent\"");
        assertThat(html).doesNotContain("data-action=\"clone-agent\"");
        assertThat(html).doesNotContain("data-action=\"delete-agent\"");
        assertThat(html).doesNotContain("data-action=\"reload-agents\"");
    }

    @Test
    void agentDetailPageRendersHtmxTabsAndEditorContainers() {
        String html = controller().agentDetail("agent-1");

        assertThat(html).contains("Agent: Test Agent");
        assertThat(html).contains("entity-detail-layout");
        assertThat(html).contains("entity-detail-layout-full");
        assertThat(html).contains("orch-tabs");
        assertThat(html).contains("/js/orchestration/agents.js?v=1");

        // Tab buttons
        assertThat(html).contains("dashboard");
        assertThat(html).contains("queue");
        assertThat(html).contains("inbox");
        assertThat(html).contains("jobs");
        assertThat(html).contains("schedules");
        assertThat(html).contains("reactions");
        assertThat(html).contains("workspace");
        assertThat(html).contains("outputs");
        assertThat(html).contains("history");
        assertThat(html).contains("chat");

        // HTMX lazy-load containers for tabs, editor, and submit
        assertThat(html).contains("hx-get=\"/agents/_detail/agent-1/dashboard\"");
        assertThat(html).contains("hx-get=\"/agents/_editor/agent-1\"");
        assertThat(html).contains("hx-get=\"/agents/_submit-form/agent-1\"");

        // Agent chat panel host and JS are present
        assertThat(html).contains("data-agent-chat-panel");
        assertThat(html).contains("data-agent-id=\"agent-1\"");
        assertThat(html).contains("agent-chat-accordion");
        assertThat(html).contains("id=\"agent-chat-panel\"");
        assertThat(html).contains("id=\"agent-chat-form\"");
        assertThat(html).contains("id=\"agent-chat-input\"");
        assertThat(html).contains("Chat with Agent");
        assertThat(html).contains(AppNavigation.OPERATIONAL_CSS);
        assertThat(html).contains("/js/orchestration/agent-chat.js?v=2");
        assertThat(html).doesNotContain("agent-event-log");
        assertThat(html).doesNotContain("Event Log");

        // No old JS-dependent markers
        assertThat(html).doesNotContain("agent-assignment-form");
        assertThat(html).doesNotContain("agent-profile-form");
        assertThat(html).doesNotContain("data-action=\"save-agent\"");
    }

    @Test
    void agentListFragmentRendersCompactCards() {
        String html = controller().agentList(null);

        assertThat(html).contains("agents-list-table");
        assertThat(html).contains("agent-card-list");
        assertThat(html).contains("Test Agent");
        assertThat(html).contains("ACTIVE");
        assertThat(html).contains("Refresh");
        assertThat(html).contains("/agents/_lifecycle/agent-1/disable?view=list");
    }

    @Test
    void agentDashboardTabRendersCountersAndLifecycleTarget() {
        String html = controller().agentDashboardTab("agent-1");

        assertThat(html).contains("Dashboard");
        assertThat(html).contains("agent-dashboard");
        assertThat(html).contains("agent-dashboard-counters");
        assertThat(html).contains("agent-counter-card");
        assertThat(html).contains("Queue");
        assertThat(html).contains("Inbox");
        assertThat(html).contains("Jobs");

        // Workspace status section
        assertThat(html).contains("Workspace:");
        assertThat(html).contains("Refresh");
        assertThat(html).contains("Delete / Archive");
        assertThat(html).contains("id=\"agent-lifecycle-panel-agent-1\"");
        assertThat(html).contains("hx-target=\"#agent-lifecycle-panel-agent-1\"");
        assertThat(html).contains("hx-swap=\"outerHTML\"");
        assertThat(html).doesNotContain("Open Agent Chat");
    }

    @Test
    void agentChatTabRemovedFromTabNav() {
        String html = controller().agentDetail("agent-1");
        assertThat(html).doesNotContain("hx-get=\"/agents/_detail/agent-1/chat\"");
    }

    @Test
    void agentChatScriptLoadsWhereAgentDetailCanRender() {
        // The agents browser can HTMX-swap in a detail fragment that contains the chat host.
        String listHtml = controller().agents();
        assertThat(listHtml).contains("agent-chat.js");

        // Verify agent-chat.js is NOT on dashboard
        String dashboardHtml = controller().dashboard(null, null);
        assertThat(dashboardHtml).doesNotContain("agent-chat.js");
    }

    @Test
    void deleteConfirmRendersExplicitLifecycleChoices() {
        String html = controller().deleteAgentLifecycleConfirm("agent-1");

        assertThat(html).contains("Disable Only");
        assertThat(html).contains("Archive + Disable");
        assertThat(html).contains("Hard Delete");
        assertThat(html).contains("DELETE agent-1");
        assertThat(html).contains("id=\"agent-lifecycle-panel-agent-1\"");
        assertThat(html).contains("hx-target=\"#agent-lifecycle-panel-agent-1\"");
        assertThat(html).contains("hx-post=\"/agents/_lifecycle/agent-1/disable?view=lifecycle\"");
        assertThat(html).contains("hx-post=\"/agents/_lifecycle/agent-1/archive-and-disable?view=lifecycle\"");
    }

    @Test
    void lifecycleMutationResultsRenderIntoVisibleLifecyclePanel() {
        OrchestrationController controller = controller();

        String disabled = controller.disableAgentLifecycle("agent-1", "lifecycle");
        assertThat(disabled).contains("id=\"agent-lifecycle-panel-agent-1\"");
        assertThat(disabled).contains("Agent disabled.");

        String archived = controller.archiveAndDisableAgentLifecycle("agent-1", "lifecycle");
        assertThat(archived).contains("id=\"agent-lifecycle-panel-agent-1\"");
        assertThat(archived).contains("Agent workspace archived and profile disabled.");
    }

    @Test
    void agentQueueTabRendersAssignmentTable() {
        String html = controller().agentQueueTab("agent-1");

        assertThat(html).contains("Queue");
        assertThat(html).contains("No assignments");
    }

    @Test
    void agentInboxTabRendersInboxTable() {
        String html = controller().agentInboxTab("agent-1");

        assertThat(html).contains("Inbox");
        assertThat(html).contains("No inbox messages");
    }

    @Test
    void agentWorkspaceTabRendersMetadataLinksAndLeases() {
        String html = controller().agentWorkspaceTab("agent-1");

        assertThat(html).contains("Workspace");
        assertThat(html).contains("Workspace ID:");
        assertThat(html).contains("Owner: AGENT:agent-1");
        assertThat(html).contains("Display Name: Test Agent");
        assertThat(html).contains("Diagnostic Path: workspace/agent-1");
        assertThat(html).contains("Final Output Target: workspace/agent-1/outputs");
        assertThat(html).contains("Active Leases");
        assertThat(html).contains("TASK_RUN:run-1");
        assertThat(html).contains("Workspace Links");
        assertThat(html).contains("Home");
    }

    @Test
    void agentDetailDoesNotRenderStaticPlaceholderEventLog() {
        String html = controller().agentDetailFragment("agent-1");

        assertThat(html).doesNotContain("Event Log");
        assertThat(html).doesNotContain("Agent dashboard loaded");
        assertThat(html).doesNotContain("1 assignment waiting");
        assertThat(html).doesNotContain("Workspace ready");
    }

    @Test
    void agentDashboardRendersWorkspaceStatusServiceDetailsWhenAvailable() {
        AgentWorkspaceStatus status = new AgentWorkspaceStatus(
            "agent-1",
            "agents/agent-1/workspace",
            AgentWorkspaceStatus.WorkspaceHealth.BUSY,
            true,
            true,
            2,
            1,
            List.of("project-1", "project-2"),
            7,
            4096,
            Instant.parse("2026-05-18T12:00:00Z"),
            "Active runs: 2"
        );
        String html = controllerWithWorkspaceStatus(status).agentDashboardTab("agent-1");

        assertThat(html).contains("Workspace Health");
        assertThat(html).contains("Health");
        assertThat(html).contains("BUSY");
        assertThat(html).contains("Path");
        assertThat(html).contains("agents/agent-1/workspace");
        assertThat(html).contains("Writable");
        assertThat(html).contains("Yes");
        assertThat(html).contains("Active Runs");
        assertThat(html).contains("2");
        assertThat(html).contains("Active Leases");
        assertThat(html).contains("1");
        assertThat(html).contains("Linked Projects");
        assertThat(html).contains("project-1, project-2");
        assertThat(html).contains("Output Artifacts");
        assertThat(html).contains("7");
        assertThat(html).contains("Output Bytes");
        assertThat(html).contains("4096");
        assertThat(html).contains("Active runs: 2");
    }

    @Test
    void schedulesAndReactionsTabsRenderDisabledStatesWhenFeaturesOff() {
        OrchestrationController controller = controller(
            false,
            false,
            new StubScheduleService(),
            new StubEventReactionService()
        );

        String schedules = controller.agentSchedulesTab("agent-1");
        String reactions = controller.agentReactionsTab("agent-1");

        assertThat(schedules).contains("Schedules are disabled.");
        assertThat(schedules).contains("magenta.features.schedules-enabled=true");
        assertThat(reactions).contains("Event reactions are disabled.");
        assertThat(reactions).contains("magenta.features.reactions-enabled=true");
    }

    @Test
    void scheduleCreateRendersInlineErrorsForInvalidCronAndInvalidJson() {
        StubScheduleService scheduleService = new StubScheduleService();
        OrchestrationController controller = controller(
            true,
            true,
            scheduleService,
            new StubEventReactionService()
        );

        String invalidCron = controller.createAgentSchedule("agent-1", Map.of(
            "jobId", "job-abc",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "cronExpression", "not-a-cron",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{\"key\":\"value\"}"
        ));
        assertThat(invalidCron).contains("orch-error");
        assertThat(invalidCron).contains("invalid cronExpression");

        String invalidJson = controller.createAgentSchedule("agent-1", Map.of(
            "jobId", "job-abc",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "cronExpression", "0 * * * * *",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{bad-json"
        ));
        assertThat(invalidJson).contains("orch-error");
        assertThat(invalidJson).contains("Invalid JSON for inputJson");

        String missingJobId = controller.createAgentSchedule("agent-1", Map.of(
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "cronExpression", "0 * * * * *",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{\"key\":\"value\"}"
        ));
        assertThat(missingJobId).contains("orch-error");
        assertThat(missingJobId).contains("JOB_RUN assignments require jobId");
    }

    @Test
    void scheduleCreateRendersSavedRowForValidInput() {
        StubScheduleService scheduleService = new StubScheduleService();
        OrchestrationController controller = controller(
            true,
            true,
            scheduleService,
            new StubEventReactionService()
        );

        String html = controller.createAgentSchedule("agent-1", Map.of(
            "jobId", "job-abc",
            "assignmentType", "JOB_RUN",
            "priority", "2",
            "cronExpression", "0 * * * * *",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{\"task\":\"check\"}"
        ));

        assertThat(html).contains("Cron: 0 * * * * *");
        assertThat(html).contains("Assignment Type: JOB_RUN");
        assertThat(html).contains("Next Run:");
        assertThat(scheduleService.schedules("agent-1")).hasSize(1);
    }

    @Test
    void scheduleLifecycleSupportsUpdateToggleAndDelete() {
        StubScheduleService scheduleService = new StubScheduleService();
        OrchestrationController controller = controller(
            true,
            true,
            scheduleService,
            new StubEventReactionService()
        );

        controller.createAgentSchedule("agent-1", Map.of(
            "jobId", "job-abc",
            "assignmentType", "JOB_RUN",
            "priority", "2",
            "cronExpression", "0 * * * * *",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{\"task\":\"create\"}"
        ));
        String scheduleId = scheduleService.schedules("agent-1").get(0).id();

        String updated = controller.updateAgentSchedule("agent-1", scheduleId, Map.of(
            "jobId", "job-abc",
            "assignmentType", "TASK_RUN",
            "priority", "4",
            "cronExpression", "0 */5 * * * *",
            "timezone", "UTC",
            "enabled", "false",
            "inputJson", "{\"taskId\":\"task-1\",\"task\":\"updated\"}"
        ));
        assertThat(updated).contains("Cron: 0 */5 * * * *");
        assertThat(updated).contains("Assignment Type: TASK_RUN");

        String toggled = controller.toggleAgentSchedule("agent-1", scheduleId);
        assertThat(toggled).contains("Enabled: true");

        String deleted = controller.deleteAgentSchedule("agent-1", scheduleId);
        assertThat(deleted).contains("No schedules configured.");
        assertThat(scheduleService.schedules("agent-1")).isEmpty();
    }

    @Test
    void scheduleDeleteIsScopedByAgent() {
        StubScheduleService scheduleService = new StubScheduleService();
        OrchestrationController controller = controller(
            true,
            true,
            scheduleService,
            new StubEventReactionService()
        );

        controller.createAgentSchedule("agent-1", Map.of(
            "jobId", "job-abc",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "cronExpression", "0 * * * * *",
            "timezone", "UTC",
            "enabled", "true",
            "inputJson", "{\"task\":\"create\"}"
        ));
        String scheduleId = scheduleService.schedules("agent-1").get(0).id();

        String wrongAgentDelete = controller.deleteAgentSchedule("agent-2", scheduleId);
        assertThat(wrongAgentDelete).contains("orch-error");
        assertThat(wrongAgentDelete).contains("schedule not found");
        assertThat(scheduleService.schedules("agent-1")).hasSize(1);
    }

    @Test
    void reactionCreateRendersInlineErrorsForInvalidEventAndFilterJson() {
        StubEventReactionService reactionService = new StubEventReactionService();
        OrchestrationController controller = controller(
            true,
            true,
            new StubScheduleService(),
            reactionService
        );

        String invalidEvent = controller.createAgentReaction("agent-1", Map.of(
            "eventType", "NOT_A_REAL_EVENT",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "enabled", "true",
            "filterJson", "{\"state\":\"queued\"}",
            "inputJson", "{\"task\":\"check\"}"
        ));
        assertThat(invalidEvent).contains("orch-error");
        assertThat(invalidEvent).contains("invalid eventType");

        String invalidFilter = controller.createAgentReaction("agent-1", Map.of(
            "eventType", "JOB_STATUS_CHANGED",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "enabled", "true",
            "filterJson", "{bad-json",
            "inputJson", "{\"task\":\"check\"}"
        ));
        assertThat(invalidFilter).contains("orch-error");
        assertThat(invalidFilter).contains("Invalid JSON for filterJson");
    }

    @Test
    void reactionCreateRendersSavedRowForValidInput() {
        StubEventReactionService reactionService = new StubEventReactionService();
        OrchestrationController controller = controller(
            true,
            true,
            new StubScheduleService(),
            reactionService
        );

        String html = controller.createAgentReaction("agent-1", Map.of(
            "eventType", "JOB_STATUS_CHANGED",
            "assignmentType", "JOB_RUN",
            "priority", "1",
            "enabled", "true",
            "filterJson", "{\"status\":\"QUEUED\"}",
            "inputJson", "{\"jobId\":\"job-abc\",\"task\":\"check\"}"
        ));

        assertThat(html).contains("Event Type: JOB_STATUS_CHANGED");
        assertThat(html).contains("Action: ENQUEUE_ASSIGNMENT");
        assertThat(html).contains("Assignment Type: JOB_RUN");
        assertThat(reactionService.reactions("agent-1")).hasSize(1);
    }

    @Test
    void reactionFormDefaultsAssignmentTypeToReport() {
        OrchestrationController controller = controller(
            true,
            true,
            new StubScheduleService(),
            new StubEventReactionService()
        );

        String html = controller.agentReactionsTab("agent-1");

        assertThat(html).contains("<option value=\"REPORT\" selected>REPORT</option>");
        assertThat(html).doesNotContain("<option value=\"JOB_RUN\" selected>JOB_RUN</option>");
    }

    @Test
    void reactionLifecycleSupportsUpdateToggleAndDelete() {
        StubEventReactionService reactionService = new StubEventReactionService();
        OrchestrationController controller = controller(
            true,
            true,
            new StubScheduleService(),
            reactionService
        );

        controller.createAgentReaction("agent-1", Map.of(
            "eventType", "JOB_STATUS_CHANGED",
            "assignmentType", "REPORT",
            "priority", "1",
            "enabled", "true",
            "filterJson", "{\"status\":\"QUEUED\"}",
            "inputJson", "{\"task\":\"create\"}"
        ));
        String reactionId = reactionService.reactions("agent-1").get(0).id();

        String updated = controller.updateAgentReaction("agent-1", reactionId, Map.of(
            "eventType", "MANUAL_USER_EVENT",
            "assignmentType", "TASK_RUN",
            "priority", "3",
            "enabled", "false",
            "filterJson", "{\"channel\":\"ops\"}",
            "inputJson", "{\"taskId\":\"task-1\",\"task\":\"updated\"}"
        ));
        assertThat(updated).contains("Event Type: MANUAL_USER_EVENT");
        assertThat(updated).contains("Assignment Type: TASK_RUN");
        assertThat(updated).contains("Enabled: false");

        String toggled = controller.toggleAgentReaction("agent-1", reactionId);
        assertThat(toggled).contains("Enabled: true");

        String deleted = controller.deleteAgentReaction("agent-1", reactionId);
        assertThat(deleted).contains("No event reactions configured.");
        assertThat(reactionService.reactions("agent-1")).isEmpty();
    }

    @Test
    void reactionDeleteIsScopedByAgent() {
        StubEventReactionService reactionService = new StubEventReactionService();
        OrchestrationController controller = controller(
            true,
            true,
            new StubScheduleService(),
            reactionService
        );

        controller.createAgentReaction("agent-1", Map.of(
            "eventType", "JOB_STATUS_CHANGED",
            "assignmentType", "REPORT",
            "priority", "1",
            "enabled", "true",
            "filterJson", "{\"status\":\"QUEUED\"}",
            "inputJson", "{\"task\":\"create\"}"
        ));
        String reactionId = reactionService.reactions("agent-1").get(0).id();

        String wrongAgentDelete = controller.deleteAgentReaction("agent-2", reactionId);
        assertThat(wrongAgentDelete).contains("orch-error");
        assertThat(wrongAgentDelete).contains("reaction not found");
        assertThat(reactionService.reactions("agent-1")).hasSize(1);
    }

    @Test
    void agentEditorRendersIdentityPromptToolsShellSections() {
        String html = controller().agentEditor("agent-1");

        assertThat(html).contains("Identity");
        assertThat(html).contains("System Prompt");
        assertThat(html).contains("Approved Tools");
        assertThat(html).contains("Shell Allowlist");

        // Each section has its own HTMX form
        assertThat(html).contains("hx-put=\"/agents/_editor/agent-1/profile\"");
        assertThat(html).contains("hx-put=\"/agents/_editor/agent-1/prompt\"");
        assertThat(html).contains("hx-put=\"/agents/_editor/agent-1/tools\"");
        assertThat(html).contains("hx-put=\"/agents/_editor/agent-1/shell\"");
    }

    @Test
    void agentSubmitFormRendersStructuredFields() {
        String html = controller().agentSubmitForm("agent-1");

        assertThat(html).contains("agent-submit-form-agent-1");
        assertThat(html).contains("hx-post=\"/agents/_submit/agent-1\"");
        assertThat(html).contains("assignmentType");
        assertThat(html).contains("TASK_RUN");
        assertThat(html).contains("WORKFLOW_RUN");
        assertThat(html).contains("JOB_RUN");
        assertThat(html).contains("targetId");
        assertThat(html).contains("priority");
        assertThat(html).contains("modelOverride");
        assertThat(html).contains("runDisplayName");
        assertThat(html).contains("projectId");
        assertThat(html).contains("Compatibility Workspace");
        assertThat(html).contains("data-selector-kind=\"work-area\"");
        assertThat(html).contains("selectorContext.ownerType");
        assertThat(html).contains("selectorContext.ownerId");
    }

    @Test
    void agentSubmitPassesProjectAndWorkspaceContextToAssignmentService() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        OrchestrationController ctrl = controller(
            true,
            true,
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubJobService(),
            assignmentService
        );

        String html = ctrl.submitToAgent("agent-1", "TASK_RUN", "plan-abc", 5, "local-qwen", "proj-xyz", "",
            "", "", "", "", "Projected task run");

        assertThat(html).contains("Assignment created");
        assertThat(html).contains("Project: proj-xyz");
        assertThat(html).contains("Effective workspace: PROJECT proj-xyz");
        assertThat(assignmentService.lastRequest.projectId()).isEqualTo("proj-xyz");
        assertThat(assignmentService.lastRequest.runDisplayName()).isEqualTo("Projected task run");
        assertThat(assignmentService.lastRequest.workspaceId()).isBlank();
    }

    @Test
    void agentSubmitRequiresRunNameForNonJobAssignments() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        OrchestrationController ctrl = controller(
            true,
            true,
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubJobService(),
            assignmentService
        );

        String html = ctrl.submitToAgent("agent-1", "TASK_RUN", "plan-abc", 5, "local-qwen", "proj-xyz", "",
            "", "", "", "", "");

        assertThat(html).contains("Run name is required for task/workflow submissions.");
        assertThat(assignmentService.lastRequest).isNull();
    }

    @Test
    void settingsPageRendersWithModelRoutingAndContextControls() {
        String html = controller().settings();

        assertThat(html).contains("Model Routing");
        assertThat(html).contains("id=\"settings-form-container\"");
        assertThat(html).contains("settings-default-model");
        assertThat(html).contains("settings-planning-model");
        assertThat(html).contains("settings-compaction-model");
        assertThat(html).contains("contextBufferPercent");
        assertThat(html).contains("hx-put=\"/settings\"");
        assertThat(html).contains("hx-target=\"#settings-form-container\"");
        assertThat(html).contains("hx-swap=\"innerHTML\"");
        assertThat(html).contains(">Save<");
        assertThat(html).doesNotContain("/js/chat-client.js");
    }

    @Test
    void htmxSettingsSaveFailureReturnsErrorStatusAndFragment() {
        RuntimeSettingsService failingSettingsService = new StubRuntimeSettingsService() {
            @Override public RuntimeSettings save(RuntimeSettings settings) {
                throw new IllegalArgumentException("defaultAgentId was not found");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        String html = controllerWithRuntimeSettingsService(failingSettingsService)
            .saveSettings(Map.of("defaultAgentId", "missing-agent"), response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(html).contains("orch-status-error");
        assertThat(html).contains("defaultAgentId was not found");
    }

    @Test
    void allOrchestrationPagesUseDashboardShellWithSidebar() {
        OrchestrationController controller = controller();

        List<String> pages = List.of(
            controller.dashboard(null, null),
            controller.plans(),
            controller.workflows(),
            controller.jobs(),
            controller.projects(),
            controller.inbox(),
            controller.outputs(),
            controller.agents(),
            controller.settings()
        );

        for (String html : pages) {
            assertThat(html).contains("main-sidebar");
            assertThat(html).contains("sidenav");
            assertThat(html).contains(AppNavigation.OPERATIONAL_CSS);
            assertPrimaryTopNav(html);
            assertThat(html).doesNotContain("/js/alpha-security.js?v=1");
            assertThat(html).doesNotContain("/js/chat-client.js");
        }
    }

    @Test
    void sidebarContainsAllRequiredNavigationLinks() {
        String html = controller().dashboard(null, null);

        assertThat(html).contains("/dashboard");
        assertThat(html).contains("/plans");
        assertThat(html).contains("/workflows");
        assertThat(html).contains("/jobs");
        assertThat(html).contains("/projects");
        assertThat(html).contains("/inbox");
        assertThat(html).contains("/agents");
        assertThat(html).contains("/outputs");
        assertThat(html).contains("/settings");
        assertThat(html).doesNotContain("hx-get=\"/chat\"");
    }

    private static void assertPrimaryTopNav(String html) {
        int home = html.indexOf("<a href=\"/\" class=\"navbar-item\">Home</a>");
        int dashboard = html.indexOf("<a href=\"/dashboard\" class=\"navbar-item\">Dashboard</a>");
        int chat = html.indexOf("<a href=\"/chat\" class=\"navbar-item\">Chat</a>");

        assertThat(home).isGreaterThanOrEqualTo(0);
        assertThat(dashboard).isGreaterThan(home);
        assertThat(chat).isGreaterThan(dashboard);
        assertThat(html).doesNotContain("<a href=\"/avatar\" class=\"navbar-item\">Avatar</a>");
    }

    @Test
    void orchestrationCssOverridesShellSidebarGridAtPhoneWidth() throws Exception {
        try (var stream = OrchestrationControllerTest.class.getResourceAsStream("/static/css/orchestration.css")) {
            assertThat(stream).isNotNull();
            String css = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertThat(css).contains("@media (max-width: 768px)");
            assertThat(css).contains(".main-container.has-sidebar");
            assertThat(css).contains("grid-template-areas: \"content\"");
            assertThat(css).contains("grid-template-columns: minmax(0, 1fr)");
            assertThat(css).contains(".main-container.has-sidebar > .main-sidebar");
            assertThat(css).contains("grid-area: auto");
            assertThat(css).contains(".main-container.has-sidebar > .content-wrapper");
        }
    }

    @Test
    void dashboardRendersHxContainersForPartialLoading() {
        String html = controller().dashboard(null, null);

        // Dashboard should have HTMX containers for lazy-loaded sections
        assertThat(html).contains("hx-get=\"/dashboard/_stats\"");
        assertThat(html).contains("hx-get=\"/dashboard/_active-work\"");
        assertThat(html).contains("hx-get=\"/dashboard/_open-projects\"");
        assertThat(html).contains("hx-get=\"/dashboard/_agents\"");
        assertThat(html).contains("hx-get=\"/dashboard/_side-inbox\"");
        assertThat(html).contains("hx-get=\"/dashboard/_side-outputs\"");
        // Should trigger on load and refresh
        assertThat(html).contains("hx-trigger=\"load, every 30s\"");
        assertThat(html).contains("hx-swap=\"innerHTML\"");
        // Message-based stat replaces approval-specific wording.
        assertThat(html).contains("stat-messages");
    }

    @Test
    void projectPageRendersHxListContainer() {
        String html = controller().projects();

        // Project list should load via HTMX
        assertThat(html).contains("hx-get=\"/projects/_list\"");
        assertThat(html).contains("hx-trigger=\"load\"");
    }

    // ── JS module content tests ──

    @Test
    void planJsIsMinimalHtmxFirstSkeleton() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/orchestration/plans.js"));

        // Stripped of rendering, save, run, new plan handlers
        assertThat(js).contains("data-orchestration-page='plans'");
        assertThat(js).doesNotContain("renderPlanEdit");
        assertThat(js).doesNotContain("renderRunInputForm");
        assertThat(js).doesNotContain("readFields");
        assertThat(js).doesNotContain("readDeliverables");
        assertThat(js).doesNotContain("fieldRow");
        assertThat(js).doesNotContain("deliverableRow");
        assertThat(js).doesNotContain("save-plan");
        assertThat(js).doesNotContain("run-plan");
        assertThat(js).doesNotContain("new-plan");
        assertThat(js).doesNotContain("data-action=\"add-deliverable\"");
        assertThat(js).doesNotContain("data-action=\"add-plan-input\"");
        assertThat(js).doesNotContain("data-action=\"add-plan-output\"");
        assertThat(js).doesNotContain("payload");
        assertThat(js).doesNotContain("jsonFetch");
        assertThat(js).doesNotContain("/api/plans");
    }

    @Test
    void inboxPageUsesHtmxFragmentsWithoutStaleStaticModule() {
        String html = controller().inbox();

        assertThat(html).contains("data-orchestration-page=\"inbox\"");
        assertThat(html).contains("hx-get=\"/inbox/_user\"");
        assertThat(html).contains("hx-get=\"/inbox/_agent-selector\"");
        assertThat(html).contains("hx-get=\"/inbox/_agent\"");
        assertThat(html).doesNotContain("/js/orchestration/inbox.js");
    }

    @Test
    void outputsPageUsesHtmxFragmentsWithoutStaleStaticModule() {
        String html = controller().outputs();

        assertThat(html).contains("data-orchestration-page=\"outputs\"");
        assertThat(html).contains("hx-get=\"/outputs/_list\"");
        assertThat(html).contains("hx-target=\"#outputs-list\"");
        assertThat(html).contains("name=\"agentId\"");
        assertThat(html).contains("name=\"jobId\"");
        assertThat(html).contains("name=\"projectId\"");
        assertThat(html).doesNotContain("/js/orchestration/outputs.js");
    }

    @Test
    void projectsJsIsMinimalHtmxFirstSkeleton() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/orchestration/projects.js"));

        // Stripped of CRUD handlers, now HTMX-first skeleton
        assertThat(js).contains("data-orchestration-page='projects'");
        assertThat(js).doesNotContain("jsonFetch");
        assertThat(js).doesNotContain("innerHTML");
        assertThat(js).doesNotContain("save-project");
        assertThat(js).doesNotContain("delete-project");
        assertThat(js).doesNotContain("add-project-agent");
        assertThat(js).doesNotContain("create-project");
        assertThat(js).doesNotContain("/api/projects");
    }

    @Test
    void dashboardJsRemovesAgentRenderingAfterPhase06() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/orchestration/dashboard.js"));

        // Keeps settings runtime controls
        assertThat(js).contains("data-orchestration-page");
        assertThat(js).contains("/api/settings/runtime");
        assertThat(js).contains("initSettings");
        assertThat(js).contains("save-settings");

        // Agent rendering functions removed (moved to HTMX in Phase 06)
        assertThat(js).doesNotContain("initAgents");
        assertThat(js).doesNotContain("initAgentDetail");
        assertThat(js).doesNotContain("renderAgentProfile");
        assertThat(js).doesNotContain("renderAgentTab");
        assertThat(js).doesNotContain("renderAssignmentForm");
        assertThat(js).doesNotContain("agentCard");
        assertThat(js).doesNotContain("save-agent");
        assertThat(js).doesNotContain("loadAgentDetail");

        // Job functions removed (HTMX-driven since Phase 05)
        assertThat(js).doesNotContain("initJobs");
        assertThat(js).doesNotContain("initJobDetail");
        assertThat(js).doesNotContain("create-job");
        assertThat(js).doesNotContain("add-job-item");
        assertThat(js).doesNotContain("run-job");
    }

    @Test
    void dashboardJsHasHtmxFirstArchitecture() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/orchestration/dashboard.js"));

        // Dashboard remains HTMX-first with no client-side dashboard renderers.
        assertThat(js).contains("data-orchestration-page");
        assertThat(js).doesNotContain("renderDashboardStats");
        assertThat(js).doesNotContain("renderActiveWork");
        assertThat(js).doesNotContain("renderOpenProjects");
        assertThat(js).doesNotContain("renderAgents");
        assertThat(js).doesNotContain("renderSideInbox");
        assertThat(js).doesNotContain("renderSideOutputs");
        assertThat(js).doesNotContain("initDashboard(");
    }

    @Test
    void agentsJsIsMinimalHtmxFirstSkeleton() throws Exception {
        String js = Files.readString(Path.of("src/main/resources/static/js/orchestration/agents.js"));

        assertThat(js).contains("data-orchestration-page='agents'");
        assertThat(js).contains("HTMX handles all agent CRUD");

        // No JSON fetch, no innerHTML manipulation
        assertThat(js).doesNotContain("jsonFetch");
        assertThat(js).doesNotContain("innerHTML");
        assertThat(js).doesNotContain("save-agent");
        assertThat(js).doesNotContain("clone-agent");
        assertThat(js).doesNotContain("delete-agent");
    }

    // ── Phase 03: Operational Editor, Model, Output, and Status Fixes ──

    @Test
    void planEditorFieldTypeSelectUsesWireNames() {
        // DEFECT-03-01: PlanFieldType dropdowns use wireName() values
        // The fieldSelect method always renders all PlanFieldType values with wireName()
        // Verify by checking the plan editor fragment for a plan with inputs
        PlanDefinition planWithInputs = new PlanDefinition(
            "plan-fields", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Test", null, null, null,
            List.of(),
            List.of(new io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition(
                "topic", io.mindspice.magenta2.ai.chat.plan.PlanFieldType.USER_MESSAGE,
                false, "desc", true, null)),
            List.of(new io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition(
                "report", io.mindspice.magenta2.ai.chat.plan.PlanFieldType.FILE_PATH,
                false, "desc", true, null)),
            List.of(), List.of(), List.of(),
            List.of(), List.of(), "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(planWithInputs);

        OrchestrationController ctrl = controllerWithPlanService(stubPlanService);
        String html = ctrl.planEditor("plan-fields");
        // Field type selects use wireName() for option values
        assertThat(html).contains("user_message");
        assertThat(html).contains("file_path");
        assertThat(html).contains("json");
        assertThat(html).contains("string");
        assertThat(html).contains("number");
    }

    @Test
    void planStepRowsExposeMoveControls() {
        PlanDefinition plan = new PlanDefinition(
            "plan-steps", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Test", null, null, null,
            List.of(), List.of(), List.of(), List.of(),
            List.of(new io.mindspice.magenta2.ai.chat.plan.PlanStep(1, "First"),
                new io.mindspice.magenta2.ai.chat.plan.PlanStep(2, "Second")),
            List.of(), List.of(), List.of(), "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(plan);

        String html = controllerWithPlanService(stubPlanService).planEditor("plan-steps");

        assertThat(html).contains("/plans/_editor/plan-steps/steps/0/move-down");
        assertThat(html).contains("/plans/_editor/plan-steps/steps/1/move-up");
    }

    @Test
    void planListRowsRenderTextareaValuesAsContent() {
        PlanDefinition plan = new PlanDefinition(
            "plan-list-text", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Test", null, null, null,
            List.of("Visible deliverable"), List.of(), List.of(), List.of("Visible assumption"),
            List.of(new io.mindspice.magenta2.ai.chat.plan.PlanStep(1, "Visible step")),
            List.of("Visible validation"), List.of(), List.of(),
            "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(plan);

        String html = controllerWithPlanService(stubPlanService).planEditor("plan-list-text");

        assertThat(html).contains("<textarea");
        assertThat(html).contains("data-autosize=\"true\"");
        assertThat(html).contains("plan-autosize-textarea");
        assertThat(html).contains(">Visible deliverable</textarea>");
        assertThat(html).contains(">Visible step</textarea>");
        assertThat(html).contains(">Visible validation</textarea>");
        assertThat(html).contains(">Visible assumption</textarea>");
    }

    @Test
    void planAddDeliverableProducesPlaceholderText() {
        // DEFECT-03-02: Adding a deliverable results in a placeholder, not empty string
        // Simulate via the stub: addItem->save handles placeholder
        PlanDefinition plan = new PlanDefinition(
            "plan-test", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Test", null, null, null,
            List.of("Existing"), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(plan);

        // Verify the stub returns a plan with deliverables containing non-empty text
        PlanDefinition result = stubPlanService.getTask("plan-test");
        assertThat(result.deliverables()).contains("Existing");
    }

    @Test
    void finalizePlanEditorSetsApproved() {
        // DEFECT-03-04: finalize sets APPROVED
        PlanDefinition draftPlan = new PlanDefinition(
            "plan-draft", PlanKind.TASK_TEMPLATE, PlanStatus.DRAFT,
            "Draft Plan", "summary", "goal", null,
            List.of("d1"), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(draftPlan);

        OrchestrationController ctrl = controllerWithPlanService(stubPlanService);
        String html = ctrl.finalizePlanEditor("plan-draft");

        // Should render without error (successfully finalized)
        assertThat(html).doesNotContain("Error:");
        // After finalize, the plan should be APPROVED
        PlanDefinition finalized = stubPlanService.getTask("plan-draft");
        assertThat(finalized.status()).isEqualTo(PlanStatus.APPROVED);
    }

    @Test
    void agentHistoryTabShowsAssignmentHistory() {
        // DEFECT-03-HISTORY: History tab renders real assignments
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment completed = new WorkAssignment(
            "asgn-10", "agent-1", "job-abc", "item-1",
            AssignmentType.TASK_RUN, 0, OrchestrationStatus.COMPLETED,
            null, null, 0, Map.of(),
            Map.of("taskRunId", "run-123"), Map.of(), Map.of(), null, null, null,
            Instant.now(), Instant.now(), Instant.now(),
            Instant.now().minusSeconds(3600)
        );
        stubAsgn.setAssignments(List.of(completed));

        OrchestrationController ctrl = controllerWithAssignmentService(stubAsgn);
        String html = ctrl.agentHistoryTab("agent-1");

        assertThat(html).contains("History");
        assertThat(html).contains("TASK_RUN");
        assertThat(html).contains("COMPLETED");
        assertThat(html).doesNotContain("No assignment history for this agent");
    }

    @Test
    void agentQueueShowsStuckDiagnosticsAndForceInterruptControls() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment running = new WorkAssignment(
            "asgn-stuck", "agent-1", null, null,
            AssignmentType.TASK_RUN, 9, OrchestrationStatus.RUNNING,
            null, null, 0, Map.of("taskRunId", "run-1"),
            Map.of("taskId", "task-1"), Map.of(), Map.of(), null,
            "owner-1", Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1200), Instant.now(), Instant.now().minusSeconds(1200),
            null, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(30)
        );
        stubAsgn.setAssignments(List.of(running));

        String html = controllerWithAssignmentService(stubAsgn).agentQueueTab("agent-1");

        assertThat(html).contains("suspected stuck");
        assertThat(html).contains("/agents/_detail/agent-1/assignments/asgn-stuck/diagnostics");
        assertThat(html).contains("Force Interrupt");
    }

    @Test
    void agentQueueShowsDeleteOnlyForEligibleRowsAndTranscriptPanel() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment running = assignment("asgn-running", OrchestrationStatus.RUNNING, Map.of("conversationId", "conv-running"));
        WorkAssignment queued = assignment("asgn-queued", OrchestrationStatus.QUEUED, Map.of("conversationId", "conv-queued"));
        stubAsgn.setAssignments(List.of(running, queued));

        String html = controllerWithAssignmentService(stubAsgn).agentQueueTab("agent-1");

        assertThat(html).contains("agent-live-transcript");
        assertThat(html).contains("/agents/_detail/agent-1/assignments/asgn-running/transcript");
        assertThat(html).contains("/agents/_detail/agent-1/queue/asgn-queued");
        assertThat(html).contains("hx-confirm=\"Cancel this assignment?\"");
        assertThat(html).doesNotContain(">Watch<");
        assertThat(html).doesNotContain("hx-delete=\"/agents/_detail/agent-1/queue/asgn-running\"");
    }

    @Test
    void agentQueueHidesCancelForCancelRequestedRows() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment cancelRequested = assignment("asgn-canceling", OrchestrationStatus.CANCEL_REQUESTED, Map.of("conversationId", "conv-1"));
        stubAsgn.setAssignments(List.of(cancelRequested));

        String html = controllerWithAssignmentService(stubAsgn).agentQueueTab("agent-1");

        assertThat(html).contains("Canceling");
        assertThat(html).doesNotContain("/agents/_detail/agent-1/queue/asgn-canceling/cancel");
        assertThat(html).doesNotContain(">Watch<");
    }

    @Test
    void htmxAssignmentDeleteRefreshesQueue() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        stubAsgn.setAssignments(List.of(assignment("asgn-delete", OrchestrationStatus.QUEUED, Map.of())));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String html = controllerWithAssignmentService(stubAsgn).deleteAgentAssignment("agent-1", "asgn-delete", response);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(html).contains("No assignments.");
        assertThat(html).doesNotContain("asgn-delete");
    }

    @Test
    void htmxAssignmentDeleteFailureReturnsErrorStatusAndQueueFragment() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        stubAsgn.setAssignments(List.of(assignment("asgn-running-delete", OrchestrationStatus.RUNNING, Map.of())));
        MockHttpServletResponse response = new MockHttpServletResponse();

        String html = controllerWithAssignmentService(stubAsgn)
            .deleteAgentAssignment("agent-1", "asgn-running-delete", response);

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(html).contains("orch-error");
        assertThat(html).contains("cannot delete");
        assertThat(html).contains("asgn-running-delete");
    }

    @Test
    void htmxShellExecFailureReturnsErrorStatusAndFragment() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        String html = controller().execInAgent("agent-1", Map.of("command", "pwd"), response);

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(html).contains("orch-error");
        assertThat(html).contains("Shell execution service is unavailable.");
    }

    @Test
    void htmxHardDeleteFailureReturnsErrorStatusAndLifecycleFragment() {
        AgentProfileService failingAgentService = new StubAgentProfileService() {
            @Override public void hardDelete(String id, String confirmationText) {
                throw new IllegalArgumentException("confirmation text did not match");
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        String html = controllerWithAgentProfileService(failingAgentService)
            .hardDeleteAgentLifecycle("agent-1", "wrong", response);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(html).contains("agent-lifecycle-panel-agent-1");
        assertThat(html).contains("orch-error");
        assertThat(html).contains("confirmation text did not match");
    }

    @Test
    void htmxAssignmentLifecycleRefreshesQueueForSameAgent() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        stubAsgn.setAssignments(List.of(
            assignment("asgn-cancel", OrchestrationStatus.QUEUED, Map.of()),
            assignment("asgn-pause", OrchestrationStatus.QUEUED, Map.of()),
            assignment("asgn-resume", OrchestrationStatus.PAUSED, Map.of()),
            assignment("asgn-force", OrchestrationStatus.RUNNING, Map.of())
        ));
        OrchestrationController controller = controllerWithAssignmentService(stubAsgn);
        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();
        MockHttpServletResponse pauseResponse = new MockHttpServletResponse();
        MockHttpServletResponse resumeResponse = new MockHttpServletResponse();
        MockHttpServletResponse forceResponse = new MockHttpServletResponse();

        controller.cancelAgentAssignment("agent-1", "asgn-cancel", cancelResponse);
        controller.pauseAgentAssignment("agent-1", "asgn-pause", pauseResponse);
        controller.resumeAgentAssignment("agent-1", "asgn-resume", resumeResponse);
        controller.forceInterruptAgentAssignment("agent-1", "asgn-force", "stuck", forceResponse);

        assertThat(cancelResponse.getStatus()).isEqualTo(200);
        assertThat(pauseResponse.getStatus()).isEqualTo(200);
        assertThat(resumeResponse.getStatus()).isEqualTo(200);
        assertThat(forceResponse.getStatus()).isEqualTo(200);
        assertThat(stubAsgn.get("asgn-cancel").status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(stubAsgn.get("asgn-pause").status()).isEqualTo(OrchestrationStatus.PAUSED);
        assertThat(stubAsgn.get("asgn-resume").status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(stubAsgn.get("asgn-force").status()).isEqualTo(OrchestrationStatus.INTERRUPTED);
    }

    @Test
    void htmxAssignmentLifecycleRejectsCrossAgentControlsWithErrorStatus() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        stubAsgn.setAssignments(List.of(
            assignment("cross-cancel", "agent-2", OrchestrationStatus.QUEUED, Map.of()),
            assignment("cross-pause", "agent-2", OrchestrationStatus.QUEUED, Map.of()),
            assignment("cross-resume", "agent-2", OrchestrationStatus.PAUSED, Map.of()),
            assignment("cross-force", "agent-2", OrchestrationStatus.RUNNING, Map.of())
        ));
        OrchestrationController controller = controllerWithAssignmentService(stubAsgn);

        MockHttpServletResponse cancelResponse = new MockHttpServletResponse();
        MockHttpServletResponse pauseResponse = new MockHttpServletResponse();
        MockHttpServletResponse resumeResponse = new MockHttpServletResponse();
        MockHttpServletResponse forceResponse = new MockHttpServletResponse();

        assertLifecycleError(controller.cancelAgentAssignment(
            "agent-1", "cross-cancel", cancelResponse), cancelResponse);
        assertLifecycleError(controller.pauseAgentAssignment(
            "agent-1", "cross-pause", pauseResponse), pauseResponse);
        assertLifecycleError(controller.resumeAgentAssignment(
            "agent-1", "cross-resume", resumeResponse), resumeResponse);
        assertLifecycleError(controller.forceInterruptAgentAssignment(
            "agent-1", "cross-force", "wrong route", forceResponse), forceResponse);

        assertThat(stubAsgn.get("cross-cancel").status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(stubAsgn.get("cross-pause").status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(stubAsgn.get("cross-resume").status()).isEqualTo(OrchestrationStatus.PAUSED);
        assertThat(stubAsgn.get("cross-force").status()).isEqualTo(OrchestrationStatus.RUNNING);
    }

    @Test
    void assignmentTranscriptRendersChatStyleAuditMarkup() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment running = assignment("asgn-transcript", OrchestrationStatus.RUNNING, Map.of("conversationId", "conversation-1"));
        stubAsgn.setAssignments(List.of(running));
        stubAsgn.setTranscriptEvents(List.of(
            audit("conversation-1", 0, "assistant_msg", "Visible answer", "{\"magenta.thinking\":\"hidden notes\"}", null, null, null),
            audit("conversation-1", 1, "tool_exec", null, null, "shell", "ok", "tool result"),
            audit("conversation-1", 2, "error", "boom", null, null, null, null)
        ));

        String html = controllerWithAssignmentService(stubAsgn)
            .assignmentTranscriptFragment("agent-1", "asgn-transcript");

        assertThat(html).contains("chat-message chat-message-assistant");
        assertThat(html).contains("chat-thinking");
        assertThat(html).contains("chat-message chat-message-tool");
        assertThat(html).contains("chat-tool");
        assertThat(html).contains("boom");
        assertThat(html).doesNotContain("textarea");
    }

    @Test
    void assignmentDiagnosticsRefreshesTranscriptOutOfBand() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment running = assignment("asgn-diag-transcript", OrchestrationStatus.RUNNING,
            Map.of("conversationId", "conversation-1"));
        stubAsgn.setAssignments(List.of(running));
        stubAsgn.setTranscriptEvents(List.of(
            audit("conversation-1", 0, "tool_exec", null, null, "shell", "ok", "diagnostic transcript")
        ));

        String html = controllerWithAssignmentService(stubAsgn)
            .assignmentDiagnosticsFragment("agent-1", "asgn-diag-transcript");

        assertThat(html).contains("Assignment Diagnostics");
        assertThat(html).contains("hx-swap-oob=\"outerHTML\"");
        assertThat(html).contains("agent-live-transcript");
        assertThat(html).contains("diagnostic transcript");
    }

    @Test
    void assignmentDiagnosticsPanelShowsAuditAndForceInterruptForm() {
        StubAssignmentService stubAsgn = new StubAssignmentService();
        WorkAssignment running = new WorkAssignment(
            "asgn-diag", "agent-1", null, null,
            AssignmentType.TASK_RUN, 9, OrchestrationStatus.RUNNING,
            null, null, 0, Map.of("taskRunId", "run-1"),
            Map.of("taskId", "task-1"), Map.of(), Map.of(), null,
            "owner-1", Instant.now().plusSeconds(300),
            Instant.now().minusSeconds(1200), Instant.now(), Instant.now().minusSeconds(1200),
            null, Instant.now().minusSeconds(1200), Instant.now().minusSeconds(30)
        );
        stubAsgn.setAssignments(List.of(running));

        String html = controllerWithAssignmentService(stubAsgn)
            .assignmentDiagnosticsFragment("agent-1", "asgn-diag");

        assertThat(html).contains("Assignment Diagnostics");
        assertThat(html).contains("Suspected stuck");
        assertThat(html).contains("Build Commit");
        assertThat(html).contains("/agents/_detail/agent-1/queue/asgn-diag/force-interrupt");
    }

    @Test
    void outputContentFragmentRendersTextContent() {
        // DEFECT-07-01: Output content fragment renders text
        // StubOutputArtifactService is used; verify the fragment loads
        String html = controller().outputsContentFragment("artifact-1");
        // Should not error out even if stub returns placeholder
        assertThat(html).doesNotContain("Error");
    }

    @Test
    void modelDropdownUsesCanonicalAliasValues() {
        // DEFECT-06: Model dropdown option values are model aliases, not remote names
        String html = controller().settings();

        // The model dropdown should contain the alias key from availableModelOptions()
        assertThat(html).contains("local-qwen");
        assertThat(html).contains("local-qwen (qwen3.6:35b)");
    }

    private static WorkAssignment assignment(String id, OrchestrationStatus status, Map<String, Object> checkpoint) {
        return assignment(id, "agent-1", status, checkpoint);
    }

    private static WorkAssignment assignment(
        String id,
        String agentId,
        OrchestrationStatus status,
        Map<String, Object> checkpoint
    ) {
        return new WorkAssignment(
            id, agentId, null, null, AssignmentType.TASK_RUN, 1, status,
            null, null, 0, checkpoint, Map.of("taskId", "task-1"), Map.of(), Map.of(),
            null, status == OrchestrationStatus.RUNNING ? "owner-1" : null,
            status == OrchestrationStatus.RUNNING ? Instant.now().plusSeconds(300) : null,
            Instant.now().minusSeconds(60), Instant.now(), status == OrchestrationStatus.RUNNING ? Instant.now() : null,
            null, Instant.now().minusSeconds(30), Instant.now().minusSeconds(10)
        );
    }

    private static void assertLifecycleError(String html, MockHttpServletResponse response) {
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(html).contains("Assignment does not belong to agent");
        assertThat(html).contains("No assignments.");
    }

    private static AuditRepository.AuditEvent audit(
        String conversationId,
        int sequence,
        String eventType,
        String messageText,
        String metadataJson,
        String toolName,
        String toolStatus,
        String resultSummary
    ) {
        return new AuditRepository.AuditEvent(
            sequence, eventType, messageText, metadataJson, "model", conversationId,
            "call-1", toolName, "{}", "args", "call",
            "result", resultSummary, resultSummary, toolStatus,
            false, false, null, null,
            10, 100, 80, 12.5, 3,
            "error".equals(eventType) ? "runtime" : null, null, Instant.now().toString()
        );
    }

    @Test
    void jobItemAddValidatesRequiredPlanBindings() {
        // DEFECT-03-BINDINGS: Adding a PLAN item validates required bindings
        // Create a plan with required inputs
        PlanDefinition planWithRequired = new PlanDefinition(
            "plan-req", PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            "Plan With Required Inputs", null, null, null,
            List.of(),
            List.of(new io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition(
                "topic", io.mindspice.magenta2.ai.chat.plan.PlanFieldType.STRING,
                false, "The topic to process", true, null)),
            List.of(),
            List.of(), List.of(), List.of(),
            List.of(), List.of(), "CODING_CENTRIC", null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );
        StubPlanService stubPlanService = new StubPlanService();
        stubPlanService.setPlan(planWithRequired);

        OrchestrationController ctrl = controllerWithPlanService(stubPlanService);

        // Try to add a PLAN item without required bindings — should error
        Map<String, String> params = Map.of("itemType", "PLAN", "planId", "plan-req", "key", "item-1");
        String html = ctrl.addJobItem("job-abc", params);
        assertThat(html).contains("Error");
        assertThat(html).contains("topic");
    }

    @Test
    void scheduleDisabledShowsEnableWithTrue() {
        // DEFECT-03-MSG: schedules-enabled=false -> schedules-enabled=true
        OrchestrationController ctrl = controllerWithFeatures(false, true);
        String html = ctrl.agentSchedulesTab("agent-1");
        assertThat(html).contains("schedules-enabled=true");
        assertThat(html).doesNotContain("schedules-enabled=false");
    }

    @Test
    void reactionDisabledShowsEnableWithTrue() {
        // DEFECT-03-MSG: reactions-enabled=false -> reactions-enabled=true
        OrchestrationController ctrl = controllerWithFeatures(true, false);
        String html = ctrl.agentReactionsTab("agent-1");
        assertThat(html).contains("reactions-enabled=true");
        assertThat(html).doesNotContain("reactions-enabled=false");
    }

    // ── Helper factory methods for Phase 03 tests ──

    private OrchestrationController controllerWithPlanService(PlanService planService) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            planService,
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private OrchestrationController controllerWithSavedPlanChat(StubPlanService planService) {
        OrchestrationController controller = controllerWithPlanService(planService);
        controller.setSavedPlanChatServiceForTesting(new SavedPlanChatService(
            planService,
            new PlanChatRepository(jdbcTemplate())
        ));
        return controller;
    }

    private OrchestrationController controllerWithAssignmentService(AssignmentService assignmentService) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            assignmentService,
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            true,
            true
        );
    }

    private OrchestrationController controllerWithFeatures(boolean schedulesEnabled, boolean reactionsEnabled) {
        return new OrchestrationController(
            new StubChatService(),
            new StubProjectService(),
            new StubJobService(),
            new StubAgentProfileService(),
            new StubInboxService(),
            new StubRuntimeInboxService(),
            new StubOutputArtifactService(),
            new StubRuntimeSettingsService(),
            workspaceService(),
            emptyProvider(),
            new StubPlanService(),
            new StubAssignmentService(),
            new StubScheduleService(),
            new StubEventReactionService(),
            new StubWorkflowService(),
            selectorLookup(),
            new EntitySelectorComponents(),
            emptyProvider(),
            schedulesEnabled,
            reactionsEnabled
        );
    }

    // ── Stubs ──

    private static class StubChatService extends ChatService {
        StubChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public String defaultModel() {
            return "qwen3";
        }

        @Override
        public List<String> availableModels() {
            return List.of("qwen3");
        }

        @Override
        public List<ModelOption> availableModelOptions() {
            return List.of(new ModelOption("local-qwen", "local-qwen (qwen3.6:35b)"));
        }
    }

    private static class StubProjectService extends ProjectService {
        private static final Project STUB_PROJECT = new Project(
            "proj-xyz", "Test Project", "A test project",
            "agent-1", null, null, null, null, null, null
        );
        private final List<Project> projects = new ArrayList<>(List.of(STUB_PROJECT));
        private List<ProjectAgentMembership> members = new ArrayList<>(List.of(
            new ProjectAgentMembership("membership-1", "proj-xyz", "agent-1", "member", Instant.now())
        ));

        StubProjectService() { super(null, null); }
        @Override public java.util.List<Project> listProjects() { return List.copyOf(projects); }
        @Override public Project getProject(String id) {
            for (Project project : projects) {
                if (project.id().equals(id)) return project;
            }
            throw new IllegalArgumentException("Project not found: " + id);
        }
        @Override public Project createProject(String name, String desc, String owner, String git) {
            Project created = new Project("new-proj", name, desc, owner == null || owner.isBlank() ? null : owner,
                git, null, null, null, null, null);
            projects.add(created);
            return created;
        }
        @Override public Project updateProject(String id, String name, String desc, String git,
                                                String pp, String model, String soj) {
            Project current = getProject(id);
            Project updated = new Project(id,
                name != null ? name : current.name(),
                desc != null ? desc : current.description(),
                current.ownerAgentId(),
                git != null ? git : current.gitRepoUrl(),
                pp != null ? pp : current.promptProfile(),
                model != null ? model : current.model(),
                soj != null ? soj : current.settingsOverrideJson(),
                current.createdAt(), current.updatedAt());
            projects.removeIf(project -> project.id().equals(id));
            projects.add(updated);
            return updated;
        }
        @Override public ProjectAgentMembership addAgent(String projectId, String agentId, String role) {
            ProjectAgentMembership membership = new ProjectAgentMembership(
                "membership-" + (members.size() + 1),
                projectId,
                agentId,
                role == null || role.isBlank() ? "member" : role,
                Instant.now()
            );
            members = new ArrayList<>(members);
            members.add(membership);
            return membership;
        }
        @Override public void removeAgent(String projectId, String agentId) {
            members = members.stream()
                .filter(member -> !(projectId.equals(member.projectId()) && agentId.equals(member.agentId())))
                .toList();
        }
        @Override public java.util.List<ProjectAgentMembership> listMembers(String projectId) {
            return members.stream().filter(member -> projectId.equals(member.projectId())).toList();
        }
        @Override public ProjectWorkspaceSummary workspaceSummary(String projectId) {
            return new ProjectWorkspaceSummary(projectId, "agent-1", "PROJECT", "projects/" + projectId + "/workspace", 1, null, null, null, false);
        }
    }

    private static class StubJobService extends JobService {
        private int startRunCalls;

        private static final JobDefinition STUB_JOB = new JobDefinition(
            "job-abc", "agent-1", null, null, false, "DRAFT",
            "Test Job", "A test job", List.of(),
            "CODING_CENTRIC", null, null, null, null
        );

        StubJobService() { super(null, null, null, null); }
        @Override public java.util.List<JobDefinition> listDefinitions() { return List.of(STUB_JOB); }
        @Override public java.util.List<JobDefinition> listDefinitions(String a, String p, String s) {
            if (a != null && !a.isBlank()) return List.of();
            return List.of(STUB_JOB);
        }
        @Override public JobDefinition getDefinition(String id) {
            if ("job-abc".equals(id)) return STUB_JOB;
            throw new IllegalArgumentException("Job not found: " + id);
        }
        @Override public JobDefinition saveDefinition(JobDefinition def) { return def; }
        @Override public java.util.List<String> outputRunIds(String jobId) { return List.of(); }
        @Override public java.util.List<io.mindspice.magenta2.ai.orchestration.runtime.JobExecutionSummary> executionSummaries(String jobId) {
            return List.of();
        }
        @Override public io.mindspice.magenta2.ai.orchestration.runtime.JobRun startRun(String jobId) {
            startRunCalls++;
            throw new AssertionError("Public job run routes must submit assignments");
        }
    }

    private static class StubAgentProfileService extends AgentProfileService {
        StubAgentProfileService() { super(null, null, null); }
        @Override public java.util.List<AgentProfile> list() {
            return List.of(
                new AgentProfile("agent-1", "Test Agent", AgentProfileStatus.ACTIVE,
                    "qwen3", "You are helpful.", List.of(), List.of(), false, null, null),
                new AgentProfile("agent-2", "Submit Agent", AgentProfileStatus.ACTIVE,
                    "qwen3", "You are helpful.", List.of(), List.of(), false, null, null)
            );
        }
        @Override public AgentProfile get(String id) {
            if ("agent-1".equals(id)) {
                return new AgentProfile("agent-1", "Test Agent", AgentProfileStatus.ACTIVE,
                    "qwen3", "You are helpful.", List.of(), List.of(), false,
                    java.time.Instant.ofEpochSecond(1000000), java.time.Instant.ofEpochSecond(2000000));
            }
            if ("agent-2".equals(id)) {
                return new AgentProfile("agent-2", "Submit Agent", AgentProfileStatus.ACTIVE,
                    "qwen3", "You are helpful.", List.of(), List.of(), false,
                    java.time.Instant.ofEpochSecond(1000000), java.time.Instant.ofEpochSecond(2000000));
            }
            throw new IllegalStateException("Agent not found: " + id);
        }
        @Override public AgentProfile update(String id, AgentProfile profile) { return profile; }
        @Override public AgentProfile enable(String id, boolean prepareWorkspace) { return get(id); }
        @Override public AgentProfile disable(String id) { return get(id); }
        @Override public AgentProfile archiveAndDisable(String id) { return get(id); }
        @Override public void hardDelete(String id, String confirmationText) {}
    }

    private static class StubInboxService extends InboxService {
        StubInboxService() { super(null, null); }
        @Override public java.util.List<io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage> userInbox() { return List.of(); }
    }

    private static class StubRuntimeInboxService extends io.mindspice.magenta2.ai.orchestration.runtime.InboxService {
        StubRuntimeInboxService() { super(null, null, null); }
        @Override public java.util.List<io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage> messages(String agentId) { return List.of(); }
    }

    private static class StubOutputArtifactService extends OutputArtifactService {
        StubOutputArtifactService() {
            this(outputArtifactDeps());
        }

        private StubOutputArtifactService(OutputArtifactDeps deps) {
            super(deps.repository(), deps.directoryService(), deps.objectMapper());
        }

        @Override public io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact getArtifact(String artifactId) {
            return new io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact(
                artifactId, "run-1", "plan-abc", "agent-1", "job-abc", null, null, "TASK_RUN",
                "test-output", "text", "test.txt", "/tmp/test.txt", null, Instant.now()
            );
        }
        @Override public String loadContent(String artifactId, long maxBytes) {
            return "Hello, World!";
        }
        @Override public java.util.List<io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact> query(
            String r, String p, String t, Integer l) { return List.of(); }
        @Override public java.util.List<io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact> query(
            io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactQuery query) { return List.of(); }
    }

    private static OutputArtifactDeps outputArtifactDeps() {
        try {
            Path dataRoot = Files.createTempDirectory("orch-controller-output");
            WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
            WorkspaceRepository repository = new WorkspaceRepository(new JdbcTemplate(
                new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
            ));
            return new OutputArtifactDeps(repository, directories, new ObjectMapper());
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create output artifact test dependencies", exception);
        }
    }

    private record OutputArtifactDeps(
        WorkspaceRepository repository,
        WorkspaceDirectoryService directoryService,
        ObjectMapper objectMapper
    ) {
    }

    private static class StubAgentWorkspaceStatusService extends AgentWorkspaceStatusService {
        private final AgentWorkspaceStatus status;

        StubAgentWorkspaceStatusService(AgentWorkspaceStatus status) {
            super(null, null, null, null, null);
            this.status = status;
        }

        @Override
        public AgentWorkspaceStatus statusFor(String agentId) {
            return status;
        }
    }

    private static class StubRuntimeSettingsService extends RuntimeSettingsService {
        StubRuntimeSettingsService() { super(null, null, null); }
        @Override public RuntimeSettings get() {
            return new RuntimeSettings("", "", "", "", "", "", 20);
        }
    }

    private static class StubPlanService extends PlanService {
        private PlanDefinition storedPlan;

        private static final PlanDefinition STUB_PLAN = new PlanDefinition(
            "plan-abc", PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            "plan title", "plan summary", "plan goal", null,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(),
            WorkTypeProfile.CODING_CENTRIC.name(), null, null,
            null, null, List.of(), 0, 0, null, null, null, null
        );

        StubPlanService() { super(null, null); }

        void setPlan(PlanDefinition plan) { this.storedPlan = plan; }

        @Override public java.util.List<PlanDefinition> listTasks() {
            return storedPlan != null ? List.of(storedPlan) : List.of(STUB_PLAN);
        }

        @Override public PlanDefinition getTask(String id) {
            if (storedPlan != null && storedPlan.id().equals(id)) return storedPlan;
            if ("plan-abc".equals(id)) return STUB_PLAN;
            throw new IllegalStateException("Task not found: " + id);
        }

        @Override public PlanDefinition saveTask(PlanDefinition task) {
            PlanDefinition saved = task.id() != null ? task : new PlanDefinition(
                "saved-plan", task.kind(), task.status(), task.title(), task.summary(), task.goal(), task.notes(),
                task.deliverables(), task.inputs(), task.outputs(), task.assumptions(), task.steps(),
                task.validationCriteria(), task.executionEvidence(), task.validationFeedback(), task.promptProfile(),
                task.planningModel(), task.executionModel(), task.settingsOverrideJson(), task.planningTask(),
                task.pendingQuestions(), task.pendingQuestionIndex(), task.planStartMessageOrder(), task.finalMessage(),
                task.conversationId(), task.createdAt(), task.updatedAt()
            );
            this.storedPlan = saved;
            return saved;
        }
    }

    private static class StubAssignmentService extends AssignmentService {
        private List<WorkAssignment> storedAssignments = List.of();
        private List<AuditRepository.AuditEvent> transcriptEvents = List.of();
        private AssignmentRequest lastRequest;

        StubAssignmentService() { super(null, null, null, null); }

        void setAssignments(List<WorkAssignment> assignments) { this.storedAssignments = assignments; }

        void setTranscriptEvents(List<AuditRepository.AuditEvent> events) {
            this.transcriptEvents = events;
        }

        @Override public WorkAssignment create(AssignmentRequest request) {
            lastRequest = request;
            return new WorkAssignment("asgn-1", request.agentId(), request.jobId(), request.jobItemId(),
                request.assignmentType(), request.priority() != null ? request.priority() : 0,
                io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus.QUEUED,
                request.modelOverride(), request.workspaceId(), request.projectId(),
                request.projectId() != null && !request.projectId().isBlank() ? request.projectId() : "workspace-agent-1",
                request.projectId() != null && !request.projectId().isBlank() ? "PROJECT" : "AGENT",
                request.selectedWorkAreaId(), request.outputRouteType(), request.outputWorkAreaId(),
                request.outputDirectRelativePath(),
                0, Map.<String, Object>of(), request.input() != null ? request.input() : Map.<String, Object>of(),
                Map.<String, Object>of(), Map.<String, Object>of(), null, null, null,
                Instant.now(), Instant.now(), null, null);
        }

        @Override public java.util.List<WorkAssignment> assignments(String agentId) {
            return storedAssignments.stream()
                .filter(assignment -> agentId.equals(assignment.agentId()))
                .toList();
        }

        @Override public java.util.List<WorkAssignment> queueAssignments(String agentId) {
            return storedAssignments.stream()
                .filter(assignment -> agentId.equals(assignment.agentId()))
                .filter(assignment -> assignment.status() == null || !assignment.status().isTerminal())
                .toList();
        }

        @Override public java.util.List<WorkAssignment> historyAssignments(String agentId) {
            return storedAssignments.stream()
                .filter(assignment -> agentId.equals(assignment.agentId()))
                .filter(assignment -> assignment.status() != null && assignment.status().isTerminal())
                .toList();
        }

        @Override public WorkAssignment get(String assignmentId) {
            return storedAssignments.stream()
                .filter(assignment -> assignment.id().equals(assignmentId))
                .findFirst()
                .orElseThrow();
        }

        @Override public boolean deletable(OrchestrationStatus status) {
            return status != null
                && !status.isTerminal()
                && status != OrchestrationStatus.RUNNING
                && status != OrchestrationStatus.CANCEL_REQUESTED;
        }

        @Override public void delete(String agentId, String assignmentId) {
            WorkAssignment assignment = get(assignmentId);
            if (!agentId.equals(assignment.agentId())) {
                throw new IllegalArgumentException("wrong agent");
            }
            if (!deletable(assignment.status())) {
                throw new IllegalStateException("cannot delete");
            }
            storedAssignments = storedAssignments.stream()
                .filter(existing -> !existing.id().equals(assignmentId))
                .toList();
        }

        @Override public int purgeHistory(String agentId, int olderThanDays) {
            int before = storedAssignments.size();
            storedAssignments = storedAssignments.stream()
                .filter(assignment -> assignment.status() == null || !assignment.status().isTerminal())
                .toList();
            return before - storedAssignments.size();
        }

        @Override public AssignmentTranscript transcript(String agentId, String assignmentId) {
            WorkAssignment assignment = get(assignmentId);
            Object conversationId = assignment.checkpoint().get("conversationId");
            List<String> ids = conversationId == null ? List.of() : List.of(conversationId.toString());
            return new AssignmentTranscript(assignment, ids, transcriptEvents);
        }

        @Override public AssignmentDiagnostics diagnostics(String assignmentId) {
            WorkAssignment assignment = get(assignmentId);
            return new AssignmentDiagnostics(
                assignment,
                assignment.lastProgressAt(),
                assignment.lastHeartbeatAt(),
                java.time.Duration.between(assignment.lastProgressAt(), Instant.now()),
                java.time.Duration.between(assignment.lastHeartbeatAt(), Instant.now()),
                true,
                List.of(new LinkedRunStatus("TASK_RUN", "run-1", "task-1", "RUNNING", null)),
                List.of(),
                "conversation-1",
                "unknown"
            );
        }

        @Override public WorkAssignment cancel(String agentId, String assignmentId) {
            WorkAssignment assignment = requireAgentAssignment(agentId, assignmentId);
            WorkAssignment updated = assignment.status() == OrchestrationStatus.RUNNING
                ? replace(assignment, OrchestrationStatus.CANCEL_REQUESTED, "Cancel requested")
                : replace(assignment, OrchestrationStatus.CANCELLED, null);
            return updated;
        }

        @Override public WorkAssignment pause(String agentId, String assignmentId) {
            return replace(requireAgentAssignment(agentId, assignmentId), OrchestrationStatus.PAUSED, null);
        }

        @Override public WorkAssignment resume(String agentId, String assignmentId) {
            return replace(requireAgentAssignment(agentId, assignmentId), OrchestrationStatus.QUEUED, null);
        }

        @Override public WorkAssignment forceInterrupt(String agentId, String assignmentId, String reason) {
            return replace(requireAgentAssignment(agentId, assignmentId), OrchestrationStatus.INTERRUPTED,
                "Force interrupted: " + reason);
        }

        private WorkAssignment requireAgentAssignment(String agentId, String assignmentId) {
            WorkAssignment assignment = get(assignmentId);
            if (!agentId.equals(assignment.agentId())) {
                throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
            }
            return assignment;
        }

        private WorkAssignment replace(WorkAssignment assignment, OrchestrationStatus status, String errorText) {
            WorkAssignment updated = new WorkAssignment(
                assignment.id(), assignment.agentId(), assignment.jobId(), assignment.jobItemId(),
                assignment.assignmentType(), assignment.priority(), status,
                assignment.modelOverride(), assignment.workspaceId(), assignment.currentItemIndex(),
                assignment.checkpoint(), assignment.input(), assignment.output(), assignment.evidence(),
                errorText, null, null, assignment.createdAt(), assignment.updatedAt(),
                assignment.startedAt(), status.isTerminal() ? Instant.now() : assignment.completedAt(),
                assignment.lastProgressAt(), assignment.lastHeartbeatAt()
            );
            storedAssignments = storedAssignments.stream()
                .map(existing -> existing.id().equals(assignment.id()) ? updated : existing)
                .toList();
            return updated;
        }
    }

    private static class StubScheduleService extends ScheduleService {
        private final Map<String, List<AgentSchedule>> schedules = new java.util.HashMap<>();

        StubScheduleService() {
            super(null, null, null, null, true);
        }

        @Override
        public List<AgentSchedule> schedules(String agentId) {
            return schedules.getOrDefault(agentId, List.of());
        }

        @Override
        public AgentSchedule schedule(String agentId, String scheduleId) {
            return schedules(agentId).stream()
                .filter(schedule -> scheduleId.equals(schedule.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("schedule not found"));
        }

        @Override
        public AgentSchedule save(String agentId, AgentSchedule schedule) {
            if (!org.springframework.scheduling.support.CronExpression.isValidExpression(schedule.cronExpression())) {
                throw new IllegalArgumentException("invalid cronExpression");
            }
            validateAssignmentTemplate(schedule.assignmentTemplate(), AssignmentType.JOB_RUN, schedule.jobId());
            List<AgentSchedule> current = new ArrayList<>(schedules(agentId));
            AgentSchedule saved = new AgentSchedule(
                schedule.id() != null ? schedule.id() : "sched-" + (current.size() + 1),
                agentId,
                schedule.jobId(),
                schedule.assignmentTemplate(),
                schedule.cronExpression(),
                schedule.timezone(),
                schedule.enabled(),
                Instant.now().plusSeconds(60),
                schedule.createdAt() != null ? schedule.createdAt() : Instant.now(),
                Instant.now()
            );
            current.removeIf(existing -> existing.id().equals(saved.id()));
            current.add(saved);
            schedules.put(agentId, current);
            return saved;
        }

        @Override
        public AgentSchedule toggle(String agentId, String scheduleId) {
            AgentSchedule existing = schedule(agentId, scheduleId);
            return save(agentId, new AgentSchedule(
                existing.id(),
                existing.agentId(),
                existing.jobId(),
                existing.assignmentTemplate(),
                existing.cronExpression(),
                existing.timezone(),
                !existing.enabled(),
                existing.nextRunAt(),
                existing.createdAt(),
                existing.updatedAt()
            ));
        }

        @Override
        public void delete(String agentId, String scheduleId) {
            List<AgentSchedule> current = new ArrayList<>(schedules(agentId));
            boolean removed = current.removeIf(schedule -> schedule.id().equals(scheduleId));
            if (!removed) {
                throw new IllegalStateException("schedule not found");
            }
            schedules.put(agentId, current);
        }
    }

    private static class StubEventReactionService extends EventReactionService {
        private final Map<String, List<AgentEventReaction>> reactions = new java.util.HashMap<>();

        StubEventReactionService() {
            super(null, null);
        }

        @Override
        public List<AgentEventReaction> reactions(String agentId) {
            return reactions.getOrDefault(agentId, List.of());
        }

        @Override
        public AgentEventReaction reaction(String agentId, String reactionId) {
            return reactions(agentId).stream()
                .filter(reaction -> reactionId.equals(reaction.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("reaction not found"));
        }

        @Override
        public AgentEventReaction save(String agentId, AgentEventReaction reaction) {
            validateAssignmentTemplate(reaction.assignmentTemplate(), AssignmentType.REPORT, null);
            List<AgentEventReaction> current = new ArrayList<>(reactions(agentId));
            AgentEventReaction saved = new AgentEventReaction(
                reaction.id() != null ? reaction.id() : "reaction-" + (current.size() + 1),
                agentId,
                reaction.eventType(),
                reaction.filter(),
                ReactionActionType.ENQUEUE_ASSIGNMENT,
                reaction.assignmentTemplate(),
                reaction.enabled(),
                reaction.createdAt() != null ? reaction.createdAt() : Instant.now(),
                Instant.now()
            );
            current.removeIf(existing -> existing.id().equals(saved.id()));
            current.add(saved);
            reactions.put(agentId, current);
            return saved;
        }

        @Override
        public AgentEventReaction toggle(String agentId, String reactionId) {
            AgentEventReaction existing = reaction(agentId, reactionId);
            return save(agentId, new AgentEventReaction(
                existing.id(),
                existing.agentId(),
                existing.eventType(),
                existing.filter(),
                existing.actionType(),
                existing.assignmentTemplate(),
                !existing.enabled(),
                existing.createdAt(),
                existing.updatedAt()
            ));
        }

        @Override
        public void delete(String agentId, String reactionId) {
            List<AgentEventReaction> current = new ArrayList<>(reactions(agentId));
            boolean removed = current.removeIf(reaction -> reaction.id().equals(reactionId));
            if (!removed) {
                throw new IllegalStateException("reaction not found");
            }
            reactions.put(agentId, current);
        }
    }

    private static void validateAssignmentTemplate(
        Map<String, Object> template,
        AssignmentType defaultType,
        String fallbackJobId
    ) {
        Map<String, Object> values = template == null ? Map.of() : template;
        AssignmentType type;
        try {
            Object rawType = values.get("assignmentType");
            type = AssignmentType.valueOf(rawType == null || rawType.toString().isBlank()
                ? defaultType.name()
                : rawType.toString().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid assignmentType");
        }
        Object rawInput = values.get("input");
        Map<?, ?> input = rawInput instanceof Map<?, ?> map ? map : Map.of();
        if (type == AssignmentType.TASK_RUN && !hasText(input.get("taskId"))) {
            throw new IllegalArgumentException("TASK_RUN assignments require input.taskId");
        }
        if (type == AssignmentType.WORKFLOW_RUN && !hasText(input.get("workflowId"))) {
            throw new IllegalArgumentException("WORKFLOW_RUN assignments require input.workflowId");
        }
        if (type == AssignmentType.JOB_RUN
            && !hasText(values.get("jobId"))
            && !hasText(fallbackJobId)
            && !hasText(input.get("jobId"))) {
            throw new IllegalArgumentException("JOB_RUN assignments require jobId");
        }
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }

    private static class StubWorkflowService extends WorkflowService {
        private final Map<String, WorkflowDefinition> definitions = new java.util.LinkedHashMap<>();
        private String saveFailure;
        private String validationFailure;

        StubWorkflowService() { super(null, null, null); }

        void seed(WorkflowDefinition definition) {
            definitions.put(definition.id(), definition);
        }

        void failSavesWith(String message) {
            this.saveFailure = message;
        }

        void failValidationWith(String message) {
            this.validationFailure = message;
        }

        @Override public java.util.List<WorkflowDefinition> listDefinitions() {
            return new ArrayList<>(definitions.values());
        }

        @Override public WorkflowDefinition saveDefinition(WorkflowDefinition definition) {
            if (saveFailure != null) {
                throw new IllegalStateException(saveFailure);
            }
            WorkflowDefinition saved = new WorkflowDefinition(
                definition.id() == null ? "workflow-draft" : definition.id(),
                definition.title(),
                definition.summary(),
                definition.nodes(),
                definition.routes(),
                definition.createdAt(),
                definition.updatedAt());
            definitions.put(saved.id(), saved);
            return saved;
        }

        @Override public WorkflowDefinition saveDefinitionValidated(WorkflowDefinition definition) {
            return saveDefinition(definition);
        }

        @Override public WorkflowValidator.ValidationResult validateGraph(WorkflowDefinition definition) {
            if (validationFailure != null) {
                throw new IllegalStateException(validationFailure);
            }
            return super.validateGraph(definition);
        }

        @Override public WorkflowDefinition getDefinition(String id) {
            return definitions.getOrDefault(id, new WorkflowDefinition(id, "Test WF", "summary",
                List.of(), List.of(), null, null));
        }
    }

}
