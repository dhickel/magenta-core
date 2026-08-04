package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Objects;

/**
 * Request for a lineage-linked operation (retry or replay). Creates a new
 * task run ID linked to a parent via {@link RunLineageMode}.
 */
public record LifecycleLineageRequest(
        String operationKey,
        String parentTaskRunId,
        RunLineageMode lineageMode,
        String taskRunId,
        String nativeRunId,
        String assignmentId,
        String inputDigest
) {
    public LifecycleLineageRequest {
        Objects.requireNonNull(operationKey, "operationKey");
        if (parentTaskRunId == null || parentTaskRunId.isBlank()) {
            throw new IllegalArgumentException("parentTaskRunId is required");
        }
        Objects.requireNonNull(lineageMode, "lineageMode");
        if (taskRunId == null || taskRunId.isBlank()) {
            throw new IllegalArgumentException("taskRunId is required");
        }
        if (nativeRunId == null || nativeRunId.isBlank()) {
            throw new IllegalArgumentException("nativeRunId is required");
        }
        Objects.requireNonNull(inputDigest, "inputDigest");
    }
}
