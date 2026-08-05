package io.mindspice.core;

import io.mindspice.core.spi.ThinkingExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Composite {@link ThinkingExtractor} that applies three extraction strategies
 * in priority order:
 * <ol>
 *   <li><strong>Ollama metadata</strong>: checks the {@code "thinking"} key in
 *       the metadata map for provider-separated thinking content.</li>
 *   <li><strong>DeepSeek reasoningContent</strong>: uses the dedicated
 *       {@code reasoningContent} field when non-null.</li>
 *   <li><strong>{@code <think>} tag fallback</strong>: regex extraction of
 *       {@code <think>...</think>} blocks using {@link Pattern#DOTALL}.</li>
 * </ol>
 *
 * <p>If no strategy matches, {@code visibleText} is the raw input text
 * and {@code thinkingText} is empty.</p>
 */
public final class CompositeThinkingExtractor implements ThinkingExtractor {

    private static final Logger logger = LoggerFactory.getLogger(CompositeThinkingExtractor.class);

    private static final Pattern THINK_TAG_PATTERN = Pattern.compile(
        "<think>(.*?)</think>", Pattern.DOTALL);

    @Override
    @NonNull
    public ThinkingResult extract(@NonNull AssistantMessageContent content) {
        String raw = content.rawText();

        // Strategy 1: Ollama metadata "thinking" field
        Object thinkingMeta = content.metadata().get("thinking");
        if (thinkingMeta instanceof String thinkingStr && !thinkingStr.isBlank()) {
            logger.debug("Extracted thinking via Ollama metadata ({} chars)", thinkingStr.length());
            String visible = stripThinkingBlock(raw, thinkingStr);
            return new ThinkingResult(
                visible != null ? visible : raw,
                thinkingStr
            );
        }

        // Strategy 2: DeepSeek reasoningContent
        String reasoning = content.reasoningContent();
        if (reasoning != null && !reasoning.isBlank()) {
            logger.debug("Extracted thinking via DeepSeek reasoningContent ({} chars)", reasoning.length());
            String visible = stripThinkingBlock(raw, reasoning);
            return new ThinkingResult(
                visible != null ? visible : raw,
                reasoning
            );
        }

        // Strategy 3: <think> tag regex fallback
        Matcher matcher = THINK_TAG_PATTERN.matcher(raw);
        if (matcher.find()) {
            String thinking = matcher.group(1);
            String visible = matcher.replaceFirst("").trim();
            logger.debug("Extracted thinking via <think> tag regex ({} chars)", thinking.length());
            return new ThinkingResult(visible, thinking);
        }

        // No match — return raw text as visible, empty thinking
        return new ThinkingResult(raw, "");
    }

    /**
     * Attempts to strip a known thinking block from the raw text.
     * If the raw text does not contain the thinking block (e.g. the provider
     * separated them already), returns {@code null} — the caller should
     * use the raw text as-is.
     */
    @Nullable
    private static String stripThinkingBlock(String raw, String thinking) {
        int idx = raw.indexOf(thinking);
        if (idx < 0) {
            // Thinking was already separated by the provider
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        sb.append(raw, 0, idx);
        sb.append(raw, idx + thinking.length(), raw.length());
        return sb.toString().trim();
    }
}
