package io.mindspice.magenta2.ai.chat.service;

import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.model.ClaimedPendingChatMessage;
import io.mindspice.magenta2.ai.chat.model.PendingChatMessage;
import io.mindspice.magenta2.ai.chat.repository.ChatPendingMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChatPendingMessageService {
    private final ChatPendingMessageRepository repository;

    public ChatPendingMessageService(ChatPendingMessageRepository repository) {
        this.repository = repository;
    }

    public PendingChatMessage enqueue(
        String conversationId,
        String message,
        String model,
        String planningModel,
        ChatSessionSurface surface
    ) {
        String normalizedConversationId = requireText(conversationId, "conversationId is required");
        String normalizedMessage = requireText(message, "message is required");
        return repository.enqueue(
            normalizedConversationId,
            normalizedMessage,
            normalize(model),
            normalize(planningModel),
            surface
        );
    }

    public List<PendingChatMessage> list(String conversationId) {
        return repository.findVisibleByConversationId(requireText(conversationId, "conversationId is required"));
    }

    public Optional<ClaimedPendingChatMessage> claim(String conversationId) {
        return repository.claimOldest(requireText(conversationId, "conversationId is required"));
    }

    public boolean ack(String conversationId, String messageId, String claimToken) {
        return repository.ack(
            requireText(conversationId, "conversationId is required"),
            requireText(messageId, "messageId is required"),
            requireText(claimToken, "claimToken is required")
        );
    }

    public boolean release(String conversationId, String messageId, String claimToken) {
        return repository.release(
            requireText(conversationId, "conversationId is required"),
            requireText(messageId, "messageId is required"),
            requireText(claimToken, "claimToken is required")
        );
    }

    public void deleteByConversationId(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            repository.deleteByConversationId(conversationId.trim());
        }
    }

    private String requireText(String value, String message) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(message);
        }
        return normalized;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
