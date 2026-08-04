package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Objects;

/** Secret-free stable result of a lifecycle operation. */
public record LifecycleResult(
        String taskRunId,
        String nativeRunId,
        String operationKey,
        int attemptNumber,
        String lineageParentTaskRunId,
        RunLineageMode lineageMode,
        String nativeStatus
) {
    public LifecycleResult {
        Objects.requireNonNull(taskRunId, "taskRunId");
        Objects.requireNonNull(operationKey, "operationKey");
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }

    public LifecycleResultSummary toSummary() {
        return new LifecycleResultSummary(taskRunId, nativeRunId, operationKey,
                attemptNumber, lineageParentTaskRunId, lineageMode, nativeStatus);
    }

    public record LifecycleResultSummary(
            String taskRunId,
            String nativeRunId,
            String operationKey,
            int attemptNumber,
            String lineageParentTaskRunId,
            RunLineageMode lineageMode,
            String nativeStatus
    ) { }
}
