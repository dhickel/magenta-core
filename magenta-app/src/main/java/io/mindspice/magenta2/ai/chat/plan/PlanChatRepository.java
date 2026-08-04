package io.mindspice.magenta2.ai.chat.plan;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class PlanChatRepository {
    private final JdbcTemplate jdbcTemplate;

    public PlanChatRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public List<PlanChatMessage> findByPlanId(String planId) {
        if (!StringUtils.hasText(planId)) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
                select id, plan_id, role, text, created_at
                from plan_chat_messages
                where plan_id = ?
                order by created_at asc, rowid asc
                """,
            (rs, rowNum) -> new PlanChatMessage(
                rs.getString("id"),
                rs.getString("plan_id"),
                rs.getString("role"),
                rs.getString("text"),
                Instant.parse(rs.getString("created_at"))
            ),
            planId
        );
    }

    public PlanChatMessage append(String planId, String role, String text) {
        if (!StringUtils.hasText(planId)) {
            throw new IllegalArgumentException("planId is required");
        }
        if (!StringUtils.hasText(role)) {
            throw new IllegalArgumentException("role is required");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PlanChatMessage message = new PlanChatMessage(id, planId, role.trim(), text == null ? "" : text, now);
        jdbcTemplate.update(
            """
                insert into plan_chat_messages (id, plan_id, role, text, created_at)
                values (?, ?, ?, ?, ?)
                """,
            message.id(), message.planId(), message.role(), message.text(), message.createdAt().toString()
        );
        return message;
    }

    public void deleteByPlanId(String planId) {
        if (!StringUtils.hasText(planId)) {
            return;
        }
        jdbcTemplate.update("delete from plan_chat_messages where plan_id = ?", planId);
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists plan_chat_messages (
                id text primary key,
                plan_id text not null,
                role text not null,
                text text not null,
                created_at text not null
            )
            """);
        jdbcTemplate.execute("create index if not exists idx_plan_chat_messages_plan on plan_chat_messages(plan_id, created_at)");
    }
}
