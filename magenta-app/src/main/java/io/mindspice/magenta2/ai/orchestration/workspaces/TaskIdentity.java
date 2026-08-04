package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable definition identity used to derive a stable task path key. */
public record TaskIdentity(TaskSourceKind sourceKind, String stableId) {
    public static final int MAX_STABLE_ID_BYTES = 1024;

    public TaskIdentity {
        Objects.requireNonNull(sourceKind, "sourceKind");
        if (stableId == null || stableId.isBlank()) {
            throw new IllegalArgumentException("stableId is required");
        }
        if (stableId.getBytes(StandardCharsets.UTF_8).length > MAX_STABLE_ID_BYTES) {
            throw new IllegalArgumentException("stableId exceeds " + MAX_STABLE_ID_BYTES + " UTF-8 bytes");
        }
    }
}
