package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.InteractionState;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.TurnResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Advanced persistence SPI for durable user-question interactions.
 *
 * <p>The store owns interaction identity and state transitions. A caller must
 * qualify every mutation with both the session and the dedicated interaction
 * id. Implementations must not replay a terminal outcome when an answer or
 * cancellation is submitted more than once; they return a state result
 * instead.</p>
 */
public interface InteractionStore {

    /**
     * Complete durable interaction state. The {@link #questions()} value is
     * the exact caller-visible Questions snapshot that was emitted, including
     * its execution information and ordered tool activity summaries.
     */
    record Interaction(
        @NonNull String interactionId,
        @NonNull String sessionId,
        @NonNull TurnResponse.Questions questions,
        @NonNull String model,
        @Nullable List<String> explicitToolNames,
        @NonNull Map<String, String> metadata,
        @NonNull Map<String, String> continuationMetadata,
        @NonNull List<String> answers,
        @NonNull InteractionState state,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt,
        @Nullable Instant cancelledAt,
        @Nullable String cancellationReason
    ) {
        public Interaction {
            interactionId = nonBlank(interactionId, "interactionId");
            try {
                UUID.fromString(interactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "interactionId must be a UUID", e);
            }
            sessionId = nonBlank(sessionId, "sessionId");
            questions = Objects.requireNonNull(questions, "questions");
            if (!sessionId.equals(questions.execution().sessionId())) {
                throw new IllegalArgumentException(
                    "Questions execution session does not match interaction session");
            }
            if (!interactionId.equals(questions.interactionId())) {
                throw new IllegalArgumentException(
                    "Questions interaction id does not match interaction id");
            }
            if (interactionId.equals(questions.execution().turnId())) {
                throw new IllegalArgumentException(
                    "interactionId must be distinct from the execution turn id");
            }
            model = nonBlank(model, "model");
            if (explicitToolNames != null) {
                explicitToolNames = List.copyOf(explicitToolNames);
                validateNonBlank(explicitToolNames, "explicitToolNames");
            }
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            continuationMetadata = continuationMetadata == null
                ? Map.of() : Map.copyOf(continuationMetadata);
            answers = answers == null ? List.of() : List.copyOf(answers);
            validateNonBlank(answers, "answers");
            state = Objects.requireNonNull(state, "state");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
            if (cancelledAt != null && cancelledAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("cancelledAt must not be before createdAt");
            }
            if (cancellationReason != null && cancellationReason.isBlank()) {
                throw new IllegalArgumentException("cancellationReason must not be blank");
            }

            switch (state) {
                case PENDING -> {
                    if (!answers.isEmpty() || cancelledAt != null || cancellationReason != null) {
                        throw new IllegalArgumentException(
                            "pending interaction cannot contain answers or cancellation facts");
                    }
                }
                case ANSWERED -> {
                    if (answers.size() != questions.questions().size()
                        || answers.isEmpty() || cancelledAt != null
                        || cancellationReason != null) {
                        throw new IllegalArgumentException(
                            "answered interaction requires one answer per question");
                    }
                }
                case CANCELLED -> {
                    if (!answers.isEmpty() || cancelledAt == null
                        || cancellationReason == null || cancellationReason.isBlank()) {
                        throw new IllegalArgumentException(
                            "cancelled interaction requires cancellation facts only");
                    }
                }
                case MISSING -> throw new IllegalArgumentException(
                    "MISSING is an operation result, not a persisted interaction state");
            }
        }

        /** Returns a terminal answered snapshot with the supplied ordered answers. */
        public Interaction answered(List<String> orderedAnswers, Instant answeredAt) {
            return new Interaction(interactionId, sessionId, questions, model,
                explicitToolNames, metadata, continuationMetadata, orderedAnswers,
                InteractionState.ANSWERED, createdAt, answeredAt, null, null);
        }

        /** Returns a terminal cancellation snapshot. */
        public Interaction cancelled(String reason, Instant cancelledAt) {
            return new Interaction(interactionId, sessionId, questions, model,
                explicitToolNames, metadata, continuationMetadata, List.of(),
                InteractionState.CANCELLED, createdAt, cancelledAt, cancelledAt, reason);
        }

        private static String nonBlank(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        private static void validateNonBlank(List<String> values, String name) {
            for (int index = 0; index < values.size(); index++) {
                nonBlank(values.get(index), name + "[" + index + "]");
            }
        }
    }

    /** Result of an ID-qualified answer or cancellation compare-and-set. */
    record MutationResult(boolean applied, @NonNull InteractionState state) {
        public MutationResult {
            state = Objects.requireNonNull(state, "state");
            if (applied && (state == InteractionState.MISSING
                || state == InteractionState.PENDING)) {
                throw new IllegalArgumentException(
                    "A successful interaction mutation must be terminal");
            }
        }
    }

    /** Inserts a new interaction without replacing a different interaction. */
    void save(@NonNull Interaction interaction);

    /** Returns the session's current pending interaction, if any. */
    @NonNull
    Optional<Interaction> pending(@NonNull String sessionId);

    /** Finds the exact interaction, including terminal state for recovery facts. */
    @NonNull
    Optional<Interaction> find(@NonNull String sessionId, @NonNull String interactionId);

    /**
     * Atomically stores the exact ordered answers when the target is PENDING.
     * A missing, stale, duplicate, or cancelled target returns its current
     * state without mutating any interaction.
     */
    @NonNull
    MutationResult answer(@NonNull String sessionId,
                          @NonNull String interactionId,
                          @NonNull List<String> orderedAnswers);

    /** Atomically cancels the exact PENDING interaction. */
    @NonNull
    MutationResult cancel(@NonNull String sessionId,
                          @NonNull String interactionId,
                          @NonNull String reason);

    /** Deletes only the exact interaction identified by session and id. */
    void delete(@NonNull String sessionId, @NonNull String interactionId);

    /** Deletes all interaction history for a session during session cleanup. */
    void deleteBySessionId(@NonNull String sessionId);
}
