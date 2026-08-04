package io.mindspice.magenta2.ai.orchestration.runtime;

/** Closed set of run lifecycle operations. */
public enum RunOperationType {
    RESERVE,
    RESUME,
    RETRY,
    REPLAY
}
