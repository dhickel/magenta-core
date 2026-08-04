package io.mindspice.magenta2.ai.chat.service;

import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.task.TaskRun;

public record TaskExecutionResult(String conversationId, TaskRun run, ChatResponse.MsgResponse response) {
}
