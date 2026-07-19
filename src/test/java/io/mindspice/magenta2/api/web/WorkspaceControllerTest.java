package io.mindspice.magenta2.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.workspaces.LeaseMode;
import io.mindspice.magenta2.ai.orchestration.workspaces.Workspace;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLease;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLink;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLinkType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkspaceControllerTest {

    private WorkspaceController controller;
    private WorkspaceService workspaceService;
    private WorkspaceRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        repository = new WorkspaceRepository(new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)));
        Path dataRoot = Files.createTempDirectory("workspace-controller-test");
        workspaceService = new WorkspaceService(repository,
                WorkspaceDirectoryServiceTestSupport.aiConfig(dataRoot),
                WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        controller = new WorkspaceController(workspaceService);
    }

    @Test
    void listSupportsOwnerFilteringAndLimitBounds() {
        workspaceService.agentWorkspace("agent-1", "Agent One");
        workspaceService.agentWorkspace("agent-2", "Agent Two");

        List<Workspace> filtered = controller.list("AGENT", "agent-1", 500);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.getFirst().ownerId()).isEqualTo("agent-1");
        assertThat(filtered.getFirst().ownerType().name()).isEqualTo("AGENT");
    }

    @Test
    void getAndLeasesReturnWorkspaceData() {
        Workspace workspace = workspaceService.agentWorkspace("agent-1", "Agent One");
        workspaceService.addLink(workspace.id(), new WorkspaceLink(
            null, workspace.id(), "Docs", WorkspaceLinkType.PATH, "docs", true, false, null, null
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

        Workspace fetched = controller.get(workspace.id());
        List<WorkspaceLease> leases = controller.activeLeases(workspace.id());
        List<WorkspaceLink> links = controller.links(workspace.id());

        assertThat(fetched.id()).isEqualTo(workspace.id());
        assertThat(leases).hasSize(1);
        assertThat(leases.getFirst().holderId()).isEqualTo("run-1");
        assertThat(links).hasSize(1);
        assertThat(links.getFirst().label()).isEqualTo("Docs");
    }

    @Test
    void listRejectsInvalidOwnerType() {
        assertThatThrownBy(() -> controller.list("BAD_OWNER", null, 50))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("Invalid ownerType");
            });
    }

    @Test
    void getAndLeasesReturnNotFoundForMissingWorkspace() {
        assertThatThrownBy(() -> controller.get("missing"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> controller.activeLeases("missing"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
