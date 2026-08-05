package io.mindspice.magenta2.ai.chat.repository;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPendingMessageRepositoryTest {

    @Test
    void enqueueListsVisibleMessagesInFifoOrder() {
        ChatPendingMessageRepository repository = repository();

        repository.enqueue("conversation-1", "first", "main", "planner");
        repository.enqueue("conversation-1", "second", null, null);

        var messages = repository.findVisibleByConversationId("conversation-1");

        assertThat(messages).extracting(message -> message.messageText())
            .containsExactly("first", "second");
        assertThat(messages).extracting(message -> message.position())
            .containsExactly(1, 2);
        assertThat(messages).extracting(message -> message.total())
            .containsExactly(2, 2);
        assertThat(messages.getFirst().model()).isEqualTo("main");
        assertThat(messages.getFirst().planningModel()).isEqualTo("planner");
    }

    @Test
    void claimAckReleaseAndStaleRecoveryPreserveMessagesSafely() {
        ChatPendingMessageRepository repository = repository();
        repository.enqueue("conversation-1", "first", null, null);
        repository.enqueue("conversation-1", "second", null, null);

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
        repository.enqueue("conversation-1", "delete me", null, null);
        repository.enqueue("conversation-2", "keep me", null, null);

        repository.deleteByConversationId("conversation-1");

        assertThat(repository.findVisibleByConversationId("conversation-1")).isEmpty();
        assertThat(repository.findVisibleByConversationId("conversation-2"))
            .extracting(message -> message.messageText())
            .containsExactly("keep me");
    }

    private ChatPendingMessageRepository repository() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new ChatPendingMessageRepository(new JdbcTemplate(dataSource));
    }
}
