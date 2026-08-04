package io.mindspice.magenta2.api.web;

import java.util.List;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ModelController {
    private final AiConfig aiUserConfig;

    public ModelController(AiConfig aiUserConfig) {
        this.aiUserConfig = aiUserConfig;
    }

    @GetMapping("/api/models")
    public List<ModelSummary> models() {
        return aiUserConfig.models().entrySet().stream()
            .map(entry -> new ModelSummary(
                entry.getKey(),
                entry.getValue().remoteModelName(),
                entry.getValue().endpointType() != null ? entry.getValue().endpointType().name() : null,
                entry.getValue().contextLength()
            ))
            .toList();
    }

    public record ModelSummary(
        String key,
        String remoteModelName,
        String provider,
        Integer contextLength
    ) {}
}
