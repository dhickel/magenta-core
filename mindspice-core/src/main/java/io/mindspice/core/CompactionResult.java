package io.mindspice.core;

/**
 * The result of a compaction operation.
 *
 * @param summary            compacted summary text
 * @param messagesCompacted  number of original messages replaced by the summary
 * @param summaryTokenCount  estimated token count of the summary
 */
public record CompactionResult(
    @NonNull String summary,
    int messagesCompacted,
    int summaryTokenCount
) {}
