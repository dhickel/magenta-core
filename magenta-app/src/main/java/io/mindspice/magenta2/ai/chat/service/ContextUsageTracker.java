package io.mindspice.magenta2.ai.chat.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import org.springframework.stereotype.Component;

@Component
public class ContextUsageTracker {

    private final Map<String, ContextUsage> usageByConversationId = new ConcurrentHashMap<>();

    public void record(String conversationId, ContextUsage usage) {
        if (conversationId != null && usage != null) {
            usageByConversationId.put(conversationId, usage);
        }
    }

    public ContextUsage find(String conversationId) {
        return usageByConversationId.get(conversationId);
    }

    public void clear(String conversationId) {
        if (conversationId != null) {
            usageByConversationId.remove(conversationId);
        }
    }
}
