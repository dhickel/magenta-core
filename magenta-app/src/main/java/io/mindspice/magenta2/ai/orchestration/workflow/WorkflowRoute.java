package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Explicit route connecting workflow v2 node ports and control branches.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowRoute(
    String id,
    String fromNodeKey,
    String fromOutputName,
    String toNodeKey,
    String toInputName,
    WorkflowRouteType routeType,
    String condition
) {
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";

    public WorkflowRoute {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("WorkflowRoute id must not be blank");
        }
        if (toNodeKey == null || toNodeKey.isBlank()) {
            throw new IllegalArgumentException("WorkflowRoute toNodeKey must not be blank");
        }
        if (routeType == null) {
            routeType = WorkflowRouteType.MAP_OUTPUT;
        }
    }

    public boolean createsDependency() {
        return routeType == WorkflowRouteType.MAP_OUTPUT
            || routeType == WorkflowRouteType.PASS_THROUGH
            || routeType == WorkflowRouteType.CONTROL;
    }

    public String sourcePort() {
        return fromOutputName;
    }

    public String targetPort() {
        return toInputName;
    }

    public String controlOutcome() {
        if (routeType != WorkflowRouteType.CONTROL || !StringUtils.hasText(condition)) {
            return null;
        }
        return condition.trim().toUpperCase(Locale.ROOT);
    }
}
