package io.mindspice.core;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import io.mindspice.core.spi.TokenEstimator;

/**
 * JTokkit-based {@link TokenEstimator} using the {@code CL100K_BASE} encoding
 * (GPT-4 / GPT-3.5-turbo tokenizer). This is the production default.
 *
 * <p>The estimator is stateless and thread-safe.</p>
 */
public final class DefaultTokenEstimator implements TokenEstimator {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int estimateTokens(@NonNull String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.countTokens(text);
    }

    @Override
    public int estimateTokens(@NonNull TurnMessage message) {
        int tokens = 0;
        String text = message.text();
        if (text != null && !text.isEmpty()) {
            tokens += ENCODING.countTokens(text);
        }
        // Role tokens are typically 1-3 tokens; add a small fixed cost
        tokens += 3;
        return tokens;
    }
}
