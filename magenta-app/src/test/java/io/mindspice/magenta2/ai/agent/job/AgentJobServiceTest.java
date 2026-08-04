package io.mindspice.magenta2.ai.agent.job;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.agent.job.AgentJobRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.execution.MagentaWorkExecutor;
import io.mindspice.magenta2.ai.execution.MagentaWorkKind;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentJobServiceTest {

    @Test
    void cleansBlankAndOverlongTitles() {
        AgentJobService service = service(jdbcTemplate(), testExecutor());

        assertThat(service.cleanTitle("   ")).isNull();
        assertThat(service.cleanTitle("<think>notes</think>\n\"Discuss reminder follow-up\""))
            .isEqualTo("Discuss reminder follow-up");
        assertThat(service.cleanTitle("This title is intentionally far too long for a compact sidebar label and should be shortened"))
            .hasSizeLessThanOrEqualTo(AgentJobService.MAX_TITLE_LENGTH)
            .doesNotEndWith(" ");
    }

    @Test
    void titleJobUpdatesConversationMetadata() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AgentJobRepository jobRepository = new AgentJobRepository(jdbcTemplate);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        MagentaWorkExecutor executor = testExecutor();
        AgentJobService service = new FixedTitleAgentJobService(jobRepository, metadataRepository, executor, "Useful Title");

        service.submitConversationTitle("conversation-1", "qwen3", "Please help me plan reminders");
        Thread.sleep(100); // wait for async background job

        assertThat(metadataRepository.findTitle("conversation-1")).contains("Useful Title");
        AgentJob job = jobRepository.findAll().getFirst();
        assertThat(job.status()).isEqualTo(AgentJobStatus.SUCCEEDED);
        assertThat(job.selectedModel()).isEqualTo("summary-remote");
        assertThat(job.resultJson()).contains("Useful Title");
    }

    @Test
    void titleJobDoesNotReplaceManualTitle() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AgentJobRepository jobRepository = new AgentJobRepository(jdbcTemplate);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        AgentJob job = jobRepository.enqueue(
            "job-1",
            AgentJobType.CONVERSATION_TITLE,
            "conversation-1",
            "summary-remote",
            "{\"firstUserMessage\":\"Please help me plan reminders\"}"
        ).orElseThrow();
        metadataRepository.updateTitle("conversation-1", "Manual Title");
        AgentJobService service = new FixedTitleAgentJobService(jobRepository, metadataRepository, testExecutor(), "Generated Title");

        service.runConversationTitleJob(job.id());

        assertThat(metadataRepository.findTitle("conversation-1")).contains("Manual Title");
    }

    @Test
    void titleJobUsesSummaryModelInsteadOfChatModel() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AgentJobRepository jobRepository = new AgentJobRepository(jdbcTemplate);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        MagentaWorkExecutor executor = testExecutor();
        FixedTitleAgentJobService service = new FixedTitleAgentJobService(
            jobRepository,
            metadataRepository,
            executor,
            "Useful Title"
        );

        service.submitConversationTitle("conversation-1", "chat-remote", "Please help me plan reminders");
        Thread.sleep(100); // wait for async background job

        assertThat(service.lastSelectedModel).isEqualTo("summary-remote");
        assertThat(jobRepository.findAll().getFirst().selectedModel()).isEqualTo("summary-remote");
    }

    @Test
    void boundedExecutorRunsAtMostTwoJobsAtOnce() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AgentJobRepository jobRepository = new AgentJobRepository(jdbcTemplate);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        MagentaWorkExecutor executor = new MagentaWorkExecutor(Map.of(
            MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 2, 10)
        ));
        try {
            BlockingTitleAgentJobService service = new BlockingTitleAgentJobService(
                jobRepository,
                metadataRepository,
                executor
            );

            for (int i = 0; i < 4; i++) {
                service.submitConversationTitle("conversation-" + i, "qwen3", "message " + i);
            }

            assertThat(service.twoJobsStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.maxRunning.get()).isEqualTo(2);
            service.release.countDown();
        } finally {
            executor.shutdown();
        }
    }

    private AgentJobService service(JdbcTemplate jdbcTemplate, MagentaWorkExecutor executor) {
        return new AgentJobService(
            new AgentJobRepository(jdbcTemplate),
            new ChatSessionMetadataRepository(jdbcTemplate),
            null,
            aiConfig(),
            null,
            new ObjectMapper(),
            executor
        );
    }

    private MagentaWorkExecutor testExecutor() {
        return new MagentaWorkExecutor(Map.of(
            MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
        ));
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }

    private static AiConfig aiConfig() {
        return new AiConfig(
            "magenta",
            "summary",
            10,
            null,
            Map.of(
                "summary", new ModelConfig("summary-remote", "http://localhost:11434", EndpointType.OLLAMA, 8192, null, null)
            ),
            Map.of()
        );
    }

    private static class FixedTitleAgentJobService extends AgentJobService {
        private final String title;
        private String lastSelectedModel;

        FixedTitleAgentJobService(
            AgentJobRepository jobRepository,
            ChatSessionMetadataRepository metadataRepository,
            MagentaWorkExecutor executor,
            String title
        ) {
            super(jobRepository, metadataRepository, null, aiConfig(), null, new ObjectMapper(), executor);
            this.title = title;
        }

        @Override
        String generateTitle(String selectedModel, String firstUserMessage) {
            lastSelectedModel = selectedModel;
            return title;
        }
    }

    private static class BlockingTitleAgentJobService extends AgentJobService {
        private final AtomicInteger running = new AtomicInteger();
        private final AtomicInteger maxRunning = new AtomicInteger();
        private final CountDownLatch twoJobsStarted = new CountDownLatch(2);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingTitleAgentJobService(
            AgentJobRepository jobRepository,
            ChatSessionMetadataRepository metadataRepository,
            MagentaWorkExecutor executor
        ) {
            super(jobRepository, metadataRepository, null, aiConfig(), null, new ObjectMapper(), executor);
        }

        @Override
        String generateTitle(String selectedModel, String firstUserMessage) {
            int current = running.incrementAndGet();
            maxRunning.accumulateAndGet(current, Math::max);
            twoJobsStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
                return "Title";
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            } finally {
                running.decrementAndGet();
            }
        }
    }
}
