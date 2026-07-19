package io.mindspice.magenta2.api.web;

import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.service.TaskExecutionEvent;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunResult;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;

class TaskStreamSupportTest {

    @Test
    void orchestrationRunEventsEmitsStartedAndCompleted() {
        OrchestrationRunResult result = new OrchestrationRunResult(
            new WorkAssignment("assign-1", "agent-1", "job-1", null,
                AssignmentType.TASK_RUN, 0, OrchestrationStatus.COMPLETED, null,
                null, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                null, null, null, null, null, null, null),
            "run-1", Map.of("output", "value")
        );

        List<SsePayload> events = TaskStreamSupport.orchestrationRunEvents("task-1", result)
            .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0).name()).isEqualTo("started");
        assertThat(events.get(1).name()).isEqualTo("completed");
    }

    @Test
    void orchestrationRunEventsEmitsFailedOnNonCompletedStatus() {
        OrchestrationRunResult result = new OrchestrationRunResult(
            new WorkAssignment("assign-2", "agent-1", "job-1", null,
                AssignmentType.TASK_RUN, 0, OrchestrationStatus.FAILED, null,
                null, 0, Map.of(), Map.of(), Map.of(), Map.of(),
                "error occurred", null, null, null, null, null, null),
            "run-2", Map.of()
        );

        List<SsePayload> events = TaskStreamSupport.orchestrationRunEvents("task-1", result)
            .collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(1).name()).isEqualTo("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(1).data();
        assertThat(payload).containsEntry("error", "error occurred");
    }

    @Test
    void chatServiceRunEventsMapsStartedEvent() {
        Flux<TaskExecutionEvent> eventStream = Flux.just(
            new TaskExecutionEvent("started", "conv-1", "run-1", null, null)
        );

        List<SsePayload> events = TaskStreamSupport.chatServiceRunEvents("task-1", eventStream)
            .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("started");
        assertThat(events.get(0).data()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(0).data();
        assertThat(payload).containsEntry("taskId", "task-1");
    }

    @Test
    void chatServiceRunEventsMapsToolAndProgressEvents() {
        Flux<TaskExecutionEvent> eventStream = Flux.just(
            new TaskExecutionEvent("tool", "conv-1", "run-1",
                new ChatMessage("assistant", "searching", "<p>searching</p>", null,
                    new io.mindspice.magenta2.ai.chat.model.ChatToolActivity(
                        "act-1", "call-1", "search_tool", "completed", null,
                        "Searching...", null, null, null, null, false, false
                    )), null)
        );

        List<SsePayload> events = TaskStreamSupport.chatServiceRunEvents("task-1", eventStream)
            .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("tool");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) events.get(0).data();
        assertThat(payload).containsEntry("event", "tool");
        assertThat(payload).containsKey("toolActivity");
    }

    @Test
    void chatServiceRunEventsMapsTerminalEvent() {
        TaskRun run = new TaskRun("run-1", "task-1", TaskRunStatus.COMPLETED,
            Map.of(), Map.of("result", "done"), null, List.of(), List.of(),
            "done", null,
            java.time.Instant.now(), java.time.Instant.now(),
            java.time.Instant.now(), java.time.Instant.now());
        Flux<TaskExecutionEvent> eventStream = Flux.just(
            new TaskExecutionEvent("completed", "conv-1", "run-1", null, run)
        );

        List<SsePayload> events = TaskStreamSupport.chatServiceRunEvents("task-1", eventStream)
            .collectList().block();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).name()).isEqualTo("completed");
    }

    @Test
    void toContextWithNullRequestReturnsEmptyContext() {
        OrchestrationRunContext context = TaskStreamSupport.toContext(null);
        assertThat(context.hasContext()).isFalse();
    }

    @Test
    void toContextWithRequestPopulatesFields() {
        var request = new TaskController.TaskRunRequest(
            Map.of(), "conv-1", "agent-1", "job-1", "project-1", null, "ws-1", null, "model-1", 5
        );

        OrchestrationRunContext context = TaskStreamSupport.toContext(request);

        assertThat(context.agentId()).isEqualTo("agent-1");
        assertThat(context.jobId()).isEqualTo("job-1");
        assertThat(context.projectId()).isEqualTo("project-1");
        assertThat(context.workspaceKey()).isEqualTo("ws-1");
        assertThat(context.modelOverride()).isEqualTo("model-1");
        assertThat(context.priority()).isEqualTo(5);
    }

    @Test
    void errorPayloadCreatesFailedEvent() {
        SsePayload payload = TaskStreamSupport.errorPayload("something went wrong");

        assertThat(payload.name()).isEqualTo("failed");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) payload.data();
        assertThat(data).containsEntry("error", "something went wrong");
    }
}
