package io.mindspice.magenta2.ai.agent.job;

import io.mindspice.magenta2.ai.agent.job.AgentJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class AgentJobRepositoryTest {

    @Test
    void persistsStatusTransitionsAndFailureText() {
        AgentJobRepository repository = new AgentJobRepository(jdbcTemplate());
        AgentJob job = repository.enqueue(
            "job-1",
            AgentJobType.CONVERSATION_TITLE,
            "conversation-1",
            "qwen3",
            "{\"firstUserMessage\":\"hello\"}"
        ).orElseThrow();

        assertThat(job.status()).isEqualTo(AgentJobStatus.QUEUED);

        repository.markRunning("job-1");
        assertThat(repository.findById("job-1").orElseThrow().status()).isEqualTo(AgentJobStatus.RUNNING);

        repository.markFailed("job-1", "model failed");
        AgentJob failed = repository.findById("job-1").orElseThrow();
        assertThat(failed.status()).isEqualTo(AgentJobStatus.FAILED);
        assertThat(failed.errorText()).isEqualTo("model failed");
        assertThat(failed.completedAt()).isNotNull();
    }

    @Test
    void preventsDuplicateActiveTitleJobsForConversation() {
        AgentJobRepository repository = new AgentJobRepository(jdbcTemplate());

        assertThat(repository.enqueue("job-1", AgentJobType.CONVERSATION_TITLE, "conversation-1", "qwen3", "{}")).isPresent();
        assertThat(repository.enqueue("job-2", AgentJobType.CONVERSATION_TITLE, "conversation-1", "qwen3", "{}")).isEmpty();

        repository.markFailed("job-1", "failed");

        assertThat(repository.enqueue("job-3", AgentJobType.CONVERSATION_TITLE, "conversation-1", "qwen3", "{}")).isPresent();
        repository.markSucceeded("job-3", "{\"title\":\"Hello\"}");
        assertThat(repository.enqueue("job-4", AgentJobType.CONVERSATION_TITLE, "conversation-1", "qwen3", "{}")).isEmpty();
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }
}
