package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;

public record WorkspaceFileLabel(
    String id,
    String slug,
    String displayName,
    String color,
    boolean system,
    String metadataJson,
    Instant createdAt,
    Instant updatedAt
) {
}
