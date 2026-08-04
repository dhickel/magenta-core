package io.mindspice.magenta2.api.web.selector;

import java.util.Map;

public record EntitySelectorConfig(
    String name,
    EntityKind kind,
    String currentValue,
    String label,
    String placeholder,
    boolean required,
    Map<String, String> contextParams
) {
    public EntitySelectorConfig {
        contextParams = contextParams == null ? Map.of() : Map.copyOf(contextParams);
    }
}
