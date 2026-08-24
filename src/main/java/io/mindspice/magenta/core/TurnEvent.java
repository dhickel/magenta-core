package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * Sealed hierarchy of transport-neutral turn lifecycle events.
 * Every terminal path has an explicit diagnostic, including queueing and
 * question suspension.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TurnEvent.Started.class, name = "started"),
    @JsonSubTypes.Type(value = TurnEvent.Chunk.class, name = "chunk"),
    @JsonSubTypes.Type(value = TurnEvent.ToolActivity.class, name = "tool_activity"),
    @JsonSubTypes.Type(value = TurnEvent.SystemNotice.class, name = "system_notice"),
    @JsonSubTypes.Type(value = TurnEvent.QuestionPending.class, name = "question_pending"),
    @JsonSubTypes.Type(value = TurnEvent.Queued.class, name = "queued"),
    @JsonSubTypes.Type(value = TurnEvent.Interrupt.class, name = "interrupt"),
    @JsonSubTypes.Type(value = TurnEvent.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = TurnEvent.Failed.class, name = "failed")
})
public sealed interface TurnEvent
    permits TurnEvent.Started, TurnEvent.Chunk, TurnEvent.ToolActivity,
            TurnEvent.SystemNotice, TurnEvent.QuestionPending, TurnEvent.Queued,
            TurnEvent.Interrupt, TurnEvent.Completed, TurnEvent.Failed {

    @NonNull
    String turnId();

    @NonNull
    Instant timestamp();

    @Nullable
    ContextUsage contextUsage();

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

    /**
     * Indicates that model execution suspended for an ordered set of
     * questions. {@link #questionText()} remains as a compatibility-friendly
     * first-question view for callers written against the original API.
     */
    record QuestionPending(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String questionText,
        @Nullable ContextUsage contextUsage,
        @NonNull List<String> questions
    ) implements TurnEvent {
        public QuestionPending(String turnId, Instant timestamp, String questionText,
                               ContextUsage contextUsage) {
            this(turnId, timestamp, questionText, contextUsage,
                questionText == null || questionText.isBlank()
                    ? List.of()
                    : List.of(questionText));
        }

        public QuestionPending(String turnId, Instant timestamp, List<String> questions,
                               ContextUsage contextUsage) {
            this(turnId, timestamp,
                questions == null || questions.isEmpty() ? "" : questions.getFirst(),
                contextUsage, questions == null ? List.of() : questions);
        }

        public QuestionPending {
            questions = List.copyOf(questions == null ? List.of() : questions);
        }
    }

    /** Terminal event for a submission persisted in the session queue. */
    record Queued(
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String queuedMessageId,
        @NonNull TurnDiagnostic diagnostic,
        @Nullable ContextUsage contextUsage
    ) implements TurnEvent {
        /** Compatibility alias for callers that use the queue record name. */
        public String messageId() {
            return queuedMessageId;
        }
    }

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
