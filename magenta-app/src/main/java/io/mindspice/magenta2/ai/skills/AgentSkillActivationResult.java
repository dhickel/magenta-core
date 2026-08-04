package io.mindspice.magenta2.ai.skills;

import java.util.List;

public record AgentSkillActivationResult(
    AgentSkillActivationOutcome outcome,
    String skillName,
    String message,
    String content,
    String skillDirectory,
    List<String> resources
) {
    public static AgentSkillActivationResult failure(
        AgentSkillActivationOutcome outcome,
        String skillName,
        String message
    ) {
        return new AgentSkillActivationResult(outcome, skillName, message, null, null, List.of());
    }
}
