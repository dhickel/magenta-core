package io.mindspice.magenta2.ai.chat.plan;

import io.mindspice.magenta2.ai.chat.model.PlanMode;

public record PlanToolContext(
    String conversationId,
    PlanMode mode,
    String runId
) {
    public PlanToolContext(String conversationId, PlanMode mode) {
        this(conversationId, mode, null);
    }
}
