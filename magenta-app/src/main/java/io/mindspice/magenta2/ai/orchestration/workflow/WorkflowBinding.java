package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Binds an input of a workflow node to either a literal value
 * or the output of a prior node.
 *
 * @param inputName       the target input name on the receiving node
 * @param sourceNodeKey   the source node key (null for literal bindings)
 * @param sourceOutputName the source node output name (null for literal bindings)
 * @param literalValue    a constant value (null for step-output bindings)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowBinding(
    String inputName,
    @JsonProperty("sourceNodeKey") String sourceNodeKey,
    @JsonProperty("sourceOutputName") String sourceOutputName,
    Object literalValue
) {
    public WorkflowBinding {
        if (inputName == null || inputName.isBlank()) {
            throw new IllegalArgumentException("Workflow binding inputName must not be blank");
        }
    }

    public boolean isLiteral() {
        return sourceNodeKey == null || sourceNodeKey.isBlank();
    }

    public boolean isStepOutput() {
        return sourceNodeKey != null && !sourceNodeKey.isBlank();
    }
}
