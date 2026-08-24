package io.mindspice.magenta.core;

import java.util.Map;

/**
 * Framework-native message — no Spring AI dependency.
 *
 * @param role       "system", "user", "assistant", or "tool"
 * @param text       message content
 * @param toolName   non-null for tool messages, null otherwise
 * @param toolCallId non-null for tool messages, null otherwise
 * @param metadata   extension metadata (empty map, never null when stored)
 */
public record TurnMessage(
    @NonNull String role,
    @NonNull String text,
    @Nullable String toolName,
    @Nullable String toolCallId,
    @Nullable Map<String, String> metadata
) {
    public static TurnMessage user(String text) {
        return new TurnMessage("user", text, null, null, Map.of());
    }

    public static TurnMessage assistant(String text) {
        return new TurnMessage("assistant", text, null, null, Map.of());
    }

    public static TurnMessage system(String text) {
        return new TurnMessage("system", text, null, null, Map.of());
    }

    public static TurnMessage tool(String toolName, String toolCallId, String resultText) {
        return new TurnMessage("tool", resultText, toolName, toolCallId, Map.of());
    }
}
