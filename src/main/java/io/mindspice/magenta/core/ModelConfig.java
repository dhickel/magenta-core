package io.mindspice.magenta.core;

/**
 * Configuration descriptor for a single AI model endpoint.
 *
 * <p>This is the framework's model-configuration record. Embedding
 * applications map their own configuration system into this type
 * before passing it to {@code DefaultModelRouter}.</p>
 */
public record ModelConfig(
    @NonNull String alias,
    @NonNull String remoteModelName,
    @NonNull String remoteEndpoint,
    @Nullable String apiKey,
    @NonNull EndpointType endpointType,
    @Nullable Integer contextLength,
    @Nullable Integer thinkLevel
) {
    /** Returns the effective think level, clamped to 0-4. */
    public int effectiveThinkLevel() {
        if (thinkLevel == null) return 0;
        return Math.clamp(thinkLevel, 0, 4);
    }
}
