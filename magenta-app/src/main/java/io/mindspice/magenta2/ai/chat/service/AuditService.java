package io.mindspice.magenta2.ai.chat.service;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.agent.job.AgentJobService;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Owns audit-recording and title-job side effects for chat interactions.
 *
 * <p>Audit writes record user messages, assistant messages, and context usage snapshots
 * for each chat turn. Title-job submission kicks off background conversation naming.
 *
 * <p>All methods are safe to call with null dependencies -- they become no-ops when
 * the corresponding repository or service is unavailable. This matches the pre-extraction
 * behavior where ChatService gated each call with null checks.
 */
@Service
public class AuditService {
    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final AgentJobService agentJobService;
    private final ContextUsageTracker contextUsageTracker;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;

    @Autowired
    public AuditService(
        ChatMemoryRepository chatMemoryRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        @Autowired(required = false) AuditRepository auditRepository,
        @Autowired(required = false) ObjectMapper objectMapper,
        @Autowired(required = false) AgentJobService agentJobService,
        @Autowired(required = false) ContextUsageTracker contextUsageTracker
    ) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper;
        this.agentJobService = agentJobService;
        this.contextUsageTracker = contextUsageTracker;
    }

    /** Package-private minimal constructor for tests. Audit is a no-op when only memory is available. */
    AuditService(ChatMemoryRepository chatMemoryRepository, ChatSessionMetadataRepository chatSessionMetadataRepository) {
        this(chatMemoryRepository, chatSessionMetadataRepository, null, null, null, null);
    }

    public void auditUserMessage(ResolvedChatRequest request) {
        if (auditRepository != null) {
            auditRepository.recordUserMessage(request.conversationId(), request.message(), request.model());
        }
    }

    public void auditAssistantMessage(AssistantMessage message, ResolvedChatRequest request) {
        if (auditRepository == null || message == null) return;
        String metaJson = null;
        if (message.getMetadata() != null && !message.getMetadata().isEmpty() && objectMapper != null) {
            try {
                metaJson = objectMapper.writeValueAsString(message.getMetadata());
            } catch (JsonProcessingException ignored) {
            }
        }
        auditRepository.recordAssistantMessage(
            request.conversationId(), message.getText(), metaJson, request.model());
    }

    public void auditEndOfTurnContext(ResolvedChatRequest request, StoredContextUsage maintenance) {
        if (auditRepository != null && maintenance != null && maintenance.usage() != null) {
            int count = chatMemoryRepository.findByConversationId(request.conversationId()).size();
            auditRepository.recordContext(request.conversationId(), maintenance.usage(), count, request.model());
        }
    }

    public void recordContextUsage(String conversationId, ContextUsage usage, String model) {
        if (contextUsageTracker != null) {
            contextUsageTracker.record(conversationId, usage);
        }
        if (auditRepository != null && usage != null) {
            int count = chatMemoryRepository.findByConversationId(conversationId).size();
            auditRepository.recordContext(conversationId, usage, count, model);
        }
    }

    private static final int MAX_STACK_TRACE_LENGTH = 4000;

    public void recordError(String conversationId, String errorType,
                            String errorMessage, String stackTrace, String model) {
        if (auditRepository != null) {
            String boundedTrace = stackTrace != null && stackTrace.length() > MAX_STACK_TRACE_LENGTH
                ? stackTrace.substring(0, MAX_STACK_TRACE_LENGTH)
                : stackTrace;
            auditRepository.recordError(conversationId, errorType, errorMessage, boundedTrace, model);
        }
    }

    public void enqueueTitleJobIfFirstTurn(ResolvedChatRequest request) {
        if (agentJobService == null || request == null || !request.newConversation() || !request.titleJobEligible()) {
            return;
        }
        agentJobService.submitConversationTitle(request.conversationId(), request.model(), request.message());
    }
}
