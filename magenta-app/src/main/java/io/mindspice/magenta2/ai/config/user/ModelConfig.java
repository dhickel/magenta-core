package io.mindspice.magenta2.ai.config.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.lang.Nullable;

public record ModelConfig(
    @JsonProperty("remoteModelName") String remoteModelName,
    @JsonProperty("remoteEndpoint") String remoteEndpoint,
    @JsonProperty("endpointType") EndpointType endpointType,
    @JsonProperty("contextLength") Integer contextLength,
    @JsonProperty("thinkLevel") @Nullable Integer thinkLevel,
    @JsonProperty("apiKey") @Nullable String apiKey
) {
}
