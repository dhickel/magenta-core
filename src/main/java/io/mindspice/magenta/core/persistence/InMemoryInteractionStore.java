package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.spi.InteractionStore;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Ephemeral implementation of the interaction SPI for embedders and focused
 * tests. It preserves the same ID-qualified CAS semantics as the SQLite
 * implementation but is intentionally not restart-safe.
 */
public final class InMemoryInteractionStore implements InteractionStore {

    private final Map<String, Interaction> interactions = new HashMap<>();

    @Override
    public synchronized void save(Interaction interaction) {
        Objects.requireNonNull(interaction, "interaction");
        if (interactions.containsKey(interaction.interactionId())) {
            throw new IllegalStateException(
                "Interaction id already exists: " + interaction.interactionId());
        }
        if (interaction instanceof Interaction.Pending
            && pending(interaction.sessionId()).isPresent()) {
            throw new IllegalStateException(
                "A pending interaction already exists for session " + interaction.sessionId());
        }
        interactions.put(interaction.interactionId(), interaction);
    }

    @Override
    public synchronized Optional<Interaction> pending(String sessionId) {
        requireText(sessionId, "sessionId");
        return interactions.values().stream()
            .filter(interaction -> sessionId.equals(interaction.sessionId()))
            .filter(Interaction.Pending.class::isInstance)
            .max(Comparator.comparing(Interaction::createdAt)
                .thenComparing(Interaction::interactionId));
    }

    @Override
    public synchronized Optional<Interaction> find(String sessionId, String interactionId) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        Interaction interaction = interactions.get(interactionId);
        return interaction != null && sessionId.equals(interaction.sessionId())
            ? Optional.of(interaction) : Optional.empty();
    }

    @Override
    public synchronized MutationResult answer(String sessionId,
                                               String interactionId,
                                               List<String> orderedAnswers) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        Objects.requireNonNull(orderedAnswers, "orderedAnswers");
        Interaction interaction = exact(sessionId, interactionId);
        if (interaction == null) {
            return new MutationResult.Missing();
        }
        if (interaction instanceof Interaction.Answered) {
            return new MutationResult.AlreadyAnswered();
        }
        if (interaction instanceof Interaction.Cancelled) {
            return new MutationResult.AlreadyCancelled();
        }

        Interaction.Pending pending = (Interaction.Pending) interaction;
        validateAnswers(pending.questions().questions(), orderedAnswers);
        Instant answeredAt = Instant.now();
        InteractionStore.InteractionInfo info = updated(pending.info(), answeredAt);
        Interaction.Answered answered = new Interaction.Answered(
            info, List.copyOf(orderedAnswers));
        interactions.put(interactionId, answered);
        return new MutationResult.Applied(answered);
    }

    @Override
    public synchronized MutationResult cancel(String sessionId,
                                               String interactionId,
                                               String reason) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        requireText(reason, "reason");
        Interaction interaction = exact(sessionId, interactionId);
        if (interaction == null) {
            return new MutationResult.Missing();
        }
        if (interaction instanceof Interaction.Answered) {
            return new MutationResult.AlreadyAnswered();
        }
        if (interaction instanceof Interaction.Cancelled) {
            return new MutationResult.AlreadyCancelled();
        }

        Interaction.Pending pending = (Interaction.Pending) interaction;
        Instant cancelledAt = Instant.now();
        InteractionStore.InteractionInfo info = updated(pending.info(), cancelledAt);
        Interaction.Cancelled cancelled = new Interaction.Cancelled(
            info, cancelledAt, reason);
        interactions.put(interactionId, cancelled);
        return new MutationResult.Applied(cancelled);
    }

    @Override
    public synchronized void delete(String sessionId, String interactionId) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        Interaction interaction = interactions.get(interactionId);
        if (interaction != null && sessionId.equals(interaction.sessionId())) {
            interactions.remove(interactionId);
        }
    }

    @Override
    public synchronized void deleteBySessionId(String sessionId) {
        requireText(sessionId, "sessionId");
        interactions.values().removeIf(
            interaction -> sessionId.equals(interaction.sessionId()));
    }

    private Interaction exact(String sessionId, String interactionId) {
        Interaction interaction = interactions.get(interactionId);
        return interaction != null && sessionId.equals(interaction.sessionId())
            ? interaction : null;
    }

    private static InteractionStore.InteractionInfo updated(
        InteractionStore.InteractionInfo info, Instant updatedAt) {
        return new InteractionStore.InteractionInfo(
            info.interactionId(), info.sessionId(), info.questions(), info.model(),
            info.explicitToolNames(), info.metadata(), info.continuationMetadata(),
            info.createdAt(), updatedAt);
    }

    private static void validateAnswers(List<String> questions, List<String> answers) {
        if (answers.size() != questions.size()) {
            throw new IllegalArgumentException(
                "Exactly one answer is required for each ordered question");
        }
        for (int index = 0; index < answers.size(); index++) {
            String answer = answers.get(index);
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException(
                    "answers[" + index + "] must not be blank");
            }
        }
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
