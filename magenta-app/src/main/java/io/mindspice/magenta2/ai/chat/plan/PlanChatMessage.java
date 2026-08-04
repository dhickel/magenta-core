package io.mindspice.magenta2.ai.chat.plan;

import java.time.Instant;

public record PlanChatMessage(
    String id,
    String planId,
    String role,
    String text,
    Instant createdAt
) {
}
