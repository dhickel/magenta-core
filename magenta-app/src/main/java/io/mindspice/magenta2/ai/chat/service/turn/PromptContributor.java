package io.mindspice.magenta2.ai.chat.service.turn;

/**
 * SPI for contributing text to the system prompt during turn assembly.
 * <p>
 * This is a temporary Magenta-scoped bridge interface that will be replaced
 * by a framework-native SPI in Phase 07.
 */
@FunctionalInterface
public interface PromptContributor {

    String contribute(TurnContext context);
}
