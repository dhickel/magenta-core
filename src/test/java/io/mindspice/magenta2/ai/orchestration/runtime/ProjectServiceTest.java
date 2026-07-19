package io.mindspice.magenta2.ai.orchestration.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectServiceTest {

    @TempDir
    java.nio.file.Path tempDir;

    private ProjectService projectService;
    private ProjectRepository projectRepository;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(tempDir.resolve("projects"));
        projectRepository = repository();
        projectService = new ProjectService(projectRepository, null);
    }

    @Test
    void projectCanBeCreatedWithoutOwnerAgent() {
        Project project = projectService.createProject("Test", "desc", null, null);

        assertThat(project.ownerAgentId()).isNull();
        assertThat(projectService.listMembers(project.id())).isEmpty();
    }

    @Test
    void legacyOwnerAgentIsAutoAddedAsMember() {
        Project project = projectService.createProject("My Project", "desc", "agent-1", null);
        List<ProjectAgentMembership> members = projectService.listMembers(project.id());
        assertThat(members).hasSize(1);
        assertThat(members.get(0).agentId()).isEqualTo("agent-1");
        assertThat(members.get(0).role()).isEqualTo("owner");
    }

    @Test
    void agentCanBeAddedToMultipleProjects() {
        Project p1 = projectService.createProject("P1", "desc", "agent-a", null);
        Project p2 = projectService.createProject("P2", "desc", "agent-b", null);

        projectService.addAgent(p1.id(), "agent-x", "member");
        projectService.addAgent(p2.id(), "agent-x", "member");

        List<String> projects = projectService.listAgentProjects("agent-x");
        assertThat(projects).contains(p1.id(), p2.id());
    }

    @Test
    void legacyOwnerMembershipCanBeRemoved() {
        Project project = projectService.createProject("Test", "desc", "agent-owner", null);

        projectService.removeAgent(project.id(), "agent-owner");

        assertThat(projectService.listMembers(project.id())).isEmpty();
        assertThat(projectService.getProject(project.id()).ownerAgentId()).isEqualTo("agent-owner");
    }

    @Test
    void activeProjectAssignmentBlocksDeleteAndMembershipRemoval() {
        JdbcTemplate jdbc = jdbcTemplate();
        ProjectRepository projects = new ProjectRepository(jdbc, new ObjectMapper());
        OrchestrationRuntimeRepository runtime = new OrchestrationRuntimeRepository(jdbc, new ObjectMapper());
        ProjectService service = new ProjectService(projects, null, null, null, runtime);
        Project project = service.createProject("Active", "desc", "agent-active", null);
        runtime.saveAssignment(new WorkAssignment(
            "assignment-active", "agent-active", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.RUNNING, null, null, "ws-1", "ws-1", project.id(),
            0, Map.of(), Map.of("projectId", project.id()), Map.of(), Map.of(),
            null, "lease-owner", Instant.now().plusSeconds(60), null, null, Instant.now(), null
        ));

        assertThatThrownBy(() -> service.deleteProject(project.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active assignments");
        assertThatThrownBy(() -> service.removeAgent(project.id(), "agent-active"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("active assignments");
    }

    @Test
    void projectNetworkGatesMessaging() {
        Project project = projectService.createProject("Net", "desc", "agent-a", null);
        projectService.addAgent(project.id(), "agent-b", "member");

        // Same project network
        assertThat(projectService.agentsShareProject("agent-a", "agent-b")).isTrue();

        // Outside the project
        assertThat(projectService.agentsShareProject("agent-a", "agent-x")).isFalse();
    }

    @Test
    void requireProjectNetworkRejectsOutsiders() {
        Project project = projectService.createProject("Net2", "desc", "agent-1", null);
        projectService.addAgent(project.id(), "agent-2", "member");

        // Members can communicate
        projectService.requireProjectNetwork(project.id(), "agent-1", "agent-2");

        // Non-member rejected
        assertThatThrownBy(() ->
            projectService.requireProjectNetwork(project.id(), "agent-1", "agent-x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not a member");
    }

    @Test
    void listEventsReturnsRecordedEvents() {
        Project project = projectService.createProject("Events", "desc", "agent-1", null);
        projectService.recordEvent(project.id(), "test", "{\"key\":\"val\"}");
        projectService.recordEvent(project.id(), "update", null);

        List<ProjectEvent> events = projectService.listEvents(project.id());
        assertThat(events).hasSize(2);
        assertThat(events).extracting(ProjectEvent::type).contains("test", "update");
    }

    private ProjectRepository repository() {
        return new ProjectRepository(jdbcTemplate(), new ObjectMapper());
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(ds);
    }
}
