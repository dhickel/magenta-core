package io.mindspice.magenta.core;

import io.mindspice.magenta.core.CompactionResult;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.TurnContext;
import io.mindspice.magenta.core.TurnMessage;
import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.TokenEstimator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Owns prompt-window selection and context usage calculation. The model
 * context length is supplied by the engine's model boundary; this class never
 * guesses from a provider-specific type.
 */
final class ContextManager {

    private static final int FALLBACK_CONTEXT_LENGTH = 128_000;

    private final ConversationMemory conversationMemory;
    private final TokenEstimator tokenEstimator;
    private final CompactionStrategy compactionStrategy;
    private final int bufferPercent;
    private volatile ToIntFunction<String> contextLengthProvider = alias -> FALLBACK_CONTEXT_LENGTH;

    public ContextManager(@NonNull ConversationMemory conversationMemory,
                          @NonNull TokenEstimator tokenEstimator,
                          @NonNull CompactionStrategy compactionStrategy,
                          int bufferPercent) {
        this.conversationMemory = Objects.requireNonNull(conversationMemory, "conversationMemory");
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.compactionStrategy = Objects.requireNonNull(compactionStrategy, "compactionStrategy");
        this.bufferPercent = Math.clamp(bufferPercent, 0, 50);
    }

    /**
     * Installs the model-alias to context-window lookup used by subsequent
     * calculations. The engine supplies this from its internal router.
     */
    public void contextLengthProvider(@NonNull ToIntFunction<String> provider) {
        this.contextLengthProvider = Objects.requireNonNull(provider, "provider");
    }

    /** Returns the prompt history that fits the model's configured window. */
    @NonNull
    public List<TurnMessage> window(@NonNull TurnContext ctx) {
        return window(ctx, contextLength(ctx.model()));
    }

    /**
     * Returns the prompt history that fits an explicit model window. The
     * newest messages are retained when trimming is necessary, which keeps the
     * current conversation tail useful instead of returning an empty prefix
     * after one large message.
     */
    @NonNull
    public List<TurnMessage> window(@NonNull TurnContext ctx, int contextWindow) {
        List<TurnMessage> allMessages = visibleMessages(conversationMemory.getMessages(ctx.sessionId()));
        int maxTokens = contextWindow > 0 ? contextWindow : contextLength(ctx.model());
        int budget = budgetFromContextWindow(maxTokens);
        return trimToBudget(allMessages, budget);
    }

    /** Computes usage for the durable, model-visible session context. */
    @NonNull
    public ContextUsage computeUsage(@NonNull String sessionId, @NonNull String modelAlias) {
        List<TurnMessage> messages = visibleMessages(conversationMemory.getMessages(sessionId));
        int usedTokens = 0;
        for (TurnMessage msg : messages) {
            usedTokens = saturatingAdd(usedTokens, tokenEstimator.estimateTokens(msg));
        }
        int maxTokens = contextLength(modelAlias);
        int triggerTokens = (int) Math.round(maxTokens * 0.75d);
        return new ContextUsage(usedTokens, triggerTokens, maxTokens);
    }

    /** Delegates compaction through the configured strategy boundary. */
    @NonNull
    CompactionResult compact(@NonNull io.mindspice.magenta.core.CompactionContext context) {
        return compactionStrategy.compact(context);
    }

    /** Returns whether usage has crossed the configured compaction trigger. */
    public boolean needsCompaction(@NonNull ContextUsage usage, int contextWindow) {
        if (usage == null || contextWindow <= 0) {
            return false;
        }
        int trigger = (int) Math.round(contextWindow * 0.75d);
        return usage.usedTokens() >= trigger;
    }

    int contextLength(@NonNull String modelAlias) {
        try {
            int configured = contextLengthProvider.applyAsInt(modelAlias);
            return configured > 0 ? configured : FALLBACK_CONTEXT_LENGTH;
        } catch (RuntimeException ignored) {
            return FALLBACK_CONTEXT_LENGTH;
        }
    }

    private int budgetFromContextWindow(int contextWindow) {
        long budget = (long) contextWindow * (100 - bufferPercent) / 100;
        return (int) Math.max(budget, 1);
    }

    private List<TurnMessage> trimToBudget(List<TurnMessage> messages, int tokenBudget) {
        if (messages.isEmpty()) {
            return List.of();
        }

        int total = 0;
        for (TurnMessage message : messages) {
            total = saturatingAdd(total, tokenEstimator.estimateTokens(message));
        }
        if (total <= tokenBudget) {
            return List.copyOf(messages);
        }

        List<TurnMessage> newestFirst = new ArrayList<>();
        int accumulated = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            TurnMessage message = messages.get(i);
            int tokens = Math.max(0, tokenEstimator.estimateTokens(message));
            if (!newestFirst.isEmpty() && accumulated + tokens > tokenBudget) {
                break;
            }
            newestFirst.add(message);
            accumulated = saturatingAdd(accumulated, tokens);
            if (accumulated >= tokenBudget) {
                break;
            }
        }
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    private List<TurnMessage> visibleMessages(List<TurnMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
            .filter(message -> !isPendingMarker(message))
            .toList();
    }

    private boolean isPendingMarker(TurnMessage message) {
        return message != null
            && message.metadata() != null
            && "turnloop.pending_interaction".equals(
                message.metadata().get("turnloop.persistence_type"));
    }

    private int saturatingAdd(int left, int right) {
        long sum = (long) left + Math.max(0, right);
        return sum >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }
}
