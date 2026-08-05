package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Set;

/**
 * Resolves which tool callbacks are available for a turn, filtered by
 * approved-tool configuration.
 */
public class ToolAccessPolicy {

    private static final List<String> NORMAL_BLOCKED_TOOLS = List.of(
        "plan_update", "plan_set_goal", "plan_set_task", "plan_put_item", "plan_delete_item",
        "ask_user_questions", "plan_ready_for_approval", "plan_report", "plan_complete",
        "task_set_goal", "task_set_task", "task_put_item", "task_delete_item", "task_ready_for_approval",
        "task_report", "task_complete"
    );

    private final ChatToolRegistry chatToolRegistry;
    private final Set<String> allowedTools;
    private final Set<String> blockedTools;

    public ToolAccessPolicy(ChatToolRegistry chatToolRegistry) {
        this(chatToolRegistry, null, null);
    }

    ToolAccessPolicy(
        ChatToolRegistry chatToolRegistry,
        Set<String> allowedTools,
        Set<String> blockedTools
    ) {
        this.chatToolRegistry = chatToolRegistry;
        this.allowedTools = allowedTools;
        this.blockedTools = blockedTools;
    }

    // ── Static factories ──

    public static ToolAccessPolicy allowAll() {
        return new ToolAccessPolicy(null, null, null);
    }

    public static ToolAccessPolicy allowList(Set<String> toolNames) {
        return new ToolAccessPolicy(null, Set.copyOf(toolNames), null);
    }

    public static ToolAccessPolicy blockList(Set<String> toolNames) {
        return new ToolAccessPolicy(null, null, Set.copyOf(toolNames));
    }

    /**
     * Resolves approved tools from the given tool names for normal mode.
     */
    public List<ToolCallback> filterToolsByMode(List<String> approvedToolNames) {
        return filterToolsByMode(approvedToolNames, null);
    }

    public List<ToolCallback> filterToolsByMode(List<String> approvedToolNames, String conversationId) {
        if (chatToolRegistry == null) {
            return List.of();
        }
        return chatToolRegistry.resolveApprovedTools(approvedToolNames).stream()
            .filter(callback -> !NORMAL_BLOCKED_TOOLS.contains(callback.getToolDefinition().name()))
            .toList();
    }
}
