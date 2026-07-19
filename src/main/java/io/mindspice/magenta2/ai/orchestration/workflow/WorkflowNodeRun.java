package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Runtime state for one node execution.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowNodeRun(
    String nodeKey,
    WorkflowNodeType type,
    WorkflowNodeRunStatus status,
    Map<String, Object> inputValues,
    Map<String, Object> outputValues,
    List<String> routeContext,
    Instant startedAt,
    Instant completedAt
) {
    public WorkflowNodeRun {
        if (nodeKey == null || nodeKey.isBlank()) {
            throw new IllegalArgumentException("WorkflowNodeRun nodeKey must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("WorkflowNodeRun type must not be null");
        }
        if (status == null) {
            status = WorkflowNodeRunStatus.PENDING;
        }
        inputValues = inputValues == null ? Map.of() : Map.copyOf(inputValues);
        outputValues = outputValues == null ? Map.of() : Map.copyOf(outputValues);
        routeContext = routeContext == null ? List.of() : List.copyOf(routeContext);
    }

}
