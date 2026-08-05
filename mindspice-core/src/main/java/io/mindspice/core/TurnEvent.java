package io.mindspice.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/**
 * Sealed hierarchy of transport-agnostic turn lifecycle events.
 * Each event is an immutable record carrying a wall-clock timestamp
 * and a nullable {@link ContextUsage} snapshot.
 *
 * <p>All 8 subtypes are inner records in this file. Transport rendering
 * (SSE, JSON Lines, etc.) is handled externally via utilities.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TurnEvent.Started.class, name = "started"),
    @JsonSubTypes.Type(value = TurnEvent.Chunk.class, name = "chunk"),
    @JsonSubTypes.Type(value = TurnEvent.ToolActivity.class, name = "tool_activity"),
    @JsonSubTypes.Type(value = TurnEvent.SystemNotice.class, name = "system_notice"),
    @JsonSubTypes.Type(value = TurnEvent.QuestionPending.class, name = "question_pending"),
    @JsonSubTypes.Type(value = TurnEvent.Interrupt.class, name = "interrupt"),
    @JsonSubTypes.Type(value = TurnEvent.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = TurnEvent.Failed.class, name = "failed")
})
public sealed interface TurnEvent
    permits TurnEvent.Started, TurnEvent.Chunk, TurnEvent.ToolActivity,
            TurnEvent.SystemNotice, TurnEvent.QuestionPending,
            TurnEvent.Interrupt, TurnEvent.Completed, TurnEvent.Failed {

    @NonNull
    String turnId();

    @NonNull
    Instant timestamp();

    @Nullable
    ContextUsage contextUsage();

    // ── Subtypes ──

    record Started(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String interruptToken,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Chunk(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String text,
        boolean thinking,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record ToolActivity(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull ToolActivityStatus status,
        @NonNull String summary,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record SystemNotice(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull NoticeKind kind,
        @NonNull String message,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record QuestionPending(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String questionText,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Interrupt(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String reason,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Completed(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull TurnDiagnostic diagnostic,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}

    record Failed(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull TurnDiagnostic diagnostic,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {}
}
