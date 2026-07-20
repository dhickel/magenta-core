package io.mindspice.magenta2.ai.skills;

/** Deterministic root-precedence order for scoped skill discovery. */
public enum SkillScope {
    /** Effective non-chat workspace (.skills/). Highest precedence. */
    WORKSPACE,

    /** Physically distinct bound-agent workspace (.skills/). */
    BOUND_AGENT,

    /** Authoritative project root (disabled unless proven). */
    PROJECT,

    /** Global Magenta package repository (skills/). Lowest precedence. */
    GLOBAL;

    /**
     * Lower ordinal has higher precedence. The ordinal MUST NOT be relied upon
     * in user-facing code; use this comparator explicitly.
     */
    public static int compare(SkillScope a, SkillScope b) {
        return Integer.compare(a.ordinal(), b.ordinal());
    }
}
