package io.mindspice.magenta2.ai.chat.plan;

import java.util.ArrayList;
import java.util.List;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SavedPlanPlanningModelClient implements SavedPlanModelClient {
    private static final int MAX_MODEL_TOOL_LOOPS = 6;
    private static final List<String> SAVED_PLAN_TOOL_NAMES = List.of(
        "saved_plan_update_fields",
        "saved_plan_set_task",
        "saved_plan_put_item",
        "saved_plan_delete_item",
        "saved_plan_ask_user_questions",
        "saved_plan_ready_for_approval"
    );

    private final ChatModelRouter chatModelRouter;
    private final ChatToolRegistry toolRegistry;
    private final ToolCallingManager toolCallingManager;

    public SavedPlanPlanningModelClient(
        ChatModelRouter chatModelRouter,
        ChatToolRegistry toolRegistry,
        ToolCallingManager toolCallingManager
    ) {
        this.chatModelRouter = chatModelRouter;
        this.toolRegistry = toolRegistry;
        this.toolCallingManager = toolCallingManager;
    }

    public void runTurn(String planId, String model, String systemPrompt, String userMessage) {
        if (!StringUtils.hasText(planId)) {
            throw new IllegalArgumentException("planId is required");
        }
        if (!StringUtils.hasText(systemPrompt)) {
            throw new IllegalArgumentException("systemPrompt is required");
        }
        if (!StringUtils.hasText(userMessage)) {
            throw new IllegalArgumentException("userMessage is required");
        }
        List<ToolCallback> tools = toolRegistry.resolveApprovedTools(SAVED_PLAN_TOOL_NAMES, SAVED_PLAN_TOOL_NAMES);
        ToolCallingChatOptions options = toolOptions(model, tools);
        List<Message> messages = new ArrayList<>(List.of(
            new SystemMessage(systemPrompt),
            new UserMessage(userMessage)
        ));
        Prompt prompt = new Prompt(messages, options);

        PlanToolExecutionContext.set(new PlanToolContext(planId, PlanMode.TASK));
        try {
            int loops = 0;
            while (loops++ < MAX_MODEL_TOOL_LOOPS) {
                ChatResponse response = chatModelRouter.chatModel(model).call(prompt);
                if (response == null || !response.hasToolCalls()) {
                    return;
                }
                ToolExecutionResult toolResult = toolCallingManager.executeToolCalls(prompt, response);
                messages = new ArrayList<>(toolResult.conversationHistory());
                prompt = new Prompt(messages, options);
            }
        } finally {
            PlanToolExecutionContext.clear();
        }
    }

    private ToolCallingChatOptions toolOptions(String model, List<ToolCallback> tools) {
        ToolCallingChatOptions options = StringUtils.hasText(model)
            ? chatModelRouter.toolCallingOptions(model)
            : DefaultToolCallingChatOptions.builder().build();
        return options.mutate().toolCallbacks(tools).build();
    }
}
