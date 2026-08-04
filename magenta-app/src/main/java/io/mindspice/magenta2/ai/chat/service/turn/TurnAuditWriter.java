package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.service.AuditService;
import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import io.mindspice.magenta2.ai.chat.service.StoredContextUsage;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService.ToolTranscriptEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Facade over AuditService and AuditRepository for turn-level audit recording.
 * All methods are null-safe — if audit services are not configured, calls are no-ops.
 */
public class TurnAuditWriter {
    private static final Logger logger = LoggerFactory.getLogger(TurnAuditWriter.class);

    private final AuditService auditService;
    private final AuditRepository auditRepository;

    public TurnAuditWriter(AuditService auditService, AuditRepository auditRepository) {
        this.auditService = auditService;
        this.auditRepository = auditRepository;
    }

    public void recordTurnStart(ResolvedChatRequest request) {
        if (auditService != null) {
            auditService.auditUserMessage(request);
        }
    }

    public void recordToolExec(ToolTranscriptEntry entry, String conversationId, String model) {
        if (auditRepository != null) {
            auditRepository.recordToolExec(entry, conversationId, model);
        }
    }

    public void recordContextUsage(String conversationId, ContextUsage usage, String model) {
        if (auditService != null) {
            auditService.recordContextUsage(conversationId, usage, model);
        }
    }

    public void recordAssistantMessage(AssistantMessage message, ResolvedChatRequest request) {
        if (auditService != null && message != null) {
            auditService.auditAssistantMessage(message, request);
        }
    }

    public void recordEndOfTurnContext(ResolvedChatRequest request, StoredContextUsage maintenance) {
        if (auditService != null) {
            auditService.auditEndOfTurnContext(request, maintenance);
        }
    }

    public void enqueueTitleJob(ResolvedChatRequest request) {
        if (auditService != null) {
            auditService.enqueueTitleJobIfFirstTurn(request);
        }
    }
}
