package io.mindspice.magenta2.ai.chat.repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AuditRepositoryTest {

    @Test
    void concurrentInsertsProduceUniqueSequences() throws Exception {
        String url = "jdbc:sqlite::memory:?foreign_keys=true";

        // Single shared instance (matches production singleton pattern)
        JdbcTemplate jt = new JdbcTemplate(new SingleConnectionDataSource(url, true));
        AuditRepository repo = new AuditRepository(jt);

        int threadCount = 10;
        int insertsPerThread = 100;
        String conversationId = "concurrent-test-" + UUID.randomUUID().toString().replace("-", "_");
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    for (int i = 0; i < insertsPerThread; i++) {
                        try {
                            repo.recordUserMessage(conversationId, "concurrent test", "test-model");
                        } catch (Exception e) {
                            errors.add(e);
                        }
                    }
                }, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdown();
        }

        assertThat(errors).describedAs("No errors during concurrent inserts").isEmpty();

        List<Integer> sequences = jt.queryForList(
            "select sequence from audit_event where conversation_id = ? order by sequence",
            Integer.class, conversationId
        );

        assertThat(sequences).describedAs("All inserts recorded")
            .hasSize(threadCount * insertsPerThread);
        assertThat(sequences).describedAs("No duplicate sequences")
            .doesNotHaveDuplicates();
        assertThat(sequences.getFirst()).describedAs("Sequence starts at 0").isZero();
        assertThat(sequences.getLast()).describedAs("Sequence ends at count-1")
            .isEqualTo(threadCount * insertsPerThread - 1);
    }

    @Test
    void findsMultipleConversationIdsInRecordedOrder() {
        JdbcTemplate jt = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        AuditRepository repo = new AuditRepository(jt);

        repo.recordUserMessage("conversation-b", "first", "model");
        repo.recordUserMessage("conversation-a", "second", "model");
        repo.recordAssistantMessage("conversation-b", "third", null, "model");

        List<AuditRepository.AuditEvent> events = repo.findByConversationIds(List.of("conversation-b", "conversation-a"));

        assertThat(events).extracting(AuditRepository.AuditEvent::conversationId)
            .containsExactly("conversation-b", "conversation-a", "conversation-b");
        assertThat(events).extracting(AuditRepository.AuditEvent::messageText)
            .containsExactly("first", "second", "third");
    }
}
