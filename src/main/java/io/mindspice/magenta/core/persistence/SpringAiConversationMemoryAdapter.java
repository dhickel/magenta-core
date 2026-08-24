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
import java.util.List;

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

    private static Message toSpringAiMessage(TurnMessage tm) {
        return switch (tm.role()) {
            case "user" -> new UserMessage(tm.text());
            case "assistant" -> new AssistantMessage(tm.text());
            case "system" -> new SystemMessage(tm.text());
            case "tool" -> ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    tm.toolCallId(), tm.toolName(), tm.text())))
                .build();
            default -> new UserMessage(tm.text()); // fallback
        };
    }

    // ── Spring AI Message → TurnMessage ──

    private static void fromSpringAiMessage(Message msg, List<TurnMessage> sink) {
        MessageType type = msg.getMessageType();
        switch (type) {
            case USER -> sink.add(TurnMessage.user(msg.getText()));
            case ASSISTANT -> sink.add(TurnMessage.assistant(msg.getText()));
            case SYSTEM -> sink.add(TurnMessage.system(msg.getText()));
            case TOOL -> {
                if (msg instanceof ToolResponseMessage trm) {
                    for (ToolResponseMessage.ToolResponse tr : trm.getResponses()) {
                        sink.add(TurnMessage.tool(tr.name(), tr.id(), tr.responseData()));
                    }
                }
            }
        }
    }
}
