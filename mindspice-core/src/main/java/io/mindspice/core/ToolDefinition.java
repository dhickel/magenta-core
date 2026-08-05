package io.mindspice.core;

/**
 * Framework-owned tool definition.
 *
 * @param name        unique tool name
 * @param description human-readable description
 * @param inputSchema JSON Schema string describing the tool's input
 */
public record ToolDefinition(
    @NonNull String name,
    @NonNull String description,
    @NonNull String inputSchema
) {}
