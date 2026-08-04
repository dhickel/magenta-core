package io.mindspice.magenta2.ai.orchestration.workflow;

public interface WorkflowExecutionObserver {
    WorkflowExecutionObserver NOOP = (workflowRunId, nodeKey, conversationId) -> { };

    void taskConversationStarted(String workflowRunId, String nodeKey, String conversationId);
}
