package io.mindspice.core;

/**
 * Framework-owned tool callback — no Spring AI dependency.
 * Each tool exposes its definition and a JSON-in/JSON-out call method.
 */
public interface ToolCallback {

    @NonNull
    ToolDefinition getToolDefinition();

    @NonNull
    String call(@NonNull String toolInput);
}
