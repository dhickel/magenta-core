package io.mindspice.magenta2.api.web.selector;

import java.util.Arrays;

public enum EntityKind {
    AGENT("agent"),
    PLAN("plan"),
    TASK("task"),
    WORKFLOW("workflow"),
    JOB("job"),
    PROJECT("project"),
    MODEL("model"),
    RUN("run"),
    TARGET("target");

    private final String wireName;

    EntityKind(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static EntityKind fromWireName(String value) {
        return Arrays.stream(values())
            .filter(kind -> kind.wireName.equalsIgnoreCase(value) || kind.name().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown selector kind: " + value));
    }
}
