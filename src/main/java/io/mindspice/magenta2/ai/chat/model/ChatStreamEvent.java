package io.mindspice.magenta2.ai.chat.model;

/**
 * Typed stream event hierarchy for SSE chat streaming.
 * Each event type carries only the fields relevant to that event.
 */
public sealed interface ChatStreamEvent {

    record Start(
        String conversationId,
        String model,
        String turnId,
        String interruptToken,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Chunk(
        String text,
        String renderedHtml,
        String thinkingHtml,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Tool(
        ChatToolActivity toolActivity,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record SystemNotice(
        String text,
        String renderedHtml,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Interrupt(
        String text,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Context(
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Done(
        String conversationId,
        String model,
        String text,
        String renderedHtml,
        ContextUsage contextUsage,
        ChatPlanState planState
    ) implements ChatStreamEvent {}

    record Error(
        String message
    ) implements ChatStreamEvent {}

    record OutputsChanged(
        String conversationId
    ) implements ChatStreamEvent {}
}
