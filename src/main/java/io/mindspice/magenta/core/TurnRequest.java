package io.mindspice.magenta.core;

import java.util.List;
import java.util.Map;

/**
 * Caller intent for one turn submission.
 *
 * @param sessionId target session
 * @param message user message text
 * @param model explicitly selected model, or {@code null} for normal resolution
 * @param toolNames explicitly selected tools, or {@code null} for normal resolution
 * @param metadata caller metadata retained with queued intent
 */
public record TurnRequest(
    @NonNull String sessionId,
    @NonNull String message,
    @Nullable String model,
    @Nullable List<String> toolNames,
    @Nullable Map<String, String> metadata
) {
    public TurnRequest {
        sessionId = ResponseValidation.nonBlank(sessionId, "sessionId");
        message = ResponseValidation.nonBlank(message, "message");
        if (model != null) {
            model = ResponseValidation.nonBlank(model, "model");
        }
        if (toolNames != null) {
            toolNames = List.copyOf(toolNames);
            for (int index = 0; index < toolNames.size(); index++) {
                ResponseValidation.nonBlank(toolNames.get(index),
                    "toolNames[" + index + "]");
            }
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /** Creates a request using normal model and tool resolution. */
    public static TurnRequest of(String sessionId, String message) {
        return new TurnRequest(sessionId, message, null, null, Map.of());
    }
}
