package io.mindspice.magenta.core.example;

import io.mindspice.magenta.core.Tool;
import io.mindspice.magenta.core.ToolParam;

/**
 * Simple echo tool for testing and demonstration.
 *
 * <p>Returns the input message verbatim. The registry wraps this ordinary
 * return value as {@code ToolResult.Success}. Not auto-registered — embedding
 * applications must instantiate and register this class explicitly with a
 * {@code ToolRegistry}.</p>
 */
public final class EchoTool {

    /**
     * Echoes the input message back to the caller.
     *
     * @param message the message to echo
     * @return the same message, unmodified
     */
    @Tool(name = "echo", description = "Echoes the input message back to the caller")
    public String echo(@ToolParam(name = "message", description = "Message to echo") String message) {
        return message == null ? "" : message;
    }
}
