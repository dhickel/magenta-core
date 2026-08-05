package io.mindspice.core;

import io.mindspice.core.spi.ToolResultSummarizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic default {@link ToolResultSummarizer} implementation.
 *
 * <p>Summarizes every tool result as {@code "Returned N characters"} with
 * configurable truncation at {@code maxResultChars}. No tool-type-specific
 * switch — the generic format is sufficient for model consumption and avoids
 * embedding app-specific tool names in framework code.</p>
 */
public final class DefaultToolResultSummarizer implements ToolResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultToolResultSummarizer.class);

    private final int maxResultChars;

    /**
     * Creates a summarizer with the given result-character limit.
     *
     * @param maxResultChars maximum characters of raw result to include
     *                       in the summary (must be positive)
     */
    public DefaultToolResultSummarizer(int maxResultChars) {
        if (maxResultChars <= 0) {
            throw new IllegalArgumentException("maxResultChars must be positive");
        }
        this.maxResultChars = maxResultChars;
    }

    /** Creates a summarizer with a 4000-character default limit. */
    public DefaultToolResultSummarizer() {
        this(4_000);
    }

    // ── ToolResultSummarizer ──

    @Override
    @NonNull
    public SummarizeResult summarize(@NonNull ToolExecution execution) {
        String raw = execution.rawResult();
        if (raw == null || raw.isEmpty()) {
            return new SummarizeResult("Returned empty result.", false);
        }

        int length = raw.length();
        boolean truncated = length > maxResultChars;

        StringBuilder sb = new StringBuilder();
        sb.append("Returned ").append(length).append(" characters");
        if (truncated) {
            sb.append(" (truncated at ").append(maxResultChars).append(" chars)");
        }
        sb.append(":\n");
        sb.append(truncated ? raw.substring(0, maxResultChars) : raw);

        return new SummarizeResult(sb.toString(), truncated);
    }
}
