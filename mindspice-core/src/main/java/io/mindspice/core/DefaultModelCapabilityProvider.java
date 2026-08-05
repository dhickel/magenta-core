package io.mindspice.core;

import io.mindspice.core.spi.ModelCapabilityProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Optimistic default {@link ModelCapabilityProvider} that assumes every model
 * supports {@link ModelCapability#STREAMING} and
 * {@link ModelCapability#TOOL_CALLING}. {@link ModelCapability#VISION} is
 * <strong>not</strong> included by default.
 *
 * <p>Embedding applications should provide a real capability provider (backed
 * by their model registry or configuration) for accurate capability
 * detection. A warning is logged on construction to encourage this.</p>
 */
public final class DefaultModelCapabilityProvider implements ModelCapabilityProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultModelCapabilityProvider.class);

    private static final Set<ModelCapability> DEFAULT_CAPS =
        Set.of(ModelCapability.STREAMING, ModelCapability.TOOL_CALLING);

    private static final DefaultModelCapabilityProvider INSTANCE = new DefaultModelCapabilityProvider();

    private DefaultModelCapabilityProvider() {
        log.warn("Using optimistic DefaultModelCapabilityProvider — all models assumed to "
            + "support STREAMING and TOOL_CALLING. Provide a real ModelCapabilityProvider "
            + "for accurate capability detection.");
    }

    /** Returns the singleton instance. */
    public static DefaultModelCapabilityProvider instance() {
        return INSTANCE;
    }

    @Override
    @NonNull
    public Set<ModelCapability> capabilities(@NonNull String modelAlias) {
        return DEFAULT_CAPS;
    }
}
