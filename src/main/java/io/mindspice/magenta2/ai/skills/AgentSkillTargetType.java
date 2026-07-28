package io.mindspice.magenta2.ai.skills;

/**
 * Canonical assignment target types. Ordered by specificity for overlay
 * resolution (Chat Session > Workflow > Task > Job > Project > Agent).
 */
public enum AgentSkillTargetType {
    AGENT,
    PROJECT,
    JOB,
    TASK,
    WORKFLOW,
    CHAT_SESSION
}
