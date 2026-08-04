package io.mindspice.magenta2.ai.chat.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class ChatSessionMetadataRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public boolean existsByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_chat_session_metadata where conversation_id = ?",
                Integer.class,
                conversationId);
        return count != null && count > 0;
    }

    public void saveModel(String conversationId, String model) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(model)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, model, updated_at)
                values (?, ?, ?)
                on conflict(conversation_id) do update set
                    model = excluded.model,
                    updated_at = excluded.updated_at
                """,
            conversationId,
            model,
            now()
        );
    }

    public void saveTitle(String conversationId, String title) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(title)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, title)
                values (?, ?)
                on conflict(conversation_id) do update set title = excluded.title
                """,
            conversationId,
            title
        );
    }

    public void updateTitle(String conversationId, String title) {
        saveTitle(conversationId, title);
    }

    public void saveTitleIfAbsent(String conversationId, String title) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(title)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, title)
                values (?, ?)
                on conflict(conversation_id) do update set title = excluded.title
                where ai_chat_session_metadata.title is null or trim(ai_chat_session_metadata.title) = ''
                """,
            conversationId,
            title
        );
    }

    public Optional<String> findModel(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select model
                from ai_chat_session_metadata
                where conversation_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String model = rs.getString("model");
                return StringUtils.hasText(model) ? Optional.of(model) : Optional.empty();
            },
            conversationId
        );
    }

    public void saveActiveTaskRunId(String conversationId, String runId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(runId)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, active_task_run_id, updated_at)
                values (?, ?, ?)
                on conflict(conversation_id) do update set
                    active_task_run_id = excluded.active_task_run_id,
                    updated_at = excluded.updated_at
                """,
            conversationId,
            runId,
            now()
        );
    }

    public Optional<String> findActiveTaskRunId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select active_task_run_id
                from ai_chat_session_metadata
                where conversation_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String runId = rs.getString("active_task_run_id");
                return StringUtils.hasText(runId) ? Optional.of(runId) : Optional.empty();
            },
            conversationId
        );
    }

    public void clearActiveTaskRunId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        jdbcTemplate.update(
            """
                update ai_chat_session_metadata
                set active_task_run_id = null, updated_at = ?
                where conversation_id = ?
                """,
            now(),
            conversationId
        );
    }

    public void savePlanningModel(String conversationId, String model) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(model)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, planning_model, updated_at)
                values (?, ?, ?)
                on conflict(conversation_id) do update set
                    planning_model = excluded.planning_model,
                    updated_at = excluded.updated_at
                """,
            conversationId,
            model,
            now()
        );
    }

    public Optional<String> findPlanningModel(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select planning_model
                from ai_chat_session_metadata
                where conversation_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String model = rs.getString("planning_model");
                return StringUtils.hasText(model) ? Optional.of(model) : Optional.empty();
            },
            conversationId
        );
    }

    public Optional<String> findTitle(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select title
                from ai_chat_session_metadata
                where conversation_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String title = rs.getString("title");
                return StringUtils.hasText(title) ? Optional.of(title) : Optional.empty();
            },
            conversationId
        );
    }

    public boolean isFavorite(String conversationId) {
        return booleanValue(conversationId, "favorite");
    }

    public boolean isArchived(String conversationId) {
        return booleanValue(conversationId, "archived");
    }

    public Optional<String> findUpdatedAt(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select updated_at
                from ai_chat_session_metadata
                where conversation_id = ?
                """,
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String updatedAt = rs.getString("updated_at");
                return StringUtils.hasText(updatedAt) ? Optional.of(updatedAt) : Optional.empty();
            },
            conversationId
        );
    }

    public void setFavorite(String conversationId, boolean favorite) {
        updateFlag(conversationId, "favorite", favorite);
    }

    public void setArchived(String conversationId, boolean archived) {
        updateFlag(conversationId, "archived", archived);
    }

    public void deleteByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        jdbcTemplate.update(
            "delete from ai_chat_session_metadata where conversation_id = ?",
            conversationId
        );
    }

    public void saveOriginIfAbsent(String conversationId, ChatSessionOrigin origin, String agentId) {
        if (!StringUtils.hasText(conversationId) || origin == null) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, origin, agent_id, updated_at)
                values (?, ?, ?, ?)
                on conflict(conversation_id) do update set
                    origin = coalesce(ai_chat_session_metadata.origin, excluded.origin),
                    agent_id = coalesce(ai_chat_session_metadata.agent_id, excluded.agent_id),
                    updated_at = excluded.updated_at
                """,
            conversationId, origin.name(), agentId, now()
        );
    }

    /**
     * Creates one immutable server-owned project-chat identity. A conflicting
     * existing conversation is never retargeted.
     */
    public boolean createProjectChatSession(ProjectChatSession session) {
        if (session == null || !session.isComplete()) {
            return false;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (
                    conversation_id, origin, agent_id, project_id, member_agent_id,
                    materialization_key, materialization_generation, owner_binding_key, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(conversation_id) do nothing
                """,
            session.conversationId(), ChatSessionOrigin.PROJECT_CHAT.name(), session.memberAgentId(),
            session.projectId(), session.memberAgentId(), session.materializationKey(),
            session.materializationGeneration(), session.ownerBindingKey(), now());
        return findProjectChatSession(session.conversationId()).map(session::equals).orElse(false);
    }

    public Optional<ProjectChatSession> findProjectChatSession(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select conversation_id, project_id, member_agent_id, materialization_key,
                       materialization_generation, owner_binding_key
                from ai_chat_session_metadata
                where conversation_id = ? and origin = ?
                """,
            rs -> rs.next() ? Optional.of(new ProjectChatSession(
                rs.getString("conversation_id"), rs.getString("project_id"),
                rs.getString("member_agent_id"), rs.getString("materialization_key"),
                rs.getInt("materialization_generation"), rs.getString("owner_binding_key")))
                : Optional.empty(),
            conversationId, ChatSessionOrigin.PROJECT_CHAT.name());
    }

    public Optional<String> findProjectId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select project_id from ai_chat_session_metadata where conversation_id = ?",
            rs -> {
                if (!rs.next()) return Optional.empty();
                String projectId = rs.getString("project_id");
                return StringUtils.hasText(projectId) ? Optional.of(projectId) : Optional.empty();
            },
            conversationId
        );
    }

    public Optional<String> findMemberAgentId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select member_agent_id from ai_chat_session_metadata where conversation_id = ?",
            rs -> {
                if (!rs.next()) return Optional.empty();
                String agentId = rs.getString("member_agent_id");
                return StringUtils.hasText(agentId) ? Optional.of(agentId) : Optional.empty();
            },
            conversationId
        );
    }

    public void saveAgentOrigin(String conversationId, String agentId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(agentId)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, origin, agent_id, updated_at)
                values (?, ?, ?, ?)
                on conflict(conversation_id) do update set
                    origin = excluded.origin,
                    agent_id = excluded.agent_id,
                    updated_at = excluded.updated_at
                """,
            conversationId, ChatSessionOrigin.AGENT_CHAT.name(), agentId, now()
        );
    }

    public void saveSurfaceIfAbsent(String conversationId, ChatSessionSurface surface) {
        if (!StringUtils.hasText(conversationId) || surface == null) {
            return;
        }
        jdbcTemplate.update(
            """
                insert into ai_chat_session_metadata (conversation_id, surface, updated_at)
                values (?, ?, ?)
                on conflict(conversation_id) do update set
                    surface = coalesce(ai_chat_session_metadata.surface, excluded.surface),
                    updated_at = excluded.updated_at
                """,
            conversationId, surface.name(), now()
        );
    }

    public Optional<ChatSessionOrigin> findOrigin(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select origin from ai_chat_session_metadata where conversation_id = ?",
            rs -> {
                if (!rs.next() || !StringUtils.hasText(rs.getString("origin"))) {
                    return Optional.empty();
                }
                return Optional.of(ChatSessionOrigin.valueOf(rs.getString("origin")));
            },
            conversationId
        );
    }

    public Optional<ChatSessionSurface> findSurface(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select surface from ai_chat_session_metadata where conversation_id = ?",
            rs -> {
                if (!rs.next() || !StringUtils.hasText(rs.getString("surface"))) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(ChatSessionSurface.valueOf(rs.getString("surface")));
                } catch (IllegalArgumentException exception) {
                    return Optional.empty();
                }
            },
            conversationId
        );
    }

    public Optional<String> findAgentId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select agent_id from ai_chat_session_metadata where conversation_id = ?",
            rs -> {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String agentId = rs.getString("agent_id");
                return StringUtils.hasText(agentId) ? Optional.of(agentId) : Optional.empty();
            },
            conversationId
        );
    }

    public List<String> findAgentConversationIds(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
            """
                select conversation_id
                from ai_chat_session_metadata
                where origin = ? and agent_id = ?
                order by coalesce(updated_at, '') desc, conversation_id asc
                """,
            String.class,
            ChatSessionOrigin.AGENT_CHAT.name(),
            agentId
        );
    }

    public List<String> findBrowserConversationIds() {
        return jdbcTemplate.queryForList(
            """
                select conversation_id
                from ai_chat_session_metadata
                where surface = ?
                order by coalesce(updated_at, '') desc, conversation_id asc
                """,
            String.class,
            ChatSessionSurface.BROWSER.name()
        );
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists ai_chat_session_metadata (
                conversation_id text primary key,
                model text,
                title text,
                active_task_run_id text,
                favorite integer not null default 0,
                archived integer not null default 0,
                origin text,
                surface text,
                agent_id text,
                updated_at text
            )
            """);
        java.util.List<String> columns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('ai_chat_session_metadata')",
            String.class
        );
        if (!columns.contains("title")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column title text");
        }
        if (!columns.contains("favorite")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column favorite integer not null default 0");
        }
        if (!columns.contains("archived")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column archived integer not null default 0");
        }
        if (!columns.contains("updated_at")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column updated_at text");
        }
        if (!columns.contains("planning_model")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column planning_model text");
        }
        if (!columns.contains("active_task_run_id")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column active_task_run_id text");
        }
        if (!columns.contains("origin")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column origin text");
        }
        if (!columns.contains("surface")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column surface text");
        }
        if (!columns.contains("agent_id")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column agent_id text");
        }
        if (!columns.contains("project_id")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column project_id text");
        }
        if (!columns.contains("member_agent_id")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column member_agent_id text");
        }
        if (!columns.contains("materialization_key")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column materialization_key text");
        }
        if (!columns.contains("materialization_generation")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column materialization_generation integer");
        }
        if (!columns.contains("owner_binding_key")) {
            jdbcTemplate.execute("alter table ai_chat_session_metadata add column owner_binding_key text");
        }
    }

    private boolean booleanValue(String conversationId, String column) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        return Boolean.TRUE.equals(jdbcTemplate.query(
            "select " + column + " from ai_chat_session_metadata where conversation_id = ?",
            rs -> rs.next() && rs.getInt(column) != 0,
            conversationId
        ));
    }

    private void updateFlag(String conversationId, String column, boolean value) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        jdbcTemplate.update(
            "insert into ai_chat_session_metadata (conversation_id, " + column + ") values (?, ?) "
                + "on conflict(conversation_id) do update set " + column + " = excluded." + column,
            conversationId,
            value ? 1 : 0
        );
    }

    private String now() {
        return Instant.now().toString();
    }

    public record ProjectChatSession(String conversationId, String projectId, String memberAgentId,
                                     String materializationKey, int materializationGeneration,
                                     String ownerBindingKey) {
        public boolean isComplete() {
            return StringUtils.hasText(conversationId) && StringUtils.hasText(projectId)
                    && StringUtils.hasText(memberAgentId) && StringUtils.hasText(materializationKey)
                    && materializationGeneration > 0 && StringUtils.hasText(ownerBindingKey);
        }
    }
}
