package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public record ChatSessions(
    List<String> conversationIds,
    List<ChatSession> sessions
) {
    public ChatSessions(List<String> conversationIds) {
        this(
            conversationIds,
            conversationIds.stream()
                .map(conversationId -> new ChatSession(conversationId, null, null))
                .toList()
        );
    }
}
