package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnContext;

/**
 * Determines whether a turn has reached a terminal state after the
 * latest model response (no further tool calls expected).
 */
@FunctionalInterface
public interface CompletionDetector {

    boolean isComplete(@NonNull TurnContext context);
}
