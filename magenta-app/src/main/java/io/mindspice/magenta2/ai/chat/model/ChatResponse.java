package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public sealed interface ChatResponse {
    record MsgResponse(
        String conversationId,
        String model,
        String response,
        ContextUsage contextUsage,
        ChatPlanState planState,
        List<ChatToolActivity> toolActivities
    ) implements ChatResponse {
        public MsgResponse(
            String conversationId,
            String model,
            String response,
            ContextUsage contextUsage,
            ChatPlanState planState
        ) {
            this(conversationId, model, response, contextUsage, planState, List.of());
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
            ChatPlanState planState,
            List<ChatToolActivity> toolActivities
    ) implements ChatResponse {
        public CmdResponse(
            String conversationId,
            String model,
            String message,
            List<String> conversationIds,
            List<ChatMessage> history,
            ContextUsage contextUsage,
            ChatPlanState planState
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
                planState,
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
            ChatPlanState planState,
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
                planState,
                toolActivities
            );
        }
    }
}
