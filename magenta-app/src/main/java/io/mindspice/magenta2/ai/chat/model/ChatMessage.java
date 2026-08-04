package io.mindspice.magenta2.ai.chat.model;

public record ChatMessage(
    String role,
    String text,
    String renderedHtml,
    String thinkingHtml,
    ChatToolActivity toolActivity
) {
    public ChatMessage(String role, String text, String renderedHtml, String thinkingHtml) {
        this(role, text, renderedHtml, thinkingHtml, null);
    }
}
