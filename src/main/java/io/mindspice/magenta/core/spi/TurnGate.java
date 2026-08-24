package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;

/**
 * Stateful gate serializing turn execution per session.
 *
 * <p>State machine: IDLE → EXECUTING → AWAITING_ANSWER → EXECUTING → IDLE.</p>
 */
public interface TurnGate {

    @NonNull
    AcquireResult acquire(@NonNull String sessionId, @NonNull TurnGateMode mode);

    void release(@NonNull String sessionId);

    void transitionToAwaitingAnswer(@NonNull String sessionId);

    void drain(long timeoutMs);

    @NonNull
    GateState state(@NonNull String sessionId);

    // ── Nested types ──

    sealed interface AcquireResult
        permits AcquireResult.Acquired, AcquireResult.Rejected {

        record Acquired(@NonNull String lockToken) implements AcquireResult {}

        record Rejected(@NonNull String reason) implements AcquireResult {}
    }

    enum TurnGateMode { SEND, STREAM, ANSWER }

    enum GateState { IDLE, EXECUTING, AWAITING_ANSWER }
}
