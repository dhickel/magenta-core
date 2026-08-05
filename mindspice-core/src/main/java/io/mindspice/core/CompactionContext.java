package io.mindspice.core;

import java.util.List;

/**
 * Input to a compaction strategy — the messages that should be summarized.
 *
 * @param sessionId         owning session
 * @param messagesToCompact oldest messages to compact into a summary
 * @param currentUsage      token usage at the time compaction is triggered
 */
public record CompactionContext(
    @NonNull String sessionId,
    @NonNull List<TurnMessage> messagesToCompact,
    @NonNull ContextUsage currentUsage
) {}
