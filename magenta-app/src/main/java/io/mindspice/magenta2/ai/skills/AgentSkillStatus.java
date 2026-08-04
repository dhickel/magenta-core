package io.mindspice.magenta2.ai.skills;

public enum AgentSkillStatus {
    VALID,
    WARNING,
    INVALID;

    public boolean loadable() {
        return this != INVALID;
    }
}
