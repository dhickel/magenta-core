package io.mindspice.magenta2.ai.chat.tool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class ChatToolRegistry {
    private static final String WILDCARD = "*";

    private final Map<String, ToolCallback> toolsByName;
    private final List<ToolCallback> allTools;

    public ChatToolRegistry(List<ToolCallback> toolCallbacks, List<ToolCallbackProvider> toolCallbackProviders) {
        List<ToolCallback> callbacks = new ArrayList<>();
        if (toolCallbacks != null) {
            callbacks.addAll(toolCallbacks);
        }
        if (toolCallbackProviders != null) {
            toolCallbackProviders.stream()
                .filter(provider -> provider.getToolCallbacks() != null)
                .flatMap(provider -> Arrays.stream(provider.getToolCallbacks()))
                .forEach(callbacks::add);
        }
        this.allTools = List.copyOf(callbacks);
        this.toolsByName = callbacks.stream()
            .collect(Collectors.toUnmodifiableMap(
                callback -> callback.getToolDefinition().name(),
                Function.identity(),
                (left, right) -> left
            ));
    }

    public List<ToolCallback> resolveApprovedTools(List<String> approvedToolNames) {
        if (approvedToolNames == null || approvedToolNames.isEmpty()) {
            return List.of();
        }
        if (approvedToolNames.contains(WILDCARD)) {
            return allTools;
        }
        List<ToolCallback> resolved = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String toolName : approvedToolNames) {
            ToolCallback callback = toolsByName.get(toolName);
            if (callback == null) {
                unknown.add(toolName);
            } else {
                resolved.add(callback);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Approved tool names are not registered: " + String.join(", ", unknown));
        }
        return List.copyOf(resolved);
    }

    public List<ToolCallback> resolveApprovedTools(List<String> approvedToolNames, List<String> allowedToolNames) {
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return List.of();
        }
        List<ToolCallback> approved = resolveApprovedTools(approvedToolNames);
        return approved.stream()
            .filter(callback -> allowedToolNames.contains(callback.getToolDefinition().name()))
            .toList();
    }

    public void validateToolNames(List<String> approvedToolNames) {
        resolveApprovedTools(approvedToolNames);
    }
}
