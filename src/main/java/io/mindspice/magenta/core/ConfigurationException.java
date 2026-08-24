package io.mindspice.magenta.core;

import java.util.Map;

/**
 * Thrown when framework configuration is invalid.
 */
public final class ConfigurationException extends TurnLoopException {

    private final Map<String, String> details;

    public ConfigurationException(@NonNull String message,
                                  @NonNull Map<String, String> details) {
        this(message, details, null);
    }

    public ConfigurationException(@NonNull String message,
                                  @NonNull Map<String, String> details,
                                  Throwable cause) {
        super(message, cause);
        this.details = Map.copyOf(details);
    }

    @NonNull
    public Map<String, String> details() {
        return details;
    }
}
