package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Overall status of a workflow run.
 */
public enum WorkflowRunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    WAITING("waiting"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    NEEDS_REVIEW("needs_review");

    private final String wireName;

    WorkflowRunStatus(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static WorkflowRunStatus fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WorkflowRunStatus must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowRunStatus status : values()) {
            if (status.wireName.equals(normalized) || status.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown workflow run status: " + value);
    }
}
