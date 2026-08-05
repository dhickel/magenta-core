package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnContext;

/**
 * Contributes prompt text on every turn. Registered contributors
 * are called in priority order to assemble the system prompt.
 */
@FunctionalInterface
public interface PromptContributor {

    @NonNull
    String contribute(@NonNull TurnContext context);
}
