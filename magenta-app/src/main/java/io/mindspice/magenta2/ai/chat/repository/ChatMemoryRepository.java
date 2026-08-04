package io.mindspice.magenta2.ai.chat.repository;

import java.util.Map;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class ChatMemoryRepository implements org.springframework.ai.chat.memory.ChatMemoryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChatMemoryRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        ensureMetadataColumn();
    }

    public boolean existsByConversationId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from ai_chat_memory where conversation_id = ?",
                Integer.class,
                conversationId);
        return count != null && count > 0;
    }

    @Override
    public List<String> findConversationIds() {
        return jdbcTemplate.queryForList(
            "select distinct conversation_id from ai_chat_memory order by conversation_id",
            String.class
        );
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        return jdbcTemplate.query(
            """
                select message_type, message_text
                     , message_metadata_json
                from ai_chat_memory
                where conversation_id = ?
                order by message_order asc
                """,
            (rs, rowNum) -> toMessage(
                rs.getString("message_type"),
                rs.getString("message_text"),
                rs.getString("message_metadata_json")
            ),
            conversationId
        );
    }

    @Override
    @Transactional
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcTemplate.update("delete from ai_chat_memory where conversation_id = ?", conversationId);
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            jdbcTemplate.update(
                """
                    insert into ai_chat_memory (conversation_id, message_order, message_type, message_text, message_metadata_json)
                    values (?, ?, ?, ?, ?)
                    """,
                conversationId,
                i,
                message.getMessageType().getValue(),
                message.getText(),
                metadataJson(message)
            );
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcTemplate.update("delete from ai_chat_memory where conversation_id = ?", conversationId);
    }

    private void ensureMetadataColumn() {
        jdbcTemplate.execute("""
            create table if not exists ai_chat_memory (
                conversation_id text not null,
                message_order integer not null,
                message_type text not null,
                message_text text,
                message_metadata_json text,
                primary key (conversation_id, message_order)
            )
            """);
        List<String> columns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('ai_chat_memory')",
            String.class
        );
        if (!columns.contains("message_metadata_json")) {
            jdbcTemplate.execute("alter table ai_chat_memory add column message_metadata_json text");
        }
    }

    private Message toMessage(String messageTypeValue, String messageText, String metadataJson) {
        MessageType messageType = java.util.Arrays.stream(MessageType.values())
            .filter(candidate -> candidate.getValue().equalsIgnoreCase(messageTypeValue))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown message type: " + messageTypeValue));
        Map<String, Object> metadata = metadata(metadataJson);
        return switch (messageType) {
            case USER -> new UserMessage(messageText);
            case SYSTEM -> new SystemMessage(messageText);
            default -> AssistantMessage.builder()
                .content(messageText)
                .properties(metadata)
                .build();
        };
    }

    private String metadataJson(Message message) {
        if (message.getMetadata() == null || message.getMetadata().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(message.getMetadata());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize chat message metadata", exception);
        }
    }

    private Map<String, Object> metadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse chat message metadata", exception);
        }
    }
}
