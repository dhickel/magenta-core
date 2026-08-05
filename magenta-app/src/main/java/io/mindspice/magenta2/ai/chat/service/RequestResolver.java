package io.mindspice.magenta2.ai.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns request and model resolution for chat interactions.
 *
 * <p>Resolves conversation IDs, selects models based on stored preferences and
 * available model configs. Also provides conversation existence checks.
 */
@Service
public class RequestResolver {

    private final AiConfig aiConfig;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatMemoryRepository chatMemoryRepository;

    public RequestResolver(
        AiConfig aiConfig,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMemoryRepository chatMemoryRepository
    ) {
        this.aiConfig = aiConfig;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.chatMemoryRepository = chatMemoryRepository;
    }

    @Transactional
    public ResolvedChatRequest resolve(ChatRequest request) {
        if (request instanceof ChatRequest.MsgRequest msgRequest) {
            return resolve(msgRequest.conversationId(), msgRequest.message(), msgRequest.model());
        }
        throw new IllegalArgumentException("message request is required");
    }

    @Transactional
    public ResolvedChatRequest resolve(String conversationId, String message, String model) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
        boolean newConversation = !conversationExists(resolvedConversationId);
        String storedModel = storedConversationModel(resolvedConversationId);
        String selectedModel = StringUtils.hasText(model)
                ? model
                : (StringUtils.hasText(storedModel) ? storedModel : defaultModel());
        return new ResolvedChatRequest(resolvedConversationId, message, selectedModel, newConversation, true, false, null);
    }

    public String storedConversationModel(String conversationId) {
        return chatSessionMetadataRepository.findModel(conversationId).orElse(null);
    }

    public String defaultModel() {
        if (aiConfig != null && StringUtils.hasText(aiConfig.resolvedDefaultModelKey())) {
            return aiConfig.resolvedDefaultModelKey();
        }
        return null;
    }

    public List<String> availableModels() {
        if (aiConfig == null || aiConfig.models() == null) {
            return List.of();
        }
        return aiConfig.models().values().stream()
            .map(config -> config.remoteModelName())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    public boolean conversationExists(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        return rawConversationIds().contains(conversationId);
    }

    public List<String> rawConversationIds() {
        return new ArrayList<>(chatMemoryRepository.findConversationIds());
    }

    public String newConversationId() {
        return UUID.randomUUID().toString();
    }
}
