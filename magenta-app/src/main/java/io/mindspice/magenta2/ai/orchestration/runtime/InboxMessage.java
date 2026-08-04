package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Map;

import jakarta.validation.constraints.NotBlank;

public record InboxMessage(
    String id,
    String toAgentId,
    String fromId,
    String messageType,
    @NotBlank String body,
    Map<String, Object> metadata,
    boolean read,
    boolean handled,
    Instant createdAt,
    Instant updatedAt
) {
}
