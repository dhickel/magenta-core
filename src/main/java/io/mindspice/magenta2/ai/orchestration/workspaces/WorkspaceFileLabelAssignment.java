package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;

public record WorkspaceFileLabelAssignment(
    String id,
    String workspaceIdentity,
    String fileRelativePath,
    WorkspaceFileLabel label,
    String metadataJson,
    Instant createdAt,
    Instant updatedAt
) {
}
