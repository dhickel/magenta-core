package io.mindspice.magenta2.ai.chat.service.turn;

/**
 * SPI for determining whether a turn has reached a terminal state.
 * <p>
 * This is a temporary Magenta-scoped bridge interface that will be replaced
 * by a framework-native SPI in Phase 07.
 */
@FunctionalInterface
public interface CompletionDetector {

    boolean isComplete(TurnContext context);
}
