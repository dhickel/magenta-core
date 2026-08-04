package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service("orchestrationInboxService")
public class InboxService {
    private final OrchestrationRuntimeRepository repository;
    private final AgentProfileService agentProfileService;
    private final OrchestrationEventService eventService;

    public InboxService(
        OrchestrationRuntimeRepository repository,
        AgentProfileService agentProfileService,
        OrchestrationEventService eventService
    ) {
        this.repository = repository;
        this.agentProfileService = agentProfileService;
        this.eventService = eventService;
    }

    public List<InboxMessage> messages(String agentId) {
        agentProfileService.get(agentId);
        return repository.findInboxMessages(agentId);
    }

    public InboxMessage send(String toAgentId, InboxMessage message) {
        AgentProfile target = agentProfileService.get(toAgentId);
        if (!target.directLineEnabled()) {
            throw new IllegalStateException("Direct-line inbox is disabled for agent: " + toAgentId);
        }
        if (!StringUtils.hasText(message.messageType())) {
            throw new IllegalArgumentException("messageType is required");
        }
        InboxMessage saved = repository.saveInboxMessage(new InboxMessage(
            StringUtils.hasText(message.id()) ? message.id() : UUID.randomUUID().toString(),
            toAgentId,
            normalize(message.fromId()),
            message.messageType().trim(),
            normalize(message.body()),
            message.metadata() == null ? Map.of() : message.metadata(),
            message.read(),
            message.handled(),
            message.createdAt(),
            message.updatedAt()
        ));
        eventService.publish(EventType.INBOX_MESSAGE_RECEIVED, "INBOX_MESSAGE", saved.id(), Map.of(
            "messageId", saved.id(),
            "toAgentId", saved.toAgentId(),
            "fromId", saved.fromId() == null ? "" : saved.fromId(),
            "messageType", saved.messageType()
        ));
        return saved;
    }

    public InboxMessage markRead(String messageId) {
        InboxMessage current = repository.findInboxMessage(messageId)
            .orElseThrow(() -> new IllegalStateException("Inbox message not found: " + messageId));
        return repository.saveInboxMessage(new InboxMessage(
            current.id(), current.toAgentId(), current.fromId(), current.messageType(), current.body(),
            current.metadata(), true, current.handled(), current.createdAt(), current.updatedAt()
        ));
    }

    public InboxMessage markHandled(String messageId) {
        InboxMessage current = repository.findInboxMessage(messageId)
            .orElseThrow(() -> new IllegalStateException("Inbox message not found: " + messageId));
        return repository.saveInboxMessage(new InboxMessage(
            current.id(), current.toAgentId(), current.fromId(), current.messageType(), current.body(),
            current.metadata(), true, true, current.createdAt(), current.updatedAt()
        ));
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
