package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Map;

public record OrchestrationRunResult(
    WorkAssignment assignment,
    String runId,
    Map<String, Object> outputValues
) {
    public OrchestrationRunResult {
        outputValues = outputValues == null ? Map.of() : Map.copyOf(outputValues);
    }
}
