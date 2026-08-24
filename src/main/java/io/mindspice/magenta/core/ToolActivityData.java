package io.mindspice.magenta.core;

/**
 * Summary of a single tool execution within a turn.
 *
 * @param toolName   the tool's name
 * @param toolCallId the model-generated call identifier
 * @param status     {@link ToolActivityStatus#COMPLETED} or {@link ToolActivityStatus#FAILED}
 * @param summary    human-readable result summary
 */
public record ToolActivityData(
    @NonNull String toolName,
    @NonNull String toolCallId,
    @NonNull ToolActivityStatus status,
    @NonNull String summary
) {}
