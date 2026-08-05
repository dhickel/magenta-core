package io.mindspice.magenta2.ai.config.user;

import java.nio.file.Path;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.util.StringUtils;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiConfig(
    String defaultModel,
    String summaryModel,
    @JsonProperty("summeryModel") String summeryModel,
    Integer contextBufferPercent,
    Path dataRoot,
    WebSearchConfig webSearch,
    Map<String, ModelConfig> models
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
}
