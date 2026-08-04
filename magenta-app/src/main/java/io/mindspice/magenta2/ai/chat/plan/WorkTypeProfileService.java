package io.mindspice.magenta2.ai.chat.plan;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Returns append-only system prompt text for each {@link WorkTypeProfile}.
 * Text is appended after mode-specific runtime instructions and must not
 * override PLAN/TASK mandatory terminal-state rules.
 */
@Service
public class WorkTypeProfileService {

    /**
     * Returns the append-only system prompt text for the given profile.
     * Returns empty string for null profile.
     */
    public String getSystemPromptAppend(WorkTypeProfile profile) {
        if (profile == null) {
            return "";
        }
        return switch (profile) {
            case CODING_CENTRIC -> """
                Worktype: coding-centric.
                Prioritize repository evidence, small coherent code changes, tests, startup smoke checks, and clear implementation closeout. Prefer existing project patterns over new abstractions.
                """;
            case DATA_CENTRIC -> """
                Worktype: data-centric.
                Prioritize data contracts, schema clarity, source provenance, validation, transformation correctness, and reproducible outputs. Call out assumptions about missing or dirty data.
                """;
            case RESEARCH_CENTRIC -> """
                Worktype: research-centric.
                Prioritize source quality, recency where relevant, citations, uncertainty tracking, and separating evidence from inference. Avoid unsupported conclusions.
                """;
        };
    }

    /**
     * Resolves the worktype profile from the plan's promptProfile string
     * and returns the system prompt append text.
     */
    public String getSystemPromptAppendForPlan(String promptProfileField) {
        if (!StringUtils.hasText(promptProfileField)) {
            return "";
        }
        WorkTypeProfile profile = WorkTypeProfile.fromString(promptProfileField);
        return getSystemPromptAppend(profile);
    }
}
