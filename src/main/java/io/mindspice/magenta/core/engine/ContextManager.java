package io.mindspice.magenta.core.engine;

import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnContext;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.TokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * Package-private context manager that provides windowed message queries,
 * token-budget management, and compaction gating.
 *
 * <p>Messages are accumulated oldest-to-newest while staying within a
 * fractional buffer of the model's context window. When token usage
 * exceeds the compaction threshold, the {@link CompactionStrategy}
 * is triggered to reduce the working set.</p>
 */
public final class ContextManager {

    private final ConversationMemory conversationMemory;
    private final TokenEstimator tokenEstimator;
    private final CompactionStrategy compactionStrategy;
    private final int bufferPercent;

    public ContextManager(@NonNull ConversationMemory conversationMemory,
                   @NonNull TokenEstimator tokenEstimator,
                   @NonNull CompactionStrategy compactionStrategy,
                   int bufferPercent) {
        this.conversationMemory = conversationMemory;
        this.tokenEstimator = tokenEstimator;
        this.compactionStrategy = compactionStrategy;
        this.bufferPercent = Math.clamp(bufferPercent, 0, 50);
    }

    /**
     * Returns a windowed list of messages for the given turn context.
     * Accumulates messages oldest-to-newest, applying {@code bufferPercent}
     * to the model's context window, and stops when the budget is exhausted.
     *
     * @param ctx turn context carrying session and model information
     * @return messages within the token budget, oldest first
     */
    @NonNull
    List<TurnMessage> window(@NonNull TurnContext ctx) {
        List<TurnMessage> allMessages = conversationMemory.getMessages(ctx.sessionId());
        ContextUsage usage = computeUsage(ctx.sessionId(), ctx.model());
        if (usage == null || usage.maxTokens() <= 0) {
            return List.copyOf(allMessages);
        }
        int budget = budgetFromContextWindow(usage.maxTokens());
        return trimToBudget(allMessages, budget);
    }

    /**
     * Computes the current token usage for a session and model pair.
     *
     * @param sessionId  session identifier
     * @param modelAlias model alias
     * @return a {@link ContextUsage} snapshot, or {@code null} if not computable
     */
    @NonNull
    ContextUsage computeUsage(@NonNull String sessionId, @NonNull String modelAlias) {
        List<TurnMessage> messages = conversationMemory.getMessages(sessionId);
        int usedTokens = 0;
        for (TurnMessage msg : messages) {
            usedTokens += tokenEstimator.estimateTokens(msg);
        }
        // maxTokens and triggerTokens are model-specific — use a reasonable
        // default; the ModelRouter refines this in later phases.
        int maxTokens = 128_000; // default conservative window
        int triggerTokens = (int) (maxTokens * 0.75);
        return new ContextUsage(usedTokens, triggerTokens, maxTokens);
    }

    /**
     * Returns {@code true} when the current usage exceeds the given
     * context window, indicating compaction is needed.
     *
     * @param usage         current token usage
     * @param contextWindow model's context window size
     * @return {@code true} if compaction should be triggered
     */
    boolean needsCompaction(@NonNull ContextUsage usage, int contextWindow) {
        if (contextWindow <= 0 || usage == null) {
            return false;
        }
        int trigger = (int) (contextWindow * 0.75);
        return usage.usedTokens() > trigger;
    }

    // ── Internal helpers ──

    private int budgetFromContextWindow(int contextWindow) {
        long budget = (long) contextWindow * (100 - bufferPercent) / 100;
        return (int) Math.max(budget, 1);
    }

    private List<TurnMessage> trimToBudget(List<TurnMessage> messages, int tokenBudget) {
        List<TurnMessage> result = new ArrayList<>();
        int accumulated = 0;
        for (TurnMessage msg : messages) {
            int tokens = tokenEstimator.estimateTokens(msg);
            if (accumulated + tokens > tokenBudget) {
                break;
            }
            accumulated += tokens;
            result.add(msg);
        }
        return List.copyOf(result);
    }
}
