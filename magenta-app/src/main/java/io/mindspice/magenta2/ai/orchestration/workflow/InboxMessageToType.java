package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Recipient type for inbox messages.
 */
public enum InboxMessageToType {
    USER("user"),
    AGENT("agent");

    private final String wireName;

    InboxMessageToType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static InboxMessageToType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("InboxMessageToType must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (InboxMessageToType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown inbox message toType: " + value);
    }
}
