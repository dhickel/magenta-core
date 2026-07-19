package io.mindspice.magenta2.api.web;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentProfileControllerTest {
    @TempDir
    Path tempDir;

    @Test
    void createRejectsBlankName() {
        AgentProfileController controller = new AgentProfileController(stubService());
        AgentProfile blankName = new AgentProfile(
            null, "  ", AgentProfileStatus.ACTIVE, "qwen3", null, List.of(), List.of(), true, null, null
        );

        assertThatThrownBy(() -> controller.create(blankName))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            });
    }

    @Test
    void getReturns404ForMissingId() {
        AgentProfileController controller = new AgentProfileController(missingService());

        assertThatThrownBy(() -> controller.get("non-existent"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void updateReturns404ForMissingId() {
        AgentProfileController controller = new AgentProfileController(missingService());
        AgentProfile profile = new AgentProfile(
            "non-existent", "Test", AgentProfileStatus.ACTIVE, "qwen3", null,
            List.of(), List.of(), true, null, null
        );

        assertThatThrownBy(() -> controller.update("non-existent", profile))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            });
    }

    @Test
    void createSucceedsWithValidProfile() {
        AgentProfileController controller = new AgentProfileController(stubService());
        AgentProfile valid = new AgentProfile(
            null, "Valid Agent", AgentProfileStatus.ACTIVE, "qwen3", null,
            List.of(), List.of(), true, null, null
        );

        AgentProfile result = controller.create(valid);

        assertThat(result.name()).isEqualTo("Valid Agent");
        assertThat(result.id()).isEqualTo("agent-1");
    }

    @Test
    void createReturns400ForInvalidPathSegmentId() {
        AgentProfileController controller = new AgentProfileController(realService());
        AgentProfile invalid = new AgentProfile(
            "%2e%2e", "Invalid Agent", AgentProfileStatus.ACTIVE, "main", null,
            List.of(), List.of(), true, null, null
        );

        assertThatThrownBy(() -> controller.create(invalid))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("agent id");
            });
    }

    @Test
    void updateReturns400ForInvalidPathAgentId() {
        AgentProfileController controller = new AgentProfileController(realService());
        AgentProfile profile = new AgentProfile(
            "../projects/example", "Invalid Agent", AgentProfileStatus.ACTIVE, "main", null,
            List.of(), List.of(), true, null, null
        );

        assertThatThrownBy(() -> controller.update("../projects/example", profile))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("agent id");
            });
    }

    @Test
    void listReturnsProfiles() {
        AgentProfileController controller = new AgentProfileController(stubService());

        List<AgentProfile> result = controller.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Default Agent");
    }

    private static AgentProfileService stubService() {
        return new AgentProfileService(null, null, null) {
            @Override
            public List<AgentProfile> list() {
                return List.of(new AgentProfile(
                    "agent-1", "Default Agent", AgentProfileStatus.ACTIVE, "qwen3",
                    null, List.of(), List.of(), true, Instant.EPOCH, Instant.EPOCH
                ));
            }

            @Override
            public AgentProfile create(AgentProfile profile) {
                if (profile.name() == null || profile.name().isBlank()) {
                    throw new IllegalArgumentException("name is required");
                }
                return new AgentProfile(
                    "agent-1", profile.name(), profile.status(), profile.defaultModel(),
                    profile.systemPrompt(), profile.approvedTools(), profile.allowedShellCommands(),
                    profile.directLineEnabled(), Instant.EPOCH, Instant.EPOCH
                );
            }

            @Override
            public AgentProfile get(String id) {
                throw new IllegalStateException("agent not found: " + id);
            }
        };
    }

    private static AgentProfileService missingService() {
        return new AgentProfileService(null, null, null) {
            @Override
            public AgentProfile get(String id) {
                throw new IllegalStateException("agent not found: " + id);
            }

            @Override
            public AgentProfile update(String id, AgentProfile profile) {
                throw new IllegalStateException("agent not found: " + id);
            }
        };
    }

    private AgentProfileService realService() {
        return new AgentProfileService(
            new AgentProfileRepository(
                new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)),
                new ObjectMapper()
            ),
            new AiConfig(
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
            ),
            null
        );
    }
}
