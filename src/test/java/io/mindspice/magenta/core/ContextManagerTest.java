package io.mindspice.magenta.core;

import io.mindspice.magenta.core.spi.CompactionStrategy;
import io.mindspice.magenta.core.spi.ConversationMemory;
import io.mindspice.magenta.core.spi.TokenEstimator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextManagerTest {

    @Test
    void configurableTriggerIsTheSingleSourceForUsageAndCompactionDecision() {
        Memory memory = new Memory();
        memory.addMessages("session", List.of(new TurnMessage.User("x".repeat(500), java.util.Map.of())));
        TokenEstimator estimator = new TokenEstimator() {
            @Override public int estimateTokens(String text) { return text.length(); }
            @Override public int estimateTokens(TurnMessage message) { return message.text().length(); }
        };
        CompactionStrategy compaction = context -> new CompactionResult("summary", 1, 1);

        ContextManager defaultManager = new ContextManager(memory, estimator, compaction, 0, 75);
        defaultManager.contextLengthProvider(alias -> 1_000);
        ContextUsage defaultUsage = defaultManager.computeUsage("session", "model");
        assertEquals(750, defaultUsage.triggerTokens());
        assertFalse(defaultManager.needsCompaction(defaultUsage, 1_000));

        ContextManager earlierManager = new ContextManager(memory, estimator, compaction, 0, 50);
        earlierManager.contextLengthProvider(alias -> 1_000);
        ContextUsage earlierUsage = earlierManager.computeUsage("session", "model");
        assertEquals(500, earlierUsage.triggerTokens());
        assertTrue(earlierManager.needsCompaction(earlierUsage, 1_000));
    }

    @Test
    void triggerPercentClampsToSupportedRange() {
        Memory memory = new Memory();
        TokenEstimator estimator = new TokenEstimator() {
            @Override public int estimateTokens(String text) { return 0; }
            @Override public int estimateTokens(TurnMessage message) { return 0; }
        };
        CompactionStrategy compaction = context -> new CompactionResult("summary", 0, 0);

        ContextManager low = new ContextManager(memory, estimator, compaction, 0, 0);
        low.contextLengthProvider(alias -> 1_000);
        assertEquals(10, low.computeUsage("session", "model").triggerTokens());

        ContextManager high = new ContextManager(memory, estimator, compaction, 0, 100);
        high.contextLengthProvider(alias -> 1_000);
        assertEquals(990, high.computeUsage("session", "model").triggerTokens());
    }

    private static final class Memory implements ConversationMemory {
        private final List<TurnMessage> messages = new ArrayList<>();

        @Override public List<TurnMessage> getMessages(String sessionId) {
            return List.copyOf(messages);
        }

        @Override public void addMessages(String sessionId, List<TurnMessage> additions) {
            messages.addAll(additions);
        }

        @Override public void replaceMessages(String sessionId, List<TurnMessage> replacement) {
            messages.clear();
            messages.addAll(replacement);
        }

        @Override public void clear(String sessionId) {
            messages.clear();
        }
    }
}
