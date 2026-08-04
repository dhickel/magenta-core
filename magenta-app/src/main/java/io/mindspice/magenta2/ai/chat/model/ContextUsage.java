package io.mindspice.magenta2.ai.chat.model;

public record ContextUsage(
    int usedTokens,
    int maxTokens,
    int triggerTokens,
    double percentUsed
) {
    public static ContextUsage empty(int maxTokens, int triggerTokens) {
        return new ContextUsage(0, maxTokens, triggerTokens, 0.0);
    }
}
