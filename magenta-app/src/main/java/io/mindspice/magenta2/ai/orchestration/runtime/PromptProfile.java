package io.mindspice.magenta2.ai.orchestration.runtime;

/**
 * Prompt profiles that guide agent behavior for different workload types.
 * Attached to plan definitions, job definitions, and projects to steer
 * model response style and tool usage patterns.
 */
public enum PromptProfile {
    RESEARCH,
    CODING,
    WRITING,
    TECHNICAL_WRITING,
    VALIDATION,
    MANAGEMENT,
    GENERAL
}
