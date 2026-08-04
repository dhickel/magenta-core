package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Policy governing what happens when a gate node receives an approval response.
 */
public enum ResumePolicy {
    /** Approve continues the workflow; reject marks it FAILED. */
    APPROVE_CONTINUE_REJECT_FAILED("approve_continue_reject_failed"),
    /** Approve continues the workflow; reject marks it NEEDS_REVIEW. */
    APPROVE_CONTINUE_REJECT_NEEDS_REVIEW("approve_continue_reject_needs_review"),
    /** Any response continues the workflow. */
    ANY_CONTINUE("any_continue");

    private final String wireName;

    ResumePolicy(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static ResumePolicy fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return APPROVE_CONTINUE_REJECT_FAILED;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ResumePolicy policy : values()) {
            if (policy.wireName.equals(normalized) || policy.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("Unknown resume policy: " + value);
    }
}
