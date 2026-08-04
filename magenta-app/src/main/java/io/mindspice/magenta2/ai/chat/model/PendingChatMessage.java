package io.mindspice.magenta2.ai.chat.model;

public record PendingChatMessage(
    String id,
    String conversationId,
    String messageText,
    String model,
    String planningModel,
    ChatSessionSurface surface,
    String status,
    int position,
    int total,
    String createdAt,
    String updatedAt
) { }
