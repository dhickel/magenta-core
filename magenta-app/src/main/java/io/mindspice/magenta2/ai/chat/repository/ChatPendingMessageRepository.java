package io.mindspice.magenta2.ai.chat.repository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.model.ClaimedPendingChatMessage;
import io.mindspice.magenta2.ai.chat.model.PendingChatMessage;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ChatPendingMessageRepository {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_CLAIMED = "CLAIMED";
    private static final Duration STALE_CLAIM_AGE = Duration.ofMinutes(10);

    private final JdbcTemplate jdbcTemplate;

    public ChatPendingMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    @Transactional
    public PendingChatMessage enqueue(
        String conversationId,
        String messageText,
        String model,
        String planningModel,
        ChatSessionSurface surface
    ) {
        recoverStaleClaims(conversationId);
        String id = UUID.randomUUID().toString();
        String now = now();
        Integer nextOrder = jdbcTemplate.queryForObject(
            """
                select coalesce(max(message_order), 0) + 1
                from ai_chat_pending_messages
                where conversation_id = ?
                """,
            Integer.class,
            conversationId
        );
        jdbcTemplate.update(
            """
                insert into ai_chat_pending_messages (
                    id, conversation_id, message_order, message_text, model, planning_model,
                    surface, status, claim_token, claimed_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, null, null, ?, ?)
                """,
            id,
            conversationId,
            nextOrder == null ? 1 : nextOrder,
            messageText,
            model,
            planningModel,
            surface == null ? null : surface.name(),
            STATUS_PENDING,
            now,
            now
        );
        return findVisibleByConversationId(conversationId).stream()
            .filter(message -> message.id().equals(id))
            .findFirst()
            .orElseThrow();
    }

    public List<PendingChatMessage> findVisibleByConversationId(String conversationId) {
        recoverStaleClaims(conversationId);
        List<PendingChatMessageRow> rows = jdbcTemplate.query(
            """
                select id, conversation_id, message_text, model, planning_model, surface,
                       status, created_at, updated_at
                from ai_chat_pending_messages
                where conversation_id = ?
                  and status in ('PENDING', 'CLAIMED')
                order by message_order asc
                """,
            (rs, rowNum) -> new PendingChatMessageRow(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("message_text"),
                rs.getString("model"),
                rs.getString("planning_model"),
                surface(rs.getString("surface")),
                rs.getString("status"),
                rs.getString("created_at"),
                rs.getString("updated_at")
            ),
            conversationId
        );
        int total = rows.size();
        return rows.stream()
            .map(row -> row.toMessage(rows.indexOf(row) + 1, total))
            .toList();
    }

    @Transactional
    public Optional<ClaimedPendingChatMessage> claimOldest(String conversationId) {
        recoverStaleClaims(conversationId);
        Optional<String> messageId = findOldestPendingId(conversationId);
        if (messageId.isEmpty()) {
            return Optional.empty();
        }
        String claimToken = UUID.randomUUID().toString();
        String now = now();
        int updated = jdbcTemplate.update(
            """
                update ai_chat_pending_messages
                set status = ?, claim_token = ?, claimed_at = ?, updated_at = ?
                where id = ?
                  and conversation_id = ?
                  and status = ?
                """,
            STATUS_CLAIMED,
            claimToken,
            now,
            now,
            messageId.get(),
            conversationId,
            STATUS_PENDING
        );
        if (updated == 0) {
            return Optional.empty();
        }
        return findVisibleByConversationId(conversationId).stream()
            .filter(message -> message.id().equals(messageId.get()))
            .findFirst()
            .map(message -> new ClaimedPendingChatMessage(message, claimToken));
    }

    @Transactional
    public boolean ack(String conversationId, String messageId, String claimToken) {
        int updated = jdbcTemplate.update(
            """
                delete from ai_chat_pending_messages
                where id = ?
                  and conversation_id = ?
                  and status = ?
                  and claim_token = ?
                """,
            messageId,
            conversationId,
            STATUS_CLAIMED,
            claimToken
        );
        return updated > 0;
    }

    @Transactional
    public boolean release(String conversationId, String messageId, String claimToken) {
        int updated = jdbcTemplate.update(
            """
                update ai_chat_pending_messages
                set status = ?, claim_token = null, claimed_at = null, updated_at = ?
                where id = ?
                  and conversation_id = ?
                  and status = ?
                  and claim_token = ?
                """,
            STATUS_PENDING,
            now(),
            messageId,
            conversationId,
            STATUS_CLAIMED,
            claimToken
        );
        return updated > 0;
    }

    public int recoverStaleClaims(String conversationId) {
        return jdbcTemplate.update(
            """
                update ai_chat_pending_messages
                set status = ?, claim_token = null, claimed_at = null, updated_at = ?
                where conversation_id = ?
                  and status = ?
                  and claimed_at < ?
                """,
            STATUS_PENDING,
            now(),
            conversationId,
            STATUS_CLAIMED,
            Instant.now().minus(STALE_CLAIM_AGE).toString()
        );
    }

    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update(
            "delete from ai_chat_pending_messages where conversation_id = ?",
            conversationId
        );
    }

    void markClaimStaleForTest(String messageId, Instant claimedAt) {
        jdbcTemplate.update(
            """
                update ai_chat_pending_messages
                set claimed_at = ?, updated_at = ?
                where id = ?
                """,
            claimedAt.toString(),
            claimedAt.toString(),
            messageId
        );
    }

    private Optional<String> findOldestPendingId(String conversationId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                """
                    select id
                    from ai_chat_pending_messages
                    where conversation_id = ?
                      and status = ?
                    order by message_order asc
                    limit 1
                    """,
                String.class,
                conversationId,
                STATUS_PENDING
            ));
        } catch (EmptyResultDataAccessException exception) {
            return Optional.empty();
        }
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists ai_chat_pending_messages (
                id text primary key,
                conversation_id text not null,
                message_order integer not null,
                message_text text not null,
                model text,
                planning_model text,
                surface text,
                status text not null,
                claim_token text,
                claimed_at text,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_ai_chat_pending_messages_status_order
                on ai_chat_pending_messages (conversation_id, status, message_order)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_ai_chat_pending_messages_order
                on ai_chat_pending_messages (conversation_id, message_order)
            """);
    }

    private ChatSessionSurface surface(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ChatSessionSurface.valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String now() {
        return Instant.now().toString();
    }

    private record PendingChatMessageRow(
        String id,
        String conversationId,
        String messageText,
        String model,
        String planningModel,
        ChatSessionSurface surface,
        String status,
        String createdAt,
        String updatedAt
    ) {
        PendingChatMessage toMessage(int position, int total) {
            return new PendingChatMessage(
                id,
                conversationId,
                messageText,
                model,
                planningModel,
                surface,
                status,
                position,
                total,
                createdAt,
                updatedAt
            );
        }
    }
}
