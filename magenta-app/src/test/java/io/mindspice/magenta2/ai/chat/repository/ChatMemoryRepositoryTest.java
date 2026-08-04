package io.mindspice.magenta2.ai.chat.repository;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatMemoryRepositoryTest {

    @Test
    void preservesAssistantMessageMetadata() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        jdbcTemplate.execute("""
            create table ai_chat_memory (
                conversation_id text not null,
                message_order integer not null,
                message_type text not null,
                message_text text,
                primary key (conversation_id, message_order)
            )
            """);

        ChatMemoryRepository repository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        AssistantMessage message = AssistantMessage.builder()
            .content("Visible answer")
            .properties(Map.of("magenta.thinking", "structured notes"))
            .build();

        repository.saveAll("conversation-1", List.of(message));

        List<Message> messages = repository.findByConversationId("conversation-1");
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getText()).isEqualTo("Visible answer");
        assertThat(messages.getFirst().getMetadata()).containsEntry("magenta.thinking", "structured notes");
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }
}
