package io.mindspice.magenta2.api.web;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowValidator;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow definition CRUD, run lifecycle, SSE streaming, and inbox endpoints.
 */
@RestController
public class WorkflowController {
    private static final int PUBLIC_SUBMIT_PRIORITY = 9;

    private final WorkflowService workflowService;
    private final InboxService inboxService;
    private final AssignmentService assignmentService;
    private final AgentProfileService agentProfileService;

    public WorkflowController(WorkflowService workflowService, InboxService inboxService) {
        this(workflowService, inboxService, null, null);
    }

    @Autowired
    public WorkflowController(WorkflowService workflowService, InboxService inboxService,
                              AssignmentService assignmentService, AgentProfileService agentProfileService) {
        this.workflowService = workflowService;
        this.inboxService = inboxService;
        this.assignmentService = assignmentService;
        this.agentProfileService = agentProfileService;
    }

    // ════════════════════════════════════════════════════════════════
    //  Workflow Definition CRUD
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/workflows")
    public List<WorkflowDefinition> list() {
        return workflowService.listDefinitions();
    }

    @GetMapping("/api/workflows/{workflowId}")
    public WorkflowDefinition get(@PathVariable String workflowId) {
        try {
            return workflowService.getDefinition(workflowId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/api/workflows")
    public WorkflowDefinition create(@RequestBody WorkflowDefinition definition) {
        try {
            return workflowService.saveDefinition(withId(definition, UUID.randomUUID().toString()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/api/workflows/{workflowId}")
    public WorkflowDefinition update(@PathVariable String workflowId,
                                     @RequestBody WorkflowDefinition definition) {
        try {
            return workflowService.saveDefinition(withId(definition, workflowId));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/api/workflows/{workflowId}")
    public void delete(@PathVariable String workflowId) {
        workflowService.deleteDefinition(workflowId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Validation (structured errors + warnings)
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/workflows/validate")
    public WorkflowValidator.ValidationResult validateNew(@RequestBody WorkflowDefinition definition) {
        return workflowService.validateGraph(withId(definition, UUID.randomUUID().toString()));
    }

    @PostMapping("/api/workflows/{workflowId}/validate")
    public WorkflowValidator.ValidationResult validate(@PathVariable String workflowId,
                                  @RequestBody WorkflowDefinition definition) {
        return workflowService.validateGraph(withId(definition, workflowId));
    }

    // ════════════════════════════════════════════════════════════════
    //  Runs
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/workflows/{workflowId}/runs")
    public WorkAssignment startRun(@PathVariable String workflowId, @RequestBody(required = false) WorkflowRunRequest request) {
        try {
            requireSubmissionServices();
            WorkflowDefinition workflow = workflowService.getDefinition(workflowId);
            WorkflowValidator.ValidationResult validation = workflowService.validateGraph(workflow);
            if (!validation.valid()) {
                throw new IllegalArgumentException(String.join("; ", validation.errors()));
            }
            String jobId = normalize(request == null ? null : request.jobId());
            String runDisplayName = requireRunDisplayName(request == null ? null : request.runDisplayName(), jobId);
            return assignmentService.create(new AssignmentRequest(
                resolveAgentId(request == null ? null : request.agentId()),
                jobId,
                null,
                AssignmentType.WORKFLOW_RUN,
                runDisplayName,
                request == null || request.priority() == null ? PUBLIC_SUBMIT_PRIORITY : request.priority(),
                normalize(request == null ? null : request.modelOverride()),
                normalize(request == null ? null : request.projectId()),
                normalize(request == null ? null : request.workspaceType()),
                normalize(request == null ? null : request.workspaceKey()),
                Map.of("workflowId", workflowId)
            ));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping(value = "/api/workflows/{workflowId}/runs/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String workflowId, @RequestBody(required = false) WorkflowRunRequest request) {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        try {
            WorkAssignment assignment = startRun(workflowId, request);
            SseStreamLifecycle.sendSseEvent(emitter, "submitted", Map.of(
                "event", "submitted",
                "assignmentId", assignment.id(),
                "workflowId", workflowId,
                "runDisplayName", displayValue(assignment.runDisplayName()),
                "status", assignment.status().name(),
                "priority", assignment.priority()
            ));
            SseStreamLifecycle.completeQuietly(emitter);
        } catch (IllegalArgumentException exception) {
            SseStreamLifecycle.trySendSseEvent(emitter, "failed",
                Map.of("event", "failed", "error", exception.getMessage()));
            SseStreamLifecycle.completeQuietly(emitter);
        } catch (Exception exception) {
            SseStreamLifecycle.trySendSseEvent(emitter, "failed",
                Map.of("event", "failed", "error", exception.getMessage()));
            SseStreamLifecycle.completeQuietly(emitter);
        }

        return emitter;
    }

    private void requireSubmissionServices() {
        if (assignmentService == null || agentProfileService == null) {
            throw new IllegalStateException("Workflow run submission requires assignment services");
        }
    }

    private String resolveAgentId(String requestedAgentId) {
        String normalized = normalize(requestedAgentId);
        if (normalized != null) {
            return normalized;
        }
        return agentProfileService.list().stream()
            .filter(agent -> agent.status() != null && !"DISABLED".equals(agent.status().name()))
            .findFirst()
            .map(agent -> agent.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active agents available"));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String requireRunDisplayName(String value, String jobId) {
        String normalized = normalize(value);
        if (normalized == null && !StringUtils.hasText(jobId)) {
            throw new IllegalArgumentException("Run name is required for workflow submissions.");
        }
        return normalized;
    }

    private String displayValue(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    @GetMapping("/api/workflow-runs/{runId}")
    public WorkflowRun getRun(@PathVariable String runId) {
        try {
            return workflowService.getRun(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/api/workflows/{workflowId}/runs")
    public List<WorkflowRun> listRuns(@PathVariable String workflowId) {
        return workflowService.listRuns(workflowId);
    }

    @PostMapping("/api/workflow-runs/{runId}/resume")
    public WorkflowRun resumeRun(@PathVariable String runId) {
        try {
            return workflowService.resumeRun(runId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  User Inbox
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/users/inbox")
    public List<InboxMessage> userInbox() {
        return inboxService.userInbox();
    }

    @PostMapping("/api/users/inbox/{messageId}/respond")
    public Map<String, Object> respondUser(@PathVariable String messageId,
                                           @RequestBody InboxRespondRequest request) {
        try {
            InboxMessage message = inboxService.respondUserApproval(
                messageId, request.approved(), request.comment());
            return Map.of(
                "messageId", message.id(),
                "responded", true,
                "approved", request.approved(),
                "workflowRunId", workflowRunId(message)
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    // ── Request DTOs ──

    public record WorkflowRunRequest(
        String agentId,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String runDisplayName,
        String modelOverride,
        Integer priority
    ) {
        public WorkflowRunRequest(
            String agentId,
            String jobId,
            String projectId,
            String modelOverride,
            Integer priority
        ) {
            this(agentId, jobId, projectId, null, null, null, modelOverride, priority);
        }
    }

    public record InboxRespondRequest(boolean approved, String comment) {
    }

    private String workflowRunId(InboxMessage message) {
        if (message.metadataJson() == null || message.metadataJson().isBlank()) {
            return "";
        }
        try {
            Object value = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(message.metadataJson(), Map.class)
                .get("workflowRunId");
            return value == null ? "" : value.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private WorkflowDefinition withId(WorkflowDefinition definition, String id) {
        return new WorkflowDefinition(
            id,
            definition.schemaVersion(),
            definition.title(),
            definition.summary(),
            definition.maxConcurrency(),
            definition.nodes(),
            definition.routes(),
            definition.uiLayout(),
            definition.createdAt(),
            definition.updatedAt()
        );
    }
}
