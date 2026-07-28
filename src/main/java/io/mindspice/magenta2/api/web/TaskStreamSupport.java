package io.mindspice.magenta2.api.web;

import java.util.LinkedHashMap;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.service.TaskExecutionEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunResult;
import reactor.core.publisher.Flux;

/**
 * Event mapping and context conversion support for task run SSE streams.
 * Extracted from TaskController to keep controllers thin.
 */
public final class TaskStreamSupport {

    private TaskStreamSupport() {}

    /**
     * Maps an orchestration run result to started/terminal SSE events.
     */
    public static Flux<SsePayload> orchestrationRunEvents(String taskId, OrchestrationRunResult result) {
        String terminalEvent = terminalEventName(result.assignment().status().name());
        return Flux.just(
            new SsePayload("started", Map.of(
                "event", "started",
                "assignmentId", result.assignment().id(),
                "runId", result.runId() == null ? "" : result.runId(),
                "taskId", taskId
            )),
            new SsePayload(terminalEvent, Map.of(
                "event", terminalEvent,
                "assignmentId", result.assignment().id(),
                "runId", result.runId() == null ? "" : result.runId(),
                "status", result.assignment().status().name(),
                "outputValues", result.outputValues(),
                "error", result.assignment().errorText() == null ? "" : result.assignment().errorText()
            ))
        );
    }

    /**
     * Maps a chat service task execution event stream to SSE payloads.
     */
    public static Flux<SsePayload> chatServiceRunEvents(
        String taskId,
        Flux<TaskExecutionEvent> eventStream
    ) {
        return eventStream.map(event -> mapExecutionEvent(taskId, event));
    }

    /**
     * Converts a TaskRunRequest to an OrchestrationRunContext.
     */
    public static OrchestrationRunContext toContext(TaskController.TaskRunRequest request) {
        if (request == null) {
            return new OrchestrationRunContext(null, null, null, null, null);
        }
        return new OrchestrationRunContext(
            request.agentId(), request.jobId(), request.projectId(),
            request.workspaceType(), request.workspaceKey(),
            request.modelOverride(), request.priority()
        );
    }

    /**
     * Creates a failed-event SSE payload for error reporting.
     */
    public static SsePayload errorPayload(String errorMessage) {
        return new SsePayload("failed", Map.of(
            "event", "failed",
            "error", errorMessage
        ));
    }

    private static String terminalEventName(String statusName) {
        return "COMPLETED".equals(statusName) ? "completed" : "failed";
    }

    private static SsePayload mapExecutionEvent(String taskId, TaskExecutionEvent event) {
        if ("started".equals(event.event())) {
            return new SsePayload("started", Map.of(
                "event", "started",
                "conversationId", event.conversationId(),
                "runId", event.runId(),
                "taskId", taskId
            ));
        }
        if ("tool".equals(event.event()) || "progress".equals(event.event())) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", event.event());
            payload.put("conversationId", event.conversationId());
            payload.put("runId", event.runId());
            payload.put("message", event.message() == null ? "" : event.message().text());
            payload.put("toolActivity", event.message() == null ? null : event.message().toolActivity());
            return new SsePayload(event.event(), payload);
        }
        var run = event.run();
        return new SsePayload(event.event(), Map.of(
            "event", event.event(),
            "conversationId", event.conversationId(),
            "runId", event.runId(),
            "status", run.status().name(),
            "outputValues", run.outputValues(),
            "error", run.errorText() == null ? "" : run.errorText()
        ));
    }
}
