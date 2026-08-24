package io.mindspice.magenta.core.internal.provider;

import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;

import java.util.Objects;

/** Adapts a provider-neutral Magenta callback at the Spring AI boundary. */
public record SpringAiToolCallbackAdapter(ToolCallback delegate)
    implements org.springframework.ai.tool.ToolCallback {

    public SpringAiToolCallbackAdapter {
        Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        ToolDefinition definition = Objects.requireNonNull(delegate.getToolDefinition(),
            "tool definition");
        return org.springframework.ai.tool.definition.ToolDefinition.builder()
            .name(definition.name())
            .description(definition.description())
            .inputSchema(definition.inputSchema())
            .build();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }
}
