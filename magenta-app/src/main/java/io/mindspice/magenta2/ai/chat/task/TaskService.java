package io.mindspice.magenta2.ai.chat.task;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanRun;
import io.mindspice.magenta2.ai.chat.plan.PlanRunStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanStep;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Compatibility facade delegating to {@link PlanService}.
 * Maintains the old TaskService API surface for code that has not yet been
 * migrated to the unified plan service.
 *
 * <p>New code should call {@code PlanService} directly.
 */
@Service
public class TaskService {
    private final PlanService planService;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;

    public TaskService(PlanService planService) {
        this(planService, null);
    }

    @Autowired
    public TaskService(PlanService planService,
                       @Autowired(required = false) ChatSessionMetadataRepository chatSessionMetadataRepository) {
        this.planService = planService;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
    }

    // ── Task CRUD ──

    public List<TaskDefinition> listTasks() {
        return planService.listTasks().stream()
            .map(TaskService::toTaskDefinition)
            .toList();
    }

    public TaskDefinition getTask(String id) {
        return toTaskDefinition(planService.getTask(id));
    }

    public TaskDefinition saveTask(TaskDefinition task) {
        PlanDefinition def = toPlanDefinition(task);
        PlanDefinition saved = planService.saveTask(def);
        return toTaskDefinition(saved);
    }

    public void deleteTask(String id) {
        planService.deleteTask(id);
    }

    // ── Draft operations ──

    public TaskDraft beginDraft(String conversationId, String prePlanningModel, String executionModel) {
        PlanDefinition def = planService.beginDraft(conversationId, prePlanningModel, executionModel);
        return toTaskDraft(def);
    }

    public Optional<TaskDraft> activeDraft(String conversationId) {
        return planService.activeDraft(conversationId).map(TaskService::toTaskDraft);
    }

    public List<String> listDraftConversationIds() {
        return planService.listDraftConversationIds();
    }

    public PlanMode mode(String conversationId) {
        return planService.mode(conversationId);
    }

    public TaskDraft setGoal(String conversationId, String goal) {
        return toTaskDraft(planService.setTaskGoal(conversationId, goal));
    }

    public TaskDraft setTask(String conversationId, String planningTask) {
        return toTaskDraft(planService.setTask(conversationId, planningTask));
    }

    public TaskDraft putTextItem(String conversationId, String section, Integer key, String text) {
        return toTaskDraft(planService.putTextItem(conversationId, section, key, text));
    }

    public TaskDraft putFieldItem(String conversationId, String section, Integer key, TaskFieldDefinition field) {
        return toTaskDraft(planService.putFieldItem(conversationId, section, key, toPlanFieldDef(field)));
    }

    public TaskDraft deleteItem(String conversationId, String section, Integer key) {
        return toTaskDraft(planService.deleteTaskItem(conversationId, section, key));
    }

    public TaskDraft askQuestions(String conversationId, List<String> questions) {
        return toTaskDraft(planService.askTaskQuestions(conversationId, questions));
    }

    public TaskDraft recordPromptAnswer(String conversationId, String answer, String notes, Integer expectedQuestionIndex) {
        return toTaskDraft(planService.recordTaskPromptAnswer(conversationId, answer, notes, expectedQuestionIndex));
    }

    public TaskDraft markReadyForApproval(String conversationId) {
        return toTaskDraft(planService.markTaskReadyForApproval(conversationId));
    }

    public TaskDefinition approveDraft(String conversationId) {
        return toTaskDefinition(planService.approveDraft(conversationId));
    }

    public String runtimeInstructions(String conversationId) {
        return planService.runtimeInstructions(conversationId);
    }

    // ── Run operations ──

    public TaskRun startRun(String taskId, Map<String, Object> inputValues) {
        PlanRun run = planService.startRun(taskId, inputValues);
        return toTaskRun(run);
    }

    public TaskRun startChatExecution(String conversationId, String taskId, Map<String, Object> inputValues) {
        PlanRun run = planService.startChatExecution(conversationId, taskId, inputValues);
        return toTaskRun(run);
    }

    public TaskRun startChatExecution(String conversationId, String taskId, Map<String, Object> inputValues,
                                      OrchestrationTaskContext context) {
        PlanRun run = planService.startChatExecution(conversationId, taskId, inputValues, context);
        return toTaskRun(run);
    }

    public void registerExecutionContext(String conversationId, String runId) {
        planService.registerExecutionContext(conversationId, runId);
    }

    public void clearExecutionContext(String conversationId) {
        planService.clearExecutionContext(conversationId);
    }

    public String runIdForConversation(String conversationId) {
        return planService.runIdForConversation(conversationId);
    }

    public String finalMessage(String runId) {
        return planService.runFinalMessage(runId);
    }

    public TaskRun recordReport(String runId, String summary, List<String> evidence) {
        return toTaskRun(planService.recordRunReport(runId, summary, evidence));
    }

    public TaskRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage, List<String> evidence) {
        return toTaskRun(planService.completeRun(runId, outputValues, finalMessage, evidence));
    }

    public TaskRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage,
                               List<String> evidence, boolean includeTempWithOutput) {
        return toTaskRun(planService.completeRun(runId, outputValues, finalMessage, evidence, includeTempWithOutput));
    }

    public TaskRun failRun(String runId, String errorText) {
        return toTaskRun(planService.failRun(runId, errorText));
    }

    public TaskRun markNeedsReview(String runId, String reason) {
        return toTaskRun(planService.markRunNeedsReview(runId, reason));
    }

    public TaskRun markActiveRunNeedsReview(String conversationId, String reason) {
        return toTaskRun(planService.markActiveRunNeedsReview(conversationId, reason));
    }

    public TaskRun failActiveRun(String conversationId, String errorText) {
        return toTaskRun(planService.failActiveRun(conversationId, errorText));
    }

    public TaskRun getRun(String runId) {
        return toTaskRun(planService.getRun(runId));
    }

    public List<TaskRun> listRuns(String taskId) {
        return planService.listRuns(taskId).stream()
            .map(TaskService::toTaskRun)
            .toList();
    }

    public List<String> compatibilityWarnings(TaskDefinition upstream, TaskDefinition downstream,
                                               Map<String, String> outputToInput) {
        if (upstream == null || downstream == null || outputToInput == null) return List.of();
        return List.of();
    }

    // ── Package-private helpers for TaskServiceTest ──

    TaskService(PlanService planService, ChatSessionMetadataRepository chatSessionMetadataRepository, boolean dummy) {
        this.planService = planService;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
    }

    // ── Conversion helpers (package-private for test access) ──

    static TaskDefinition toTaskDefinition(PlanDefinition def) {
        return new TaskDefinition(
            def.id(), def.title(), def.summary(), def.goal(), def.notes(),
            null, toTaskFieldDefs(def.inputs()),
            null, toTaskFieldDefs(def.outputs()),
            def.assumptions(), toTaskSteps(def.steps()), def.validationCriteria(),
            def.createdAt(), def.updatedAt()
        );
    }

    static PlanDefinition toPlanDefinition(TaskDefinition task) {
        return new PlanDefinition(
            task.id(), PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            task.title(), task.summary(), task.goal(), task.notes(),
            List.of(), toPlanFieldDefs(task.inputs()), toPlanFieldDefs(task.outputs()),
            task.assumptions(), toPlanSteps(task.steps()), task.validationCriteria(),
            List.of(), List.of(),
            null, null, null, null,
            null, List.of(), 0, 0, null, null,
            task.createdAt(), task.updatedAt()
        );
    }

    static TaskDraft toTaskDraft(PlanDefinition def) {
        return new TaskDraft(
            def.conversationId(),
            def.status() == PlanStatus.APPROVED ? TaskDraftStatus.APPROVED
                : def.status() == PlanStatus.READY_FOR_APPROVAL ? TaskDraftStatus.READY_FOR_APPROVAL
                : TaskDraftStatus.DRAFT,
            def.planningTask(), def.title(), def.summary(), def.goal(), def.notes(),
            null, toTaskFieldDefs(def.inputs()),
            null, toTaskFieldDefs(def.outputs()),
            def.assumptions(), toTaskSteps(def.steps()), def.validationCriteria(),
            def.pendingQuestions(), def.pendingQuestionIndex(),
            def.planningModel(), def.executionModel(), null,
            def.createdAt(), def.updatedAt()
        );
    }

    static TaskRun toTaskRun(PlanRun run) {
        return new TaskRun(
            run.id(), run.planId(),
            run.status() == PlanRunStatus.RUNNING ? TaskRunStatus.RUNNING
                : run.status() == PlanRunStatus.COMPLETED ? TaskRunStatus.COMPLETED
                : run.status() == PlanRunStatus.NEEDS_REVIEW ? TaskRunStatus.NEEDS_REVIEW
                : run.status() == PlanRunStatus.FAILED ? TaskRunStatus.FAILED
                : TaskRunStatus.QUEUED,
            run.inputValues(), run.outputValues(), toTaskDefinition(run.planSnapshot()),
            run.executionEvidence(), run.validationFeedback(),
            run.finalMessage(), run.errorText(),
            run.createdAt(), run.updatedAt(), run.startedAt(), run.completedAt()
        );
    }

    static PlanRun toPlanRun(TaskRun run) {
        return new PlanRun(
            run.id(), run.taskId(),
            run.status() == TaskRunStatus.RUNNING ? PlanRunStatus.RUNNING
                : run.status() == TaskRunStatus.COMPLETED ? PlanRunStatus.COMPLETED
                : run.status() == TaskRunStatus.NEEDS_REVIEW ? PlanRunStatus.NEEDS_REVIEW
                : run.status() == TaskRunStatus.FAILED ? PlanRunStatus.FAILED
                : PlanRunStatus.QUEUED,
            run.inputValues(), run.outputValues(), toPlanDefinition(run.taskSnapshot()),
            null, null, null,
            run.executionEvidence(), run.validationFeedback(), List.of(),
            run.finalMessage(), run.errorText(),
            run.createdAt(), run.updatedAt(), run.startedAt(), run.completedAt()
        );
    }

    private static List<TaskFieldDefinition> toTaskFieldDefs(List<PlanFieldDefinition> fields) {
        return fields.stream().map(f -> new TaskFieldDefinition(
            f.name(), toTaskValueType(f.type()), f.description(), f.required(), f.schema()
        )).toList();
    }

    private static List<PlanFieldDefinition> toPlanFieldDefs(List<TaskFieldDefinition> fields) {
        return fields.stream().map(f -> new PlanFieldDefinition(
            f.name(), toPlanFieldType(f.type()), false, f.description(), f.required(), f.schema()
        )).toList();
    }

    private static List<TaskStep> toTaskSteps(List<PlanStep> steps) {
        return steps.stream().map(s -> new TaskStep(s.order(), s.text())).toList();
    }

    private static List<PlanStep> toPlanSteps(List<TaskStep> steps) {
        return steps.stream().map(s -> new PlanStep(s.order(), s.text())).toList();
    }

    private static PlanFieldDefinition toPlanFieldDef(TaskFieldDefinition f) {
        return new PlanFieldDefinition(f.name(), toPlanFieldType(f.type()), false, f.description(), f.required(), f.schema());
    }

    private static TaskValueType toTaskValueType(PlanFieldType type) {
        return switch (type) {
            case STRING, USER_MESSAGE -> TaskValueType.STRING;
            case FILE_PATH -> TaskValueType.FILE_PATH;
            case NUMBER -> TaskValueType.NUMBER;
            case JSON -> TaskValueType.JSON;
        };
    }

    private static PlanFieldType toPlanFieldType(TaskValueType type) {
        return switch (type) {
            case STRING, LONG_TEXT, BOOLEAN -> PlanFieldType.STRING;
            case FILE_PATH -> PlanFieldType.FILE_PATH;
            case NUMBER -> PlanFieldType.NUMBER;
            case JSON -> PlanFieldType.JSON;
        };
    }
}
