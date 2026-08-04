package io.mindspice.magenta2.ai.orchestration.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class ProjectRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProjectRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureTables();
    }

    // ── Project ──

    public Optional<Project> findById(String id) {
        if (!StringUtils.hasText(id)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from projects where id = ?",
            rs -> rs.next() ? Optional.of(projectFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<Project> findAll() {
        return jdbcTemplate.query(
            "select * from projects order by updated_at desc, name asc",
            (rs, rowNum) -> projectFromRow(rs)
        );
    }

    public List<Project> findByOwnerAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from projects where owner_agent_id = ? order by name asc",
            (rs, rowNum) -> projectFromRow(rs),
            agentId
        );
    }

    public Project save(Project project) {
        Instant now = Instant.now();
        Instant createdAt = project.createdAt() == null ? now : project.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into projects (
                    id, name, description, owner_agent_id, git_repo_url,
                    prompt_profile, model, settings_override_json,
                    archived, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    name = excluded.name,
                    description = excluded.description,
                    owner_agent_id = excluded.owner_agent_id,
                    git_repo_url = excluded.git_repo_url,
                    prompt_profile = excluded.prompt_profile,
                    model = excluded.model,
                    settings_override_json = excluded.settings_override_json,
                    updated_at = excluded.updated_at
                """,
            project.id(), project.name(), project.description(),
            project.ownerAgentId(), project.gitRepoUrl(),
            project.promptProfile(), project.model(),
            project.settingsOverrideJson(),
            project.archived() ? 1 : 0,
            createdAt.toString(), updatedAt.toString()
        );
        return findById(project.id()).orElseThrow();
    }

    public void archive(String id) {
        jdbcTemplate.update(
            "update projects set archived = 1, updated_at = ? where id = ? and archived = 0",
            Instant.now().toString(), id);
    }

    public void restore(String id) {
        jdbcTemplate.update(
            "update projects set archived = 0, updated_at = ? where id = ? and archived = 1",
            Instant.now().toString(), id);
    }

    public void delete(String id) {
        if (StringUtils.hasText(id)) {
            jdbcTemplate.update("delete from project_events where project_id = ?", id);
            jdbcTemplate.update("delete from project_agent_memberships where project_id = ?", id);
            jdbcTemplate.update("delete from projects where id = ?", id);
        }
    }

    @Transactional
    public void purgeAgentReferences(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        jdbcTemplate.update("delete from project_agent_memberships where agent_id = ?", agentId);
        jdbcTemplate.update("update projects set owner_agent_id = null where owner_agent_id = ?", agentId);
    }

    // ── ProjectAgentMembership ──

    public Optional<ProjectAgentMembership> findMembership(String projectId, String agentId) {
        return jdbcTemplate.query(
            "select * from project_agent_memberships where project_id = ? and agent_id = ?",
            rs -> rs.next() ? Optional.of(membershipFromRow(rs)) : Optional.empty(),
            projectId, agentId
        );
    }

    public List<ProjectAgentMembership> findMembershipsByProject(String projectId) {
        return jdbcTemplate.query(
            "select * from project_agent_memberships where project_id = ? order by joined_at asc",
            (rs, rowNum) -> membershipFromRow(rs),
            projectId
        );
    }

    public List<ProjectAgentMembership> findMembershipsByAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from project_agent_memberships where agent_id = ? order by joined_at asc",
            (rs, rowNum) -> membershipFromRow(rs),
            agentId
        );
    }

    public List<String> findProjectIdsByAgent(String agentId) {
        return jdbcTemplate.queryForList(
            "select project_id from project_agent_memberships where agent_id = ?",
            String.class, agentId
        );
    }

    public ProjectAgentMembership saveMembership(ProjectAgentMembership membership) {
        Instant joinedAt = membership.joinedAt() == null ? Instant.now() : membership.joinedAt();
        jdbcTemplate.update(
            """
                insert into project_agent_memberships (
                    id, project_id, agent_id, role, joined_at
                )
                values (?, ?, ?, ?, ?)
                on conflict(project_id, agent_id) do update set
                    role = excluded.role
                """,
            membership.id(), membership.projectId(), membership.agentId(),
            membership.role(), joinedAt.toString()
        );
        return findMembership(membership.projectId(), membership.agentId()).orElseThrow();
    }

    public void deleteMembership(String projectId, String agentId) {
        jdbcTemplate.update(
            "delete from project_agent_memberships where project_id = ? and agent_id = ?",
            projectId, agentId
        );
    }

    public boolean isMember(String projectId, String agentId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from project_agent_memberships where project_id = ? and agent_id = ?",
            Integer.class, projectId, agentId
        );
        return count != null && count > 0;
    }

    // ── ProjectEvent ──

    public List<ProjectEvent> findEvents(String projectId) {
        return jdbcTemplate.query(
            "select * from project_events where project_id = ? order by created_at desc",
            (rs, rowNum) -> eventFromRow(rs),
            projectId
        );
    }

    public ProjectEvent saveEvent(ProjectEvent event) {
        Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        jdbcTemplate.update(
            """
                insert into project_events (id, project_id, type, payload_json, created_at)
                values (?, ?, ?, ?, ?)
                """,
            event.id(), event.projectId(), event.type(),
            event.payloadJson(), createdAt.toString()
        );
        return event;
    }

    // ── Row mapping ──

    private Project projectFromRow(ResultSet rs) throws SQLException {
        return new Project(
            rs.getString("id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("owner_agent_id"),
            rs.getString("git_repo_url"),
            rs.getString("prompt_profile"),
            rs.getString("model"),
            rs.getString("settings_override_json"),
            readArchived(rs),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private boolean readArchived(ResultSet rs) {
        try {
            return rs.getInt("archived") == 1;
        } catch (SQLException e) {
            return false;
        }
    }

    private ProjectAgentMembership membershipFromRow(ResultSet rs) throws SQLException {
        return new ProjectAgentMembership(
            rs.getString("id"),
            rs.getString("project_id"),
            rs.getString("agent_id"),
            rs.getString("role"),
            Instant.parse(rs.getString("joined_at"))
        );
    }

    private ProjectEvent eventFromRow(ResultSet rs) throws SQLException {
        return new ProjectEvent(
            rs.getString("id"),
            rs.getString("project_id"),
            rs.getString("type"),
            rs.getString("payload_json"),
            Instant.parse(rs.getString("created_at"))
        );
    }

    // ── Schema bootstrapping ──

    private void ensureTables() {
        createProjectsTable();
        migrateNullableOwnerAgentId();
        createMembershipsTable();
        createEventsTable();
    }

    private void createProjectsTable() {
        jdbcTemplate.execute("""
            create table if not exists projects (
                id text primary key,
                name text not null,
                description text,
                owner_agent_id text,
                git_repo_url text,
                prompt_profile text,
                model text,
                settings_override_json text,
                archived integer not null default 0 check (archived in (0, 1)),
                created_at text not null,
                updated_at text not null
            )
            """);
        // Add archived column if missing on existing databases
        java.util.List<String> columns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('projects')", String.class);
        if (!columns.contains("archived")) {
            jdbcTemplate.execute("alter table projects add column archived integer not null default 0 check (archived in (0, 1))");
        }
        jdbcTemplate.execute("""
            create index if not exists idx_projects_archived
                on projects (archived, updated_at desc)
            """);
    }

    private void createMembershipsTable() {
        jdbcTemplate.execute("""
            create table if not exists project_agent_memberships (
                id text primary key,
                project_id text not null,
                agent_id text not null,
                role text not null default 'member',
                joined_at text not null,
                foreign key (project_id) references projects(id) on delete cascade
            )
            """);
        jdbcTemplate.execute("""
            create unique index if not exists idx_project_membership_unique
                on project_agent_memberships (project_id, agent_id)
            """);
    }

    private void createEventsTable() {
        jdbcTemplate.execute("""
            create table if not exists project_events (
                id text primary key,
                project_id text not null,
                type text not null,
                payload_json text,
                created_at text not null,
                foreign key (project_id) references projects(id) on delete cascade
            )
            """);
    }

    private void migrateNullableOwnerAgentId() {
        if (!tableExists("projects") || !ownerAgentIdIsRequired()) {
            return;
        }
        boolean hasMemberships = tableExists("project_agent_memberships");
        boolean hasEvents = tableExists("project_events");
        jdbcTemplate.execute("pragma foreign_keys = off");
        try {
            if (hasMemberships) {
                jdbcTemplate.execute("alter table project_agent_memberships rename to project_agent_memberships_owner_migration");
            }
            if (hasEvents) {
                jdbcTemplate.execute("alter table project_events rename to project_events_owner_migration");
            }
            jdbcTemplate.execute("alter table projects rename to projects_owner_migration");
            createProjectsTable();
            jdbcTemplate.execute("""
                insert into projects (
                    id, name, description, owner_agent_id, git_repo_url,
                    prompt_profile, model, settings_override_json, archived, created_at, updated_at
                )
                select id, name, description, owner_agent_id, git_repo_url,
                    prompt_profile, model, settings_override_json, 0, created_at, updated_at
                from projects_owner_migration
                """);
            jdbcTemplate.execute("drop table projects_owner_migration");
            if (hasMemberships) {
                createMembershipsTable();
                jdbcTemplate.execute("""
                    insert into project_agent_memberships (id, project_id, agent_id, role, joined_at)
                    select id, project_id, agent_id, role, joined_at
                    from project_agent_memberships_owner_migration
                    """);
                jdbcTemplate.execute("drop table project_agent_memberships_owner_migration");
            }
            if (hasEvents) {
                createEventsTable();
                jdbcTemplate.execute("""
                    insert into project_events (id, project_id, type, payload_json, created_at)
                    select id, project_id, type, payload_json, created_at
                    from project_events_owner_migration
                    """);
                jdbcTemplate.execute("drop table project_events_owner_migration");
            }
        } finally {
            jdbcTemplate.execute("pragma foreign_keys = on");
        }
    }

    private boolean ownerAgentIdIsRequired() {
        Integer required = jdbcTemplate.query(
            "select \"notnull\" from pragma_table_info('projects') where name = 'owner_agent_id'",
            rs -> rs.next() ? rs.getInt(1) : 0
        );
        return required != null && required == 1;
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }
}
