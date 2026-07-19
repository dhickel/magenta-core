package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class OutputDirectoryServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void taskOutputUsesProjectWorkspaceAndIgnoresCompatibilityWorkspaceIdForResolution() throws Exception {
        Fixture fixture = fixture("project-task");
        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.task(
            "task-1", "run-1", "agent-1", "project-1", "legacy-workspace-id"));

        assertThat(resolved.workspaceOwnerType()).isEqualTo(WorkspaceOwnerType.PROJECT);
        assertThat(resolved.workspaceOwnerId()).isEqualTo("project-1");
        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.dataRoot().resolve("projects/project-1/outputs"));
        assertThat(resolved.workspaceId()).isNotEqualTo("legacy-workspace-id");
        assertThat(resolved.artifactContext().workspaceId()).isEqualTo(resolved.workspaceId());
        assertThat(resolved.artifactContext().projectId()).isEqualTo("project-1");
        assertThat(resolved.artifactContext().runType()).isEqualTo("TASK_RUN");
        assertThat(Files.isDirectory(resolved.outputDirectory())).isTrue();
    }

    @Test
    void workflowOutputUsesAgentWorkspaceWhenProjectIsAbsent() throws Exception {
        Fixture fixture = fixture("agent-workflow");
        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.workflow(
            "workflow-1", "run-2", "agent-1", null, null));

        assertThat(resolved.workspaceOwnerType()).isEqualTo(WorkspaceOwnerType.AGENT);
        assertThat(resolved.workspaceOwnerId()).isEqualTo("agent-1");
        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.dataRoot().resolve("workspace/agent-1/outputs"));
        assertThat(resolved.artifactContext().agentId()).isEqualTo("agent-1");
        assertThat(resolved.artifactContext().projectId()).isNull();
        assertThat(resolved.artifactContext().runType()).isEqualTo("WORKFLOW_RUN");
    }

    @Test
    void jobOutputUsesAssignmentAndJobRunPathWithJobAttribution() throws Exception {
        Fixture fixture = fixture("job-output");
        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.job(
            "job-1", "assignment-1", "job-run-1", "agent-1", "project-1", null));

        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.dataRoot().resolve("projects/project-1/outputs"));
        assertThat(resolved.artifactContext().jobId()).isEqualTo("job-1");
        assertThat(resolved.artifactContext().jobAssignmentId()).isEqualTo("assignment-1");
        assertThat(resolved.artifactContext().jobRunId()).isEqualTo("job-run-1");
        assertThat(resolved.artifactContext().runType()).isEqualTo("JOB_RUN");
    }

    @Test
    void selectedWorkAreaBecomesWorkspaceRootAndDefaultOutputsStayUnderSelectedOutputs() throws Exception {
        Fixture fixture = fixture("selected-work-area");
        Files.createDirectories(fixture.dataRoot().resolve("workspace/agent-1/research"));
        WorkArea selected = fixture.workAreaService()
            .markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "research", "Research");

        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.task(
            "task-1", "run-1", "agent-1", null, null,
            selected.id(), AssignmentRequest.OUTPUT_ROUTE_DEFAULT, null, null));

        assertThat(resolved.ownerRoot())
            .isEqualTo(fixture.dataRoot().resolve("workspace/agent-1"));
        assertThat(resolved.workspaceRoot())
            .isEqualTo(fixture.workAreaService().resolve(selected));
        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.workAreaService().resolve(selected));
    }

    @Test
    void outputWorkAreaRedirectUsesTargetWorkAreaOutputs() throws Exception {
        Fixture fixture = fixture("output-work-area");
        Files.createDirectories(fixture.dataRoot().resolve("workspace/agent-1/home"));
        Files.createDirectories(fixture.dataRoot().resolve("workspace/agent-1/review"));
        WorkArea selected = fixture.workAreaService()
            .markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "home", "Home");
        WorkArea output = fixture.workAreaService()
            .markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "review", "Review");

        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.workflow(
            "workflow-1", "run-1", "agent-1", null, null,
            selected.id(), AssignmentRequest.OUTPUT_ROUTE_WORK_AREA, output.id(), null));

        assertThat(resolved.workspaceRoot())
            .isEqualTo(fixture.workAreaService().resolve(selected));
        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.workAreaService().resolve(output));
    }

    @Test
    void directOutputRouteUsesExistingDirectoryDirectly() throws Exception {
        Fixture fixture = fixture("direct-output");
        Files.createDirectories(fixture.dataRoot().resolve("workspace/agent-1/home"));
        Files.createDirectories(fixture.dataRoot().resolve("workspace/agent-1/manual"));
        WorkArea selected = fixture.workAreaService()
            .markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "home", "Home");

        ResolvedOutputDirectory resolved = fixture.service().resolve(OutputPublicationTarget.job(
            "job-1", "assignment-1", "job-run-1", "agent-1", null, null,
            selected.id(), AssignmentRequest.OUTPUT_ROUTE_DIRECT_DIRECTORY, null, "manual"));

        assertThat(resolved.outputDirectory())
            .isEqualTo(fixture.dataRoot().resolve("workspace/agent-1/manual"));
    }

    private Fixture fixture(String name) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(
            new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
        );
        WorkspaceRepository repository = new WorkspaceRepository(jdbc);
        Path dataRoot = Files.createDirectories(tempDir.resolve(name));
        AiConfig aiConfig = new AiConfig(
            null, null, null, null, null, 10, dataRoot, null, Map.of(), Map.of()
        );
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        WorkspaceService workspaceService = new WorkspaceService(repository, aiConfig, new RootRelativePathService(directoryService));
        WorkAreaRepository workAreaRepository = new WorkAreaRepository(jdbc);
        WorkAreaService workAreaService = new WorkAreaService(workAreaRepository, workspaceService, directoryService);
        EffectiveWorkspaceResolver resolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        return new Fixture(
            new OutputDirectoryService(resolver, directoryService, workAreaService),
            workAreaService,
            directoryService.dataRoot()
        );
    }

    private record Fixture(OutputDirectoryService service, WorkAreaService workAreaService, Path dataRoot) {
    }
}
