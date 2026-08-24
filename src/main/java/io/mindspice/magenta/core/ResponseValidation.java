package io.mindspice.magenta.core;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Package-private validation shared by the public turn-response records. */
final class ResponseValidation {

    private ResponseValidation() {
    }

    static String nonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    static Instant timestamp(Instant value, String name) {
        return required(value, name);
    }

    static <T> List<T> nonEmptyList(List<T> values, String name) {
        required(values, name);
        if (values.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        return List.copyOf(values);
    }

    static List<String> nonBlankList(List<String> values, String name) {
        List<String> copy = nonEmptyList(values, name);
        for (int index = 0; index < copy.size(); index++) {
            nonBlank(copy.get(index), name + "[" + index + "]");
        }
        return copy;
    }

    static void ordered(Instant startedAt, Instant completedAt,
                        String completedName) {
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                completedName + " must not be before startedAt");
        }
    }
}
