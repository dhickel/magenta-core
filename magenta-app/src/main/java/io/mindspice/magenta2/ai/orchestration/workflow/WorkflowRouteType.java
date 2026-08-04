package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * Types of routes connecting workflow nodes.
 */
public enum WorkflowRouteType {
    /** Source output populates downstream input by name mapping. */
    MAP_OUTPUT("map_output"),
    /** Source output is forwarded unchanged to downstream node. */
    PASS_THROUGH("pass_through"),
    /** Source output is materialized/logged but does not feed a downstream node. */
    LOG("log"),
    /** Gate/approval route for status flow control. */
    CONTROL("control");

    private final String wireName;

    WorkflowRouteType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    @JsonCreator
    public static WorkflowRouteType fromWireName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Workflow route type must not be blank");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (WorkflowRouteType type : values()) {
            if (type.wireName.equals(normalized) || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown workflow route type: " + value);
    }
}
