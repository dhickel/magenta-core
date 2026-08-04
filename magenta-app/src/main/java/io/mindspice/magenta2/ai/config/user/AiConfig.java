package io.mindspice.magenta2.ai.config.user;

import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

public record AiConfig(
    String defaultAgent,
    String defaultModel,
    String summaryModel,
    @JsonProperty("summeryModel") String summeryModel,
    String planningModel,
    @JsonProperty("compactionModel") String compactionModel,
    Integer contextBufferPercent,
    Path dataRoot,
    WebSearchConfig webSearch,
    Map<String, ModelConfig> models,
    Map<String, AgentConfig> agents,
    Boolean unsafeAllowWildcardShellCommands
) {

    public int resolvedContextBufferPercent() {
        return contextBufferPercent == null ? 10 : contextBufferPercent;
    }

    public String resolvedDefaultModelKey() {
        return StringUtils.hasText(defaultModel) ? defaultModel : null;
    }

    public String resolvedSummaryModelKey() {
        return StringUtils.hasText(summaryModel) ? summaryModel : summeryModel;
    }

    public String resolvedPlanningModelKey() {
        return planningModel == null || planningModel.isBlank() ? "local-gemma-26b" : planningModel;
    }

    public String resolvedCompactionModelKey() {
        return StringUtils.hasText(compactionModel) ? compactionModel : resolvedSummaryModelKey();
    }

    public boolean unsafeAllowWildcardShellCommandsEnabled() {
        return Boolean.TRUE.equals(unsafeAllowWildcardShellCommands);
    }

    public AiConfig(
        String defaultAgent,
        String defaultModel,
        String summaryModel,
        String planningModel,
        String compactionModel,
        Integer contextBufferPercent,
        Path dataRoot,
        WebSearchConfig webSearch,
        Map<String, ModelConfig> models,
        Map<String, AgentConfig> agents
    ) {
        this(defaultAgent, defaultModel, summaryModel, null, planningModel, compactionModel,
            contextBufferPercent, dataRoot, webSearch, models, agents, false);
    }

    public AiConfig(
        String defaultAgent,
        String summaryModel,
        String planningModel,
        Integer contextBufferPercent,
        Path dataRoot,
        Map<String, ModelConfig> models,
        Map<String, AgentConfig> agents
    ) {
        this(defaultAgent, null, summaryModel, null, planningModel, null, contextBufferPercent, dataRoot, null, models, agents, false);
    }

    public AiConfig(
        String defaultAgent,
        String summaryModel,
        Integer contextBufferPercent,
        Path dataRoot,
        WebSearchConfig webSearch,
        Map<String, ModelConfig> models,
        Map<String, AgentConfig> agents
    ) {
        this(defaultAgent, null, summaryModel, null, null, null, contextBufferPercent, dataRoot, webSearch, models, agents, false);
    }

    public AiConfig(
        String defaultAgent,
        String summaryModel,
        Integer contextBufferPercent,
        Path dataRoot,
        Map<String, ModelConfig> models,
        Map<String, AgentConfig> agents
    ) {
        this(defaultAgent, null, summaryModel, null, null, null, contextBufferPercent, dataRoot, null, models, agents, false);
    }

}
