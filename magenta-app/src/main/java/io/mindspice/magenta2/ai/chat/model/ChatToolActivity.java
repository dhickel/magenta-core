package io.mindspice.magenta2.ai.chat.model;

public record ChatToolActivity(
    String id,
    String toolCallId,
    String toolName,
    String status,
    String createdAt,
    String summary,
    String callPreview,
    String callDetail,
    String resultPreview,
    String resultDetail,
    boolean callTruncated,
    boolean resultTruncated
) {
}
