package io.mindspice.magenta2.ai.chat.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskFieldDefinition(
    String name,
    TaskValueType type,
    String description,
    boolean required,
    String schema
) {
    public TaskFieldDefinition {
        type = type == null ? TaskValueType.STRING : type;
    }
}
