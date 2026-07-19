package io.mindspice.magenta2.ai.chat.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ChatSessionSurface {
    BROWSER,
    INTERNAL;

    @JsonCreator
    public static ChatSessionSurface fromJson(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Unknown chat session surface: " + value);
        }
        for (ChatSessionSurface surface : values()) {
            if (surface.name().equalsIgnoreCase(normalized)) {
                return surface;
            }
        }
        throw new IllegalArgumentException("Unknown chat session surface: " + value);
    }

    @JsonValue
    public String jsonValue() {
        return name();
    }
}
