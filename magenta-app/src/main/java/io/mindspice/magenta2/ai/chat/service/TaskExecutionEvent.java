package io.mindspice.magenta2.ai.chat.service;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;

public record TaskExecutionEvent(String event, String conversationId, String runId, ChatMessage message, TaskRun run) {
    public static TaskExecutionEvent started(String conversationId, String runId) {
        return new TaskExecutionEvent("started", conversationId, runId, null, null);
    }

    public static TaskExecutionEvent message(String conversationId, String runId, ChatMessage message) {
        String event = message != null && message.toolActivity() != null ? "tool" : "progress";
        return new TaskExecutionEvent(event, conversationId, runId, message, null);
    }

    public static TaskExecutionEvent finished(String conversationId, TaskRun run) {
        String event = run.status() == TaskRunStatus.COMPLETED ? "completed" : "failed";
        return new TaskExecutionEvent(event, conversationId, run.id(), null, run);
    }
}
