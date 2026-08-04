package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public record ChatHistory(
    String conversationId,
    String title,
    String titleJobStatus,
    String model,
    List<ChatMessage> messages,
    ContextUsage contextUsage,
    ChatPlanState planState
) {
    public ChatHistory(
        String conversationId,
        String model,
        List<ChatMessage> messages,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) {
        this(conversationId, null, null, model, messages, contextUsage, planState);
    }
}
