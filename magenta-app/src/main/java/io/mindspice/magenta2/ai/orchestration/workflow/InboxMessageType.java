package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Type of inbox message determining how it is presented and handled.
 */
public enum InboxMessageType {
    /** Informational message; no response required. */
    INFO("info"),
    /** Question for the recipient; response expected. */
    QUESTION("question"),
    /** Approval request; response required to proceed. */
    APPROVAL("approval"),
    /** Output from a completed run delivered to inbox. */
    RUN_OUTPUT("run_output");

    private final String wireName;

    InboxMessageType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static InboxMessageType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return INFO;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (InboxMessageType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown inbox message type: " + value);
    }
}
