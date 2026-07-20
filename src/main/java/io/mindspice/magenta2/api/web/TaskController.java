package io.mindspice.magenta2.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mindspice.magenta2.ai.chat.task.TaskDefinition;
import io.mindspice.magenta2.ai.chat.task.TaskDraft;
import io.mindspice.magenta2.ai.chat.task.TaskFieldDefinition;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.task.TaskStep;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private static final int PUBLIC_SUBMIT_PRIORITY = 9;

    private final TaskService taskService;
    private final AssignmentService assignmentService;
    private final AgentProfileService agentProfileService;

    public TaskController(TaskService taskService, OrchestrationRunService orchestrationRunService) {
        this(taskService, null, orchestrationRunService, null, null);
    }

    @Autowired
    public TaskController(TaskService taskService, ChatService chatService, OrchestrationRunService orchestrationRunService,
                          AssignmentService assignmentService, AgentProfileService agentProfileService) {
        this.taskService = taskService;
        this.assignmentService = assignmentService;
        this.agentProfileService = agentProfileService;
    }

    @GetMapping
    public List<TaskDefinition> list() {
        return taskService.listTasks();
    }

    @GetMapping("/{taskId}")
    public TaskDefinition get(@PathVariable String taskId) {
        try {
            return taskService.getTask(taskId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping
    public TaskDefinition create(@Valid @RequestBody TaskCreateRequest request) {
        try {
            return taskService.saveTask(toDomain(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/{taskId}")
    public TaskDefinition update(@PathVariable String taskId, @Valid @RequestBody TaskUpdateRequest request) {
        try {
            return taskService.saveTask(toDomain(taskId, request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private static TaskDefinition toDomain(TaskCreateRequest request) {
        return new TaskDefinition(
            null,
            request.title(),
            request.summary(),
            request.goal(),
            request.notes(),
            request.inputDescription(),
            request.inputs(),
            request.outputDescription(),
            request.outputs(),
            request.assumptions(),
            request.steps(),
            request.validationCriteria(),
            null,
            null
        );
    }

    private static TaskDefinition toDomain(String taskId, TaskUpdateRequest request) {
        return new TaskDefinition(
            taskId,
            request.title(),
            request.summary(),
            request.goal(),
            request.notes(),
            request.inputDescription(),
            request.inputs(),
            request.outputDescription(),
            request.outputs(),
            request.assumptions(),
            request.steps(),
            request.validationCriteria(),
            null,
            null
        );
    }

    @DeleteMapping("/{taskId}")
    public void delete(@PathVariable String taskId) {
        taskService.deleteTask(taskId);
    }

    @PostMapping("/drafts/{conversationId}")
    public TaskDraft beginDraft(@PathVariable String conversationId, @RequestBody(required = false) DraftRequest request) {
        DraftRequest body = request == null ? new DraftRequest(null, null) : request;
        return taskService.beginDraft(conversationId, body.prePlanningModel(), body.executionModel());
    }

    @GetMapping("/drafts/{conversationId}")
    public TaskDraft getDraft(@PathVariable String conversationId) {
        return taskService.activeDraft(conversationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task draft not found"));
    }

    @PostMapping("/drafts/{conversationId}/answers")
    public TaskDraft answerDraftQuestion(@PathVariable String conversationId, @Valid @RequestBody TaskAnswerRequest request) {
        try {
            return taskService.recordPromptAnswer(conversationId, request.answer(), request.notes(), request.questionIndex());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/drafts/{conversationId}/approve")
    public TaskDefinition approveDraft(@PathVariable String conversationId) {
        try {
            return taskService.approveDraft(conversationId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{taskId}/runs")
    public List<TaskRun> listRuns(@PathVariable String taskId) {
        return taskService.listRuns(taskId);
    }

    @GetMapping("/runs/{runId}")
    public TaskRun getRun(@PathVariable String runId) {
        try {
            return taskService.getRun(runId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping(value = "/{taskId}/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String taskId, @RequestBody(required = false) TaskRunRequest request) {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        try {
            requireSubmissionServices();
            taskService.getTask(taskId);
            String jobId = normalize(request == null ? null : request.jobId());
            String runDisplayName = requireRunDisplayName(request == null ? null : request.runDisplayName(), jobId);
            WorkAssignment assignment = assignmentService.create(new AssignmentRequest(
                resolveAgentId(request == null ? null : request.agentId()),
                jobId,
                null,
                AssignmentType.TASK_RUN,
                runDisplayName,
                request == null || request.priority() == null ? PUBLIC_SUBMIT_PRIORITY : request.priority(),
                normalize(request == null ? null : request.modelOverride()),
                normalize(request == null ? null : request.projectId()),
                normalize(request == null ? null : request.workspaceType()),
                normalize(request == null ? null : request.workspaceKey()),
                taskRunInput(taskId, request),
                normalize(request == null ? null : request.operationKey())
            ));
            SseStreamLifecycle.sendSseEvent(emitter, "submitted", Map.of(
                "event", "submitted",
                "assignmentId", assignment.id(),
                "taskId", taskId,
                "runDisplayName", displayValue(assignment.runDisplayName()),
                "status", assignment.status().name(),
                "priority", assignment.priority(),
                "taskRunId", displayValue(String.valueOf(assignment.checkpoint().get("taskRunId"))),
                "operationKey", displayValue(String.valueOf(assignment.checkpoint().get("lifecycleOperationKey")))
            ));
            SseStreamLifecycle.completeQuietly(emitter);
        } catch (IllegalArgumentException exception) {
            if (SseStreamLifecycle.trySendSseEvent(emitter, "failed",
                    Map.of("event", "failed", "error", exception.getMessage()))) {
                SseStreamLifecycle.completeQuietly(emitter);
            }
        } catch (Exception exception) {
            if (SseStreamLifecycle.trySendSseEvent(emitter, "failed",
                    Map.of("event", "failed", "error", exception.getMessage()))) {
                SseStreamLifecycle.completeQuietly(emitter);
            }
        }
        return emitter;
    }

    private void requireSubmissionServices() {
        if (assignmentService == null || agentProfileService == null) {
            throw new IllegalStateException("Task run submission requires assignment services");
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

    private Map<String, Object> taskRunInput(String taskId, TaskRunRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", taskId);
        input.put("inputValues", request == null || request.inputValues() == null ? Map.of() : request.inputValues());
        if (request != null && StringUtils.hasText(request.conversationId())) {
            input.put("conversationId", request.conversationId().trim());
        }
        return input;
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
            throw new IllegalArgumentException("Run name is required for task submissions.");
        }
        return normalized;
    }

    private String displayValue(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    public record DraftRequest(String prePlanningModel, String executionModel) {
    }

    public record TaskAnswerRequest(@NotBlank String answer, String notes, Integer questionIndex) {
    }

    public record TaskRunRequest(
        Map<String, Object> inputValues,
        String conversationId,
        String agentId,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String runDisplayName,
        String modelOverride,
        Integer priority,
        String operationKey
    ) {
        public TaskRunRequest(
            Map<String, Object> inputValues, String conversationId, String agentId, String jobId,
            String projectId, String workspaceType, String workspaceKey, String runDisplayName,
            String modelOverride, Integer priority
        ) {
            this(inputValues, conversationId, agentId, jobId, projectId, workspaceType, workspaceKey,
                runDisplayName, modelOverride, priority, null);
        }

        public TaskRunRequest(
            Map<String, Object> inputValues,
            String conversationId,
            String agentId,
            String jobId,
            String projectId,
            String modelOverride,
            Integer priority
        ) {
            this(inputValues, conversationId, agentId, jobId, projectId, null, null, null,
                modelOverride, priority, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskCreateRequest(
        @NotBlank String title,
        String summary,
        String goal,
        String notes,
        String inputDescription,
        List<TaskFieldDefinition> inputs,
        String outputDescription,
        List<TaskFieldDefinition> outputs,
        List<String> assumptions,
        List<TaskStep> steps,
        List<String> validationCriteria
    ) {
        public TaskCreateRequest {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            steps = steps == null ? List.of() : List.copyOf(steps);
            validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskUpdateRequest(
        @NotBlank String title,
        String summary,
        String goal,
        String notes,
        String inputDescription,
        List<TaskFieldDefinition> inputs,
        String outputDescription,
        List<TaskFieldDefinition> outputs,
        List<String> assumptions,
        List<TaskStep> steps,
        List<String> validationCriteria
    ) {
        public TaskUpdateRequest {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            steps = steps == null ? List.of() : List.copyOf(steps);
            validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
        }
    }
}
