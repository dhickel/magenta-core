package io.mindspice.magenta2.ai.chat.service;

import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.repository.ChatPendingMessageRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatPendingMessageServiceTest {

    @Test
    void enqueueNormalizesValuesAndRejectsBlankMessage() {
        ChatPendingMessageService service = service();

        var message = service.enqueue(
            " conversation-1 ",
            "  queued text  ",
            " main ",
            " planner ",
            ChatSessionSurface.BROWSER
        );

        assertThat(message.conversationId()).isEqualTo("conversation-1");
        assertThat(message.messageText()).isEqualTo("queued text");
        assertThat(message.model()).isEqualTo("main");
        assertThat(message.planningModel()).isEqualTo("planner");
        assertThatThrownBy(() -> service.enqueue("conversation-1", " ", null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("message is required");
    }

    @Test
    void claimAckReleaseAndDeleteDelegateQueueUseCases() {
        ChatPendingMessageService service = service();
        service.enqueue("conversation-1", "first", null, null, null);

        var claimed = service.claim("conversation-1").orElseThrow();
        assertThat(service.release("conversation-1", claimed.message().id(), "wrong-token")).isFalse();
        assertThat(service.release("conversation-1", claimed.message().id(), claimed.claimToken())).isTrue();

        var claimedAgain = service.claim("conversation-1").orElseThrow();
        assertThat(service.ack("conversation-1", claimedAgain.message().id(), claimedAgain.claimToken())).isTrue();
        assertThat(service.list("conversation-1")).isEmpty();

        service.enqueue("conversation-1", "delete me", null, null, null);
        service.deleteByConversationId("conversation-1");
        assertThat(service.list("conversation-1")).isEmpty();
    }

    private ChatPendingMessageService service() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new ChatPendingMessageService(new ChatPendingMessageRepository(new JdbcTemplate(dataSource)));
    }
}
