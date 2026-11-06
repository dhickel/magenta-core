package io.mindspice.magenta.core;

/** Closed failure taxonomy for framework tool invocation stages. */
public enum ToolFailureCategory {
    NOT_FOUND,
    ACCESS_DENIED,
    INVALID_ARGUMENTS,
    EXECUTION_FAILED,
    LOOP_EXCEEDED
}
