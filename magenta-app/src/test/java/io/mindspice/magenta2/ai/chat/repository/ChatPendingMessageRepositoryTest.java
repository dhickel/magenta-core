package io.mindspice.magenta2.ai.chat.repository;

import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPendingMessageRepositoryTest {

    @Test
    void enqueueListsVisibleMessagesInFifoOrder() {
        ChatPendingMessageRepository repository = repository();

        repository.enqueue("conversation-1", "first", "main", "planner", ChatSessionSurface.BROWSER);
        repository.enqueue("conversation-1", "second", null, null, null);

        var messages = repository.findVisibleByConversationId("conversation-1");

        assertThat(messages).extracting(message -> message.messageText())
            .containsExactly("first", "second");
        assertThat(messages).extracting(message -> message.position())
            .containsExactly(1, 2);
        assertThat(messages).extracting(message -> message.total())
            .containsExactly(2, 2);
        assertThat(messages.getFirst().model()).isEqualTo("main");
        assertThat(messages.getFirst().planningModel()).isEqualTo("planner");
        assertThat(messages.getFirst().surface()).isEqualTo(ChatSessionSurface.BROWSER);
    }

    @Test
    void claimAckReleaseAndStaleRecoveryPreserveMessagesSafely() {
        ChatPendingMessageRepository repository = repository();
        repository.enqueue("conversation-1", "first", null, null, null);
        repository.enqueue("conversation-1", "second", null, null, null);

        var firstClaim = repository.claimOldest("conversation-1").orElseThrow();
        assertThat(firstClaim.message().messageText()).isEqualTo("first");
        assertThat(repository.findVisibleByConversationId("conversation-1").getFirst().status()).isEqualTo("CLAIMED");

        assertThat(repository.release("conversation-1", firstClaim.message().id(), "wrong-token")).isFalse();
        assertThat(repository.release("conversation-1", firstClaim.message().id(), firstClaim.claimToken())).isTrue();

        var releasedClaim = repository.claimOldest("conversation-1").orElseThrow();
        assertThat(releasedClaim.message().messageText()).isEqualTo("first");
        assertThat(repository.ack("conversation-1", releasedClaim.message().id(), releasedClaim.claimToken())).isTrue();
        assertThat(repository.findVisibleByConversationId("conversation-1"))
            .extracting(message -> message.messageText())
            .containsExactly("second");

        var staleClaim = repository.claimOldest("conversation-1").orElseThrow();
        repository.markClaimStaleForTest(staleClaim.message().id(), Instant.now().minusSeconds(900));
        List<String> recovered = repository.findVisibleByConversationId("conversation-1").stream()
            .map(message -> message.status())
            .toList();
        assertThat(recovered).containsExactly("PENDING");
        assertThat(repository.claimOldest("conversation-1").orElseThrow().message().messageText()).isEqualTo("second");
    }

    @Test
    void deleteByConversationRemovesOnlyThatQueue() {
        ChatPendingMessageRepository repository = repository();
        repository.enqueue("conversation-1", "delete me", null, null, null);
        repository.enqueue("conversation-2", "keep me", null, null, null);

        repository.deleteByConversationId("conversation-1");

        assertThat(repository.findVisibleByConversationId("conversation-1")).isEmpty();
        assertThat(repository.findVisibleByConversationId("conversation-2"))
            .extracting(message -> message.messageText())
            .containsExactly("keep me");
    }

    @Test
    void historicalUnknownSurfaceIsNonFatalAndNotRewritten() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChatPendingMessageRepository repository = new ChatPendingMessageRepository(jdbc);
        repository.enqueue("conversation-1", "legacy", null, null, ChatSessionSurface.BROWSER);
        jdbc.update("update ai_chat_pending_messages set surface = 'AVATAR' where conversation_id = 'conversation-1'");

        assertThat(repository.findVisibleByConversationId("conversation-1").getFirst().surface()).isNull();
        assertThat(jdbc.queryForObject(
            "select surface from ai_chat_pending_messages where conversation_id = 'conversation-1'", String.class
        )).isEqualTo("AVATAR");
    }

    @Test
    void historicalUnknownSessionMetadataSurfaceIsNonFatalAndNotRewritten() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ChatSessionMetadataRepository repository = new ChatSessionMetadataRepository(jdbc);
        repository.saveSurfaceIfAbsent("conversation-1", ChatSessionSurface.BROWSER);
        jdbc.update("update ai_chat_session_metadata set surface = 'AVATAR' where conversation_id = 'conversation-1'");

        assertThat(repository.findSurface("conversation-1")).isEmpty();
        assertThat(jdbc.queryForObject(
            "select surface from ai_chat_session_metadata where conversation_id = 'conversation-1'", String.class
        )).isEqualTo("AVATAR");
    }

    private ChatPendingMessageRepository repository() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new ChatPendingMessageRepository(new JdbcTemplate(dataSource));
    }
}
