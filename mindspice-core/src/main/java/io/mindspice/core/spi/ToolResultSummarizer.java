package io.mindspice.core.spi;

import io.mindspice.core.NonNull;

/**
 * Summarizes raw tool execution results for inclusion in the model transcript.
 */
public interface ToolResultSummarizer {

    @NonNull
    SummarizeResult summarize(@NonNull ToolExecution execution);

    record ToolExecution(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String arguments,
        @NonNull String rawResult
    ) {}

    record SummarizeResult(@NonNull String summary, boolean truncated) {}
}
