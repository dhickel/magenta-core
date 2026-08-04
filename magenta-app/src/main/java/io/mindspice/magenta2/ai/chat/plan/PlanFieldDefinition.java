package io.mindspice.magenta2.ai.chat.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanFieldDefinition(
    String name,
    PlanFieldType type,
    boolean array,
    String description,
    boolean required,
    String schema
) {
    public PlanFieldDefinition {
        type = type == null ? PlanFieldType.STRING : type;
    }
}
