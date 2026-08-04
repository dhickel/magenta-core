package io.mindspice.magenta2.ai.orchestration.workspaces;

import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatConversationExistenceRepositoryTest {

    private static final String CONV_ID = "00000000-0000-0000-0000-000000000001";

    private ChatConversationExistenceRepository repo(boolean memoryExists, boolean sessionExists) {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(ds);
        var objMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        var chatMemory = new ChatMemoryRepository(jdbc, objMapper) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return CONV_ID.equals(conversationId) && memoryExists;
            }
        };
        var chatSession = new ChatSessionMetadataRepository(jdbc) {
            @Override
            public boolean existsByConversationId(String conversationId) {
                return CONV_ID.equals(conversationId) && sessionExists;
            }
        };
        return new ChatConversationExistenceRepository(chatMemory, chatSession, jdbc);
    }

    @Test
    void blankIdReturnsFalse() {
        var repo = repo(false, false);
        assertThat(repo.existsByConversationId(null)).isFalse();
        assertThat(repo.existsByConversationId("")).isFalse();
        assertThat(repo.existsByConversationId("   ")).isFalse();
    }

    @Test
    void returnsTrueWhenChatMemoryHasConversation() {
        var repo = repo(true, false);
        assertThat(repo.existsByConversationId(CONV_ID)).isTrue();
    }

    @Test
    void returnsTrueWhenSessionMetadataHasConversation() {
        var repo = repo(false, true);
        assertThat(repo.existsByConversationId(CONV_ID)).isTrue();
    }

    @Test
    void returnsTrueWhenBothHaveConversation() {
        var repo = repo(true, true);
        assertThat(repo.existsByConversationId(CONV_ID)).isTrue();
    }

    @Test
    void returnsFalseWhenNeitherHasConversation() {
        var repo = repo(false, false);
        assertThat(repo.existsByConversationId(CONV_ID)).isFalse();
    }

    @Test
    void returnsFalseForUnknownConversationId() {
        var repo = repo(true, true);
        assertThat(repo.existsByConversationId("unknown-conv")).isFalse();
    }

    @Test
    void planDefinitionsTableAbsenceDoesNotThrow() {
        var repo = repo(false, false);
        assertThat(repo.existsByConversationId(CONV_ID)).isFalse();
    }

    @Test
    void returnsTrueWhenPlanDefinitionHasConversation() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(ds);
        var memory = new ChatMemoryRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var metadata = new ChatSessionMetadataRepository(jdbc);
        jdbc.execute("create table plan_definitions (id text primary key, conversation_id text)");
        jdbc.update("insert into plan_definitions (id, conversation_id) values (?, ?)", "plan-1", CONV_ID);

        assertThat(new ChatConversationExistenceRepository(memory, metadata, jdbc)
                .existsByConversationId(CONV_ID)).isTrue();
    }

    @Test
    void doesNotSwallowUnrelatedPlanSchemaErrors() {
        var ds = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        var jdbc = new JdbcTemplate(ds);
        var memory = new ChatMemoryRepository(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());
        var metadata = new ChatSessionMetadataRepository(jdbc);
        jdbc.execute("create table plan_definitions (id text primary key)");
        var repository = new ChatConversationExistenceRepository(memory, metadata, jdbc);

        assertThatThrownBy(() -> repository.existsByConversationId(CONV_ID))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
