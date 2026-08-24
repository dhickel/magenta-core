package io.mindspice.magenta.core;

import java.time.Instant;
import java.util.Map;

/**
 * Diagnostic record produced at the end of every turn, regardless of outcome.
 *
 * @param turnId      unique turn identifier
 * @param sessionId   owning session
 * @param startedAt   wall-clock start time
 * @param completedAt wall-clock completion time
 * @param finalPhase  the last {@link TurnPhase} reached
 * @param outcomeKind the terminal outcome
 * @param details     arbitrary diagnostic key/value pairs
 */
public record TurnDiagnostic(
    @NonNull String turnId,
    @NonNull String sessionId,
    @NonNull Instant startedAt,
    @NonNull Instant completedAt,
    @NonNull TurnPhase finalPhase,
    @NonNull OutcomeKind outcomeKind,
    @NonNull Map<String, Object> details
) {
    public enum OutcomeKind {
        COMPLETED,
        SUSPENDED,
        QUEUED,
        INTERRUPTED,
        CANCELLED,
        FAILED,
        TIMED_OUT,
        ABORTED
    }
}
