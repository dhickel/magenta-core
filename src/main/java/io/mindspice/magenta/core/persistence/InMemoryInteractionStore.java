package io.mindspice.magenta.core.persistence;

import io.mindspice.magenta.core.InteractionState;
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
        if (interaction.state() == InteractionState.PENDING
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
            .filter(interaction -> interaction.state() == InteractionState.PENDING)
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
        Interaction interaction = interactions.get(interactionId);
        if (interaction == null || !sessionId.equals(interaction.sessionId())) {
            return new MutationResult(false, latestState(sessionId));
        }
        if (interaction.state() != InteractionState.PENDING) {
            return new MutationResult(false, interaction.state());
        }
        validateAnswers(interaction.questions().questions(), orderedAnswers);
        Interaction answered = interaction.answered(List.copyOf(orderedAnswers), Instant.now());
        interactions.put(interactionId, answered);
        return new MutationResult(true, InteractionState.ANSWERED);
    }

    @Override
    public synchronized MutationResult cancel(String sessionId,
                                               String interactionId,
                                               String reason) {
        requireText(sessionId, "sessionId");
        requireText(interactionId, "interactionId");
        requireText(reason, "reason");
        Interaction interaction = interactions.get(interactionId);
        if (interaction == null || !sessionId.equals(interaction.sessionId())) {
            return new MutationResult(false, latestState(sessionId));
        }
        if (interaction.state() != InteractionState.PENDING) {
            return new MutationResult(false, interaction.state());
        }
        interactions.put(interactionId, interaction.cancelled(reason, Instant.now()));
        return new MutationResult(true, InteractionState.CANCELLED);
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

    private InteractionState latestState(String sessionId) {
        return interactions.values().stream()
            .filter(interaction -> sessionId.equals(interaction.sessionId()))
            .max(Comparator.comparing(Interaction::createdAt)
                .thenComparing(Interaction::interactionId))
            .map(Interaction::state)
            .orElse(InteractionState.MISSING);
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
