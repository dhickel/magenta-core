package io.mindspice.magenta2.api.web;

import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStreamSupportTest {

    @Test
    void safeMessageReturnsMessageText() {
        assertThat(ChatStreamSupport.safeMessage(new RuntimeException("test error")))
            .isEqualTo("test error");
    }

    @Test
    void safeMessageReturnsUnknownForNullThrowable() {
        assertThat(ChatStreamSupport.safeMessage(null))
            .isEqualTo("unknown error");
    }

    @Test
    void safeMessageReturnsUnknownForNullMessage() {
        assertThat(ChatStreamSupport.safeMessage(new RuntimeException()))
            .isEqualTo("unknown error");
    }

    @Test
    void lastAssistantMessageReturnsLastAssistantMessage() {
        StubHistoryService service = new StubHistoryService(
            List.of(new ChatMessage("user", "hello", null, null),
                new ChatMessage("assistant", "hi there", "Hello!", null))
        );

        ChatMessage result = ChatStreamSupport.lastAssistantMessage(service, "conv-1");

        assertThat(result.role()).isEqualTo("assistant");
        assertThat(result.text()).isEqualTo("hi there");
    }

    @Test
    void lastAssistantMessageReturnsEmptyWhenNoAssistantMessage() {
        StubHistoryService service = new StubHistoryService(
            List.of(new ChatMessage("user", "hello", null, null))
        );

        ChatMessage result = ChatStreamSupport.lastAssistantMessage(service, "conv-1");

        assertThat(result.role()).isEqualTo("assistant");
        assertThat(result.text()).isEmpty();
    }

    @Test
    void lastAssistantMessageReturnsEmptyWhenNoHistory() {
        StubHistoryService service = new StubHistoryService(List.of());

        ChatMessage result = ChatStreamSupport.lastAssistantMessage(service, "conv-1");

        assertThat(result.role()).isEqualTo("assistant");
        assertThat(result.text()).isEmpty();
    }

    private static class StubHistoryService extends ChatService {
        private final List<ChatMessage> history;

        StubHistoryService(List<ChatMessage> history) {
            super(null, null, null, null, null);
            this.history = history;
        }

        @Override
        public List<ChatMessage> history(String conversationId) {
            return history;
        }

        @Override
        public ChatMessage renderAssistantMessage(String text) {
            return new ChatMessage("assistant", text, "<p>" + text + "</p>", null);
        }
    }
}
