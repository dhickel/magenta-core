package io.mindspice.magenta2.api.web.selector;

import java.util.Map;

public record SelectorQuery(
    String q,
    int limit,
    String current,
    boolean includeUnavailable,
    Map<String, String> context
) {
    public SelectorQuery {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
