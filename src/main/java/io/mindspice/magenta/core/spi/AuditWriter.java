package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.ContextUsage;
import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.Nullable;
import io.mindspice.magenta.core.ToolActivityData;
import io.mindspice.magenta.core.TurnMessage;

/**
 * Fire-and-forget audit writer. All methods return {@code void} —
 * implementations must catch and suppress their own exceptions.
 */
public interface AuditWriter {

    void recordUserMessage(@NonNull String sessionId, @NonNull TurnMessage message);

    void recordAssistantMessage(@NonNull String sessionId, @NonNull TurnMessage message);

    void recordToolExecution(@NonNull String sessionId, @NonNull ToolExecutionRecord record);

    void recordContextUsage(@NonNull String sessionId,
                            @NonNull ContextUsage usage,
                            @NonNull String model);

    void recordError(@NonNull String sessionId, @NonNull TurnError error);

    // ── Nested records ──

    record ToolExecutionRecord(
        @NonNull ToolActivityData activity,
        @NonNull String arguments,
        long durationMs
    ) {
        public ToolExecutionRecord {
            java.util.Objects.requireNonNull(activity, "activity");
            java.util.Objects.requireNonNull(arguments, "arguments");
            if (durationMs < 0) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
        }
    }

    record TurnError(
        @NonNull String summary,
        @Nullable String stackTrace,
        @Nullable String model
    ) {}
}
