package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskAttempt;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskDefinition;
import io.mindspice.magenta2.ai.orchestration.tasks.WorkTaskService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * REST controller for lightweight {@link WorkTaskDefinition} tasks.
 *
 * <p>Additive surface; the legacy {@code /api/tasks} family is unchanged.
 * Project-scoped fragments (Phase 03D) will be mounted under
 * {@code /api/projects/{projectId}/tasks} through {@link ProjectScope}.
 */
@RestController
@RequestMapping("/api/work/tasks")
public class WorkTaskController {

    private final WorkTaskService workTaskService;
    private final AssignmentService assignmentService;

    public WorkTaskController(WorkTaskService workTaskService,
                              AssignmentService assignmentService) {
        this.workTaskService = workTaskService;
        this.assignmentService = assignmentService;
    }

    // ── Global task list ──

    @GetMapping
    public List<WorkTaskSummary> list() {
        return workTaskService.listAll().stream()
            .map(this::toSummary)
            .toList();
    }

    // ── Task detail with attempts ──

    @GetMapping("/{taskId}")
    public WorkTaskDetail get(@PathVariable String taskId) {
        WorkTaskDefinition task = workTaskService.getById(taskId);
        List<WorkTaskAttempt> attempts = workTaskService.listAttempts(taskId);
        return toDetail(task, attempts);
    }

    // ── Create task ──

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public WorkTaskSummary create(@Valid @RequestBody WorkTaskCreateRequest request) {
        try {
            WorkTaskDefinition task = workTaskService.createOperation(
                request.operationKey(), request.title(), request.description(), request.projectId(),
                request.pin() != null && request.pin(),
                request.defaultPriority() != null ? request.defaultPriority() : 0,
                request.preferredAgentId());
            return toSummary(task);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (isProjectAccessDenied(e)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Edit task (CAS, expected revision) ──

    @PutMapping("/{taskId}")
    public WorkTaskSummary edit(@PathVariable String taskId,
                                 @Valid @RequestBody WorkTaskEditRequest request) {
        try {
            WorkTaskDefinition task = workTaskService.editOperation(
                request.operationKey(), taskId, request.expectedVersion(),
                request.title(),
                request.description(),
                request.defaultPriority(),
                request.pin(),
                request.preferredAgentId());
            return toSummary(task);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("version mismatch")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("promoted")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (isProjectAccessDenied(e)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Promote to executable Plan ──

    @PostMapping("/{taskId}/promote")
    public WorkTaskSummary promote(@PathVariable String taskId,
                                    @Valid @RequestBody PromoteRequest request) {
        try {
            WorkTaskDefinition task = workTaskService.promoteOperation(
                request.operationKey(),
                taskId,
                request.expectedVersion());
            return toSummary(task);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("already been promoted")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            if (e.getMessage() != null && e.getMessage().contains("version mismatch")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (isProjectAccessDenied(e)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }
            if (isContradictory(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Submit for execution ──

    @PostMapping("/{taskId}/submit")
    public WorkTaskAttemptSummary submit(@PathVariable String taskId,
                                          @Valid @RequestBody WorkTaskSubmitRequest request) {
        try {
            WorkTaskAttempt attempt = workTaskService.submitOperation(
                request.operationKey(), taskId, request.agentId(),
                request.runDisplayName(),
                request.workspaceType(),
                request.workspaceKey());
            return toAttemptSummary(attempt);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("not promoted")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
            }
            if (msg.contains("already has an active assignment")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, msg);
            }
            if (msg.contains("Archived") || msg.contains("archived")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, msg);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (isProjectAccessDenied(e)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }
            if (isOperationConflict(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Retry after terminal ──

    @PostMapping("/{taskId}/retry")
    public WorkTaskAttemptSummary retry(@PathVariable String taskId,
                                         @Valid @RequestBody RetryRequest request) {
        try {
            WorkTaskAttempt attempt = workTaskService.retryOperation(request.operationKey(), taskId);
            return toAttemptSummary(attempt);
        } catch (IllegalStateException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("No attempts exist")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
            }
            if (msg.contains("not terminal")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, msg);
            }
            if (msg.contains("Archived") || msg.contains("archived")) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, msg);
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (RuntimeException e) {
            if (isProjectAccessDenied(e)) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
            }
            if (isOperationConflict(e)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Mapping helpers ──

    private WorkTaskSummary toSummary(WorkTaskDefinition task) {
        List<WorkTaskAttempt> attempts = workTaskService.listAttempts(task.id());
        String derivedStatus = deriveStatus(task, attempts);
        return new WorkTaskSummary(
            task.id(), task.title(), task.description(),
            task.projectId(), task.pin(), task.defaultPriority(),
            task.preferredAgentId(), task.promotedPlanId(),
            task.definitionVersion(), derivedStatus,
            attempts.size(), task.createdAt(), task.updatedAt());
    }

    private WorkTaskDetail toDetail(WorkTaskDefinition task, List<WorkTaskAttempt> attempts) {
        return new WorkTaskDetail(
            task.id(), task.title(), task.description(),
            task.projectId(), task.pin(), task.defaultPriority(),
            task.preferredAgentId(), task.promotedPlanId(),
            task.definitionVersion(), deriveStatus(task, attempts),
            attempts.size(), task.createdAt(), task.updatedAt(),
            attempts.stream().map(this::toAttemptSummary).toList());
    }

    private WorkTaskAttemptSummary toAttemptSummary(WorkTaskAttempt attempt) {
        String assignmentStatus = null;
        try {
            WorkAssignment assignment = assignmentService.get(attempt.assignmentId());
            assignmentStatus = assignment.status() == null ? null : assignment.status().name();
        } catch (RuntimeException ignored) {
            // assignment may not exist yet or may be inaccessible
        }
        return new WorkTaskAttemptSummary(
            attempt.id(), attempt.assignmentId(), attempt.attemptOrder(),
            attempt.predecessorAttemptId(), assignmentStatus,
            attempt.createdAt());
    }

    /**
     * Derive a display status from the designated (latest) attempt's
     * assignment status. Returns {@code null} when no attempts exist.
     */
    private String deriveStatus(WorkTaskDefinition task, List<WorkTaskAttempt> attempts) {
        if (attempts.isEmpty() || task.designatedAttemptId() == null) {
            return null;
        }
        WorkTaskAttempt designated = attempts.stream()
            .filter(attempt -> task.designatedAttemptId().equals(attempt.id()))
            .findFirst().orElse(null);
        if (designated == null) {
            return null;
        }
        try {
            WorkAssignment assignment = assignmentService.get(designated.assignmentId());
            return assignment.status() == null ? null : assignment.status().name();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ── Exception classification ──

    private boolean isProjectAccessDenied(RuntimeException e) {
        return e.getClass().getSimpleName().equals("ProjectAccessDeniedException")
            || (e.getMessage() != null
                && (e.getMessage().contains("not a member")
                    || e.getMessage().contains("archived")
                    || e.getMessage().contains("Archived")));
    }

    private boolean isContradictory(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().contains("contradictory");
    }

    private boolean isOperationConflict(RuntimeException e) {
        return e.getMessage() != null
            && (e.getMessage().contains("contradictory")
                || e.getMessage().contains("OperationConflict")
                || e.getMessage().contains("reserved by another process"));
    }

    // ── DTOs ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTaskCreateRequest(
        @NotBlank String title,
        String description,
        String projectId,
        Boolean pin,
        Integer defaultPriority,
        String preferredAgentId,
        @NotBlank String operationKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTaskEditRequest(
        int expectedVersion,
        String title,
        String description,
        Integer defaultPriority,
        Boolean pin,
        String preferredAgentId,
        @NotBlank String operationKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PromoteRequest(
        int expectedVersion,
        @NotBlank String operationKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkTaskSubmitRequest(
        @NotBlank String agentId,
        String runDisplayName,
        String workspaceType,
        String workspaceKey,
        @NotBlank String operationKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RetryRequest(
        @NotBlank String operationKey
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkTaskSummary(
        String id,
        String title,
        String description,
        String projectId,
        boolean pin,
        int defaultPriority,
        String preferredAgentId,
        String promotedPlanId,
        int definitionVersion,
        String status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkTaskDetail(
        String id,
        String title,
        String description,
        String projectId,
        boolean pin,
        int defaultPriority,
        String preferredAgentId,
        String promotedPlanId,
        int definitionVersion,
        String status,
        int attemptCount,
        Instant createdAt,
        Instant updatedAt,
        List<WorkTaskAttemptSummary> attempts
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkTaskAttemptSummary(
        String id,
        String assignmentId,
        int attemptOrder,
        String predecessorAttemptId,
        String assignmentStatus,
        Instant createdAt
    ) {}
}
