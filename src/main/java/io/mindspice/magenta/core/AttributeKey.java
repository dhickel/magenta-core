package io.mindspice.magenta.core;

/**
 * Typed key for storing and retrieving values in a {@link TurnContext} attribute map.
 *
 * @param <T>  the type of the value associated with this key
 * @param name a unique name for this key
 * @param type the class token for the value type
 */
public record AttributeKey<T>(@NonNull String name, @NonNull Class<T> type) {

    public static <T> AttributeKey<T> of(String name, Class<T> type) {
        return new AttributeKey<>(name, type);
    }
}
