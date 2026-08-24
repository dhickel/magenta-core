package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnMessage;

/**
 * Estimates the token count for text or a message.
 */
public interface TokenEstimator {

    int estimateTokens(@NonNull String text);

    int estimateTokens(@NonNull TurnMessage message);
}
