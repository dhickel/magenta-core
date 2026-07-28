package io.mindspice.magenta2.ai.orchestration.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

@Repository
public class JobRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<JobWorkItem>> ITEM_LIST = new TypeReference<>() {};
    private static final TypeReference<List<JobWorkItemRun>> ITEM_RUN_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> VALUE_MAP = new TypeReference<>() {};

    public JobRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureTables();
    }

    // ── JobDefinition ──

    public Optional<JobDefinition> findDefinition(String id) {
        if (!StringUtils.hasText(id)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from job_definitions where id = ?",
            rs -> rs.next() ? Optional.of(definitionFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<JobDefinition> findAllDefinitions() {
        return jdbcTemplate.query(
            "select * from job_definitions order by updated_at desc, title asc",
            (rs, rowNum) -> definitionFromRow(rs)
        );
    }

    public List<JobDefinition> findDefinitions(String agentId, String projectId, String status) {
        StringBuilder sql = new StringBuilder("select * from job_definitions where 1 = 1");
        List<Object> args = new java.util.ArrayList<>();
        if (StringUtils.hasText(agentId)) {
            sql.append(" and owner_agent_id = ?");
            args.add(agentId);
        }
        if (StringUtils.hasText(projectId)) {
            sql.append(" and project_id = ?");
            args.add(projectId);
        }
        if (StringUtils.hasText(status)) {
            sql.append(" and status = ?");
            args.add(status);
        }
        sql.append(" order by updated_at desc, title asc");
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> definitionFromRow(rs), args.toArray());
    }

    public JobDefinition saveDefinition(JobDefinition def) {
        Instant now = Instant.now();
        Instant createdAt = def.createdAt() == null ? now : def.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into job_definitions (
                    id, owner_agent_id, project_id, workspace_type, workspace_key, workspace_identity, status,
                    title, summary, items_json, prompt_profile,
                    model, settings_override_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    owner_agent_id = excluded.owner_agent_id,
                    project_id = excluded.project_id,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    status = excluded.status,
                    title = excluded.title,
                    summary = excluded.summary,
                    items_json = excluded.items_json,
                    prompt_profile = excluded.prompt_profile,
                    model = excluded.model,
                    settings_override_json = excluded.settings_override_json,
                    updated_at = excluded.updated_at
                """,
            def.id(), def.ownerAgentId(), def.projectId(), def.workspaceType(), def.workspaceKey(), def.workspaceIdentity(),
            def.status(), def.title(), def.summary(), json(def.items()), def.promptProfile(),
            def.model(), def.settingsOverrideJson(), createdAt.toString(), updatedAt.toString()
        );
        return findDefinition(def.id()).orElseThrow();
    }

    public void deleteDefinition(String id) {
        if (StringUtils.hasText(id)) {
            jdbcTemplate.update("delete from job_recurrences where job_id = ?", id);
            jdbcTemplate.update("delete from job_runs where job_id = ?", id);
            jdbcTemplate.update("delete from job_definitions where id = ?", id);
        }
    }

    @Transactional
    public void purgeDefinitionsOwnedByAgent(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        jdbcTemplate.update(
            "delete from job_recurrences where job_id in (select id from job_definitions where owner_agent_id = ?)",
            agentId
        );
        jdbcTemplate.update(
            "delete from job_runs where job_id in (select id from job_definitions where owner_agent_id = ?)",
            agentId
        );
        jdbcTemplate.update("delete from job_definitions where owner_agent_id = ?", agentId);
    }

    // ── JobRun ──

    public Optional<JobRun> findRun(String id) {
        if (!StringUtils.hasText(id)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from job_runs where id = ?",
            rs -> rs.next() ? Optional.of(runFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<JobRun> findRunsByJobId(String jobId) {
        return jdbcTemplate.query(
            "select * from job_runs where job_id = ? order by created_at desc",
            (rs, rowNum) -> runFromRow(rs),
            jobId
        );
    }

    public Optional<JobRun> findRunByAssignmentId(String assignmentId) {
        if (!StringUtils.hasText(assignmentId)) return Optional.empty();
        return jdbcTemplate.query(
            "select * from job_runs where job_assignment_id = ? order by created_at desc limit 1",
            rs -> rs.next() ? Optional.of(runFromRow(rs)) : Optional.empty(),
            assignmentId
        );
    }

    public long countActiveRunsByJobId(String jobId) {
        Long count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from job_runs
                where job_id = ?
                  and status not in (?, ?, ?)
                """,
            Long.class,
            jobId,
            JobRunStatus.COMPLETED.name(),
            JobRunStatus.FAILED.name(),
            JobRunStatus.CANCELLED.name()
        );
        return count == null ? 0 : count;
    }

    public long countActiveRunsByProject(String projectId) {
        Long count = jdbcTemplate.queryForObject(
            """
                select count(*)
                from job_runs r
                join job_definitions d on d.id = r.job_id
                where d.project_id = ?
                  and r.status not in (?, ?, ?)
                """,
            Long.class,
            projectId,
            JobRunStatus.COMPLETED.name(),
            JobRunStatus.FAILED.name(),
            JobRunStatus.CANCELLED.name()
        );
        return count == null ? 0 : count;
    }

    public JobRun saveRun(JobRun run) {
        Instant now = Instant.now();
        Instant createdAt = run.createdAt() == null ? now : run.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into job_runs (
                    id, job_id, job_assignment_id, workspace_type, workspace_key, workspace_identity,
                    status, work_item_runs_json, final_message, error_text,
                    created_at, updated_at, started_at, completed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    job_assignment_id = excluded.job_assignment_id,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    status = excluded.status,
                    work_item_runs_json = excluded.work_item_runs_json,
                    final_message = excluded.final_message,
                    error_text = excluded.error_text,
                    updated_at = excluded.updated_at,
                    started_at = excluded.started_at,
                    completed_at = excluded.completed_at
                """,
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), run.status().name(), json(run.workItemRuns()),
            run.finalMessage(), run.errorText(), createdAt.toString(), updatedAt.toString(),
            instant(run.startedAt()), instant(run.completedAt())
        );
        return findRun(run.id()).orElseThrow();
    }

    // ── JobRecurrence ──

    public Optional<JobRecurrence> findRecurrence(String jobId) {
        return jdbcTemplate.query(
            "select * from job_recurrences where job_id = ?",
            rs -> rs.next() ? Optional.of(recurrenceFromRow(rs)) : Optional.empty(),
            jobId
        );
    }

    public JobRecurrence saveRecurrence(JobRecurrence rec) {
        Instant now = Instant.now();
        Instant createdAt = rec.createdAt() == null ? now : rec.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into job_recurrences (
                    id, job_id, cron_expression, timezone,
                    next_fire_time, enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(job_id) do update set
                    cron_expression = excluded.cron_expression,
                    timezone = excluded.timezone,
                    next_fire_time = excluded.next_fire_time,
                    enabled = excluded.enabled,
                    updated_at = excluded.updated_at
                """,
            rec.id(), rec.jobId(), rec.cronExpression(), rec.timezone(),
            instant(rec.nextFireTime()), rec.enabled() ? 1 : 0,
            createdAt.toString(), updatedAt.toString()
        );
        return findRecurrence(rec.jobId()).orElseThrow();
    }

    public void deleteRecurrence(String jobId) {
        jdbcTemplate.update("delete from job_recurrences where job_id = ?", jobId);
    }

    public List<JobRecurrence> findDueRecurrences(Instant before) {
        return jdbcTemplate.query(
            """
                select * from job_recurrences
                where enabled = 1 and next_fire_time <= ?
                order by next_fire_time asc
                """,
            (rs, rowNum) -> recurrenceFromRow(rs),
            before.toString()
        );
    }

    // ── Row mapping ──

    private JobDefinition definitionFromRow(ResultSet rs) throws SQLException {
        return new JobDefinition(
            rs.getString("id"),
            getNullable(rs, "owner_agent_id"),
            getNullable(rs, "project_id"),
            selectionType(rs, "owner_agent_id"),
            selectionKey(rs, "owner_agent_id"),
            selectionIdentity(rs, "owner_agent_id"),
            getNullable(rs, "status"),
            rs.getString("title"),
            rs.getString("summary"),
            read(rs.getString("items_json"), ITEM_LIST, List.of()),
            rs.getString("prompt_profile"),
            rs.getString("model"),
            rs.getString("settings_override_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private JobRun runFromRow(ResultSet rs) throws SQLException {
        return new JobRun(
            rs.getString("id"),
            rs.getString("job_id"),
            getNullable(rs, "job_assignment_id"),
            selectionType(rs, null),
            selectionKey(rs, null),
            selectionIdentity(rs, null),
            JobRunStatus.valueOf(rs.getString("status")),
            read(rs.getString("work_item_runs_json"), ITEM_RUN_LIST, List.of()),
            rs.getString("final_message"),
            rs.getString("error_text"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at")),
            parseInstant(rs.getString("started_at")),
            parseInstant(rs.getString("completed_at"))
        );
    }

    private JobRecurrence recurrenceFromRow(ResultSet rs) throws SQLException {
        return new JobRecurrence(
            rs.getString("id"),
            rs.getString("job_id"),
            rs.getString("cron_expression"),
            rs.getString("timezone"),
            parseInstant(rs.getString("next_fire_time")),
            rs.getInt("enabled") == 1,
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    // ── JSON helpers ──

    private <T> T read(String json, TypeReference<T> type, T defaultValue) {
        if (!StringUtils.hasText(json)) return defaultValue;
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse job JSON", e);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize job JSON", e);
        }
    }

    private String instant(Instant i) {
        return i == null ? null : i.toString();
    }

    private Instant parseInstant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private String getNullable(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String selectionType(ResultSet rs, String agentColumn) throws SQLException {
        String type = getNullable(rs, "workspace_type");
        String key = getNullable(rs, "workspace_key");
        if (StringUtils.hasText(type) && StringUtils.hasText(key)) return type;
        return StringUtils.hasText(agentColumn) && StringUtils.hasText(getNullable(rs, agentColumn)) ? "agent" : null;
    }

    private String selectionKey(ResultSet rs, String agentColumn) throws SQLException {
        String type = getNullable(rs, "workspace_type");
        String key = getNullable(rs, "workspace_key");
        if (StringUtils.hasText(type) && StringUtils.hasText(key)) return key;
        return StringUtils.hasText(agentColumn) ? getNullable(rs, agentColumn) : null;
    }

    private String selectionIdentity(ResultSet rs, String agentColumn) throws SQLException {
        String type = selectionType(rs, agentColumn);
        String key = selectionKey(rs, agentColumn);
        return StringUtils.hasText(type) && StringUtils.hasText(key) ? type + "/" + key : null;
    }

    // ── Schema bootstrapping ──

    private void ensureTables() {
        jdbcTemplate.execute("""
            create table if not exists job_definitions (
                id text primary key,
                owner_agent_id text,
                project_id text,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                status text,
                title text not null,
                summary text,
                items_json text not null,
                prompt_profile text,
                model text,
                settings_override_json text,
                created_at text not null,
                updated_at text not null
            )
            """);
        List<String> definitionColumns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('job_definitions')",
            String.class
        );
        if (!definitionColumns.contains("owner_agent_id")) {
            jdbcTemplate.execute("alter table job_definitions add column owner_agent_id text");
        }
        if (!definitionColumns.contains("project_id")) {
            jdbcTemplate.execute("alter table job_definitions add column project_id text");
        }
        addColumnIfMissing(definitionColumns, "job_definitions", "workspace_type");
        addColumnIfMissing(definitionColumns, "job_definitions", "workspace_key");
        addColumnIfMissing(definitionColumns, "job_definitions", "workspace_identity");
        if (!definitionColumns.contains("status")) {
            jdbcTemplate.execute("alter table job_definitions add column status text");
        }
        jdbcTemplate.execute("""
            create table if not exists job_runs (
                id text primary key,
                job_id text not null,
                job_assignment_id text,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                status text not null,
                work_item_runs_json text not null,
                final_message text,
                error_text text,
                created_at text not null,
                updated_at text not null,
                started_at text,
                completed_at text,
                foreign key (job_id) references job_definitions(id) on delete cascade
            )
            """);
        List<String> runColumns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('job_runs')",
            String.class
        );
        if (!runColumns.contains("job_assignment_id")) {
            jdbcTemplate.execute("alter table job_runs add column job_assignment_id text");
        }
        addColumnIfMissing(runColumns, "job_runs", "workspace_type");
        addColumnIfMissing(runColumns, "job_runs", "workspace_key");
        addColumnIfMissing(runColumns, "job_runs", "workspace_identity");
        jdbcTemplate.execute("""
            create table if not exists job_recurrences (
                id text primary key,
                job_id text not null unique,
                cron_expression text not null,
                timezone text not null,
                next_fire_time text,
                enabled integer not null default 1,
                created_at text not null,
                updated_at text not null,
                foreign key (job_id) references job_definitions(id) on delete cascade
            )
            """);
    }

    private void addColumnIfMissing(List<String> columns, String table, String column) {
        if (!columns.contains(column)) {
            jdbcTemplate.execute("alter table " + table + " add column " + column + " text");
        }
    }
}
