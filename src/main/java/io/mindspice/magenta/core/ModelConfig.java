package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.net.URI;
import java.util.Objects;

/**
 * Configuration for one of the provider families bundled with Magenta Core.
 *
 * <p>The provider kind is represented by the Java variant rather than a
 * nullable endpoint discriminator. Each variant carries the same common
 * routing and optional provider-setting accessors, while its constructor
 * enforces the options supported by that provider.</p>
 *
 * <p>Variants can be constructed directly or through the fluent variant
 * builder: {@link #ollama()}, {@link #openAiCompatible()}, {@link #deepSeek()},
 * and {@link #responses()}.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ModelConfig.Ollama.class, name = "ollama"),
    @JsonSubTypes.Type(value = ModelConfig.OpenAiCompatible.class, name = "openai_compatible"),
    @JsonSubTypes.Type(value = ModelConfig.DeepSeek.class, name = "deepseek"),
    @JsonSubTypes.Type(value = ModelConfig.Responses.class, name = "responses")
})
public sealed interface ModelConfig
    permits ModelConfig.Ollama, ModelConfig.OpenAiCompatible, ModelConfig.DeepSeek, ModelConfig.Responses {

    /** Magenta-owned model alias used for requests and session selection. */
    @NonNull
    String alias();

    /** Model name sent to the configured remote provider. */
    @NonNull
    String remoteModelName();

    /** Absolute HTTP(S) endpoint used by the provider adapter. */
    @NonNull
    String remoteEndpoint();

    /** Optional provider credential. It is intentionally included in JSON. */
    @Nullable
    String apiKey();

    /** Optional provider context-length override. */
    @Nullable
    Integer contextLength();

    /** Optional provider thinking/reasoning level from zero through four. */
    @Nullable
    Integer thinkLevel();

    /**
     * Returns the effective thinking level used by provider adapters.
     * Unset values retain the historical zero default.
     */
    default int effectiveThinkLevel() {
        Integer level = thinkLevel();
        return level == null ? 0 : Math.clamp(level, 0, 4);
    }

    /** Ollama model configuration. Ollama does not accept an API key. */
    record Ollama(
        @NonNull String alias,
        @NonNull String remoteModelName,
        @NonNull String remoteEndpoint,
        @Nullable String apiKey,
        @Nullable Integer contextLength,
        @Nullable Integer thinkLevel
    ) implements ModelConfig {
        public Ollama {
            validateCommon(alias, remoteModelName, remoteEndpoint, contextLength, thinkLevel);
            validateOptionalApiKey(apiKey);
            if (apiKey != null && !apiKey.isBlank()) {
                throw new IllegalArgumentException("Ollama does not permit an API key");
            }
            if (apiKey != null) {
                throw new IllegalArgumentException("apiKey must be null or non-blank");
            }
        }

        @Override
        public String toString() {
            return redactedToString("Ollama", alias, remoteModelName, remoteEndpoint,
                contextLength, thinkLevel, null);
        }
    }

    /**
     * OpenAI-compatible chat-completions configuration with an optional API
     * key and an optional explicit reasoning-effort override.
     *
     * <p>When {@code reasoningEffort} is set it takes precedence over the
     * coarse {@code thinkLevel} mapping so providers such as DeepSeek V4 can
     * request the {@code max} effort tier that the level-based mapping never
     * emits.</p>
     */
    record OpenAiCompatible(
        @NonNull String alias,
        @NonNull String remoteModelName,
        @NonNull String remoteEndpoint,
        @Nullable String apiKey,
        @Nullable Integer contextLength,
        @Nullable Integer thinkLevel,
        @Nullable String reasoningEffort
    ) implements ModelConfig {
        public OpenAiCompatible {
            validateCommon(alias, remoteModelName, remoteEndpoint, contextLength, thinkLevel);
            validateOptionalApiKey(apiKey);
            reasoningEffort = normalizeReasoningEffort(reasoningEffort);
        }

        @Override
        public String toString() {
            return redactedToString("OpenAiCompatible", alias, remoteModelName, remoteEndpoint,
                contextLength, thinkLevel, reasoningEffort);
        }
    }

    /**
     * Responses-API model configuration (OpenAI Responses protocol, also
     * served by DeepSeek V4 and the OpenAI Codex endpoints). Requires an API
     * key; reasoning effort is optional and accepts the {@code max} tier.
     */
    record Responses(
        @NonNull String alias,
        @NonNull String remoteModelName,
        @NonNull String remoteEndpoint,
        @NonNull String apiKey,
        @Nullable Integer contextLength,
        @Nullable Integer thinkLevel,
        @Nullable String reasoningEffort
    ) implements ModelConfig {
        public Responses {
            validateCommon(alias, remoteModelName, remoteEndpoint, contextLength, thinkLevel);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("Responses requires a non-blank API key");
            }
            reasoningEffort = normalizeReasoningEffort(reasoningEffort);
        }

        @Override
        public String toString() {
            return redactedToString("Responses", alias, remoteModelName, remoteEndpoint,
                contextLength, thinkLevel, reasoningEffort);
        }
    }

    /** DeepSeek model configuration requiring a key and zero thinking level. */
    record DeepSeek(
        @NonNull String alias,
        @NonNull String remoteModelName,
        @NonNull String remoteEndpoint,
        @Nullable String apiKey,
        @Nullable Integer contextLength,
        @Nullable Integer thinkLevel
    ) implements ModelConfig {
        public DeepSeek {
            validateCommon(alias, remoteModelName, remoteEndpoint, contextLength, thinkLevel);
            validateOptionalApiKey(apiKey);
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("DeepSeek requires a non-blank API key");
            }
            if (thinkLevel != null && thinkLevel != 0) {
                throw new IllegalArgumentException(
                    "DeepSeek supports only a zero or unset think level");
            }
        }

        @Override
        public String toString() {
            return redactedToString("DeepSeek", alias, remoteModelName, remoteEndpoint,
                contextLength, thinkLevel, null);
        }
    }

    /** Creates a builder for an Ollama configuration; Ollama accepts no API key. */
    static Builder ollama() {
        return new Builder(Builder.Variant.OLLAMA);
    }

    /** Creates a builder for an OpenAI-compatible chat-completions configuration. */
    static Builder openAiCompatible() {
        return new Builder(Builder.Variant.OPENAI_COMPATIBLE);
    }

    /** Creates a builder for a DeepSeek chat-completions configuration. */
    static Builder deepSeek() {
        return new Builder(Builder.Variant.DEEPSEEK);
    }

    /** Creates a builder for an OpenAI Responses-protocol configuration. */
    static Builder responses() {
        return new Builder(Builder.Variant.RESPONSES);
    }

    /**
     * Fluent builder for one {@link ModelConfig} variant.
     *
     * <p>Start from a variant factory ({@link ModelConfig#ollama()},
     * {@link ModelConfig#openAiCompatible()}, {@link ModelConfig#deepSeek()},
     * or {@link ModelConfig#responses()}), set the wanted components, then call
     * {@link #build()}. Validation is performed by the variant record
     * constructors, so an invalid combination fails with the same
     * {@link IllegalArgumentException} the direct constructor raises.</p>
     */
    final class Builder {

        private enum Variant {
            OLLAMA, OPENAI_COMPATIBLE, DEEPSEEK, RESPONSES
        }

        private final Variant variant;
        private String alias;
        private String remoteModelName;
        private String remoteEndpoint;
        private String apiKey;
        private Integer contextLength;
        private Integer thinkLevel;
        private String reasoningEffort;

        private Builder(Variant variant) {
            this.variant = variant;
        }

        /** Sets the Magenta-owned model alias. Required. */
        public Builder alias(@NonNull String alias) {
            this.alias = Objects.requireNonNull(alias, "alias");
            return this;
        }

        /** Sets the model name sent to the remote provider. Required. */
        public Builder remoteModelName(@NonNull String remoteModelName) {
            this.remoteModelName = Objects.requireNonNull(remoteModelName, "remoteModelName");
            return this;
        }

        /** Sets the absolute HTTP(S) provider endpoint. Required. */
        public Builder remoteEndpoint(@NonNull String remoteEndpoint) {
            this.remoteEndpoint = Objects.requireNonNull(remoteEndpoint, "remoteEndpoint");
            return this;
        }

        /** Sets the optional provider credential; unsupported by Ollama. */
        public Builder apiKey(@Nullable String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** Sets the optional provider context-length override. */
        public Builder contextLength(@Nullable Integer contextLength) {
            this.contextLength = contextLength;
            return this;
        }

        /** Sets the optional provider thinking level from zero through four. */
        public Builder thinkLevel(@Nullable Integer thinkLevel) {
            this.thinkLevel = thinkLevel;
            return this;
        }

        /**
         * Sets the optional reasoning-effort override. Only
         * {@link OpenAiCompatible} and {@link Responses} carry this component.
         */
        public Builder reasoningEffort(@Nullable String reasoningEffort) {
            if (variant != Variant.OPENAI_COMPATIBLE && variant != Variant.RESPONSES) {
                throw new IllegalArgumentException(
                    variantName() + " does not support a reasoning-effort override");
            }
            this.reasoningEffort = reasoningEffort;
            return this;
        }

        /**
         * Builds the configured variant, applying the same validation as the
         * corresponding record constructor.
         */
        public ModelConfig build() {
            return switch (variant) {
                case OLLAMA -> new Ollama(
                    alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel);
                case OPENAI_COMPATIBLE -> new OpenAiCompatible(
                    alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel,
                    reasoningEffort);
                case DEEPSEEK -> new DeepSeek(
                    alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel);
                case RESPONSES -> new Responses(
                    alias, remoteModelName, remoteEndpoint, apiKey, contextLength, thinkLevel,
                    reasoningEffort);
            };
        }

        private String variantName() {
            return switch (variant) {
                case OLLAMA -> "Ollama";
                case OPENAI_COMPATIBLE -> "OpenAiCompatible";
                case DEEPSEEK -> "DeepSeek";
                case RESPONSES -> "Responses";
            };
        }
    }

    private static void validateCommon(
        String alias,
        String remoteModelName,
        String remoteEndpoint,
        Integer contextLength,
        Integer thinkLevel) {
        controlSafeNonBlank(alias, "alias");
        controlSafeNonBlank(remoteModelName, "remoteModelName");
        controlSafeNonBlank(remoteEndpoint, "remoteEndpoint");
        validateEndpoint(remoteEndpoint);
        if (contextLength != null && contextLength <= 0) {
            throw new IllegalArgumentException("contextLength must be greater than zero");
        }
        if (thinkLevel != null && (thinkLevel < 0 || thinkLevel > 4)) {
            throw new IllegalArgumentException("thinkLevel must be between 0 and 4");
        }
    }

    private static void validateOptionalApiKey(String apiKey) {
        if (apiKey != null && apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must be null or non-blank");
        }
    }

    /**
     * Normalizes an optional reasoning-effort token to lowercase and validates
     * it against the effort tiers understood by OpenAI-compatible providers
     * and the Responses protocol ({@code none}, {@code minimal}, {@code low},
     * {@code medium}, {@code high}, {@code xhigh}, {@code max}).
     */
    private static String normalizeReasoningEffort(String reasoningEffort) {
        if (reasoningEffort == null || reasoningEffort.isBlank()) {
            return null;
        }
        if (reasoningEffort.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                "reasoningEffort must not contain control characters");
        }
        String normalized = reasoningEffort.trim().toLowerCase();
        switch (normalized) {
            case "none", "minimal", "low", "medium", "high", "xhigh", "max" -> {
                return normalized;
            }
            default -> throw new IllegalArgumentException(
                "reasoningEffort must be one of none, minimal, low, medium, high, xhigh, max");
        }
    }

    private static String controlSafeNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must not contain control characters");
        }
        return value;
    }

    private static void validateEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute() || scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                || uri.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "remoteEndpoint must be an absolute HTTP(S) URL");
        }
    }

    private static String redactedToString(
        String variant,
        String alias,
        String remoteModelName,
        String remoteEndpoint,
        Integer contextLength,
        Integer thinkLevel,
        String reasoningEffort) {
        return variant + "[alias=" + alias
            + ", remoteModelName=" + remoteModelName
            + ", remoteEndpoint=" + remoteEndpoint
            + ", apiKey=<redacted>"
            + ", contextLength=" + contextLength
            + ", thinkLevel=" + thinkLevel
            + ", reasoningEffort=" + reasoningEffort + "]";
    }
}
