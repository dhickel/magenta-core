package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;

public record WorkspaceFileActionRecord(
    String id,
    String workspaceIdentity,
    String actorType,
    String actorId,
    WorkspaceFileActionType actionType,
    String sourceRelativePath,
    String targetRelativePath,
    String result,
    String payloadJson,
    Instant createdAt
) {
}
