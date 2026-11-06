package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Caller-visible, disclosure-safe progress facts for one tool invocation. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolActivityData.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = ToolActivityData.Failed.class, name = "failed")
})
public sealed interface ToolActivityData
    permits ToolActivityData.Completed, ToolActivityData.Failed {

    @NonNull
    String toolName();

    @NonNull
    String toolCallId();

    @NonNull
    String summary();

    /** A tool invocation completed with a caller-visible summary. */
    record Completed(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String summary
    ) implements ToolActivityData {
        public Completed {
            toolName = ResponseValidation.nonBlank(toolName, "toolName");
            toolCallId = ResponseValidation.nonBlank(toolCallId, "toolCallId");
            summary = ResponseValidation.nonBlank(summary, "summary");
        }
    }

    /** A tool invocation failed without exposing raw arguments or exceptions. */
    record Failed(
        @NonNull String toolName,
        @NonNull String toolCallId,
        @NonNull String summary,
        @NonNull ToolFailureCategory category
    ) implements ToolActivityData {
        public Failed {
            toolName = ResponseValidation.nonBlank(toolName, "toolName");
            toolCallId = ResponseValidation.nonBlank(toolCallId, "toolCallId");
            summary = ResponseValidation.nonBlank(summary, "summary");
            category = ResponseValidation.required(category, "category");
        }
    }
}
