package io.mindspice.magenta2.ai.skills;

import java.time.Instant;

public record AgentSkillAssignment(
    Long id,
    Long skillId,
    String skillName,
    AgentSkillTargetType targetType,
    String targetId,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) { }
