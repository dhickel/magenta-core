package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkAreaServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsHomeWorkAreaUnderAgentRoot() throws Exception {
        TestContext context = context();

        WorkArea home = context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Agent One");

        assertThat(home.ownerType()).isEqualTo(WorkspaceOwnerType.AGENT);
        assertThat(home.ownerId()).isEqualTo("agent-1");
        assertThat(home.areaRelativePath()).isEqualTo("home");
        assertThat(home.system()).isTrue();
        assertThat(home.home()).isTrue();
        assertThat(home.active()).isTrue();
        assertThat(Files.isDirectory(context.dataRoot().resolve("workspace/agent-1/home"))).isTrue();
        assertThat(context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null).id()).isEqualTo(home.id());
    }

    @Test
    void marksExistingConfinedDirectoryAndReactivatesDuplicate() throws Exception {
        TestContext context = context();
        context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        WorkArea marked = context.service().markDirectory(
            WorkspaceOwnerType.AGENT,
            "agent-1",
            "docs/research",
            "Research"
        );
        WorkArea deactivated = context.service().unmark(marked.id());
        WorkArea reactivated = context.service().markDirectory(
            WorkspaceOwnerType.AGENT,
            "agent-1",
            marked.areaRelativePath(),
            "Research Again"
        );

        assertThat(marked.areaRelativePath()).isEqualTo("workareas/" + marked.id());
        assertThat(Files.isDirectory(context.dataRoot().resolve("workspace/agent-1").resolve(marked.areaRelativePath())))
            .isTrue();
        assertThat(marked.home()).isFalse();
        assertThat(deactivated.active()).isFalse();
        assertThat(reactivated.id()).isEqualTo(marked.id());
        assertThat(reactivated.active()).isTrue();
        assertThat(reactivated.displayName()).isEqualTo("Research Again");
        assertThat(context.service().list(WorkspaceOwnerType.AGENT, "agent-1", false))
            .extracting(WorkArea::id)
            .contains(marked.id());
    }

    @Test
    void listsActiveWorkAreasAcrossOwnersForPickers() throws Exception {
        TestContext context = context();
        WorkArea agentHome = context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        WorkArea projectHome = context.service().ensureHome(WorkspaceOwnerType.PROJECT, "project-1", null);

        assertThat(context.service().listActive(20))
            .extracting(WorkArea::id)
            .contains(agentHome.id(), projectHome.id());
    }

    @Test
    void rejectsTraversalRootMarkingAndUnsupportedOwners() throws Exception {
        TestContext context = context();
        context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);

        assertThatThrownBy(() -> context.service().markDirectory(
            WorkspaceOwnerType.AGENT, "agent-1", "../outside", "Outside"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
        assertThatThrownBy(() -> context.service().markDirectory(
            WorkspaceOwnerType.AGENT, "agent-1", ".", "Root"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("workspace root cannot be marked");
        assertThatThrownBy(() -> context.service().ensureHome(WorkspaceOwnerType.JOB, "job-1", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agent and project roots only");
    }

    @Test
    void rejectsSymlinkEscapeWhenMarkingDirectory() throws Exception {
        TestContext context = context();
        context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = context.dataRoot().resolve("workspace/agent-1/escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            assumeTrue(false, "symlinks unsupported");
        }

        assertThatThrownBy(() -> context.service().requireExistingOwnerDirectory(
            WorkspaceOwnerType.AGENT, "agent-1", "escape-link", "escape link"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes workspace root");
    }

    @Test
    void refusesToUnmarkHomeAndActiveWorkAreas() throws Exception {
        TestContext context = context();
        WorkArea home = context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        WorkArea docs = context.service().markDirectory(WorkspaceOwnerType.AGENT, "agent-1", "docs", "Docs");

        context.jdbc().execute("""
            create table work_assignments (
                id text primary key,
                selected_work_area_id text,
                output_work_area_id text,
                status text not null
            )
            """);
        context.jdbc().update(
            "insert into work_assignments (id, selected_work_area_id, output_work_area_id, status) values (?, ?, ?, ?)",
            "assignment-1",
            docs.id(),
            null,
            "RUNNING"
        );

        assertThatThrownBy(() -> context.service().unmark(home.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cannot be unmarked");
        assertThatThrownBy(() -> context.service().unmark(docs.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("active in queued or running work");

        context.jdbc().update("update work_assignments set status = 'COMPLETED' where id = 'assignment-1'");
        assertThat(context.service().unmark(docs.id()).active()).isFalse();
    }

    @Test
    void refusesToUnmarkActiveOutputTargetWorkArea() throws Exception {
        TestContext context = context();
        context.service().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        WorkArea target = context.service().markDirectory(
            WorkspaceOwnerType.AGENT, "agent-1", "outputs-target", "Outputs Target");

        context.jdbc().execute("""
            create table work_assignments (
                id text primary key,
                selected_work_area_id text,
                output_work_area_id text,
                status text not null
            )
            """);
        context.jdbc().update(
            "insert into work_assignments (id, selected_work_area_id, output_work_area_id, status) values (?, ?, ?, ?)",
            "assignment-1",
            null,
            target.id(),
            "QUEUED"
        );

        assertThatThrownBy(() -> context.service().unmark(target.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("active in queued or running work");
    }

    private TestContext context() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbc);
        WorkAreaRepository workAreaRepository = new WorkAreaRepository(jdbc);
        Path dataRoot = tempDir.resolve("data");
        AiConfig aiConfig = new AiConfig(null, null, null, null, null, 10, dataRoot, null, Map.of(), Map.of());
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        WorkspaceService workspaceService = new WorkspaceService(
            workspaceRepository,
            aiConfig,
            new RootRelativePathService(directoryService)
        );
        WorkAreaService service = new WorkAreaService(workAreaRepository, workspaceService, directoryService);
        return new TestContext(dataRoot.toRealPath(), jdbc, service);
    }

    private record TestContext(Path dataRoot, JdbcTemplate jdbc, WorkAreaService service) {
    }
}
