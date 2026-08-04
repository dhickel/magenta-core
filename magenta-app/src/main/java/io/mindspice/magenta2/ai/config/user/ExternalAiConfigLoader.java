package io.mindspice.magenta2.ai.config.user;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.util.StringUtils;

public final class ExternalAiConfigLoader {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private ExternalAiConfigLoader() {
    }

    public static AiConfig load(Path path) throws IOException {
        String fileName = path.getFileName().toString().toLowerCase();
        AiConfig config;
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            config = YAML_MAPPER.readValue(path.toFile(), AiConfig.class);
        } else if (fileName.endsWith(".json")) {
            config = JSON_MAPPER.readValue(path.toFile(), AiConfig.class);
        } else {
            throw new IllegalArgumentException("Unsupported config format: " + fileName);
        }
        return validate(resolveSystemPrompts(path, config));
    }

    private static AiConfig resolveSystemPrompts(Path configPath, AiConfig config) throws IOException {
        if (config == null || config.agents() == null) {
            return config;
        }
        Path configDirectory = configPath.toAbsolutePath().normalize().getParent();
        Map<String, AgentConfig> agents = config.agents().entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> resolveSystemPrompt(configDirectory, entry.getKey(), entry.getValue())
            ));
        return new AiConfig(
            config.defaultAgent(),
            config.defaultModel(),
            config.summaryModel(),
            config.summeryModel(),
            config.planningModel(),
            config.compactionModel(),
            config.contextBufferPercent(),
            config.dataRoot(),
            config.webSearch(),
            config.models(),
            agents,
            config.unsafeAllowWildcardShellCommands()
        );
    }

    private static AiConfig validate(AiConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AI config is required");
        }
        if (config.agents() == null || config.agents().isEmpty()) {
            throw new IllegalArgumentException("AI config must define agents");
        }
        if (config.models() == null || config.models().isEmpty()) {
            throw new IllegalArgumentException("AI config must define models");
        }
        String summaryModelKey = config.resolvedSummaryModelKey();
        if (!StringUtils.hasText(summaryModelKey)) {
            throw new IllegalArgumentException("AI config must define summaryModel");
        }
        ModelConfig summaryModel = config.models().get(summaryModelKey);
        if (summaryModel == null) {
            throw new IllegalArgumentException("summaryModel references missing model: " + summaryModelKey);
        }
        if (summaryModel.contextLength() == null || summaryModel.contextLength() <= 0) {
            throw new IllegalArgumentException("summaryModel must define a positive contextLength: " + summaryModelKey);
        }
        if (StringUtils.hasText(config.defaultModel()) && !config.models().containsKey(config.defaultModel())) {
            throw new IllegalArgumentException("defaultModel references missing model: " + config.defaultModel());
        }
        String planningModelKey = config.resolvedPlanningModelKey();
        ModelConfig planningModel = config.models().get(planningModelKey);
        if (planningModel == null) {
            throw new IllegalArgumentException("planningModel references missing model: " + planningModelKey);
        }
        if (planningModel.contextLength() == null || planningModel.contextLength() <= 0) {
            throw new IllegalArgumentException("planningModel must define a positive contextLength: " + planningModelKey);
        }
        if (StringUtils.hasText(config.compactionModel())) {
            String compactionModelKey = config.resolvedCompactionModelKey();
            ModelConfig compactionModel = config.models().get(compactionModelKey);
            if (compactionModel == null) {
                throw new IllegalArgumentException("compactionModel references missing model: " + compactionModelKey);
            }
            if (compactionModel.contextLength() == null || compactionModel.contextLength() <= 0) {
                throw new IllegalArgumentException("compactionModel must define a positive contextLength: " + compactionModelKey);
            }
        }
        int bufferPercent = config.resolvedContextBufferPercent();
        if (bufferPercent < 1 || bufferPercent > 50) {
            throw new IllegalArgumentException("contextBufferPercent must be between 1 and 50");
        }
        return config;
    }

    private static AgentConfig resolveSystemPrompt(Path configDirectory, String agentName, AgentConfig agentConfig) {
        if (agentConfig == null || !StringUtils.hasText(agentConfig.systemPrompt())) {
            throw new IllegalArgumentException("Agent '" + agentName + "' must configure a systemPrompt file path");
        }

        Path promptPath = Path.of(agentConfig.systemPrompt());
        Path resolvedPromptPath = promptPath.isAbsolute()
            ? promptPath.normalize()
            : configDirectory.resolve(promptPath).normalize();
        if (!Files.isRegularFile(resolvedPromptPath)) {
            throw new IllegalArgumentException(
                "Agent '" + agentName + "' systemPrompt file does not exist: " + resolvedPromptPath
            );
        }

        try {
            return new AgentConfig(
                agentConfig.model(),
                Files.readString(resolvedPromptPath),
                agentConfig.approvedTools(),
                agentConfig.allowedShellCommands()
            );
        } catch (IOException e) {
            throw new IllegalArgumentException(
                "Agent '" + agentName + "' systemPrompt file cannot be read: " + resolvedPromptPath,
                e
            );
        }
    }
}
