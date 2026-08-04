package io.mindspice.magenta2.ai.execution;

public enum ActiveTurnPhase {
    MODEL_CALL,
    TOOL_CALL,
    TOOL_CHECKPOINT,
    COMPLETING
}
