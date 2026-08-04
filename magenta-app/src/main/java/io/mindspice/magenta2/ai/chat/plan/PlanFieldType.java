package io.mindspice.magenta2.ai.chat.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

public enum PlanFieldType {
    USER_MESSAGE("user_message"),
    STRING("string"),
    FILE_PATH("file_path"),
    NUMBER("number"),
    JSON("json");

    private final String wireName;

    PlanFieldType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static PlanFieldType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return STRING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (PlanFieldType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown plan field type: " + value);
    }
}
