package io.mindspice.magenta2.ai.orchestration.workspaces;

import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Cycle-free chat conversation existence composed from low-level
 * repository checks. Never depends on {@code ChatService} or
 * task/plan services.
 */
@Repository
public class ChatConversationExistenceRepository {

    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final JdbcTemplate jdbcTemplate;

    public ChatConversationExistenceRepository(
            ChatMemoryRepository chatMemoryRepository,
            ChatSessionMetadataRepository chatSessionMetadataRepository,
            JdbcTemplate jdbcTemplate) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Returns {@code true} when the conversation id is non-blank and
     * appears in at least one of: chat memory, session metadata,
     * or (if the table exists) {@code plan_definitions.conversation_id}.
     * Missing optional tables are not created.
     */
    public boolean existsByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        if (chatMemoryRepository.existsByConversationId(conversationId)) {
            return true;
        }
        if (chatSessionMetadataRepository.existsByConversationId(conversationId)) {
            return true;
        }
        return existsInPlanDefinitions(conversationId);
    }

    private boolean existsInPlanDefinitions(String conversationId) {
        if (!planDefinitionsTableExists()) {
            return false;
        }
        Integer match = jdbcTemplate.query(
                "select 1 from plan_definitions where conversation_id = ? limit 1",
                rs -> rs.next() ? 1 : null,
                conversationId);
        return match != null;
    }

    private boolean planDefinitionsTableExists() {
        Integer match = jdbcTemplate.query(
                "select 1 from sqlite_master where type = 'table' and name = 'plan_definitions' limit 1",
                rs -> rs.next() ? 1 : null);
        return match != null;
    }
}
