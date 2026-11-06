package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.ExecutionInfo;
import io.mindspice.magenta.core.ExecutionOrigin;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.ToolFailureCategory;
import io.mindspice.magenta.core.TurnResponse;
import io.mindspice.magenta.core.MigrationRunner;
import io.mindspice.magenta.core.spi.InteractionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionLifecycleProtocolTest {

    private static final Instant TIME = Instant.parse("2026-08-24T13:49:27Z");

    @TempDir
    Path tempDir;

    @Test
    void interactionAndMutationFamiliesAreClosedImmutableAndTagged() throws Exception {
        assertTrue(InteractionStore.Interaction.class.isSealed());
        assertEquals(Set.of(
                InteractionStore.Interaction.Pending.class,
                InteractionStore.Interaction.Answered.class,
                InteractionStore.Interaction.Cancelled.class),
            Set.of(InteractionStore.Interaction.class.getPermittedSubclasses()));
        assertTrue(InteractionStore.MutationResult.class.isSealed());
        assertEquals(Set.of(
                InteractionStore.MutationResult.Applied.class,
                InteractionStore.MutationResult.Missing.class,
                InteractionStore.MutationResult.AlreadyAnswered.class,
                InteractionStore.MutationResult.AlreadyCancelled.class),
            Set.of(InteractionStore.MutationResult.class.getPermittedSubclasses()));

        InteractionStore.InteractionInfo info = info();
        InteractionStore.Interaction pending = new InteractionStore.Interaction.Pending(info);
        InteractionStore.Interaction answered = new InteractionStore.Interaction.Answered(
            info, List.of("answer"));
        InteractionStore.Interaction cancelled = new InteractionStore.Interaction.Cancelled(
            info, TIME.plusSeconds(1), "stopped");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        List<Object> values = List.of(
            pending, answered, cancelled,
            new InteractionStore.MutationResult.Applied(answered),
            new InteractionStore.MutationResult.Missing(),
            new InteractionStore.MutationResult.AlreadyAnswered(),
            new InteractionStore.MutationResult.AlreadyCancelled());
        List<String> tags = List.of(
            "pending", "answered", "cancelled", "applied", "missing",
            "already_answered", "already_cancelled");
        for (int index = 0; index < values.size(); index++) {
            String json = mapper.writeValueAsString(values.get(index));
            assertEquals(tags.get(index), mapper.readTree(json).get("type").asText());
            if (values.get(index) instanceof InteractionStore.Interaction) {
                assertEquals(values.get(index).getClass(),
                    mapper.readValue(json, InteractionStore.Interaction.class).getClass());
            } else {
                assertEquals(values.get(index).getClass(),
                    mapper.readValue(json, InteractionStore.MutationResult.class).getClass());
            }
        }

        assertThrows(IllegalArgumentException.class,
            () -> new InteractionStore.Interaction.Answered(info, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new InteractionStore.Interaction.Answered(info, List.of(" ")));
        assertThrows(IllegalArgumentException.class,
            () -> new InteractionStore.Interaction.Cancelled(
                info, TIME.minusSeconds(1), "stopped"));
        assertThrows(UnsupportedOperationException.class,
            () -> pending.info().metadata().put("new", "value"));
    }

    @Test
    void inMemoryMutationResultsFenceExactIdsAndReportTerminalKinds() {
        InMemoryInteractionStore store = new InMemoryInteractionStore();
        InteractionStore.Interaction.Pending pending =
            new InteractionStore.Interaction.Pending(info());
        store.save(pending);

        assertInstanceOf(InteractionStore.MutationResult.Missing.class,
            store.answer("other-session", pending.interactionId(), List.of("answer")));
        assertInstanceOf(InteractionStore.MutationResult.Missing.class,
            store.answer(pending.sessionId(), UUID.randomUUID().toString(), List.of("answer")));

        InteractionStore.MutationResult.Applied answered = assertInstanceOf(
            InteractionStore.MutationResult.Applied.class,
            store.answer(pending.sessionId(), pending.interactionId(), List.of("answer")));
        assertInstanceOf(InteractionStore.Interaction.Answered.class, answered.snapshot());
        assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class,
            store.answer(pending.sessionId(), pending.interactionId(), List.of("different")));
        assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class,
            store.cancel(pending.sessionId(), pending.interactionId(), "late"));

        InteractionStore.Interaction.Pending second =
            new InteractionStore.Interaction.Pending(info());
        store.save(second);
        InteractionStore.MutationResult.Applied cancelled = assertInstanceOf(
            InteractionStore.MutationResult.Applied.class,
            store.cancel(second.sessionId(), second.interactionId(), "stopped"));
        assertInstanceOf(InteractionStore.Interaction.Cancelled.class, cancelled.snapshot());
        assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
            store.cancel(second.sessionId(), second.interactionId(), "again"));
        assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
            store.answer(second.sessionId(), second.interactionId(), List.of("late")));
    }

    @Test
    void inMemoryConcurrentAnswersHaveOneWinner() throws Exception {
        InMemoryInteractionStore store = new InMemoryInteractionStore();
        InteractionStore.Interaction.Pending pending =
            new InteractionStore.Interaction.Pending(info());
        store.save(pending);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<InteractionStore.MutationResult> first = executor.submit(() -> {
                barrier.await();
                return store.answer(pending.sessionId(), pending.interactionId(), List.of("one"));
            });
            Future<InteractionStore.MutationResult> second = executor.submit(() -> {
                barrier.await();
                return store.answer(pending.sessionId(), pending.interactionId(), List.of("two"));
            });
            InteractionStore.MutationResult firstResult = first.get();
            InteractionStore.MutationResult secondResult = second.get();
            assertEquals(1, List.of(firstResult, secondResult).stream()
                .filter(InteractionStore.MutationResult.Applied.class::isInstance).count());
            assertEquals(1, List.of(firstResult, secondResult).stream()
                .filter(InteractionStore.MutationResult.AlreadyAnswered.class::isInstance).count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void inMemoryAnswerAndCancelHaveOneWinner() throws Exception {
        InMemoryInteractionStore store = new InMemoryInteractionStore();
        InteractionStore.Interaction.Pending pending =
            new InteractionStore.Interaction.Pending(info());
        store.save(pending);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<InteractionStore.MutationResult> answer = executor.submit(() -> {
                barrier.await();
                return store.answer(pending.sessionId(), pending.interactionId(), List.of("answer"));
            });
            Future<InteractionStore.MutationResult> cancel = executor.submit(() -> {
                barrier.await();
                return store.cancel(pending.sessionId(), pending.interactionId(), "cancelled");
            });
            InteractionStore.MutationResult answerResult = answer.get();
            InteractionStore.MutationResult cancelResult = cancel.get();
            InteractionStore.MutationResult.Applied winner = answerResult
                instanceof InteractionStore.MutationResult.Applied applied ? applied
                : assertInstanceOf(InteractionStore.MutationResult.Applied.class, cancelResult);
            if (winner.snapshot() instanceof InteractionStore.Interaction.Answered) {
                assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class,
                    answerResult instanceof InteractionStore.MutationResult.Applied
                        ? cancelResult : answerResult);
            } else {
                assertInstanceOf(InteractionStore.Interaction.Cancelled.class, winner.snapshot());
                assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
                    answerResult instanceof InteractionStore.MutationResult.Applied
                        ? cancelResult : answerResult);
            }
            assertEquals(winner.snapshot(), store.find(
                pending.sessionId(), pending.interactionId()).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sqliteAnswerAndCancelHaveOneWinnerAndRestartPreservesIt() throws Exception {
        Path database = tempDir.resolve("answer-cancel-race.db");
        SQLiteDataSource initialized = fileDataSource(database);
        new MigrationRunner(initialized);
        InteractionStore.Interaction.Pending pending =
            new InteractionStore.Interaction.Pending(info());
        new SqliteInteractionStore(initialized).save(pending);
        SqliteInteractionStore answerStore = new SqliteInteractionStore(fileDataSource(database));
        SqliteInteractionStore cancelStore = new SqliteInteractionStore(fileDataSource(database));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<InteractionStore.MutationResult> answer = executor.submit(() -> {
                barrier.await();
                return answerStore.answer(pending.sessionId(), pending.interactionId(), List.of("answer"));
            });
            Future<InteractionStore.MutationResult> cancel = executor.submit(() -> {
                barrier.await();
                return cancelStore.cancel(pending.sessionId(), pending.interactionId(), "cancelled");
            });
            InteractionStore.MutationResult answerResult = answer.get();
            InteractionStore.MutationResult cancelResult = cancel.get();
            InteractionStore.MutationResult.Applied winner = answerResult
                instanceof InteractionStore.MutationResult.Applied applied ? applied
                : assertInstanceOf(InteractionStore.MutationResult.Applied.class, cancelResult);
            if (winner.snapshot() instanceof InteractionStore.Interaction.Answered) {
                assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class,
                    answerResult instanceof InteractionStore.MutationResult.Applied
                        ? cancelResult : answerResult);
            } else {
                assertInstanceOf(InteractionStore.Interaction.Cancelled.class, winner.snapshot());
                assertInstanceOf(InteractionStore.MutationResult.AlreadyCancelled.class,
                    answerResult instanceof InteractionStore.MutationResult.Applied
                        ? cancelResult : answerResult);
            }
            assertEquals(winner.snapshot(), new SqliteInteractionStore(fileDataSource(database))
                .find(pending.sessionId(), pending.interactionId()).orElseThrow());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void sqliteConcurrentAnswersAndRestartReturnExactTerminalState() throws Exception {
        Path database = tempDir.resolve("interaction-race.db");
        SQLiteDataSource initialized = fileDataSource(database);
        new MigrationRunner(initialized);
        InteractionStore.Interaction.Pending pending =
            new InteractionStore.Interaction.Pending(info());
        new SqliteInteractionStore(initialized).save(pending);

        SqliteInteractionStore firstStore = new SqliteInteractionStore(fileDataSource(database));
        SqliteInteractionStore secondStore = new SqliteInteractionStore(fileDataSource(database));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<InteractionStore.MutationResult> first = executor.submit(() -> {
                barrier.await();
                return firstStore.answer(pending.sessionId(), pending.interactionId(), List.of("one"));
            });
            Future<InteractionStore.MutationResult> second = executor.submit(() -> {
                barrier.await();
                return secondStore.answer(pending.sessionId(), pending.interactionId(), List.of("two"));
            });
            InteractionStore.MutationResult firstResult = first.get();
            InteractionStore.MutationResult secondResult = second.get();
            assertEquals(1, List.of(firstResult, secondResult).stream()
                .filter(InteractionStore.MutationResult.Applied.class::isInstance).count());
            assertEquals(1, List.of(firstResult, secondResult).stream()
                .filter(InteractionStore.MutationResult.AlreadyAnswered.class::isInstance).count());

            InteractionStore.Interaction restored =
                new SqliteInteractionStore(fileDataSource(database))
                    .find(pending.sessionId(), pending.interactionId()).orElseThrow();
            InteractionStore.Interaction.Answered answered = assertInstanceOf(
                InteractionStore.Interaction.Answered.class, restored);
            assertTrue(List.of(List.of("one"), List.of("two"))
                .contains(answered.answers()));
            assertInstanceOf(InteractionStore.MutationResult.AlreadyAnswered.class,
                new SqliteInteractionStore(fileDataSource(database)).answer(
                    pending.sessionId(), pending.interactionId(), List.of("late")));
        } finally {
            executor.shutdownNow();
        }
    }

    private static InteractionStore.InteractionInfo info() {
        String interactionId = UUID.randomUUID().toString();
        ExecutionInfo execution = new ExecutionInfo(
            "session", UUID.randomUUID().toString(), new ExecutionOrigin.Direct(),
            TIME, TIME, new ContextUsage(12, 100, 4096), List.of(
                new ToolActivityData.Completed("lookup", "call-1", "done"),
                new ToolActivityData.Failed("deny", "call-2", "denied",
                    ToolFailureCategory.ACCESS_DENIED)));
        TurnResponse.Questions questions = new TurnResponse.Questions(
            execution, interactionId, List.of("question"));
        return new InteractionStore.InteractionInfo(
            interactionId, "session", questions, "model", null,
            Map.of("request", "one"), Map.of("continuation", "one"), TIME, TIME);
    }

    private static SQLiteDataSource fileDataSource(Path file) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        return dataSource;
    }
}
