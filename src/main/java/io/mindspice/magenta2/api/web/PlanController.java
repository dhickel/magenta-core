package io.mindspice.magenta2.api.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanRun;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanStep;
import io.mindspice.magenta2.ai.chat.plan.SavedPlanChatService;
import io.mindspice.magenta2.ai.chat.plan.SavedPlanChatService.SavedPlanChatState;
import io.mindspice.magenta2.ai.chat.plan.WorkTypeProfile;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Unified plan/task API. {@code /api/plans} exposes definition CRUD, run lifecycle,
 * submit-to-agent, chat-prompt generation, and streaming execution for both in-session
 * plans and reusable task templates.
 */
@RestController
@RequestMapping("/api/plans")
public class PlanController {
    private static final int PUBLIC_SUBMIT_PRIORITY = 9;

    private final PlanService planService;
    private final ChatService chatService;
    private final AssignmentService assignmentService;
    private final AgentProfileService agentProfileService;
    private final SavedPlanChatService savedPlanChatService;

    public PlanController(PlanService planService, ChatService chatService,
                          AssignmentService assignmentService,
                          AgentProfileService agentProfileService) {
        this(planService, chatService, assignmentService, agentProfileService, null);
    }

    @Autowired
    public PlanController(PlanService planService, ChatService chatService,
                          AssignmentService assignmentService,
                          AgentProfileService agentProfileService,
                          @Autowired(required = false) SavedPlanChatService savedPlanChatService) {
        this.planService = planService;
        this.chatService = chatService;
        this.assignmentService = assignmentService;
        this.agentProfileService = agentProfileService;
        this.savedPlanChatService = savedPlanChatService;
    }

    // ── Definition CRUD ──

    @GetMapping
    public List<PlanDefinition> list() {
        return planService.listTasks();
    }

    @GetMapping("/{planId}")
    public PlanDefinition get(@PathVariable String planId) {
        try {
            return planService.getTask(planId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping
    public PlanDefinition create(@RequestBody PlanCreateRequest request) {
        try {
            return planService.saveTask(toDomain(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/{planId}")
    public PlanDefinition update(@PathVariable String planId, @RequestBody PlanUpdateRequest request) {
        try {
            return planService.saveTask(toDomain(planId, request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{planId}")
    public void delete(@PathVariable String planId) {
        if (savedPlanChatService != null) {
            savedPlanChatService.deleteMessages(planId);
        }
        planService.deleteTask(planId);
    }

    // ── Saved plan chat ──

    @PostMapping("/planning-chats")
    public SavedPlanChatState createPlanningChat() {
        requireSavedPlanChatService();
        return savedPlanChatService.create();
    }

    @PostMapping("/{planId}/planning-chat/start")
    public SavedPlanChatState startPlanningChat(
        @PathVariable String planId,
        @RequestBody(required = false) PlanningChatMessage request
    ) {
        requireSavedPlanChatService();
        return savedPlanChatService.start(planId, request == null ? null : request.message());
    }

    @PostMapping("/{planId}/planning-chat/answers")
    public SavedPlanChatState answerPlanningChat(
        @PathVariable String planId,
        @RequestBody PlanningChatMessage request
    ) {
        requireSavedPlanChatService();
        return savedPlanChatService.answer(planId, request == null ? null : request.message());
    }

    @PostMapping("/{planId}/planning-chat/messages")
    public SavedPlanChatState messagePlanningChat(
        @PathVariable String planId,
        @RequestBody PlanningChatMessage request
    ) {
        requireSavedPlanChatService();
        return savedPlanChatService.message(planId, request == null ? null : request.message());
    }

    @GetMapping("/{planId}/planning-chat")
    public SavedPlanChatState getPlanningChat(@PathVariable String planId) {
        requireSavedPlanChatService();
        return savedPlanChatService.state(planId);
    }

    // ── Finalize / task template conversion ──

    @PostMapping("/{planId}/finalize-task")
    public PlanDefinition finalizeTask(@PathVariable String planId) {
        try {
            PlanDefinition existing = planService.getTask(planId);
            return planService.saveTask(existing); // re-save as finalized
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    // ── Submit to agent ──

    @PostMapping("/{planId}/submit")
    public WorkAssignment submitToAgent(@PathVariable String planId, @RequestBody SubmitRequest request) {
        try {
            PlanDefinition plan = planService.getTask(planId);
            String agentId = StringUtils.hasText(request.agentId())
                ? request.agentId()
                : agentProfileService.list().stream()
                    .filter(a -> a.status() != null && !"DISABLED".equals(a.status().name()))
                    .findFirst()
                    .map(a -> a.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No active agents available"));
            return assignmentService.create(new AssignmentRequest(
                agentId,
                null,          // jobId
                null,          // jobItemId
                AssignmentType.TASK_RUN,
                request.priority() != null ? request.priority() : PUBLIC_SUBMIT_PRIORITY,
                normalize(request.modelOverride()),
                normalize(request.projectId()),
                normalize(request.workspaceType()),
                normalize(request.workspaceKey()),
                Map.of("taskId", planId)
            ));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    // ── Chat prompt generation ──

    @GetMapping("/{planId}/chat-prompt")
    public Map<String, String> chatPrompt(@PathVariable String planId) {
        try {
            PlanDefinition plan = planService.getTask(planId);
            return Map.of("prompt", buildChatPrompt(plan));
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    private String buildChatPrompt(PlanDefinition plan) {
        StringBuilder sb = new StringBuilder();
        sb.append("Continue working on the following plan/task draft:\n\n");
        sb.append("Title: ").append(plan.title() != null ? plan.title() : "Untitled").append("\n");
        if (StringUtils.hasText(plan.goal())) {
            sb.append("Goal: ").append(plan.goal()).append("\n");
        }
        if (StringUtils.hasText(plan.summary())) {
            sb.append("Summary: ").append(plan.summary()).append("\n");
        }
        if (!plan.deliverables().isEmpty()) {
            sb.append("Deliverables:\n");
            for (String d : plan.deliverables()) {
                sb.append("- ").append(d).append("\n");
            }
        }
        if (!plan.inputs().isEmpty()) {
            sb.append("Inputs:\n");
            for (PlanFieldDefinition f : plan.inputs()) {
                sb.append("- ").append(f.name()).append(" (").append(f.type().wireName())
                    .append(f.array() ? "[]" : "").append(", required=").append(f.required()).append(")");
                if (StringUtils.hasText(f.description())) {
                    sb.append(": ").append(f.description());
                }
                sb.append("\n");
            }
        }
        if (!plan.outputs().isEmpty()) {
            sb.append("Outputs:\n");
            for (PlanFieldDefinition f : plan.outputs()) {
                sb.append("- ").append(f.name()).append(" (").append(f.type().wireName())
                    .append(f.array() ? "[]" : "").append(", required=").append(f.required()).append(")");
                if (StringUtils.hasText(f.description())) {
                    sb.append(": ").append(f.description());
                }
                sb.append("\n");
            }
        }
        if (!plan.steps().isEmpty()) {
            sb.append("Steps:\n");
            for (PlanStep s : plan.steps()) {
                sb.append(s.order()).append(". ").append(s.text()).append("\n");
            }
        }
        if (!plan.validationCriteria().isEmpty()) {
            sb.append("Validation Criteria:\n");
            for (String c : plan.validationCriteria()) {
                sb.append("- ").append(c).append("\n");
            }
        }
        if (!plan.assumptions().isEmpty()) {
            sb.append("Assumptions:\n");
            for (String a : plan.assumptions()) {
                sb.append("- ").append(a).append("\n");
            }
        }
        if (StringUtils.hasText(plan.notes())) {
            sb.append("Notes: ").append(plan.notes()).append("\n");
        }
        sb.append("\n");
        sb.append("Instructions:\n");
        sb.append("1. Read and grok the existing plan before asking questions.\n");
        sb.append("2. Continue questioning the user if the plan lacks context or details.\n");
        sb.append("3. If the plan appears complete or context is insufficient, summarize the current state and ask for guidance.\n");
        sb.append("4. Use plan mode tools to modify the plan as needed.\n");
        return sb.toString().trim();
    }

    // ── Runs ──

    @GetMapping("/{planId}/runs")
    public List<PlanRun> listRuns(@PathVariable String planId) {
        return planService.listRuns(planId);
    }

    @GetMapping("/runs/{runId}")
    public PlanRun getRun(@PathVariable String runId) {
        try {
            return planService.getRun(runId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping(value = "/{planId}/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamRun(@PathVariable String planId, @RequestBody(required = false) PlanRunRequest request) {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        try {
            planService.getTask(planId);
            WorkAssignment assignment = assignmentService.create(new AssignmentRequest(
                resolveAgentId(request == null ? null : request.agentId()),
                normalize(request == null ? null : request.jobId()),
                null,
                AssignmentType.TASK_RUN,
                request == null || request.priority() == null ? PUBLIC_SUBMIT_PRIORITY : request.priority(),
                normalize(request == null ? null : request.modelOverride()),
                normalize(request == null ? null : request.projectId()),
                normalize(request == null ? null : request.workspaceType()),
                normalize(request == null ? null : request.workspaceKey()),
                taskRunInput(planId, request)
            ));
            SseStreamLifecycle.sendSseEvent(emitter, "submitted", Map.of(
                "event", "submitted",
                "assignmentId", assignment.id(),
                "taskId", planId,
                "status", assignment.status().name(),
                "priority", assignment.priority()
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

    private void requireSavedPlanChatService() {
        if (savedPlanChatService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Saved plan chat service is unavailable");
        }
    }

    private Map<String, Object> taskRunInput(String planId, PlanRunRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", planId);
        input.put("inputValues", request == null || request.inputValues() == null ? Map.of() : request.inputValues());
        if (request != null && StringUtils.hasText(request.conversationId())) {
            input.put("conversationId", request.conversationId().trim());
        }
        return input;
    }

    // ── Request DTOs ──

    public record PlanRunRequest(
        Map<String, Object> inputValues,
        String conversationId,
        String agentId,
        String jobId,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String modelOverride,
        Integer priority
    ) {
        public PlanRunRequest(
            Map<String, Object> inputValues,
            String conversationId,
            String agentId,
            String jobId,
            String projectId,
            String modelOverride,
            Integer priority
        ) {
            this(inputValues, conversationId, agentId, jobId, projectId, null, null, modelOverride, priority);
        }
    }

    public record SubmitRequest(
        String agentId,
        String modelOverride,
        Integer priority,
        String projectId,
        String workspaceType,
        String workspaceKey
    ) {
        public SubmitRequest(
            String agentId,
            String modelOverride,
            Integer priority,
            String projectId
        ) {
            this(agentId, modelOverride, priority, projectId, null, null);
        }
    }

    public record PlanningChatMessage(String message) {
    }

    public record PlanCreateRequest(
        String title,
        String summary,
        String goal,
        String notes,
        List<String> deliverables,
        List<PlanFieldDefinition> inputs,
        List<PlanFieldDefinition> outputs,
        List<String> assumptions,
        List<PlanStep> steps,
        List<String> validationCriteria,
        String promptProfile,
        String workTypeProfile,
        String planningModel,
        String executionModel
    ) {
        public PlanCreateRequest {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            steps = steps == null ? List.of() : List.copyOf(steps);
            validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
        }
    }

    public record PlanUpdateRequest(
        String title,
        String summary,
        String goal,
        String notes,
        List<String> deliverables,
        List<PlanFieldDefinition> inputs,
        List<PlanFieldDefinition> outputs,
        List<String> assumptions,
        List<PlanStep> steps,
        List<String> validationCriteria,
        String promptProfile,
        String workTypeProfile,
        String planningModel,
        String executionModel
    ) {
        public PlanUpdateRequest {
            inputs = inputs == null ? List.of() : List.copyOf(inputs);
            outputs = outputs == null ? List.of() : List.copyOf(outputs);
            assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
            steps = steps == null ? List.of() : List.copyOf(steps);
            validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
            deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
        }
    }

    // ── Domain conversion ──

    private String resolvePromptProfile(String workTypeProfile, String legacyPromptProfile) {
        // If workTypeProfile is explicitly provided, use it
        if (StringUtils.hasText(workTypeProfile)) {
            return WorkTypeProfile.fromString(workTypeProfile).name();
        }
        // If legacy promptProfile is provided, map it
        if (StringUtils.hasText(legacyPromptProfile)) {
            return WorkTypeProfile.fromString(legacyPromptProfile).name();
        }
        return WorkTypeProfile.CODING_CENTRIC.name();
    }

    private PlanDefinition toDomain(PlanCreateRequest request) {
        return new PlanDefinition(
            null,
            PlanKind.TASK_TEMPLATE,
            PlanStatus.DRAFT,
            request.title(),
            request.summary(),
            request.goal(),
            request.notes(),
            request.deliverables(),
            request.inputs(),
            request.outputs(),
            request.assumptions(),
            request.steps(),
            request.validationCriteria(),
            List.of(),
            List.of(),
            resolvePromptProfile(request.workTypeProfile(), request.promptProfile()),
            request.planningModel(),
            request.executionModel(),
            null,
            null,
            List.of(),
            0,
            0,
            null,
            null,
            null,
            null
        );
    }

    private PlanDefinition toDomain(String planId, PlanUpdateRequest request) {
        return new PlanDefinition(
            planId,
            PlanKind.TASK_TEMPLATE,
            PlanStatus.DRAFT,
            request.title(),
            request.summary(),
            request.goal(),
            request.notes(),
            request.deliverables(),
            request.inputs(),
            request.outputs(),
            request.assumptions(),
            request.steps(),
            request.validationCriteria(),
            List.of(),
            List.of(),
            resolvePromptProfile(request.workTypeProfile(), request.promptProfile()),
            request.planningModel(),
            request.executionModel(),
            null,
            null,
            List.of(),
            0,
            0,
            null,
            null,
            null,
            null
        );
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
