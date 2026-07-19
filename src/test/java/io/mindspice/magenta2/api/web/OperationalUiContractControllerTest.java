package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobWorkItemType;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessageToType;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationalUiContractControllerTest {

    @TempDir
    Path tempDir;

    @Test
    void projectCreateUpdateAndWorkspaceUseCanonicalFields() throws IOException {
        ProjectController controller = projectController();

        var project = controller.create(new ProjectController.CreateProjectRequest(
            "Contract Project", "Description", null, null, "agent-1", null
        ));
        assertThat(project.name()).isEqualTo("Contract Project");
        assertThat(project.description()).isEqualTo("Description");

        var updated = controller.update(project.id(), new ProjectController.UpdateProjectRequest(
            "Renamed", "Updated", null, null, null, null, null, null
        ));
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.description()).isEqualTo("Updated");

        var workspace = controller.workspace(project.id());
        assertThat(workspace.workspaceId()).isEqualTo(project.id());
        assertThat(workspace.ownerAgentId()).isEqualTo("agent-1");
        assertThat(workspace.rootKind()).isEqualTo("PROJECT");
        assertThat(workspace.displayPath()).contains("projects").contains(project.id());
    }

    @Test
    void projectCreateWithoutOwnerAgentUsesOwnerlessCompatibilityContract() throws IOException {
        ProjectController controller = projectController();

        var project = controller.create(new ProjectController.CreateProjectRequest(
            "No Owner", "", null, null, "", null
        ));

        assertThat(project.ownerAgentId()).isNull();
        assertThat(controller.workspace(project.id()).ownerAgentId()).isNull();
    }

    @Test
    void jobDraftItemAndOutputContractsAreCallable() throws IOException {
        var services = jobServices();
        JobController controller = new JobController(services.jobService(), services.outputArtifactService());

        var job = controller.create(new io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition(
            null, "agent-1", "project-1", null, "DRAFT",
            "Job", "", List.of(), null, null, null, null, null
        ));
        assertThat(job.items()).isEmpty();
        assertThat(controller.list("agent-1", null, null)).extracting("id").contains(job.id());

        var item = controller.addItem(job.id(), new JobController.JobItemRequest(
            "step-1", null, JobWorkItemType.PLAN, "plan-1", null,
            Map.of(), null, 1, null, 0
        ));
        assertThat(item.planId()).isEqualTo("plan-1");
        assertThat(controller.items(job.id())).hasSize(1);
        assertThat(controller.events(job.id())).isEmpty();
        assertThat(controller.outputs(job.id())).isEmpty();
    }

    @Test
    void jobItemWithoutPlanOrWorkflowIdReturnsClearBadRequest() throws IOException {
        var services = jobServices();
        JobController controller = new JobController(services.jobService(), services.outputArtifactService());
        var job = controller.create(new io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition(
            null, null, null, null, "DRAFT", "Job", "", List.of(), null, null, null, null, null
        ));

        assertThatThrownBy(() -> controller.addItem(job.id(), new JobController.JobItemRequest(
            "bad", null, JobWorkItemType.PLAN, null, null, Map.of(), null, 1, null, 0
        )))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dashboardSummaryAggregatesOperationalContracts() throws IOException {
        var mapper = mapper();
        var jdbcTemplate = jdbc();
        var directoryService = workspaceDirectoryService();
        var projectService = new ProjectService(new ProjectRepository(jdbcTemplate, mapper), directoryService);
        var jobService = new JobService(new JobRepository(jdbcTemplate, mapper), directoryService, null, null);
        var agentProfileService = new AgentProfileService(
            new AgentProfileRepository(jdbcTemplate, mapper),
            new AiConfig(null, null, null, 10, tempDir.resolve("agents"),
                Map.of("qwen3", new ModelConfig("qwen3", "http://localhost:11434", EndpointType.OLLAMA, 8192, null, null)),
                Map.of()),
            null
        );
        var inboxService = new InboxService(new WorkflowRepository(jdbcTemplate, mapper), mapper);
        var outputService = new OutputArtifactService(
            new WorkspaceRepository(jdbcTemplate),
            directoryService,
            mapper
        );
        DashboardController controller = new DashboardController(
            projectService, jobService, agentProfileService, inboxService, outputService
        );

        var project = projectService.createProject("Alpha", "Active project", "agent-1", null);
        jobService.saveDefinition(new io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition(
            null, "agent-1", project.id(), null, "RUNNING", "Running Job", "", List.of(),
            null, null, null, null, null
        ));
        jobService.saveDefinition(new io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition(
            null, "agent-1", project.id(), null, "QUEUED", "Queued Job", "", List.of(),
            null, null, null, null, null
        ));
        jobService.saveDefinition(new io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition(
            null, "agent-1", project.id(), null, "COMPLETED", "Completed Job", "", List.of(),
            null, null, null, null, null
        ));
        agentProfileService.create(new AgentProfile(
            "agent-1", "Worker", AgentProfileStatus.ACTIVE, "qwen3", "Prompt",
            List.of("read_file"), List.of("ls"), false, null, null
        ));
        inboxService.createApprovalMessage(InboxMessageToType.USER, null, "agent-1", "Approve?", "run-1", 0);
        outputService.materialize(
            "run-1", "plan-1", "summary", PlanFieldType.STRING, "done",
            Files.createDirectories(directoryService.dataRoot().resolve("outputs"))
        );

        DashboardController.DashboardSummary summary = controller.summary();

        assertThat(summary.openProjects()).extracting(DashboardController.ProjectSummary::id)
            .containsExactly(project.id());
        assertThat(summary.activeWork()).extracting(DashboardController.WorkSummary::title)
            .containsExactlyInAnyOrder("Running Job", "Queued Job");
        assertThat(summary.activeWork()).extracting(DashboardController.WorkSummary::title)
            .doesNotContain("Completed Job");
        assertThat(summary.agents()).extracting(DashboardController.AgentSummary::name)
            .containsExactly("Worker");
        assertThat(summary.userInbox().waitingApprovals()).isEqualTo(1);
        assertThat(summary.recentOutputs()).extracting(DashboardController.OutputSummary::outputName)
            .containsExactly("summary");
        assertThat(summary.stats().runningJobs()).isEqualTo(1);
        assertThat(summary.stats().pendingJobs()).isEqualTo(1);
        assertThat(summary.stats().agentsByStatus()).containsEntry("ACTIVE", 1L);
    }

    @Test
    void outputQueryWithUnknownJobReturnsEmptyList() throws IOException {
        var services = jobServices();
        OutputController controller = new OutputController(services.outputArtifactService(), services.jobService());

        assertThat(controller.query(null, "missing", null, null, null, null, null, null, null, null, 20)).isEmpty();
    }

    private ProjectController projectController() throws IOException {
        var mapper = mapper();
        var jdbcTemplate = jdbc();
        var directoryService = workspaceDirectoryService();
        return new ProjectController(new ProjectService(
            new ProjectRepository(jdbcTemplate, mapper),
            directoryService
        ));
    }

    private JobServices jobServices() throws IOException {
        var mapper = mapper();
        var jdbcTemplate = jdbc();
        var directoryService = workspaceDirectoryService();
        var jobService = new JobService(new JobRepository(jdbcTemplate, mapper), directoryService, null, null);
        var outputService = new OutputArtifactService(
            new WorkspaceRepository(jdbcTemplate),
            directoryService,
            mapper
        );
        return new JobServices(jobService, outputService);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private WorkspaceDirectoryService workspaceDirectoryService() throws IOException {
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        AiConfig aiConfig = new AiConfig(null, null, null, 10, dataRoot, Map.of(), Map.of());
        return WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
    }

    private record JobServices(JobService jobService, OutputArtifactService outputArtifactService) {}
}
