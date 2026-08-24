package io.mindspice.magenta.core;

import java.time.Instant;

/**
 * Safe facts describing an unexpected execution failure. Provider-specific
 * exceptions and sensitive implementation details do not cross this boundary.
 *
 * @param sessionId session owning the failed execution
 * @param turnId execution turn identifier
 * @param origin typed source of the execution
 * @param phase last execution phase reached
 * @param category safe high-level failure category
 * @param startedAt execution start time
 * @param failedAt failure time
 * @param message safe failure message
 */
public record TurnFailure(
    @NonNull String sessionId,
    @NonNull String turnId,
    @NonNull ExecutionOrigin origin,
    @NonNull TurnPhase phase,
    @NonNull TurnFailureCategory category,
    @NonNull Instant startedAt,
    @NonNull Instant failedAt,
    @NonNull String message
) {
    public TurnFailure {
        sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
        turnId = ResponseValidation.nonBlank(turnId, "turnId");
        origin = ResponseValidation.required(origin, "origin");
        phase = ResponseValidation.required(phase, "phase");
        category = ResponseValidation.required(category, "category");
        startedAt = ResponseValidation.timestamp(startedAt, "startedAt");
        failedAt = ResponseValidation.timestamp(failedAt, "failedAt");
        ResponseValidation.ordered(startedAt, failedAt, "failedAt");
        message = ResponseValidation.nonBlank(message, "message");
    }
}
