package io.mindspice.magenta.core;

/**
 * Framework-owned tool callback — no Spring AI dependency.
 * Each tool exposes its definition and a JSON-in/typed-result call method.
 *
 * <p>Callbacks that need turn-scoped context (request metadata, session,
 * execution origin) may override {@link #call(String, TurnContext)}; the
 * engine dispatches through the context-aware variant whenever it runs a
 * tool. Existing one-argument callbacks remain fully compatible because the
 * default context-aware variant simply delegates to the legacy method.</p>
 */
public interface ToolCallback {

    @NonNull
    ToolDefinition getToolDefinition();

    @NonNull
    ToolResult call(@NonNull String toolInput);

    /**
     * Context-aware invocation. The default preserves the legacy
     * one-argument contract; context-aware callbacks override this method
     * to inspect the current turn's request metadata and state.
     */
    @NonNull
    default ToolResult call(@NonNull String toolInput, @NonNull TurnContext context) {
        return call(toolInput);
    }
}
