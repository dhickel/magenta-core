package io.mindspice.magenta2.ai.agent.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class AgentJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public AgentJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public Optional<AgentJob> enqueue(
        String id,
        AgentJobType type,
        String conversationId,
        String selectedModel,
        String inputJson
    ) {
        Instant now = Instant.now();
        try {
            jdbcTemplate.update(
                """
                    insert into agent_jobs (
                        id, type, status, conversation_id, selected_model, input_json,
                        result_json, error_text, created_at, updated_at, started_at, completed_at
                    )
                    values (?, ?, ?, ?, ?, ?, null, null, ?, ?, null, null)
                    """,
                id,
                type.name(),
                AgentJobStatus.QUEUED.name(),
                conversationId,
                selectedModel,
                inputJson,
                now.toString(),
                now.toString()
            );
            return findById(id);
        } catch (DuplicateKeyException | UncategorizedSQLException exception) {
            if (!isDuplicateConversationTitle(exception)) {
                throw exception;
            }
            return Optional.empty();
        }
    }

    public Optional<AgentJob> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from agent_jobs where id = ?",
            rs -> rs.next() ? Optional.of(toJob(rs)) : Optional.empty(),
            id
        );
    }

    public Optional<AgentJobStatus> latestStatus(AgentJobType type, String conversationId) {
        if (type == null || !StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select status
                from agent_jobs
                where type = ? and conversation_id = ?
                order by created_at desc
                limit 1
                """,
            rs -> rs.next() ? Optional.of(AgentJobStatus.valueOf(rs.getString("status"))) : Optional.empty(),
            type.name(),
            conversationId
        );
    }

    public void markRunning(String id) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                update agent_jobs
                set status = ?, updated_at = ?, started_at = coalesce(started_at, ?)
                where id = ?
                """,
            AgentJobStatus.RUNNING.name(),
            now.toString(),
            now.toString(),
            id
        );
    }

    public void markSucceeded(String id, String resultJson) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                update agent_jobs
                set status = ?, result_json = ?, error_text = null, updated_at = ?, completed_at = ?
                where id = ?
                """,
            AgentJobStatus.SUCCEEDED.name(),
            resultJson,
            now.toString(),
            now.toString(),
            id
        );
    }

    public void markFailed(String id, String errorText) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                update agent_jobs
                set status = ?, error_text = ?, updated_at = ?, completed_at = ?
                where id = ?
                """,
            AgentJobStatus.FAILED.name(),
            errorText,
            now.toString(),
            now.toString(),
            id
        );
    }

    public List<AgentJob> findAll() {
        return jdbcTemplate.query("select * from agent_jobs order by created_at", (rs, rowNum) -> toJob(rs));
    }

    private AgentJob toJob(ResultSet rs) throws SQLException {
        return new AgentJob(
            rs.getString("id"),
            AgentJobType.valueOf(rs.getString("type")),
            AgentJobStatus.valueOf(rs.getString("status")),
            rs.getString("conversation_id"),
            rs.getString("selected_model"),
            rs.getString("input_json"),
            rs.getString("result_json"),
            rs.getString("error_text"),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at")),
            instant(rs.getString("started_at")),
            instant(rs.getString("completed_at"))
        );
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private boolean isDuplicateConversationTitle(RuntimeException exception) {
        String message = exception.getMessage();
        return message != null && message.contains("agent_jobs.type, agent_jobs.conversation_id");
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists agent_jobs (
                id text primary key,
                type text not null,
                status text not null,
                conversation_id text,
                selected_model text,
                input_json text,
                result_json text,
                error_text text,
                created_at text not null,
                updated_at text not null,
                started_at text,
                completed_at text
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_agent_jobs_conversation
                on agent_jobs (conversation_id)
            """);
        jdbcTemplate.execute("""
            create unique index if not exists idx_agent_jobs_conversation_title_active
                on agent_jobs (type, conversation_id)
                where type = 'CONVERSATION_TITLE'
                  and status in ('QUEUED', 'RUNNING', 'SUCCEEDED')
            """);
    }
}
