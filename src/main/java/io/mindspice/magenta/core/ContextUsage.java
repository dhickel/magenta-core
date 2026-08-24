package io.mindspice.magenta.core;

/**
 * Snapshot of token usage at a point in the turn lifecycle.
 *
 * @param usedTokens    number of tokens currently in context
 * @param triggerTokens budget threshold that triggers compaction
 * @param maxTokens     model context window size
 */
public record ContextUsage(
    int usedTokens,
    int triggerTokens,
    int maxTokens
) {
    public double utilizationPercent() {
        return maxTokens > 0 ? (100.0 * usedTokens / maxTokens) : 0;
    }
}
