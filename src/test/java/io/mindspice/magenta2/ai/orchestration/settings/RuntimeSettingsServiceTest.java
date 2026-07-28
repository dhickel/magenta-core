package io.mindspice.magenta2.ai.orchestration.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeSettingsServiceTest {

    @Test
    void compactionModelUsesConfiguredCompactionModelWhenPresent() {
        RuntimeSettingsRepository repository = repository();
        repository.save(settings("summary", "compact"));
        RuntimeSettingsService service = new RuntimeSettingsService(repository, aiConfig(), null);

        assertThat(service.compactionModel()).isEqualTo("compact-remote");
    }

    @Test
    void compactionModelFallsBackToSummaryModelWhenBlank() {
        RuntimeSettingsRepository repository = repository();
        repository.save(settings("summary", null));
        RuntimeSettingsService service = new RuntimeSettingsService(repository, aiConfig(), null);

        assertThat(service.compactionModel()).isEqualTo("summary-remote");
    }

    @Test
    void anonymousDefaultModelUsesRuntimeSettingBeforeDefaultAgentProfileModel() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AiConfig aiConfig = aiConfig();
        AgentProfileRepository agentRepository = new AgentProfileRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = new AgentProfileService(agentRepository, aiConfig, null, null, null, null, null);
        agentService.create(new AgentProfile(
            "agent-1",
            "magenta",
            AgentProfileStatus.ACTIVE,
            "main",
            "Prompt",
            List.of(),
            List.of(),
            true,
            null,
            null
        ));
        RuntimeSettingsRepository repository = new RuntimeSettingsRepository(jdbcTemplate);
        repository.save(new RuntimeSettings(
            "agent-1",
            "magenta",
            "summary",
            "main",
            "summary",
            "compact",
            10
        ));
        RuntimeSettingsService service = new RuntimeSettingsService(repository, aiConfig, agentService);

        assertThat(service.defaultModel()).isEqualTo("summary-remote");
        assertThat(service.resolveModel(null, "main")).isEqualTo("main-remote");
    }

    @Test
    void acceptsPersistedRemoteModelNameAfterAliasRename() {
        Map<String, ModelConfig> models = new LinkedHashMap<>();
        models.put("deepseek-v4-pro-high", new ModelConfig(
            "deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 250000, 3, "sk-test"
        ));
        models.put("deepseek-v4-pro-max", new ModelConfig(
            "deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 250000, 4, "sk-test"
        ));
        AiConfig aiConfig = new AiConfig(
            "magenta",
            "deepseek-v4-pro-max",
            "deepseek-v4-pro-high",
            "deepseek-v4-pro-max",
            "deepseek-v4-pro-high",
            10,
            null,
            null,
            models,
            Map.of("magenta", new AgentConfig("deepseek-v4-pro-max", "Prompt", List.of(), List.of()))
        );
        RuntimeSettingsRepository repository = repository();
        repository.save(new RuntimeSettings(
            null,
            "magenta",
            "deepseek-v4-pro",
            "deepseek-v4-pro-max",
            "deepseek-v4-pro-high",
            "deepseek-v4-pro-high",
            10,
            "deepseek-v4-pro-max",
            null,
            null,
            10,
            true,
            -1,
            false
        ));
        RuntimeSettingsService service = new RuntimeSettingsService(repository, aiConfig, null);

        assertThat(service.defaultModel()).isEqualTo("deepseek-v4-pro");
        assertThat(service.resolveModel("deepseek-v4-pro")).isEqualTo("deepseek-v4-pro");
        assertThatThrownBy(() -> service.resolveModel("missing-model"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown configured model");
    }

    private RuntimeSettings settings(String summaryModel, String compactionModel) {
        return new RuntimeSettings(
            null,
            "magenta",
            "main",
            "main",
            summaryModel,
            compactionModel,
            10,
            "main",
            null,
            null,
            10,
            true,
            -1,
            false
        );
    }

    private RuntimeSettingsRepository repository() {
        return new RuntimeSettingsRepository(jdbcTemplate());
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true",
            true
        );
        return new JdbcTemplate(dataSource);
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "magenta",
            "main",
            "summary",
            "main",
            "compact",
            10,
            null,
            null,
            Map.of(
                "main", new ModelConfig("main-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null),
                "summary", new ModelConfig("summary-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null),
                "compact", new ModelConfig("compact-remote", "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null)
            ),
            Map.of("magenta", new AgentConfig("main", "Prompt", List.of(), List.of()))
        );
    }
}
