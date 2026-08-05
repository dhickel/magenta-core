package io.mindspice.core.spi;

import io.mindspice.core.ContextUsage;
import io.mindspice.core.NonNull;
import io.mindspice.core.Nullable;
import io.mindspice.core.TurnMessage;

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
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String arguments,
        @NonNull String resultSummary,
        boolean success,
        long durationMs
    ) {}

    record TurnError(
        @NonNull String summary,
        @Nullable String stackTrace,
        @Nullable String model
    ) {}
}
