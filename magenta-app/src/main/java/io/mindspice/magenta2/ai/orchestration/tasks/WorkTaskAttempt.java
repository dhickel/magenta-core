package io.mindspice.magenta2.ai.orchestration.tasks;

import java.time.Instant;

/**
 * Append-only execution attempt for a {@link WorkTaskDefinition}. Each
 * attempt links to exactly one {@code WorkAssignment}. Attempts are
 * monotonic per task; {@code predecessorAttemptId} provides retry lineage.
 *
 * <p>Task display state is derived by joining through {@code assignmentId}
 * to {@code WorkAssignment.status} — no status column exists here.
 */
public record WorkTaskAttempt(
    String id,
    String taskId,
    String assignmentId,
    int attemptOrder,
    String predecessorAttemptId,
    Instant createdAt
) {
    public WorkTaskAttempt {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (assignmentId == null || assignmentId.isBlank()) {
            throw new IllegalArgumentException("assignmentId is required");
        }
        if (attemptOrder < 1) {
            throw new IllegalArgumentException("attemptOrder must be >= 1");
        }
    }
}
