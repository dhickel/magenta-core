package io.mindspice.magenta.core;

import io.mindspice.magenta.core.ToolCallback;
import io.mindspice.magenta.core.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Objects;

/**
 * Adapts a provider-neutral Magenta callback at the Spring AI boundary.
 *
 * <p>The Magenta engine owns tool execution and dispatches every tool call
 * through the context-aware {@link ToolCallback#call(String, TurnContext)}
 * variant, so this adapter is primarily a schema exposure for provider
 * option construction. When an embedding application wires a caller-supplied
 * {@code ToolCallingManager} that executes tools inside the provider call,
 * the two-argument Spring AI entry point forwards a Magenta {@link TurnContext}
 * carried in the tool context map (see {@link #TURN_CONTEXT_KEY}).</p>
 */
record SpringAiToolCallbackAdapter(ToolCallback delegate)
    implements org.springframework.ai.tool.ToolCallback {

    /**
     * Key under which the current Magenta {@link TurnContext} is carried in a
     * Spring AI {@link ToolContext} when an embedding application executes
     * tools through a caller-supplied tool calling manager.
     */
    static final String TURN_CONTEXT_KEY = "magenta.turn_context";

    SpringAiToolCallbackAdapter {
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
        ToolResult result;
        try {
            result = ToolResultSupport.nonNull(delegate.call(toolInput));
        } catch (Throwable failure) {
            result = ToolResultSupport.fromThrowable(
                failure, delegate.getToolDefinition().name());
        }
        return ToolResultSupport.modelText(result);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext().get(TURN_CONTEXT_KEY)
            instanceof TurnContext turnContext) {
            ToolResult result;
            try {
                result = ToolResultSupport.nonNull(delegate.call(toolInput, turnContext));
            } catch (Throwable failure) {
                result = ToolResultSupport.fromThrowable(
                    failure, delegate.getToolDefinition().name());
            }
            return ToolResultSupport.modelText(result);
        }
        return call(toolInput);
    }
}
