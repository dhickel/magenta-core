package io.mindspice.magenta2.ai.chat.task;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskValueType {
    STRING("string"),
    LONG_TEXT("long_text"),
    FILE_PATH("file_path"),
    JSON("json"),
    NUMBER("number"),
    BOOLEAN("boolean");

    private final String wireName;

    TaskValueType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static TaskValueType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            return STRING;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (TaskValueType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown task value type: " + value);
    }
}
