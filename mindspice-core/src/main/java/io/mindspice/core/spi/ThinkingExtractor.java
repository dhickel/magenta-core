package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;

import java.util.Map;

/**
 * Extracts thinking/reasoning content from a model response in a
 * provider-agnostic way. The default composite implementation handles
 * Ollama metadata, DeepSeek reasoningContent, and {@code <think>} tag fallback.
 */
public interface ThinkingExtractor {

    @NonNull
    ThinkingResult extract(@NonNull AssistantMessageContent content);

    /**
     * Raw assistant message content as received from the model provider.
     */
    record AssistantMessageContent(
        @NonNull String rawText,
        @Nullable String thinkingMetadata,
        @Nullable String reasoningContent,
        @NonNull Map<String, Object> metadata
    ) {}

    /**
     * Extracted content split into visible user-facing text and
     * internal thinking/reasoning text.
     */
    record ThinkingResult(
        @NonNull String visibleText,
        @NonNull String thinkingText
    ) {}
}
