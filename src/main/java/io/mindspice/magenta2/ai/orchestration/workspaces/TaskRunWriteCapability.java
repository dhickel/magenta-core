package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Objects;

/**
 * Opaque run-writer identity facts reserved for Phase 02 fence verification.
 * This value performs no authorization and is not publicly constructible.
 */
public final class TaskRunWriteCapability {
    private final String taskRunId;
    private final int attemptNumber;
    private final String ownerId;
    private final long fenceGeneration;
    private final String fenceToken;

    TaskRunWriteCapability(String taskRunId, int attemptNumber, String ownerId,
                           long fenceGeneration, String fenceToken) {
        if (taskRunId == null || taskRunId.isBlank()) {
            throw new IllegalArgumentException("taskRunId is required");
        }
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
        if (fenceGeneration < 1) {
            throw new IllegalArgumentException("fenceGeneration must be positive");
        }
        this.taskRunId = taskRunId;
        this.attemptNumber = attemptNumber;
        this.ownerId = requireText(ownerId, "ownerId");
        this.fenceGeneration = fenceGeneration;
        this.fenceToken = requireText(fenceToken, "fenceToken");
    }

    public String taskRunId() { return taskRunId; }
    public int attemptNumber() { return attemptNumber; }
    public String ownerId() { return ownerId; }
    public long fenceGeneration() { return fenceGeneration; }
    public String fenceToken() { return fenceToken; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TaskRunWriteCapability that
                && attemptNumber == that.attemptNumber
                && fenceGeneration == that.fenceGeneration
                && taskRunId.equals(that.taskRunId)
                && ownerId.equals(that.ownerId)
                && fenceToken.equals(that.fenceToken);
    }

    @Override
    public int hashCode() {
        return Objects.hash(taskRunId, attemptNumber, ownerId, fenceGeneration, fenceToken);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }
}
