package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Map;

/**
 * A single work item in a job definition. Each item runs either a plan or
 * a workflow, with optional input bindings that map runtime values to the
 * child run's declared inputs.
 */
public record JobWorkItem(
    String key,
    JobWorkItemType type,
    String planId,
    String workflowId,
    Map<String, Object> inputBindings,
    int order,
    String modelOverride,
    Integer priority
) {}
