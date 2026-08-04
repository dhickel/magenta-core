package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Workflow v2 node types.
 */
public enum WorkflowNodeType {
    TASK("task"),
    USER_APPROVAL("user_approval"),
    AGENT_APPROVAL("agent_approval"),
    USER_MESSAGE("user_message"),
    AGENT_MESSAGE("agent_message"),
    DELEGATION("delegation"),
    VALIDATION("validation"),
    COPY("copy"),
    FAN_OUT("fan_out"),
    LOG("log"),
    REPORT("report"),
    FINAL_OUTPUT("final_output");

    private final String wireName;

    WorkflowNodeType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static WorkflowNodeType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Workflow node type must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowNodeType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown workflow node type: " + value);
    }

    public boolean isGate() {
        return this == USER_APPROVAL || this == AGENT_APPROVAL;
    }

    public boolean isMessage() {
        return this == USER_MESSAGE || this == AGENT_MESSAGE;
    }

    public boolean isFinalOutputNode() {
        return this == FINAL_OUTPUT || this == REPORT;
    }

    public boolean isAdapterNode() {
        return this == COPY || this == FAN_OUT || this == VALIDATION || this == FINAL_OUTPUT || this == REPORT;
    }
}
