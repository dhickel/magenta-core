package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;

/**
 * Explicit typed port for workflow v2 node wiring.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowPort(
    String name,
    PlanFieldType type,
    boolean required,
    boolean array,
    String description
) {
    public WorkflowPort {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workflow port name must not be blank");
        }
        type = type == null ? PlanFieldType.STRING : type;
    }
}
