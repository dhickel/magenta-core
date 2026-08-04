package io.mindspice.magenta2.ai.chat.model;

/**
 * Typed stream event hierarchy. Intermediate form; replaced by framework TurnEvent in Phase 07.
 */
public sealed interface TurnEvent {

    record Start(
        String conversationId,
        String model,
        String turnId,
        String interruptToken
    ) implements TurnEvent {}

    record Chunk(
        String text,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record Tool(
        ChatToolActivity toolActivity,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record SystemNotice(
        String text,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record Interrupt(
        String text,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record Context(
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record Done(
        String conversationId,
        String model,
        String text,
        ContextUsage contextUsage
    ) implements TurnEvent {}

    record Error(
        String message
    ) implements TurnEvent {}
}
