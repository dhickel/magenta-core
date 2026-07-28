package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Objects;

/**
 * Idempotent resume request. Reuses the same run ID and location, creates a new
 * attempt. Same-key/same-digest returns the recorded result.
 */
public record LifecycleResumeRequest(
        String operationKey,
        String taskRunId,
        String inputDigest
) {
    public LifecycleResumeRequest {
        Objects.requireNonNull(operationKey, "operationKey");
        if (taskRunId == null || taskRunId.isBlank()) {
            throw new IllegalArgumentException("taskRunId is required");
        }
        Objects.requireNonNull(inputDigest, "inputDigest");
    }
}
