package io.mindspice.magenta2.ai.orchestration.tasks;

import java.time.Instant;

/**
 * Lightweight tracking definition that becomes executable only through its
 * separately persisted promoted {@code TASK_TEMPLATE} Plan. Assignment state
 * is derived through {@code designatedAttemptId}, never stored here.
 */
public record WorkTaskDefinition(
    String id,
    String title,
    String description,
    String projectId,
    boolean pin,
    int defaultPriority,
    String preferredAgentId,
    String promotedPlanId,
    String designatedAttemptId,
    int definitionVersion,
    Instant createdAt,
    Instant updatedAt
) {
    public WorkTaskDefinition {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (definitionVersion < 1) {
            throw new IllegalArgumentException("definitionVersion must be >= 1");
        }
    }

    public WorkTaskDefinition withTitle(String value) {
        return copy(value, description, defaultPriority, pin, preferredAgentId, promotedPlanId, designatedAttemptId);
    }

    public WorkTaskDefinition withDescription(String value) {
        return copy(title, value, defaultPriority, pin, preferredAgentId, promotedPlanId, designatedAttemptId);
    }

    public WorkTaskDefinition withDefaultPriority(int value) {
        return copy(title, description, value, pin, preferredAgentId, promotedPlanId, designatedAttemptId);
    }

    public WorkTaskDefinition withPin(boolean value) {
        return copy(title, description, defaultPriority, value, preferredAgentId, promotedPlanId, designatedAttemptId);
    }

    public WorkTaskDefinition withPreferredAgentId(String value) {
        return copy(title, description, defaultPriority, pin, value, promotedPlanId, designatedAttemptId);
    }

    public WorkTaskDefinition withPromotedPlanId(String value) {
        return copy(title, description, defaultPriority, pin, preferredAgentId, value, designatedAttemptId);
    }

    public WorkTaskDefinition withProjectId(String value) {
        return new WorkTaskDefinition(id, title, description, value, pin, defaultPriority, preferredAgentId,
            promotedPlanId, designatedAttemptId, definitionVersion, createdAt, Instant.now());
    }

    private WorkTaskDefinition copy(String newTitle, String newDescription, int newPriority, boolean newPin,
                                    String newPreferredAgentId, String newPromotedPlanId,
                                    String newDesignatedAttemptId) {
        return new WorkTaskDefinition(id, newTitle, newDescription, projectId, newPin, newPriority,
            newPreferredAgentId, newPromotedPlanId, newDesignatedAttemptId,
            definitionVersion, createdAt, Instant.now());
    }
}
