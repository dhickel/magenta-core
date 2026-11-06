package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.ConversationMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bridges the framework {@link ConversationMemory} SPI to Spring AI's
 * {@link ChatMemory} interface.
 * <p>
 * <b>Deviation from phase file:</b> Spring AI 2.0.0 {@code ChatMemory.get(String)}
 * does not accept a {@code lastN} parameter. This adapter returns all messages
 * for the conversation — the caller (or embedding app) must apply windowing
 * separately if needed.
 * <p>
 * This class is <b>package-private</b> — embedding apps never see it directly.
 * It is the sole point where {@code TurnMessage} ↔ Spring AI {@code Message}
 * conversion occurs.
 */
class SpringAiConversationMemoryAdapter implements ChatMemory {

    private final ConversationMemory conversationMemory;

    SpringAiConversationMemoryAdapter(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    // ── ChatMemory ──

    @Override
    public List<Message> get(String conversationId) {
        List<TurnMessage> messages = conversationMemory.getMessages(conversationId);
        List<Message> result = new ArrayList<>(messages.size());
        for (TurnMessage tm : messages) {
            result.add(toSpringAiMessage(tm));
        }
        return result;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<TurnMessage> turnMessages = new ArrayList<>();
        for (Message msg : messages) {
            fromSpringAiMessage(msg, turnMessages);
        }
        if (!turnMessages.isEmpty()) {
            conversationMemory.addMessages(conversationId, turnMessages);
        }
    }

    @Override
    public void clear(String conversationId) {
        conversationMemory.clear(conversationId);
    }

    // ── TurnMessage → Spring AI Message ──

    private static Message toSpringAiMessage(TurnMessage message) {
        return switch (message) {
            case TurnMessage.User user -> UserMessage.builder()
                .text(user.text())
                .metadata(springMetadata(user.metadata()))
                .build();
            case TurnMessage.Assistant assistant -> AssistantMessage.builder()
                .content(assistant.text())
                .properties(springMetadata(assistant.metadata()))
                .build();
            case TurnMessage.System system -> SystemMessage.builder()
                .text(system.text())
                .metadata(springMetadata(system.metadata()))
                .build();
            case TurnMessage.Tool tool -> ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    tool.toolCallId(), tool.toolName(), tool.text())))
                .metadata(springMetadata(tool.metadata()))
                .build();
        };
    }

    private static Map<String, Object> springMetadata(Map<String, String> metadata) {
        Map<String, Object> converted = new LinkedHashMap<>();
        metadata.forEach(converted::put);
        return Map.copyOf(converted);
    }

    // ── Spring AI Message → TurnMessage ──

    private static void fromSpringAiMessage(Message message, List<TurnMessage> sink) {
        Objects.requireNonNull(message, "message");
        MessageType type = Objects.requireNonNull(message.getMessageType(), "messageType");
        Map<String, String> metadata = turnMetadata(message);
        switch (type) {
            case USER -> sink.add(new TurnMessage.User(message.getText(), metadata));
            case ASSISTANT -> sink.add(new TurnMessage.Assistant(message.getText(), metadata));
            case SYSTEM -> sink.add(new TurnMessage.System(message.getText(), metadata));
            case TOOL -> {
                if (!(message instanceof ToolResponseMessage toolMessage)) {
                    throw new IllegalArgumentException(
                        "TOOL message must be a ToolResponseMessage");
                }
                for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
                    sink.add(new TurnMessage.Tool(
                        response.name(), response.id(), response.responseData(), metadata));
                }
            }
        }
    }

    private static Map<String, String> turnMetadata(Message message) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (message.getMetadata() != null) {
            message.getMetadata().forEach((key, value) -> {
                if (key != null && value != null
                    && !org.springframework.ai.chat.messages.AbstractMessage.MESSAGE_TYPE
                        .equals(key)) {
                    metadata.put(key, String.valueOf(value));
                }
            });
        }
        return Map.copyOf(metadata);
    }
}
