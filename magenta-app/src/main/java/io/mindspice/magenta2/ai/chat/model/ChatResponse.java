package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public sealed interface ChatResponse {
    record MsgResponse(
        String conversationId,
        String model,
        String response,
        ContextUsage contextUsage,
        List<ChatToolActivity> toolActivities
    ) implements ChatResponse {
        public MsgResponse(
            String conversationId,
            String model,
            String response,
            ContextUsage contextUsage
        ) {
            this(conversationId, model, response, contextUsage, List.of());
        }
    }

    record CmdResponse(
            String conversationId,
            String model,
            String message,
            List<String> conversationIds,
            List<ChatSession> sessions,
            List<ChatMessage> history,
            ContextUsage contextUsage,
            List<ChatToolActivity> toolActivities
    ) implements ChatResponse {
        public CmdResponse(
            String conversationId,
            String model,
            String message,
            List<String> conversationIds,
            List<ChatMessage> history,
            ContextUsage contextUsage
        ) {
            this(
                conversationId,
                model,
                message,
                conversationIds,
                conversationIds == null
                    ? List.of()
                    : conversationIds.stream().map(id -> new ChatSession(id, null, null)).toList(),
                history,
                contextUsage,
                List.of()
            );
        }

        public CmdResponse(
            String conversationId,
            String model,
            String message,
            List<String> conversationIds,
            List<ChatMessage> history,
            ContextUsage contextUsage,
            List<ChatToolActivity> toolActivities
        ) {
            this(
                conversationId,
                model,
                message,
                conversationIds,
                conversationIds == null
                    ? List.of()
                    : conversationIds.stream().map(id -> new ChatSession(id, null, null)).toList(),
                history,
                contextUsage,
                toolActivities
            );
        }
    }
}
