package io.mindspice.magenta2.ai.orchestration.runtime;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import org.springframework.util.StringUtils;

/** Defines durable, task-like work coordinated by an agent. */
public record JobDefinition(
    String id,
    String ownerAgentId,
    String projectId,
    String workspaceType,
    String workspaceKey,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) String workspaceIdentity,
    String status,
    String title,
    String summary,
    List<JobWorkItem> items,
    String promptProfile,
    String model,
    String settingsOverrideJson,
    Instant createdAt,
    Instant updatedAt
) {
    public JobDefinition {
        workspaceType = normalize(workspaceType);
        workspaceKey = normalize(workspaceKey);
        if ((workspaceType == null) != (workspaceKey == null)) {
            throw new IllegalArgumentException("workspaceType and workspaceKey must be supplied together");
        }
        workspaceIdentity = workspaceType == null ? null : workspaceType + "/" + workspaceKey;
        items = items == null ? List.of() : List.copyOf(items);
    }

    public JobDefinition(
        String id,
        String title,
        String summary,
        List<JobWorkItem> items,
        String promptProfile,
        String model,
        String settingsOverrideJson,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, null, null, null, null, null, null, title, summary, items,
            promptProfile, model, settingsOverrideJson, createdAt, updatedAt);
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
