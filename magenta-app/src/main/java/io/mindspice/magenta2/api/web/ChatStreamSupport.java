package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.util.List;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Shared helpers for chat SSE stream event construction and terminal logic.
 * Extracted from ChatController to keep controllers thin.
 */
public final class ChatStreamSupport {

    private ChatStreamSupport() {}

    /**
     * Sends an event on the SSE emitter with JSON media type.
     */
    public static void sendSseEvent(SseEmitter emitter, String eventName, Object data) throws Exception {
        emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
    }

    /**
     * Returns a safe error message string from a throwable.
     */
    public static String safeMessage(Throwable error) {
        return error == null || error.getMessage() == null ? "unknown error" : error.getMessage();
    }

    /**
     * Gets the last assistant message from the conversation history, or returns
     * an empty assistant message if none is found.
     */
    public static ChatMessage lastAssistantMessage(ChatService chatService, String conversationId) {
        List<ChatMessage> history = chatService.history(conversationId);
        if (!history.isEmpty()) {
            ChatMessage lastMessage = history.get(history.size() - 1);
            if ("assistant".equalsIgnoreCase(lastMessage.role())) {
                return lastMessage;
            }
        }
        return chatService.renderAssistantMessage("");
    }
}
