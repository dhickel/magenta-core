package io.mindspice.magenta2.ai.agent.job;

import java.time.Instant;

public record AgentJob(
    String id,
    AgentJobType type,
    AgentJobStatus status,
    String conversationId,
    String selectedModel,
    String inputJson,
    String resultJson,
    String errorText,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt
) {
}
