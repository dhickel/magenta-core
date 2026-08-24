package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * The caller-visible protocol for turn progress and terminal outcomes.
 *
 * <p>A stream contains zero or more {@link Progress} values followed by one
 * expected {@link Outcome}. Unexpected failures use the stream error channel
 * or {@link TurnFailedException}; they are not response variants.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = TurnResponse.Started.class, name = "started"),
    @JsonSubTypes.Type(value = TurnResponse.ContentChunk.class, name = "content_chunk"),
    @JsonSubTypes.Type(value = TurnResponse.ThinkingChunk.class, name = "thinking_chunk"),
    @JsonSubTypes.Type(value = TurnResponse.ToolActivity.class, name = "tool_activity"),
    @JsonSubTypes.Type(value = TurnResponse.Notice.class, name = "notice"),
    @JsonSubTypes.Type(value = TurnResponse.Message.class, name = "message"),
    @JsonSubTypes.Type(value = TurnResponse.Questions.class, name = "questions"),
    @JsonSubTypes.Type(value = TurnResponse.Queued.class, name = "queued"),
    @JsonSubTypes.Type(value = TurnResponse.Interrupted.class, name = "interrupted"),
    @JsonSubTypes.Type(value = TurnResponse.TimedOut.class, name = "timed_out"),
    @JsonSubTypes.Type(value = TurnResponse.QuestionsCancelled.class,
        name = "questions_cancelled"),
    @JsonSubTypes.Type(value = TurnResponse.TurnCancelled.class,
        name = "turn_cancelled")
})
public sealed interface TurnResponse permits TurnResponse.Progress, TurnResponse.Outcome {

    /** Non-terminal facts emitted while an execution is in progress. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TurnResponse.Started.class, name = "started"),
        @JsonSubTypes.Type(value = TurnResponse.ContentChunk.class, name = "content_chunk"),
        @JsonSubTypes.Type(value = TurnResponse.ThinkingChunk.class, name = "thinking_chunk"),
        @JsonSubTypes.Type(value = TurnResponse.ToolActivity.class, name = "tool_activity"),
        @JsonSubTypes.Type(value = TurnResponse.Notice.class, name = "notice")
    })
    sealed interface Progress extends TurnResponse
        permits TurnResponse.Started, TurnResponse.ContentChunk,
                TurnResponse.ThinkingChunk, TurnResponse.ToolActivity,
                TurnResponse.Notice {

        @NonNull
        String sessionId();

        @NonNull
        String turnId();

        @NonNull
        Instant timestamp();
    }

    /** A terminal, expected result of a facade operation or execution. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = TurnResponse.Message.class, name = "message"),
        @JsonSubTypes.Type(value = TurnResponse.Questions.class, name = "questions"),
        @JsonSubTypes.Type(value = TurnResponse.Queued.class, name = "queued"),
        @JsonSubTypes.Type(value = TurnResponse.Interrupted.class, name = "interrupted"),
        @JsonSubTypes.Type(value = TurnResponse.TimedOut.class, name = "timed_out"),
        @JsonSubTypes.Type(value = TurnResponse.QuestionsCancelled.class,
            name = "questions_cancelled"),
        @JsonSubTypes.Type(value = TurnResponse.TurnCancelled.class,
            name = "turn_cancelled")
    })
    sealed interface Outcome extends TurnResponse
        permits TurnResponse.Message, TurnResponse.Questions, TurnResponse.Queued,
                TurnResponse.Interrupted, TurnResponse.TimedOut,
                TurnResponse.QuestionsCancelled, TurnResponse.TurnCancelled {
    }

    /** Announces the execution correlation and initial execution facts. */
    record Started(
        @NonNull String sessionId,
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull ExecutionOrigin origin,
        @NonNull String model,
        @NonNull ContextUsage initialContextUsage
    ) implements Progress {
        public Started {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            turnId = ResponseValidation.nonBlank(turnId, "turnId");
            timestamp = ResponseValidation.timestamp(timestamp, "timestamp");
            origin = ResponseValidation.required(origin, "origin");
            model = ResponseValidation.nonBlank(model, "model");
            initialContextUsage = ResponseValidation.required(initialContextUsage,
                "initialContextUsage");
        }
    }

    /** A visible assistant-content delta. */
    record ContentChunk(
        @NonNull String sessionId,
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String text
    ) implements Progress {
        public ContentChunk {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            turnId = ResponseValidation.nonBlank(turnId, "turnId");
            timestamp = ResponseValidation.timestamp(timestamp, "timestamp");
            text = ResponseValidation.required(text, "text");
        }
    }

    /** A model-thinking delta kept separate from visible assistant content. */
    record ThinkingChunk(
        @NonNull String sessionId,
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull String text
    ) implements Progress {
        public ThinkingChunk {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            turnId = ResponseValidation.nonBlank(turnId, "turnId");
            timestamp = ResponseValidation.timestamp(timestamp, "timestamp");
            text = ResponseValidation.required(text, "text");
        }
    }

    /** Reports one tool activity and its Magenta-owned summary. */
    record ToolActivity(
        @NonNull String sessionId,
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull ToolActivityData activity
    ) implements Progress {
        public ToolActivity {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            turnId = ResponseValidation.nonBlank(turnId, "turnId");
            timestamp = ResponseValidation.timestamp(timestamp, "timestamp");
            activity = ResponseValidation.required(activity, "activity");
        }
    }

    /** Reports a non-terminal framework notice. */
    record Notice(
        @NonNull String sessionId,
        @NonNull String turnId,
        @NonNull Instant timestamp,
        @NonNull NoticeKind kind,
        @NonNull String message
    ) implements Progress {
        public Notice {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            turnId = ResponseValidation.nonBlank(turnId, "turnId");
            timestamp = ResponseValidation.timestamp(timestamp, "timestamp");
            kind = ResponseValidation.required(kind, "kind");
            message = ResponseValidation.nonBlank(message, "message");
        }
    }

    /** A completed assistant response with nonblank user-visible text. */
    record Message(
        @NonNull ExecutionInfo execution,
        @NonNull String text
    ) implements Outcome {
        public Message {
            execution = ResponseValidation.required(execution, "execution");
            text = ResponseValidation.nonBlank(text, "text");
        }
    }

    /** A durable ordered interaction awaiting one answer per question. */
    record Questions(
        @NonNull ExecutionInfo execution,
        @NonNull String interactionId,
        @NonNull List<String> questions
    ) implements Outcome {
        public Questions {
            execution = ResponseValidation.required(execution, "execution");
            interactionId = ResponseValidation.nonBlank(interactionId, "interactionId");
            questions = ResponseValidation.nonBlankList(questions, "questions");
        }
    }

    /** A durable receipt for work accepted by the session queue. */
    record Queued(
        @NonNull String sessionId,
        @NonNull String messageId,
        @NonNull Instant enqueuedAt,
        @NonNull QueueReason reason
    ) implements Outcome {
        public Queued {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            messageId = ResponseValidation.nonBlank(messageId, "messageId");
            enqueuedAt = ResponseValidation.timestamp(enqueuedAt, "enqueuedAt");
            reason = ResponseValidation.required(reason, "reason");
        }
    }

    /** An execution stopped by an interrupt request. */
    record Interrupted(
        @NonNull ExecutionInfo execution,
        @NonNull String reason
    ) implements Outcome {
        public Interrupted {
            execution = ResponseValidation.required(execution, "execution");
            reason = ResponseValidation.nonBlank(reason, "reason");
        }
    }

    /** An execution stopped because its configured time budget elapsed. */
    record TimedOut(
        @NonNull ExecutionInfo execution,
        @NonNull String reason
    ) implements Outcome {
        public TimedOut {
            execution = ResponseValidation.required(execution, "execution");
            reason = ResponseValidation.nonBlank(reason, "reason");
        }
    }

    /** A pending question interaction cancelled by its caller. */
    record QuestionsCancelled(
        @NonNull String sessionId,
        @NonNull String interactionId,
        @NonNull Instant cancelledAt,
        @NonNull String reason
    ) implements Outcome {
        public QuestionsCancelled {
            sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
            interactionId = ResponseValidation.nonBlank(interactionId, "interactionId");
            cancelledAt = ResponseValidation.timestamp(cancelledAt, "cancelledAt");
            reason = ResponseValidation.nonBlank(reason, "reason");
        }
    }

    /** An execution cancelled by its owning subscriber or lifecycle control. */
    record TurnCancelled(
        @NonNull ExecutionInfo execution,
        @NonNull String reason
    ) implements Outcome {
        public TurnCancelled {
            execution = ResponseValidation.required(execution, "execution");
            reason = ResponseValidation.nonBlank(reason, "reason");
        }
    }
}
