package io.mindspice.core;

import java.util.List;

/**
 * The full result of a completed turn.
 *
 * @param sessionId      owning session
 * @param turnId         unique turn identifier
 * @param message        final assistant message text (nullable when no terminal text was produced)
 * @param contextUsage   token usage at turn completion
 * @param diagnostic     full turn diagnostic
 * @param toolActivities record of each tool executed during the turn
 */
public record TurnResult(
    @NonNull String sessionId,
    @NonNull String turnId,
    @Nullable String message,
    @NonNull ContextUsage contextUsage,
    @NonNull TurnDiagnostic diagnostic,
    @NonNull List<ToolActivityData> toolActivities
) {}
