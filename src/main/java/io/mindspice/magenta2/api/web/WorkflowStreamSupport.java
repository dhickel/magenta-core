package io.mindspice.magenta2.api.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunResult;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNodeRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import reactor.core.publisher.Flux;

/**
 * Event mapping and context conversion support for workflow run SSE streams.
 * Extracted from WorkflowController to keep controllers thin.
 */
public final class WorkflowStreamSupport {

    private WorkflowStreamSupport() {}

    /**
     * Maps an orchestration run result to started/terminal SSE events.
     */
    public static Flux<SsePayload> orchestrationRunEvents(String workflowId, OrchestrationRunResult result) {
        String terminalEvent = terminalEventName(result.assignment().status().name());
        return Flux.just(
            new SsePayload("started", Map.of(
                "event", "started",
                "assignmentId", result.assignment().id(),
                "runId", result.runId() == null ? "" : result.runId(),
                "workflowId", workflowId
            )),
            new SsePayload(terminalEvent, Map.of(
                "event", terminalEvent,
                "assignmentId", result.assignment().id(),
                "runId", result.runId() == null ? "" : result.runId(),
                "status", result.assignment().status().name(),
                "finalOutputs", result.outputValues(),
                "error", result.assignment().errorText() == null ? "" : result.assignment().errorText()
            ))
        );
    }

    /**
     * Builds SSE events for a synchronous workflow execution.
     * Emits started, step_started, step_completed, and terminal events.
     */
    public static Flux<SsePayload> synchronousRunEvents(
        String workflowId,
        WorkflowService workflowService
    ) {
        return Flux.defer(() -> {
            WorkflowDefinition workflow = workflowService.getDefinition(workflowId);
            List<SsePayload> events = new ArrayList<>();
            events.add(new SsePayload("started", Map.of("event", "started", "workflowId", workflowId)));
            for (var node : workflow.nodes()) {
                events.add(new SsePayload("node_started", Map.of(
                    "event", "node_started",
                    "nodeKey", node.key(),
                    "nodeType", node.type().wireName()
                )));
            }
            return Flux.concat(
                Flux.fromIterable(events),
                Flux.defer(() -> terminalRunEvents(workflowId, workflowService))
            );
        });
    }

    private static Flux<SsePayload> terminalRunEvents(String workflowId, WorkflowService workflowService) {
        WorkflowRun run = workflowService.runSynchronously(workflowId);
        List<SsePayload> events = new ArrayList<>();
        for (WorkflowNodeRun nodeRun : run.nodeRuns()) {
            events.add(new SsePayload("node_completed", Map.of(
                "event", "node_completed",
                "nodeKey", nodeRun.nodeKey(),
                "nodeType", nodeRun.type().wireName(),
                "status", nodeRun.status().name()
            )));
        }
        String terminalEvent = terminalEventName(run.status().name());
        events.add(new SsePayload(terminalEvent, Map.of(
            "event", terminalEvent,
            "runId", run.id(),
            "status", run.status().name(),
            "finalOutputs", finalOutputs(run),
            "error", run.errorText() == null ? "" : run.errorText()
        )));
        return Flux.fromIterable(events);
    }

    /**
     * Converts a WorkflowRunRequest to an OrchestrationRunContext.
     */
    public static OrchestrationRunContext toContext(WorkflowController.WorkflowRunRequest request) {
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

    private static Map<String, Object> finalOutputs(WorkflowRun run) {
        Map<String, Object> outputs = new java.util.LinkedHashMap<>();
        for (WorkflowNodeRun nodeRun : run.nodeRuns()) {
            if (!nodeRun.outputValues().isEmpty()) {
                outputs.put(nodeRun.nodeKey(), nodeRun.outputValues());
            }
        }
        return outputs;
    }
}
