package io.mindspice.magenta2.ai.config.user;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebSearchConfig(
    @JsonProperty("enabled") Boolean enabled,
    @JsonProperty("provider") String provider,
    @JsonProperty("baseUrl") String baseUrl
) {
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
