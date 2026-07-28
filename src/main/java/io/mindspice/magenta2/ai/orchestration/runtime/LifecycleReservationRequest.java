package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;

/**
 * Canonical request for an idempotent run reservation. The operation key and
 * input digest establish idempotency; reusing the same key with a different
 * digest is a conflict.
 */
public record LifecycleReservationRequest(
        String operationKey,
        TaskSourceKind sourceKind,
        String sourceId,
        WorkspaceKind workspaceKind,
        String workspaceKey,
        String taskRunId,
        String nativeRunId,
        String assignmentId,
        String workflowChildId,
        String jobWorkItemId,
        String inputDigest
) {
    public LifecycleReservationRequest {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(sourceKind, "sourceKind");
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId is required");
        }
        Objects.requireNonNull(workspaceKind, "workspaceKind");
        if (workspaceKey == null || workspaceKey.isBlank()) {
            throw new IllegalArgumentException("workspaceKey is required");
        }
        if (taskRunId == null || taskRunId.isBlank()) {
            throw new IllegalArgumentException("taskRunId is required");
        }
        Objects.requireNonNull(inputDigest, "inputDigest");
    }

    /** Reuse the native run ID as the task run ID (valid per Phase 01 cardinality). */
    public static LifecycleReservationRequest reuseNative(
            String operationKey,
            TaskSourceKind sourceKind,
            String sourceId,
            WorkspaceKind workspaceKind,
            String workspaceKey,
            String nativeRunId,
            String assignmentId,
            String inputDigest) {
        return new LifecycleReservationRequest(operationKey, sourceKind, sourceId,
                workspaceKind, workspaceKey, nativeRunId, nativeRunId,
                assignmentId, null, null, inputDigest);
    }

    public String workspaceIdentity() {
        return workspaceKind.token() + "/" + workspaceKey;
    }
}
