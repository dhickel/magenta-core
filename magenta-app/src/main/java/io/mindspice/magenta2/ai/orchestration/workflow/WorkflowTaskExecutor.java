package io.mindspice.magenta2.ai.orchestration.workflow;

import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Executes workflow TASK nodes through the same model-backed task path used by
 * durable orchestration assignments.
 */
@Service
public class WorkflowTaskExecutor {
    private final ChatService chatService;

    public WorkflowTaskExecutor(ChatService chatService) {
        this.chatService = chatService;
    }

    public TaskRun execute(
        String taskId,
        Map<String, Object> inputValues,
        String conversationId,
        String modelOverride
    ) {
        String resolvedConversationId = conversationId == null || conversationId.isBlank()
            ? UUID.randomUUID().toString()
            : conversationId;
        return chatService.executeTaskBlocking(
            taskId,
            inputValues == null ? Map.of() : inputValues,
            resolvedConversationId,
            modelOverride
        ).run();
    }

    public boolean succeeded(TaskRunStatus status) {
        return status == TaskRunStatus.COMPLETED;
    }
}
