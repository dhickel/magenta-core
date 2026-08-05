package io.mindspice.magenta2.ai.chat.model;

public record ChatSession(
    String conversationId,
    String title,
    String titleJobStatus,
    boolean favorite,
    boolean archived,
    String updatedAt,
    String agentId,
    int outputCount
) {
    public ChatSession(String conversationId, String title, String titleJobStatus) {
        this(conversationId, title, titleJobStatus, false, false, null, null, 0);
    }

    public ChatSession(
        String conversationId,
        String title,
        String titleJobStatus,
        boolean favorite,
        boolean archived,
        String updatedAt
    ) {
        this(conversationId, title, titleJobStatus, favorite, archived, updatedAt, null, 0);
    }

    public ChatSession(
        String conversationId,
        String title,
        String titleJobStatus,
        boolean favorite,
        boolean archived,
        String updatedAt,
        String agentId
    ) {
        this(conversationId, title, titleJobStatus, favorite, archived, updatedAt, agentId, 0);
    }
}
