package io.mindspice.magenta2.ai.config.user;

import java.io.IOException;
import java.nio.file.Path;

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
        return validate(config);
    }

    private static AiConfig validate(AiConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AI config is required");
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
        int bufferPercent = config.resolvedContextBufferPercent();
        if (bufferPercent < 1 || bufferPercent > 50) {
            throw new IllegalArgumentException("contextBufferPercent must be between 1 and 50");
        }
        return config;
    }
}
