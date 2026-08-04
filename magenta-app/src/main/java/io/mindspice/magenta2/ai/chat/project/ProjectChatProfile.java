package io.mindspice.magenta2.ai.chat.project;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, deny-by-default project-chat profile.
 *
 * <p>The profile contains only server-resolved identity and fixed capability
 * facts. It intentionally carries no filesystem path, write capability,
 * process capability, skill capability, or caller-selected configuration.</p>
 */
public record ProjectChatProfile(
        String model,
        String fixedPrompt,
        List<String> fixedCallbackNames,
        String ownerBindingKey,
        String agentId,
        String projectId,
        String materializationKey,
        int materializationGeneration,
        int maxReadTokens
) {
    public ProjectChatProfile {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(fixedPrompt, "fixedPrompt");
        Objects.requireNonNull(fixedCallbackNames, "fixedCallbackNames");
        Objects.requireNonNull(ownerBindingKey, "ownerBindingKey");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(materializationKey, "materializationKey");
        fixedCallbackNames = List.copyOf(fixedCallbackNames);
        if (model.isBlank() || fixedPrompt.isBlank() || ownerBindingKey.isBlank()
                || agentId.isBlank() || projectId.isBlank() || materializationKey.isBlank()) {
            throw new IllegalArgumentException("Project-chat profile fields must be nonblank");
        }
        if (materializationGeneration < 1 || maxReadTokens < 1) {
            throw new IllegalArgumentException("Project-chat generation and read limit must be positive");
        }
    }
}
