package io.mindspice.magenta2.ai.orchestration.workflow;

/**
 * Status of a single node run within a workflow execution.
 */
public enum WorkflowNodeRunStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    SKIPPED
}
