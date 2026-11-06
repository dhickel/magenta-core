package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Typed result of one provider-neutral tool callback invocation.
 *
 * <p>Success output may be empty, while failures carry only safe caller/model
 * visible text and the framework-owned failure category.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolResult.Success.class, name = "success"),
    @JsonSubTypes.Type(value = ToolResult.Failure.class, name = "failure")
})
public sealed interface ToolResult permits ToolResult.Success, ToolResult.Failure {

    /** A completed callback result. */
    record Success(@NonNull String output) implements ToolResult {
        public Success {
            output = ResponseValidation.required(output, "output");
        }
    }

    /** A safe, typed callback failure. */
    record Failure(
        @NonNull String message,
        @NonNull ToolFailureCategory category
    ) implements ToolResult {
        public Failure {
            message = ResponseValidation.nonBlank(message, "message");
            category = ResponseValidation.required(category, "category");
        }
    }
}
