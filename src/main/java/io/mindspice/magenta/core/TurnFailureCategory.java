package io.mindspice.magenta.core;

/** Safe high-level category for an unexpected execution failure. */
public enum TurnFailureCategory {
    MODEL,
    TOOL,
    CONTEXT,
    PERSISTENCE,
    EXECUTION,
    INTERNAL
}
