package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.TurnMessage;

/**
 * Estimates the token count for text or a message.
 */
public interface TokenEstimator {

    int estimateTokens(@NonNull String text);

    int estimateTokens(@NonNull TurnMessage message);
}
