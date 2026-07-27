package io.mindspice.magenta2.ai.chat.service;

public record ResolvedChatRequest(
    String conversationId,
    String message,
    String model,
    boolean newConversation,
    boolean titleJobEligible,
    boolean omitStoredMessages,
    String contextBindingKey
) {
    public ResolvedChatRequest(String conversationId, String message, String model) {
        this(conversationId, message, model, false, false, false, null);
    }

    public ResolvedChatRequest(String conversationId, String message, String model, boolean newConversation) {
        this(conversationId, message, model, newConversation, false, false, null);
    }

    public ResolvedChatRequest(
        String conversationId,
        String message,
        String model,
        boolean newConversation,
        boolean titleJobEligible
    ) {
        this(conversationId, message, model, newConversation, titleJobEligible, false, null);
    }

    public ResolvedChatRequest withoutTitleJob() {
        return new ResolvedChatRequest(conversationId, message, model, newConversation, false, omitStoredMessages, contextBindingKey);
    }

    public ResolvedChatRequest omittingStoredMessages() {
        return new ResolvedChatRequest(conversationId, message, model, newConversation, titleJobEligible, true, contextBindingKey);
    }

    public ResolvedChatRequest withContextBindingKey(String contextBindingKey) {
        return new ResolvedChatRequest(conversationId, message, model, newConversation, titleJobEligible, omitStoredMessages, contextBindingKey);
    }
}
