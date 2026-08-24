package io.mindspice.magenta.core;

import java.util.List;
import java.util.Map;

/**
 * A request to execute a single turn against a session.
 *
 * @param sessionId target session
 * @param message   user message text
 * @param model     model alias, null means default model
 * @param toolNames explicit tool names, null means resolve via ToolAccessPolicy
 * @param stream    true if caller expects streaming ({@code Flux<TurnEvent>})
 * @param metadata  extension metadata
 */
public record TurnRequest(
    @NonNull String sessionId,
    @NonNull String message,
    @Nullable String model,
    @Nullable List<String> toolNames,
    boolean stream,
    @Nullable Map<String, String> metadata
) {
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, message, null, null, false, Map.of());
    }
}
