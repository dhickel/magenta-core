package io.mindspice.magenta.core.spi;

import io.mindspice.magenta.core.NonNull;
import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.TurnContext;

import java.util.List;
import java.util.Set;

/**
 * Per-turn tool access policy. Implementations filter the set of
 * available tools based on the turn context.
 */
@FunctionalInterface
public interface ToolAccessPolicy {

    @NonNull
    List<ToolCallback> filterTools(@NonNull List<ToolCallback> availableTools,
                                   @NonNull TurnContext context);

    static ToolAccessPolicy allowAll() {
        return (tools, ctx) -> tools;
    }

    static ToolAccessPolicy allowList(@NonNull Set<String> toolNames) {
        return (tools, ctx) -> tools.stream()
            .filter(t -> toolNames.contains(t.getToolDefinition().name()))
            .toList();
    }

    static ToolAccessPolicy blockList(@NonNull Set<String> toolNames) {
        return (tools, ctx) -> tools.stream()
            .filter(t -> !toolNames.contains(t.getToolDefinition().name()))
            .toList();
    }
}
