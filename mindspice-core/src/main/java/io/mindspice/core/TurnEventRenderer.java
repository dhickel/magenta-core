package io.mindspice.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.function.Function;

/**
 * Transport renderers for the {@link TurnEvent} sealed hierarchy.
 *
 * <p>Provides JSON Lines and SSE encodings. All
 * serialization goes through Jackson with polymorphic type
 * handling driven by the {@code @JsonTypeInfo} / {@code @JsonSubTypes}
 * annotations on {@link TurnEvent}.</p>
 *
 * <p>All methods are static; the class is not instantiable.</p>
 */
public final class TurnEventRenderer {

    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    private TurnEventRenderer() {
        // utility class
    }

    /**
     * Create a JSON Lines renderer backed by the supplied mapper.
     *
     * <p>Each event is serialized as a single line:
     * {@code mapper.writeValueAsString(event) + "\n"}.</p>
     *
     * @param mapper a Jackson {@link ObjectMapper} (must be configured
     *               to handle the {@link TurnEvent} polymorphic hierarchy
     *               or rely on its built-in annotations)
     * @return a function that converts a {@link TurnEvent} to a
     *         JSON Lines string
     */
    public static Function<TurnEvent, String> json(ObjectMapper mapper) {
        return event -> {
            try {
                return mapper.writeValueAsString(event) + "\n";
            } catch (JsonProcessingException e) {
                throw new TurnLoopException("Failed to serialize TurnEvent to JSON", e);
            }
        };
    }

    /**
     * Create an SSE renderer backed by the supplied mapper.
     *
     * <p>Each event is wrapped as:
     * {@code "data: " + json(event) + "\n\n"}.</p>
     *
     * @param mapper a Jackson {@link ObjectMapper} configured for
     *               {@link TurnEvent} polymorphism
     * @return a function that converts a {@link TurnEvent} to an
     *         SSE {@code data:} frame
     */
    public static Function<TurnEvent, String> sse(ObjectMapper mapper) {
        Function<TurnEvent, String> jsonFn = json(mapper);
        return event -> "data: " + jsonFn.apply(event).stripTrailing() + "\n\n";
    }

    /**
     * Create an SSE renderer using a shared internal {@link ObjectMapper}.
     *
     * <p>The shared mapper relies on the Jackson annotations present on
     * {@link TurnEvent} ({@code @JsonTypeInfo} / {@code @JsonSubTypes})
     * to handle all 8 subtypes correctly.</p>
     *
     * @return a function that converts a {@link TurnEvent} to an
     *         SSE {@code data:} frame
     */
    public static Function<TurnEvent, String> sse() {
        return sse(SHARED_MAPPER);
    }
}
