package io.mindspice.magenta2.ai.chat.plan;

import java.util.Locale;

/**
 * Worktype profile that guides agent behavior for different workload types.
 * Replaces the legacy {@code promptProfile} string with structured enum values.
 *
 * <p>Legacy {@code PromptProfile} values are mapped as follows:
 * {@code CODING} -> {@code CODING_CENTRIC},
 * {@code RESEARCH} -> {@code RESEARCH_CENTRIC},
 * all others -> {@code DATA_CENTRIC}.
 */
public enum WorkTypeProfile {
    CODING_CENTRIC,
    DATA_CENTRIC,
    RESEARCH_CENTRIC;

    /**
     * Resolve a worktype profile from a string. Accepts both new canonical names
     * and legacy {@code PromptProfile} values. Defaults to {@code CODING_CENTRIC}.
     */
    public static WorkTypeProfile fromString(String value) {
        if (value == null || value.isBlank()) {
            return CODING_CENTRIC;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        // Try exact match first
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            // fall through to legacy mapping
        }
        // Legacy PromptProfile values
        return switch (normalized) {
            case "CODING" -> CODING_CENTRIC;
            case "RESEARCH" -> RESEARCH_CENTRIC;
            default -> DATA_CENTRIC; // WRITING, TECHNICAL_WRITING, VALIDATION, MANAGEMENT, GENERAL, etc.
        };
    }
}
