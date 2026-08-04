package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages user and agent inbox messages for approvals, notifications,
 * and run-output delivery.
 */
@Service
public class InboxService {
    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    private final WorkflowRepository repository;
    private final ObjectMapper objectMapper;

    public InboxService(WorkflowRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    // ════════════════════════════════════════════════════════════════
    //  User inbox
    // ════════════════════════════════════════════════════════════════

    public List<InboxMessage> userInbox() {
        return repository.findInboxByRecipient(InboxMessageToType.USER, null);
    }

    public Optional<InboxMessage> userMessage(String messageId) {
        return repository.findInboxMessage(messageId)
            .filter(m -> m.toType() == InboxMessageToType.USER);
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent inbox
    // ════════════════════════════════════════════════════════════════

    public List<InboxMessage> agentInbox(String agentId) {
        requireId(agentId, "agentId");
        return repository.findInboxByRecipient(InboxMessageToType.AGENT, agentId);
    }

    public Optional<InboxMessage> agentMessage(String agentId, String messageId) {
        requireId(agentId, "agentId");
        return repository.findInboxMessage(messageId)
            .filter(m -> m.toType() == InboxMessageToType.AGENT
                && agentId.equals(m.toId()));
    }

    // ════════════════════════════════════════════════════════════════
    //  Create messages
    // ════════════════════════════════════════════════════════════════

    public InboxMessage createApprovalMessage(
        InboxMessageToType toType, String toId, String fromId,
        String body, String workflowRunId, int nodeIndex
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("workflowRunId", workflowRunId);
        metadata.put("nodeIndex", nodeIndex);
        String metadataJson = toJson(metadata);

        return repository.saveInboxMessage(new InboxMessage(
            UUID.randomUUID().toString(),
            toType, toId, fromId,
            InboxMessageType.APPROVAL,
            body,
            metadataJson,
            null, null, null,
            Instant.now(), Instant.now()
        ));
    }

    public InboxMessage createInfoMessage(
        InboxMessageToType toType, String toId, String fromId,
        String body, String metadataJson
    ) {
        return repository.saveInboxMessage(new InboxMessage(
            UUID.randomUUID().toString(),
            toType, toId, fromId,
            InboxMessageType.INFO,
            body,
            metadataJson,
            null, null, null,
            Instant.now(), Instant.now()
        ));
    }

    public InboxMessage createRunOutputMessage(
        InboxMessageToType toType, String toId, String fromId,
        String body, String metadataJson
    ) {
        return repository.saveInboxMessage(new InboxMessage(
            UUID.randomUUID().toString(),
            toType, toId, fromId,
            InboxMessageType.RUN_OUTPUT,
            body,
            metadataJson,
            null, null, null,
            Instant.now(), Instant.now()
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Responses
    // ════════════════════════════════════════════════════════════════

    /**
     * Record an approval response from a user.
     */
    public InboxMessage respondUserApproval(String messageId, boolean approved, String comment) {
        InboxMessage message = userMessage(messageId)
            .orElseThrow(() -> new IllegalArgumentException("User inbox message not found: " + messageId));

        if (message.messageType() != InboxMessageType.APPROVAL) {
            throw new IllegalArgumentException("Message is not an approval request: " + messageId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approved", approved);
        response.put("approverType", "user");
        if (StringUtils.hasText(comment)) {
            response.put("comment", comment);
        }

        return repository.saveInboxMessage(new InboxMessage(
            message.id(), message.toType(), message.toId(), message.fromId(),
            message.messageType(), message.body(), message.metadataJson(),
            toJson(response), Instant.now(), null,
            message.createdAt(), Instant.now()
        ));
    }

    /**
     * Record an approval response from an agent.
     */
    public InboxMessage respondAgentApproval(String agentId, String messageId, boolean approved, String comment) {
        InboxMessage message = agentMessage(agentId, messageId)
            .orElseThrow(() -> new IllegalArgumentException("Agent inbox message not found: " + messageId));

        if (message.messageType() != InboxMessageType.APPROVAL) {
            throw new IllegalArgumentException("Message is not an approval request: " + messageId);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("approved", approved);
        response.put("approverType", "agent");
        response.put("approverId", agentId);
        if (StringUtils.hasText(comment)) {
            response.put("comment", comment);
        }

        return repository.saveInboxMessage(new InboxMessage(
            message.id(), message.toType(), message.toId(), message.fromId(),
            message.messageType(), message.body(), message.metadataJson(),
            toJson(response), Instant.now(), null,
            message.createdAt(), Instant.now()
        ));
    }

    // ════════════════════════════════════════════════════════════════
    //  Mark handled
    // ════════════════════════════════════════════════════════════════

    public InboxMessage markHandled(String messageId) {
        InboxMessage message = repository.findInboxMessage(messageId)
            .orElseThrow(() -> new IllegalArgumentException("Inbox message not found: " + messageId));

        return repository.saveInboxMessage(new InboxMessage(
            message.id(), message.toType(), message.toId(), message.fromId(),
            message.messageType(), message.body(), message.metadataJson(),
            message.responseJson(), message.respondedAt(), Instant.now(),
            message.createdAt(), Instant.now()
        ));
    }

    /**
     * Load a message by ID regardless of recipient type (user or agent).
     */
    public Optional<InboxMessage> findMessageById(String messageId) {
        return repository.findInboxMessage(messageId);
    }

    /**
     * Extract the "approved" field from a response JSON string.
     * Returns false if parsing fails or the field is absent.
     */
    public boolean parseApprovalFromResponse(String responseJson) {
        if (!StringUtils.hasText(responseJson)) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = objectMapper.readValue(responseJson, Map.class);
            Object approved = response.get("approved");
            return approved instanceof Boolean b && b;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse approval response JSON: {}", responseJson, e);
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Helpers
    // ════════════════════════════════════════════════════════════════

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize JSON", e);
        }
    }

    private void requireId(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
