package io.mindspice.magenta.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;
import java.util.function.Function;

/**
 * JSON-only renderer for the sealed {@link TurnResponse} protocol.
 * Transport framing such as SSE is owned by embedding applications.
 */
public final class TurnResponseRenderer {

    private static final ObjectMapper SHARED_MAPPER =
        new ObjectMapper().findAndRegisterModules();

    private TurnResponseRenderer() {
    }

    /**
     * Creates a renderer backed by the supplied Jackson mapper.
     *
     * @param mapper mapper used for response serialization
     * @return a function returning one response JSON document
     */
    public static Function<TurnResponse, String> json(@NonNull ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return response -> {
            try {
                return mapper.writeValueAsString(response);
            } catch (JsonProcessingException e) {
                throw new TurnLoopException("Failed to serialize TurnResponse to JSON", e);
            }
        };
    }

    /** Creates a renderer using the framework's module-aware mapper. */
    public static Function<TurnResponse, String> json() {
        return json(SHARED_MAPPER);
    }
}
