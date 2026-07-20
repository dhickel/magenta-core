package io.mindspice.magenta2.ai.skills;

/** Provenance of a skill candidate. */
public enum SkillProvenance {
    /** Global package directory (<slug>/SKILL.md). */
    GLOBAL_PACKAGE,

    /** Workspace flat Markdown file (.skills/<name>.md). */
    WORKSPACE_FLAT
}
