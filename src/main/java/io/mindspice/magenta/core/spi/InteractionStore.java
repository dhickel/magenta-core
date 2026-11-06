package io.mindspice.magenta.core.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
 * id. Implementations return an explicit mutation result rather than encoding
 * absence or terminal state in a persisted snapshot.</p>
 */
public interface InteractionStore {

    /**
     * Shared immutable facts retained by every interaction snapshot.
     *
     * <p>The Questions value is the exact caller-visible snapshot that was
     * emitted, including execution information and ordered tool activity
     * summaries. A {@code null} explicit tool list means that tool selection
     * was not explicitly supplied; an empty list means that an explicit empty
     * selection was supplied.</p>
     */
    record InteractionInfo(
        @NonNull String interactionId,
        @NonNull String sessionId,
        @NonNull TurnResponse.Questions questions,
        @NonNull String model,
        @Nullable List<String> explicitToolNames,
        @NonNull Map<String, String> metadata,
        @NonNull Map<String, String> continuationMetadata,
        @NonNull Instant createdAt,
        @NonNull Instant updatedAt
    ) {
        public InteractionInfo {
            interactionId = nonBlank(interactionId, "interactionId");
            try {
                UUID.fromString(interactionId);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("interactionId must be a UUID", e);
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
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
            if (updatedAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("updatedAt must not be before createdAt");
            }
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

    /**
     * Durable interaction snapshot. The hierarchy makes state-dependent
     * payloads unconstructable in the public Java model.
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = Interaction.Pending.class, name = "pending"),
        @JsonSubTypes.Type(value = Interaction.Answered.class, name = "answered"),
        @JsonSubTypes.Type(value = Interaction.Cancelled.class, name = "cancelled")
    })
    sealed interface Interaction
        permits Interaction.Pending, Interaction.Answered, Interaction.Cancelled {

        @NonNull
        InteractionInfo info();

        default String interactionId() {
            return info().interactionId();
        }

        default String sessionId() {
            return info().sessionId();
        }

        default TurnResponse.Questions questions() {
            return info().questions();
        }

        default String model() {
            return info().model();
        }

        @Nullable
        default List<String> explicitToolNames() {
            return info().explicitToolNames();
        }

        default Map<String, String> metadata() {
            return info().metadata();
        }

        default Map<String, String> continuationMetadata() {
            return info().continuationMetadata();
        }

        default Instant createdAt() {
            return info().createdAt();
        }

        default Instant updatedAt() {
            return info().updatedAt();
        }

        /** A durable interaction awaiting answers. */
        record Pending(@NonNull InteractionInfo info) implements Interaction {
            public Pending {
                info = Objects.requireNonNull(info, "info");
            }
        }

        /** A durable interaction with one ordered answer per question. */
        record Answered(
            @NonNull InteractionInfo info,
            @NonNull List<String> answers
        ) implements Interaction {
            public Answered {
                info = Objects.requireNonNull(info, "info");
                answers = List.copyOf(answers);
                if (answers.size() != info.questions().questions().size()) {
                    throw new IllegalArgumentException(
                        "Answered interaction requires exactly one answer per question");
                }
                validateNonBlank(answers, "answers");
            }
        }

        /** A durable interaction cancelled without answers. */
        record Cancelled(
            @NonNull InteractionInfo info,
            @NonNull Instant cancelledAt,
            @NonNull String reason
        ) implements Interaction {
            public Cancelled {
                info = Objects.requireNonNull(info, "info");
                cancelledAt = Objects.requireNonNull(cancelledAt, "cancelledAt");
                reason = nonBlank(reason, "reason");
                if (cancelledAt.isBefore(info.createdAt())) {
                    throw new IllegalArgumentException(
                        "cancelledAt must not be before createdAt");
                }
            }
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

    /** Explicit result of an ID-qualified answer or cancellation CAS. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = MutationResult.Applied.class, name = "applied"),
        @JsonSubTypes.Type(value = MutationResult.Missing.class, name = "missing"),
        @JsonSubTypes.Type(value = MutationResult.AlreadyAnswered.class,
            name = "already_answered"),
        @JsonSubTypes.Type(value = MutationResult.AlreadyCancelled.class,
            name = "already_cancelled")
    })
    sealed interface MutationResult
        permits MutationResult.Applied, MutationResult.Missing,
                MutationResult.AlreadyAnswered, MutationResult.AlreadyCancelled {

        /** The caller won the CAS and receives the resulting terminal snapshot. */
        record Applied(@NonNull Interaction snapshot) implements MutationResult {
            public Applied {
                snapshot = Objects.requireNonNull(snapshot, "snapshot");
                if (snapshot instanceof Interaction.Pending) {
                    throw new IllegalArgumentException(
                        "Applied interaction mutation must produce a terminal snapshot");
                }
            }
        }

        /** The exact session and interaction id were not found. */
        record Missing() implements MutationResult {
        }

        /** The exact interaction was already answered by the winning mutation. */
        record AlreadyAnswered() implements MutationResult {
        }

        /** The exact interaction was already cancelled by the winning mutation. */
        record AlreadyCancelled() implements MutationResult {
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
     * Atomically stores the exact ordered answers when the target is pending.
     * A missing, stale, duplicate, or cancelled target returns the explicit
     * result for that exact interaction without mutating any other interaction.
     */
    @NonNull
    MutationResult answer(@NonNull String sessionId,
                          @NonNull String interactionId,
                          @NonNull List<String> orderedAnswers);

    /** Atomically cancels the exact pending interaction. */
    @NonNull
    MutationResult cancel(@NonNull String sessionId,
                          @NonNull String interactionId,
                          @NonNull String reason);

    /** Deletes only the exact interaction identified by session and id. */
    void delete(@NonNull String sessionId, @NonNull String interactionId);

    /** Deletes all interaction history for a session during session cleanup. */
    void deleteBySessionId(@NonNull String sessionId);
}
