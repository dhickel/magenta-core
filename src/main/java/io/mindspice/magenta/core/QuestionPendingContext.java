package io.mindspice.magenta.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable context for a pending user-question interaction.
 *
 * <p>The three-argument constructor remains source-compatible with the
 * original interaction SPI. The extended fields let a persistent store
 * restore the exact model and continuation metadata after a process restart,
 * while retaining answer and cancellation state for durable lifecycle
 * handling.</p>
 */
public record QuestionPendingContext(
    @NonNull String sessionId,
    @NonNull String turnId,
    @NonNull List<String> questions,
    @Nullable String model,
    @NonNull Map<String, String> continuationContext,
    @NonNull State state,
    @Nullable String answer,
    @Nullable String cancellationReason
) {

    public enum State {
        PENDING,
        ANSWERED,
        CANCELLED
    }

    public QuestionPendingContext(String sessionId, String turnId, List<String> questions) {
        this(sessionId, turnId, questions, null, Map.of(), State.PENDING,
            null, null);
    }

    public QuestionPendingContext(String sessionId, String turnId, List<String> questions,
                                  String model,
                                  Map<String, String> continuationContext) {
        this(sessionId, turnId, questions, model, continuationContext, State.PENDING,
            null, null);
    }

    public QuestionPendingContext(String sessionId, String turnId, List<String> questions,
                                  String model,
                                  Map<String, String> continuationContext,
                                  State state) {
        this(sessionId, turnId, questions, model, continuationContext, state, null, null);
    }

    public QuestionPendingContext(String sessionId, String turnId, List<String> questions,
                                  String model,
                                  Map<String, String> continuationContext,
                                  State state,
                                  String answer,
                                  String cancellationReason) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.turnId = Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(questions, "questions");
        if (questions.isEmpty()) {
            throw new IllegalArgumentException("questions must contain at least one question");
        }
        this.questions = List.copyOf(questions);
        this.model = model;
        this.continuationContext = continuationContext == null
            ? Map.of() : Map.copyOf(continuationContext);
        this.state = Objects.requireNonNull(state, "state");
        this.answer = answer;
        this.cancellationReason = cancellationReason;

        switch (state) {
            case PENDING -> {
                if (answer != null || cancellationReason != null) {
                    throw new IllegalArgumentException(
                        "pending interaction cannot contain an answer or cancellation reason");
                }
            }
            case ANSWERED -> {
                if (answer == null || answer.isBlank() || cancellationReason != null) {
                    throw new IllegalArgumentException(
                        "answered interaction requires an answer only");
                }
            }
            case CANCELLED -> {
                if (cancellationReason == null || cancellationReason.isBlank() || answer != null) {
                    throw new IllegalArgumentException(
                        "cancelled interaction requires a cancellation reason only");
                }
            }
        }
    }

    /** Returns a terminal answered snapshot while retaining the questions. */
    public QuestionPendingContext answered(String value) {
        return new QuestionPendingContext(sessionId, turnId, questions, model,
            continuationContext, State.ANSWERED, value, null);
    }

    /** Returns a terminal cancelled snapshot while retaining the questions. */
    public QuestionPendingContext cancelled(String reason) {
        return new QuestionPendingContext(sessionId, turnId, questions, model,
            continuationContext, State.CANCELLED, null, reason);
    }
}
