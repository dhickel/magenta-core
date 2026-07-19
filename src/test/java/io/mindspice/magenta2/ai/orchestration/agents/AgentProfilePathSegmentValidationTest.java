package io.mindspice.magenta2.ai.orchestration.agents;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProfilePathSegmentValidationTest {
    @TempDir
    Path tempDir;

    @Test
    void createAcceptsValidAgentId() {
        AgentProfileService service = service();

        AgentProfile created = service.create(profile("agent-1"));

        assertThat(created.id()).isEqualTo("agent-1");
    }

    @Test
    void createRejectsInvalidAgentIdsBeforePersistence() {
        AgentProfileService service = service();

        for (String invalid : invalidSegments()) {
            assertThatThrownBy(() -> service.create(profile(invalid)))
                .as("invalid agent id %s", invalid)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("agent id");
        }
    }

    @Test
    void updateRejectsInvalidPathAgentIdBeforeLookup() {
        AgentProfileService service = service();

        assertThatThrownBy(() -> service.update("../projects/example", profile("../projects/example")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agent id");
    }

    private AgentProfileService service() {
        return new AgentProfileService(
            new AgentProfileRepository(
                new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)),
                new ObjectMapper()
            ),
            aiConfig(),
            null,
            null,
            null,
            null,
            null
        );
    }

    private AgentProfile profile(String id) {
        return new AgentProfile(
            id, "Agent", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("printf"), true, null, null
        );
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "legacy",
            "main",
            "main",
            "main",
            "main",
            10,
            tempDir,
            null,
            Map.of("main", new ModelConfig("main-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null)),
            Map.of("legacy", new AgentConfig("main", "Prompt", List.of(), List.of("*")))
        );
    }

    private static String[] invalidSegments() {
        return new String[] {
            "",
            " ",
            ".",
            "..",
            "...",
            "a/b",
            "a\\b",
            "/abs",
            "%2e%2e",
            "a%2fb",
            "a%5cb"
        };
    }
}
