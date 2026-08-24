package io.mindspice.magenta.core;

/**
 * The 7-state turn lifecycle: 6 active phases plus the terminal DONE state.
 */
public enum TurnPhase {
    PREPARE,
    INVOKE_MODEL,
    EVALUATE,
    EXECUTE_TOOLS,
    REPAIR,
    FINALIZE,
    DONE
}
