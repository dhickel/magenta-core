package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class WorkspaceFileActionLogRepository {
    private final JdbcTemplate jdbcTemplate;

    public WorkspaceFileActionLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public WorkspaceFileActionRecord record(
        WorkspaceDescriptor descriptor,
        WorkspaceFileActionType actionType,
        String sourceRelativePath,
        String targetRelativePath,
        String result,
        String payloadJson
    ) {
        return record(
            descriptor,
            "system",
            null,
            actionType,
            sourceRelativePath,
            targetRelativePath,
            result,
            payloadJson
        );
    }

    public WorkspaceFileActionRecord record(
        WorkspaceDescriptor descriptor,
        String actorType,
        String actorId,
        WorkspaceFileActionType actionType,
        String sourceRelativePath,
        String targetRelativePath,
        String result,
        String payloadJson
    ) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update(
            """
                insert into workspace_file_action_log (
                    id, workspace_identity, actor_type, actor_id,
                    action_type, source_relative_path, target_relative_path,
                    result, payload_json, created_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            id,
            descriptor.identity(),
            StringUtils.hasText(actorType) ? actorType.trim() : "system",
            StringUtils.hasText(actorId) ? actorId.trim() : null,
            actionType.name(),
            sourceRelativePath,
            targetRelativePath,
            StringUtils.hasText(result) ? result.trim() : "SUCCEEDED",
            StringUtils.hasText(payloadJson) ? payloadJson : "{}",
            now.toString()
        );
        return findById(id);
    }

    public WorkspaceFileActionRecord findById(String id) {
        return jdbcTemplate.query(
            "select * from workspace_file_action_log where id = ?",
            rs -> {
                if (!rs.next()) {
                    throw new IllegalArgumentException("workspace file action not found: " + id);
                }
                return toRecord(rs);
            },
            id
        );
    }

    public List<WorkspaceFileActionRecord> recentForWorkspace(String workspaceIdentity, int limit) {
        return jdbcTemplate.query(
            """
                select * from workspace_file_action_log
                where workspace_identity = ?
                order by created_at desc
                limit ?
                """,
            (rs, rowNum) -> toRecord(rs),
            workspaceIdentity,
            Math.max(1, limit)
        );
    }

    private WorkspaceFileActionRecord toRecord(ResultSet rs) throws SQLException {
        return new WorkspaceFileActionRecord(
            rs.getString("id"),
            rs.getString("workspace_identity"),
            rs.getString("actor_type"),
            rs.getString("actor_id"),
            WorkspaceFileActionType.valueOf(rs.getString("action_type")),
            rs.getString("source_relative_path"),
            rs.getString("target_relative_path"),
            rs.getString("result"),
            rs.getString("payload_json"),
            Instant.parse(rs.getString("created_at"))
        );
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists workspace_file_action_log (
                id text primary key,
                workspace_identity text not null,
                actor_type text,
                actor_id text,
                action_type text not null,
                source_relative_path text,
                target_relative_path text,
                result text not null,
                payload_json text not null default '{}',
                created_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_workspace_file_action_log_identity
                on workspace_file_action_log(workspace_identity, created_at desc)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_workspace_file_action_log_type
                on workspace_file_action_log(action_type, created_at desc)
            """);
    }
}
