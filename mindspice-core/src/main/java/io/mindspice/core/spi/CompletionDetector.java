package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnContext;

/**
 * Determines whether a turn has reached a terminal state after the
 * latest model response (no further tool calls expected).
 */
@FunctionalInterface
public interface CompletionDetector {

    boolean isComplete(@NonNull TurnContext context);
}
