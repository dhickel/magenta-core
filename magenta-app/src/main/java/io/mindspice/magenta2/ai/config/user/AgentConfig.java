package io.mindspice.magenta2.ai.config.user;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AgentConfig(
    @JsonProperty("model") String model,
    @JsonProperty("systemPrompt") String systemPrompt,
    @JsonProperty("approvedTools") List<String> approvedTools,
    @JsonProperty("allowedShellCommands") List<String> allowedShellCommands
) {
    public AgentConfig(String model, String systemPrompt, List<String> approvedTools) {
        this(model, systemPrompt, approvedTools, List.of());
    }
}
