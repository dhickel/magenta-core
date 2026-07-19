package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkspaceServicePathTest {
    @TempDir
    Path tempDir;

    @Test
    void deterministicWorkspaceRootsRemainStable() throws Exception {
        TestContext context = context();

        Workspace agent = context.service().agentWorkspace("agent-1", "Agent One");
        Workspace project = context.service().projectWorkspace("project-1", "Project One");

        assertThat(agent.rootRelativePath()).isEqualTo("workspace/agent-1");
        assertThat(project.rootRelativePath()).isEqualTo("projects/project-1");
        assertThatThrownBy(() -> context.service().jobWorkspace("job-1", "Job One"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Job-owned workspaces are retired");
    }

    @Test
    void newPathLinkWithRelativeTargetPersistsDataRootRelativeTarget() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");

        WorkspaceLink saved = context.service().addLink(workspace.id(), link(workspace.id(), WorkspaceLinkType.PATH, "docs"));

        assertThat(saved.target()).isEqualTo("workspace/agent-1/docs");
        assertThat(Path.of(saved.target()).isAbsolute()).isFalse();
        assertThat(saved.target()).doesNotContain(context.dataRoot().toString());
    }

    @Test
    void newPathLinkWithAbsoluteCurrentRootTargetPersistsDataRootRelativeTarget() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");
        Path target = context.dataRoot().resolve("workspace/agent-1/docs");

        WorkspaceLink saved = context.service().addLink(
            workspace.id(),
            link(workspace.id(), WorkspaceLinkType.PATH, target.toString())
        );

        assertThat(saved.target()).isEqualTo("workspace/agent-1/docs");
        assertThat(Path.of(saved.target()).isAbsolute()).isFalse();
    }

    @Test
    void existingAbsoluteCurrentRootPathLinkSeededDirectlyListsAsRootRelativeWithoutRewrite() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");
        String absoluteTarget = context.dataRoot().resolve("workspace/agent-1/legacy-docs").toString();
        context.repository().saveLink(new WorkspaceLink(
            "legacy-link",
            workspace.id(),
            "Legacy Docs",
            WorkspaceLinkType.PATH,
            absoluteTarget,
            true,
            false,
            null,
            null
        ));

        List<WorkspaceLink> links = context.service().links(workspace.id());

        assertThat(links).extracting(WorkspaceLink::target)
            .containsExactly("workspace/agent-1/legacy-docs");
        assertThat(context.repository().findLink("legacy-link")).get().extracting(WorkspaceLink::target)
            .isEqualTo(absoluteTarget);
    }

    @Test
    void existingAbsoluteOutsideRootPathLinkSeededDirectlyIsFilteredFromLinks() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");
        context.repository().saveLink(new WorkspaceLink(
            "current-link",
            workspace.id(),
            "Current Docs",
            WorkspaceLinkType.PATH,
            "workspace/agent-1/docs",
            true,
            false,
            null,
            null
        ));
        context.repository().saveLink(new WorkspaceLink(
            "stale-link",
            workspace.id(),
            "Stale Docs",
            WorkspaceLinkType.PATH,
            tempDir.resolve("old-root/docs").toString(),
            true,
            false,
            null,
            null
        ));

        List<WorkspaceLink> links = context.service().links(workspace.id());

        assertThat(links).extracting(WorkspaceLink::id).containsExactly("current-link");
        assertThat(links).extracting(WorkspaceLink::target)
            .containsExactly("workspace/agent-1/docs");
        assertThat(context.repository().findLink("stale-link")).isPresent();
    }

    @Test
    void absoluteOutsideRootPathLinkTargetIsRejected() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");
        String outside = tempDir.resolve("outside/docs").toString();

        assertThatThrownBy(() -> context.service().addLink(
            workspace.id(),
            link(workspace.id(), WorkspaceLinkType.PATH, outside)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void relativeTraversalPathLinkTargetIsRejected() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");

        assertThatThrownBy(() -> context.service().addLink(
            workspace.id(),
            link(workspace.id(), WorkspaceLinkType.PATH, "../../outside")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void nonPathLinkTargetRemainsUnchanged() throws Exception {
        TestContext context = context();
        Workspace workspace = context.service().agentWorkspace("agent-1", "Agent One");
        String target = "https://example.test/repo.git";

        WorkspaceLink saved = context.service().addLink(
            workspace.id(),
            link(workspace.id(), WorkspaceLinkType.REPOSITORY, target)
        );

        assertThat(saved.target()).isEqualTo(target);
    }

    private TestContext context() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceRepository repository = new WorkspaceRepository(jdbc);
        Path dataRoot = tempDir.resolve("data");
        AiConfig aiConfig = new AiConfig(null, null, null, null, null, 10, dataRoot, null, Map.of(), Map.of());
        WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        WorkspaceService service = new WorkspaceService(repository, aiConfig, new RootRelativePathService(directories));
        return new TestContext(dataRoot.toRealPath(), repository, service);
    }

    private WorkspaceLink link(String workspaceId, WorkspaceLinkType type, String target) {
        return new WorkspaceLink(null, workspaceId, "Link", type, target, true, false, null, null);
    }

    private record TestContext(Path dataRoot, WorkspaceRepository repository, WorkspaceService service) {
    }
}
