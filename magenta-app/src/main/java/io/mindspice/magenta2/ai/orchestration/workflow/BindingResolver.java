package io.mindspice.magenta2.ai.orchestration.workflow;

import io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves input bindings for a workflow node, wiring prior node outputs
 * or literal values into the node's declared inputs.
 *
 * <p>Validates field type compatibility when binding a source output to
 * a destination input using the plan's {@link PlanFieldDefinition} types.
 * Missing required bindings fail before model execution.
 */
public class BindingResolver {

    private BindingResolver() { }

    /**
     * Resolve all input values for a workflow node from its bindings.
     *
     * @param bindings       the node's declared input bindings
     * @param inputs         the plan definition's input field definitions (for type checking)
     * @param outputsByNode  outputs from already-completed nodes, keyed by node key
     * @return resolved input values map
     * @throws IllegalArgumentException if a required binding is missing or has a type mismatch
     */
    public static Map<String, Object> resolve(
        List<WorkflowBinding> bindings,
        List<PlanFieldDefinition> inputs,
        Map<String, Map<String, Object>> outputsByNode
    ) {
        Map<String, Object> values = new LinkedHashMap<>();

        for (WorkflowBinding binding : bindings) {
            if (!StringUtils.hasText(binding.inputName())) {
                continue;
            }

            if (binding.isStepOutput()) {
                Map<String, Object> sourceOutputs = outputsByNode.get(binding.sourceNodeKey());
                if (sourceOutputs == null) {
                    throw new IllegalArgumentException(
                        "Source node '" + binding.sourceNodeKey() + "' has no recorded outputs for binding '"
                            + binding.inputName() + "'");
                }
                if (!sourceOutputs.containsKey(binding.sourceOutputName())) {
                    throw new IllegalArgumentException(
                        "Source node '" + binding.sourceNodeKey() + "' has no output '"
                            + binding.sourceOutputName() + "' for binding '" + binding.inputName() + "'");
                }
                Object sourceValue = sourceOutputs.get(binding.sourceOutputName());
                validateBindingType(binding, inputs, sourceValue);
                values.put(binding.inputName(), sourceValue);
            } else {
                // Literal binding
                values.put(binding.inputName(), binding.literalValue());
            }
        }

        // Check for missing required inputs that have no binding
        if (inputs != null) {
            for (PlanFieldDefinition input : inputs) {
                if (input.required()
                    && (!values.containsKey(input.name()) || values.get(input.name()) == null
                        || (values.get(input.name()) instanceof String text && !StringUtils.hasText(text)))) {
                    throw new IllegalArgumentException(
                        "Missing required input '" + input.name() + "': no binding provided");
                }
            }
        }

        return values;
    }

    private static void validateBindingType(
        WorkflowBinding binding,
        List<PlanFieldDefinition> inputs,
        Object sourceValue
    ) {
        if (inputs == null || sourceValue == null) return;

        PlanFieldDefinition target = inputs.stream()
            .filter(f -> f.name().equals(binding.inputName()))
            .findFirst()
            .orElse(null);

        if (target == null) return; // No type constraint; allow any value

        PlanFieldType sourceType = inferType(sourceValue);

        if (!isCompatible(sourceType, target.type())) {
            throw new IllegalArgumentException(
                "Type mismatch for binding '" + binding.inputName() + "': source node '"
                    + binding.sourceNodeKey() + "' output '" + binding.sourceOutputName()
                    + "' is " + sourceType.wireName() + " but destination expects "
                    + target.type().wireName());
        }
    }

    /**
     * Infer the PlanFieldType from a Java object value.
     */
    private static PlanFieldType inferType(Object value) {
        if (value instanceof String text) {
            // Heuristic: check if it looks like a file path
            if (text.startsWith("/") || text.startsWith("./") || text.startsWith("data/")) {
                return PlanFieldType.FILE_PATH;
            }
            return PlanFieldType.STRING;
        }
        if (value instanceof Number) {
            return PlanFieldType.NUMBER;
        }
        if (value instanceof Map || value instanceof List) {
            return PlanFieldType.JSON;
        }
        return PlanFieldType.STRING;
    }

    /**
     * Check if source and destination types are compatible for binding.
     * STRING can accept any value (coerced to string). FILE_PATH requires
     * source to be FILE_PATH or STRING. NUMBER requires NUMBER. JSON requires
     * JSON, Map, or List source.
     */
    private static boolean isCompatible(PlanFieldType source, PlanFieldType destination) {
        if (source == destination) return true;
        if (destination == PlanFieldType.STRING) return true; // Strings accept anything
        if (destination == PlanFieldType.FILE_PATH && source == PlanFieldType.STRING) return true;
        if (destination == PlanFieldType.JSON && source == PlanFieldType.STRING) return true;
        return destination == PlanFieldType.USER_MESSAGE && source == PlanFieldType.STRING;
    }
}
