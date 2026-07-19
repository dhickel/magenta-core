package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.workspaces.EffectiveWorkspaceResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkArea;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaService;
import io.mindspice.magenta2.ai.orchestration.workspaces.Workspace;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLeaseService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssignmentContextServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migrationBackfillsProjectIdFromLegacyInputJson() {
        JdbcTemplate jdbc = jdbcTemplate();
        jdbc.execute("""
            create table work_assignments (
                id text primary key,
                agent_id text not null,
                job_id text,
                job_item_id text,
                assignment_type text not null,
                priority integer not null,
                status text not null,
                model_override text,
                workspace_id text,
                current_item_index integer not null,
                checkpoint_json text,
                input_json text,
                output_json text,
                evidence_json text,
                error_text text,
                lease_owner text,
                lease_expires_at text,
                created_at text not null,
                updated_at text not null,
                started_at text,
                completed_at text
            )
            """);
        Instant now = Instant.now();
        jdbc.update(
            """
                insert into work_assignments (
                    id, agent_id, assignment_type, priority, status, current_item_index,
                    checkpoint_json, input_json, output_json, evidence_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            "assignment-legacy", "agent-1", AssignmentType.TASK_RUN.name(), 1, OrchestrationStatus.QUEUED.name(), 0,
            "{}", "{\"projectId\":\"project-legacy\",\"taskId\":\"task-1\"}", "{}", "{}",
            now.toString(), now.toString()
        );

        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());

        WorkAssignment assignment = repository.findAssignment("assignment-legacy").orElseThrow();
        assertThat(assignment.projectId()).isEqualTo("project-legacy");
        assertThat(assignment.effectiveWorkspaceId()).isNull();
    }

    @Test
    void assignmentCreationPersistsFirstClassProjectAndEffectiveWorkspaceContext() {
        Context context = context();

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.TASK_RUN, 3, null,
            "project-1", null, Map.of("taskId", "task-1")
        ));

        assertThat(assignment.projectId()).isEqualTo("project-1");
        assertThat(assignment.input()).containsEntry("projectId", "project-1");
        assertThat(assignment.effectiveWorkspaceKind()).isEqualTo(WorkspaceOwnerType.PROJECT.name());
        assertThat(assignment.effectiveWorkspaceId()).isEqualTo(context.workspaceService()
            .projectWorkspace("project-1", "Project project-1").id());
        assertThat(context.leaseService().activeWritableLease(assignment.effectiveWorkspaceId())).isEmpty();
        assertThat(context.assignmentService().summary(assignment.id()).effectiveWorkspaceDisplayPath())
            .isEqualTo("projects/project-1");
    }

    @Test
    void assignmentCreationAndStatusTransitionsPreserveRunDisplayName() {
        Context context = context();

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.TASK_RUN, "Daily research run", 3, null,
            "project-1", null, null, null, null, null, Map.of("taskId", "task-1")
        ));

        assertThat(assignment.runDisplayName()).isEqualTo("Daily research run");
        assertThat(context.repository().findAssignment(assignment.id()).orElseThrow().runDisplayName())
            .isEqualTo("Daily research run");

        WorkAssignment running = context.assignmentService().saveStatus(assignment, OrchestrationStatus.RUNNING);
        assertThat(running.runDisplayName()).isEqualTo("Daily research run");
        assertThat(context.repository().findAssignment(assignment.id()).orElseThrow().runDisplayName())
            .isEqualTo("Daily research run");

        WorkAssignment completed = context.assignmentService().saveStatus(running, OrchestrationStatus.COMPLETED);
        assertThat(completed.runDisplayName()).isEqualTo("Daily research run");
        assertThat(context.repository().findAssignment(assignment.id()).orElseThrow().runDisplayName())
            .isEqualTo("Daily research run");
    }

    @Test
    void workspaceIdOnlyRemainsCompatibilityMetadataAndDoesNotScopeProjectExecution() {
        Context context = context();
        Workspace projectWorkspace = context.workspaceService().projectWorkspace("project-compat", "Project compat");

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            null, projectWorkspace.id(), Map.of("message", "hello")
        ));

        assertThat(assignment.projectId()).isNull();
        assertThat(assignment.workspaceId()).isEqualTo(projectWorkspace.id());
        assertThat(assignment.effectiveWorkspaceKind()).isEqualTo(WorkspaceOwnerType.AGENT.name());
        assertThat(context.workspaceService().get(assignment.effectiveWorkspaceId()).ownerType())
            .isEqualTo(WorkspaceOwnerType.AGENT);
    }

    @Test
    void mismatchedProjectOwnedWorkspaceIdIsRejected() {
        Context context = context();
        Workspace unrelatedProjectWorkspace = context.workspaceService().projectWorkspace("project-b", "Project B");

        assertThatThrownBy(() -> context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            "project-a", unrelatedProjectWorkspace.id(), Map.of("message", "hello")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("projectId is the only project-scoping field");
    }

    @Test
    void workspaceBlockedAssignmentRequeuesAfterBlockingLeaseIsReleased() {
        Context context = context();
        Workspace workspace = context.workspaceService().projectWorkspace("project-blocked", "Project Blocked");
        var lease = context.leaseService().acquireWritable(workspace.id(), "ASSIGNMENT", "other-assignment", Duration.ofMinutes(5));
        WorkAssignment waiting = context.repository().saveAssignment(new WorkAssignment(
            "assignment-waiting", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.WAITING, null, null, "project-blocked", workspace.id(), WorkspaceOwnerType.PROJECT.name(),
            0, Map.of("workspaceBlocker", "leased", "projectWorkspaceId", workspace.id()),
            Map.of("message", "hello", "projectId", "project-blocked"), Map.of(), Map.of(),
            "leased", null, null, null, null, null, null
        ));

        assertThatThrownBy(() -> context.assignmentService().requeueWorkspaceBlockedAssignment(waiting.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active writable lease");

        context.leaseService().release(lease.id(), "other-assignment");
        WorkAssignment requeued = context.assignmentService().requeueWorkspaceBlockedAssignment(waiting.id());

        assertThat(requeued.status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(requeued.checkpoint()).doesNotContainKey("workspaceBlocker");
        assertThat(requeued.checkpoint()).containsEntry("lastWorkspaceBlocker", "leased");
    }

    @Test
    void assignmentCreationDefaultsToHomeWorkArea() {
        Context context = context();

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            null, null, Map.of("message", "hello")
        ));
        WorkArea home = context.workAreaService().get(assignment.selectedWorkAreaId());

        assertThat(home.home()).isTrue();
        assertThat(home.ownerType()).isEqualTo(WorkspaceOwnerType.AGENT);
        assertThat(home.ownerId()).isEqualTo("agent-1");
        assertThat(assignment.outputRouteType()).isEqualTo(AssignmentRequest.OUTPUT_ROUTE_DEFAULT);
        assertThat(assignment.outputWorkAreaId()).isNull();
        assertThat(assignment.outputDirectRelativePath()).isNull();
        assertThat(Files.isDirectory(tempDir.resolve("workspace/agent-1/home"))).isTrue();
    }

    @Test
    void assignmentCreationPersistsOutputWorkAreaRoute() throws Exception {
        Context context = context();
        context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        WorkArea outputArea = context.workAreaService()
            .markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "review", "Review");

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            null, null, null, AssignmentRequest.OUTPUT_ROUTE_WORK_AREA, outputArea.id(), null,
            Map.of("message", "hello")
        ));

        assertThat(assignment.selectedWorkAreaId()).isNotBlank();
        assertThat(assignment.outputRouteType()).isEqualTo(AssignmentRequest.OUTPUT_ROUTE_WORK_AREA);
        assertThat(assignment.outputWorkAreaId()).isEqualTo(outputArea.id());
        assertThat(assignment.outputDirectRelativePath()).isNull();
    }

    @Test
    void assignmentCreationValidatesDirectOutputDirectory() throws Exception {
        Context context = context();
        Files.createDirectories(tempDir.resolve("workspace/agent-1/manual-out"));

        WorkAssignment assignment = context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            null, null, null, AssignmentRequest.OUTPUT_ROUTE_DIRECT_DIRECTORY, null, "manual-out",
            Map.of("message", "hello")
        ));

        assertThat(assignment.outputRouteType()).isEqualTo(AssignmentRequest.OUTPUT_ROUTE_DIRECT_DIRECTORY);
        assertThat(assignment.outputWorkAreaId()).isNull();
        assertThat(assignment.outputDirectRelativePath()).isEqualTo("manual-out");
        assertThatThrownBy(() -> context.assignmentService().create(new AssignmentRequest(
            "agent-1", null, null, AssignmentType.REPORT, 1, null,
            null, null, null, AssignmentRequest.OUTPUT_ROUTE_DIRECT_DIRECTORY, null, "../outside",
            Map.of("message", "hello")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
    }

    private Context context() {
        JdbcTemplate jdbc = jdbcTemplate();
        ObjectMapper mapper = new ObjectMapper();
        AiConfig config = new AiConfig(null, null, null, 10, tempDir, Map.of(
            "main", new ModelConfig("main-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null)
        ), Map.of());
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbc, mapper);
        WorkspaceDirectoryService directoryService;
        try {
            directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbc);
        WorkAreaRepository workAreaRepository = new WorkAreaRepository(jdbc);
        WorkspaceService workspaceService;
        try {
            workspaceService = new WorkspaceService(
                workspaceRepository,
                config,
                new RootRelativePathService(directoryService)
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        WorkAreaService workAreaService = new WorkAreaService(workAreaRepository, workspaceService, directoryService);
        WorkspaceLeaseService leaseService = new WorkspaceLeaseService(workspaceRepository);
        AgentProfileService agentService = new AgentProfileService(new AgentProfileRepository(jdbc, mapper), config, null);
        agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(
            repository, agentService, null, null, null, null, null,
            new EffectiveWorkspaceResolver(directoryService, workspaceService), workspaceService, leaseService,
            workAreaService
        );
        return new Context(repository, workspaceService, leaseService, workAreaService, assignmentService);
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
    }

    private record Context(
        OrchestrationRuntimeRepository repository,
        WorkspaceService workspaceService,
        WorkspaceLeaseService leaseService,
        WorkAreaService workAreaService,
        AssignmentService assignmentService
    ) {
    }
}
