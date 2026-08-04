package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRepositoryTest {

    @Test
    void savesAndFindsProject() {
        ProjectRepository repo = repository();
        Project p = project("proj-1", "Alpha", "agent-1");
        repo.save(p);

        Project found = repo.findById("proj-1").orElseThrow();
        assertThat(found.name()).isEqualTo("Alpha");
        assertThat(found.ownerAgentId()).isEqualTo("agent-1");
    }

    @Test
    void savesProjectWithoutOwnerAgent() {
        ProjectRepository repo = repository();

        repo.save(project("proj-ownerless", "Ownerless", null));

        Project found = repo.findById("proj-ownerless").orElseThrow();
        assertThat(found.ownerAgentId()).isNull();
        assertThat(repo.findByOwnerAgent("agent-missing")).isEmpty();
    }

    @Test
    void findsByOwnerAgent() {
        ProjectRepository repo = repository();
        repo.save(project("proj-2", "Beta", "agent-2"));
        repo.save(project("proj-3", "Gamma", "agent-2"));
        repo.save(project("proj-4", "Delta", "agent-3"));

        List<Project> owned = repo.findByOwnerAgent("agent-2");
        assertThat(owned).hasSize(2);
        assertThat(owned).extracting(Project::name).contains("Beta", "Gamma");
    }

    @Test
    void managesMemberships() {
        ProjectRepository repo = repository();
        repo.save(project("proj-5", "Epsilon", "agent-4"));

        // Add membership
        ProjectAgentMembership m = new ProjectAgentMembership(
            UUID.randomUUID().toString(), "proj-5", "agent-5", "member", Instant.now()
        );
        repo.saveMembership(m);

        assertThat(repo.isMember("proj-5", "agent-5")).isTrue();
        assertThat(repo.isMember("proj-5", "agent-99")).isFalse();

        List<ProjectAgentMembership> members = repo.findMembershipsByProject("proj-5");
        assertThat(members).hasSize(1);
        assertThat(members.get(0).agentId()).isEqualTo("agent-5");
    }

    @Test
    void findProjectIdsByAgent() {
        ProjectRepository repo = repository();
        repo.save(project("proj-6", "Zeta", "agent-6"));
        repo.save(project("proj-7", "Eta", "agent-7"));

        repo.saveMembership(new ProjectAgentMembership(
            UUID.randomUUID().toString(), "proj-6", "agent-x", "member", Instant.now()));
        repo.saveMembership(new ProjectAgentMembership(
            UUID.randomUUID().toString(), "proj-7", "agent-x", "member", Instant.now()));

        List<String> projectIds = repo.findProjectIdsByAgent("agent-x");
        assertThat(projectIds).containsExactlyInAnyOrder("proj-6", "proj-7");
    }

    @Test
    void deletesMembership() {
        ProjectRepository repo = repository();
        repo.save(project("proj-8", "Theta", "agent-8"));

        repo.saveMembership(new ProjectAgentMembership(
            "mem-1", "proj-8", "agent-y", "member", Instant.now()));
        assertThat(repo.isMember("proj-8", "agent-y")).isTrue();

        repo.deleteMembership("proj-8", "agent-y");
        assertThat(repo.isMember("proj-8", "agent-y")).isFalse();
    }

    @Test
    void recordsAndFindsProjectEvents() {
        ProjectRepository repo = repository();
        repo.save(project("proj-9", "Iota", "agent-9"));

        repo.saveEvent(new ProjectEvent("evt-1", "proj-9", "created", "{}", Instant.now()));
        repo.saveEvent(new ProjectEvent("evt-2", "proj-9", "agent_added", "{\"agent\":\"x\"}", Instant.now()));

        List<ProjectEvent> events = repo.findEvents("proj-9");
        assertThat(events).hasSize(2);
        assertThat(events).extracting(ProjectEvent::type).contains("created", "agent_added");
    }

    @Test
    void deleteProjectCascades() {
        ProjectRepository repo = repository();
        repo.save(project("proj-10", "Kappa", "agent-10"));
        repo.saveMembership(new ProjectAgentMembership(
            "mem-2", "proj-10", "agent-z", "member", Instant.now()));
        repo.saveEvent(new ProjectEvent("evt-3", "proj-10", "note", "{}", Instant.now()));

        repo.delete("proj-10");

        assertThat(repo.findById("proj-10")).isEmpty();
        assertThat(repo.isMember("proj-10", "agent-z")).isFalse();
    }

    @Test
    void migratesLegacyRequiredOwnerAgentColumnToNullable() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
            create table projects (
                id text primary key,
                name text not null,
                description text,
                owner_agent_id text not null,
                git_repo_url text,
                prompt_profile text,
                model text,
                settings_override_json text,
                created_at text not null,
                updated_at text not null
            )
            """);
        String now = Instant.now().toString();
        jdbc.update("""
            insert into projects (
                id, name, description, owner_agent_id, git_repo_url,
                prompt_profile, model, settings_override_json, created_at, updated_at
            )
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, "legacy-project", "Legacy", null, "agent-legacy", null, null, null, null, now, now);

        ProjectRepository repo = new ProjectRepository(jdbc, new ObjectMapper());
        repo.save(project("new-ownerless", "New Ownerless", null));

        assertThat(repo.findById("legacy-project").orElseThrow().ownerAgentId()).isEqualTo("agent-legacy");
        assertThat(repo.findById("new-ownerless").orElseThrow().ownerAgentId()).isNull();
        Integer required = jdbc.query(
            "select \"notnull\" from pragma_table_info('projects') where name = 'owner_agent_id'",
            rs -> rs.next() ? rs.getInt(1) : 1
        );
        assertThat(required).isZero();
    }

    // ── Helpers ──

    private Project project(String id, String name, String ownerAgentId) {
        return new Project(id, name, "Description", ownerAgentId, null,
            null, null, null, false, Instant.now(), Instant.now());
    }

    private ProjectRepository repository() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new ProjectRepository(new JdbcTemplate(ds), new ObjectMapper());
    }
}
