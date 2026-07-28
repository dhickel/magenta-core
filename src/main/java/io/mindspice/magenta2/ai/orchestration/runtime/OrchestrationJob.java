package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import jakarta.validation.constraints.NotBlank;

public record OrchestrationJob(
    String id,
    @NotBlank String ownerAgentId,
    @NotBlank String title,
    String summary,
    String defaultModel,
    String workspaceType,
    String workspaceKey,
    String workspaceIdentity,
    OrchestrationStatus status,
    Instant createdAt,
    Instant updatedAt
) {
    public OrchestrationJob {
        if ((workspaceType == null) != (workspaceKey == null)) {
            throw new IllegalArgumentException("workspaceType and workspaceKey must be supplied together");
        }
        if (workspaceType != null) workspaceIdentity = workspaceType + "/" + workspaceKey;
    }
}
