package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * A message sent to a user or agent inbox. Used for approvals,
 * notifications, and run output delivery.
 *
 * @param id           unique message identifier
 * @param toType       recipient type: "USER" or "AGENT"
 * @param toId         recipient id (null for user messages)
 * @param fromId       sender id (agent id or null for system)
 * @param messageType  INFO, QUESTION, APPROVAL, or RUN_OUTPUT
 * @param body         human-readable message body
 * @param metadataJson additional structured data (e.g. workflowRunId, nodeIndex)
 * @param responseJson the recipient's response payload (if any)
 * @param respondedAt  when the recipient responded
 * @param handledAt    when the message was processed/handled
 * @param createdAt    creation timestamp
 * @param updatedAt    last-update timestamp
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboxMessage(
    String id,
    InboxMessageToType toType,
    String toId,
    String fromId,
    InboxMessageType messageType,
    String body,
    String metadataJson,
    String responseJson,
    Instant respondedAt,
    Instant handledAt,
    Instant createdAt,
    Instant updatedAt
) {
    public InboxMessage {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("InboxMessage id must not be blank");
        }
        if (toType == null) {
            throw new IllegalArgumentException("InboxMessage toType must not be null");
        }
        if (messageType == null) {
            messageType = InboxMessageType.INFO;
        }
    }

    public boolean isResponded() {
        return respondedAt != null;
    }

    public boolean isHandled() {
        return handledAt != null;
    }
}
