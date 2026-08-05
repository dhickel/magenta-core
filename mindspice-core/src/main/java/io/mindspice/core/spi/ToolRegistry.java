package io.mindspice.core.spi;

import io.mindspice.core.NonNull;
import io.mindspice.core.ToolCallback;
import io.mindspice.core.ToolDefinition;
import io.mindspice.core.TurnContext;

import java.util.List;

/**
 * Registry of tools available to the turn engine. Supports two-stage
 * registration: public tools visible to callers and framework-internal
 * tools hidden from {@link #definitions()}.
 */
public interface ToolRegistry {

    void register(@NonNull ToolCallback tool);

    void registerFrameworkTool(@NonNull ToolCallback tool);

    void registerToolBean(@NonNull Object bean);

    void unregister(@NonNull String toolName);

    @NonNull
    List<ToolDefinition> definitions();

    @NonNull
    List<ToolCallback> resolveForTurn(@NonNull TurnContext context);
}
