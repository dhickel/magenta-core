package io.mindspice.magenta2.ai.chat.plan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspacePathLayout;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Unified plan/task service. Owns all definition and run lifecycle operations
 * for both in-session plans ({@link PlanKind#SESSION_PLAN}) and reusable task
 * templates ({@link PlanKind#TASK_TEMPLATE}).
 *
 * <p>In-session plans use {@code id = conversationId}. Task templates use a
 * UUID id with an optional {@code conversationId} tracking the drafting session.
 */
@Service
public class PlanService {
    private static final Logger log = LoggerFactory.getLogger(PlanService.class);
    private static final int MAX_QUEUED_QUESTIONS = 5;
    private static final Duration RUN_STAGING_RETENTION = Duration.ofDays(1);

    private final PlanRepository planRepository;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatMarkdownRenderer markdownRenderer;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final OutputArtifactService outputArtifactService;
    private final RootRelativePathService rootRelativePathService;
    private final RuntimeSettingsService runtimeSettingsService;
    private final Map<String, String> executionRunsByConversationId = new ConcurrentHashMap<>();

    public PlanService(PlanRepository planRepository, ChatMemoryRepository chatMemoryRepository) {
        this(planRepository, chatMemoryRepository, null, new ChatMarkdownRenderer(), null, null, null, null);
    }

    // Used by tests that need session metadata and markdown rendering
    public PlanService(
        PlanRepository planRepository,
        ChatMemoryRepository chatMemoryRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMarkdownRenderer markdownRenderer
    ) {
        this(planRepository, chatMemoryRepository, chatSessionMetadataRepository, markdownRenderer, null, null, null, null);
    }

    public PlanService(
        PlanRepository planRepository,
        ChatMemoryRepository chatMemoryRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMarkdownRenderer markdownRenderer,
        WorkspaceDirectoryService workspaceDirectoryService,
        OutputArtifactService outputArtifactService
    ) {
        this(planRepository, chatMemoryRepository, chatSessionMetadataRepository, markdownRenderer,
            workspaceDirectoryService, outputArtifactService, null, null);
    }

    @Autowired
    public PlanService(
        PlanRepository planRepository,
        ChatMemoryRepository chatMemoryRepository,
        @Autowired(required = false) ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMarkdownRenderer markdownRenderer,
        @Autowired(required = false) WorkspaceDirectoryService workspaceDirectoryService,
        @Autowired(required = false) OutputArtifactService outputArtifactService,
        @Autowired(required = false) RootRelativePathService rootRelativePathService,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService
    ) {
        this.planRepository = planRepository;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.markdownRenderer = markdownRenderer;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.outputArtifactService = outputArtifactService;
        this.rootRelativePathService = rootRelativePathService != null
            ? rootRelativePathService
            : workspaceDirectoryService == null ? null : new RootRelativePathService(workspaceDirectoryService);
        this.runtimeSettingsService = runtimeSettingsService;
    }

    // ════════════════════════════════════════════════════════════════
    //  Mode resolution
    // ════════════════════════════════════════════════════════════════

    public PlanMode mode(String conversationId) {
        // Check active execution run first
        String runId = activeRunId(conversationId);
        if (StringUtils.hasText(runId)) {
            PlanRun run = planRepository.findRun(runId).orElse(null);
            if (run != null && run.status() == PlanRunStatus.RUNNING) {
                PlanDefinition def = run.planSnapshot();
                return def.kind() == PlanKind.TASK_TEMPLATE ? PlanMode.EXECUTE_TASK : PlanMode.EXECUTE_PLAN;
            }
        }
        PlanDefinition def = planRepository.findDefinition(conversationId).orElse(null);
        if (def == null) {
            // Check if there's a task draft with this conversation
            def = planRepository.findDefinitionByConversationId(conversationId).orElse(null);
        }
        if (def == null) {
            return PlanMode.NORMAL;
        }
        if (def.kind() == PlanKind.TASK_TEMPLATE) {
            return def.status() == PlanStatus.APPROVED ? PlanMode.NORMAL : PlanMode.TASK;
        }
        // SESSION_PLAN
        if (def.status() == PlanStatus.EXECUTING) {
            return PlanMode.EXECUTE_PLAN;
        }
        if (def.status() == PlanStatus.APPROVED || def.status() == PlanStatus.NEEDS_REVIEW
            || def.status() == PlanStatus.COMPLETED || def.status() == PlanStatus.SAVED_TASK
            || def.status() == PlanStatus.CANCELLED) {
            return PlanMode.NORMAL;
        }
        return PlanMode.PLAN;
    }

    public String prePlanningModel(String conversationId) {
        return planRepository.findDefinition(conversationId)
            .map(PlanDefinition::planningModel)
            .filter(StringUtils::hasText)
            .orElse(null);
    }

    public String executionModel(String conversationId) {
        return planRepository.findDefinition(conversationId)
            .map(PlanDefinition::executionModel)
            .filter(StringUtils::hasText)
            .orElse(null);
    }

    public String finalMessage(String conversationId) {
        return planRepository.findDefinition(conversationId)
            .map(PlanDefinition::finalMessage)
            .orElse(null);
    }

    // ════════════════════════════════════════════════════════════════
    //  In-session plan (SESSION_PLAN) operations
    // ════════════════════════════════════════════════════════════════

    public PlanDefinition beginPlan(String conversationId, String prePlanningModel, String executionModel) {
        int startOrder = chatMemoryRepository.findByConversationId(conversationId).size();
        Instant now = Instant.now();
        PlanDefinition existing = planRepository.findDefinition(conversationId).orElse(null);
        return planRepository.saveDefinition(new PlanDefinition(
            conversationId,
            PlanKind.SESSION_PLAN,
            PlanStatus.DRAFT,
            "Untitled Plan",
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            normalize(prePlanningModel),
            normalize(executionModel),
            null,
            "goal_and_deliverables",
            anonymousOpeningQuestions(),
            0,
            startOrder,
            null,
            null,
            existing == null ? now : existing.createdAt(),
            now
        ));
    }

    public PlanDefinition beginPlan(String conversationId) {
        return beginPlan(conversationId, null, null);
    }

    public Path chatFileDirectory(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (workspaceDirectoryService == null) {
            throw new IllegalStateException("Workspace directory service is not available");
        }
        return workspaceDirectoryService.chatFiles(conversationId);
    }

    public Path persistChatFinalMessage(String conversationId, String finalMessage) {
        String normalized = normalize(finalMessage);
        if (normalized == null) {
            return null;
        }
        try {
            Path dir = chatFileDirectory(conversationId);
            Path target = dir.resolve("final-message.md");
            int suffix = 2;
            while (Files.exists(target)) {
                target = dir.resolve("final-message-" + suffix + ".md");
                suffix++;
            }
            Files.writeString(target, normalized + "\n");
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist chat final message", e);
        }
    }

    public PlanDefinition beginPlanFromDefinition(
        String conversationId,
        PlanDefinition source,
        String prePlanningModel,
        String executionModel
    ) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (source == null) {
            throw new IllegalArgumentException("source plan is required");
        }
        int startOrder = chatMemoryRepository.findByConversationId(conversationId).size();
        Instant now = Instant.now();
        PlanDefinition existing = planRepository.findDefinition(conversationId).orElse(null);
        return planRepository.saveDefinition(new PlanDefinition(
            conversationId,
            PlanKind.SESSION_PLAN,
            PlanStatus.DRAFT,
            StringUtils.hasText(source.title()) ? source.title() : "Untitled Plan",
            source.summary(),
            source.goal(),
            source.notes(),
            source.deliverables(),
            List.of(),
            List.of(),
            source.assumptions(),
            source.steps(),
            source.validationCriteria(),
            List.of(),
            List.of(),
            source.promptProfile(),
            normalize(prePlanningModel),
            normalize(executionModel),
            source.settingsOverrideJson(),
            StringUtils.hasText(source.planningTask()) ? source.planningTask() : "clarify_and_elaborate",
            List.of(),
            0,
            startOrder,
            null,
            null,
            existing == null ? now : existing.createdAt(),
            now
        ));
    }

    public Optional<PlanDefinition> activePlan(String conversationId) {
        return planRepository.findDefinition(conversationId);
    }

    public List<String> listConversationIds() {
        return planRepository.findConversationIds();
    }

    public PlanDefinition setGoal(String conversationId, String goal) {
        PlanDefinition existing = requirePlanMode(conversationId, "plan_set_goal");
        String normalizedGoal = normalize(goal);
        if (normalizedGoal == null) {
            throw new IllegalArgumentException("plan_set_goal requires a goal");
        }
        String newTitle = choose(existing.title(), titleFromGoal(normalizedGoal));
        return planRepository.saveDefinition(existing
            .withGoal(normalizedGoal)
            .withTitle(newTitle)
            .withPlanningTask(nextTaskAfterGoal(existing)));
    }

    public PlanDefinition setPlanningTask(String conversationId, String planningTask) {
        PlanDefinition existing = requirePlanMode(conversationId, "plan_set_task");
        String normalizedTask = normalize(planningTask);
        if (normalizedTask == null) {
            throw new IllegalArgumentException("plan_set_task requires a current planning task");
        }
        return planRepository.saveDefinition(existing.withPlanningTask(normalizedTask));
    }

    public PlanDefinition putItem(String conversationId, String sectionName, Integer key, String text) {
        PlanDefinition existing = requirePlanMode(conversationId, "plan_put_item");
        PlanSection section = PlanSection.fromToolName(sectionName);
        if (section == PlanSection.INPUT || section == PlanSection.OUTPUT) {
            throw new IllegalArgumentException("Anonymous chat plans do not define structured inputs or outputs; use deliverables instead");
        }
        int itemKey = requirePositiveKey(key, "plan_put_item");
        String normalizedText = normalize(text);
        if (normalizedText == null) {
            throw new IllegalArgumentException("plan_put_item requires text");
        }
        return planRepository.saveDefinition(withSection(existing, section, itemKey, normalizedText, null, false));
    }

    public PlanDefinition deleteItem(String conversationId, String sectionName, Integer key) {
        PlanDefinition existing = requirePlanMode(conversationId, "plan_delete_item");
        PlanSection section = PlanSection.fromToolName(sectionName);
        int itemKey = requirePositiveKey(key, "plan_delete_item");
        return planRepository.saveDefinition(withSection(existing, section, itemKey, null, null, true));
    }

    public PlanDefinition askQuestions(String conversationId, List<String> questions) {
        PlanDefinition existing = requirePlanMode(conversationId, "ask_user_questions");
        List<String> cleanQuestions = cleanList(questions);
        if (cleanQuestions.isEmpty()) {
            throw new IllegalArgumentException("ask_user_questions requires at least one question");
        }
        if (cleanQuestions.size() > MAX_QUEUED_QUESTIONS) {
            throw new IllegalArgumentException("ask_user_questions accepts at most five questions");
        }
        return planRepository.saveDefinition(existing
            .withPlanningTask("clarification_questions")
            .withPendingQuestions(cleanQuestions, 0));
    }

    public PlanDefinition recordPromptAnswer(String conversationId, String answer, String notes) {
        return recordPromptAnswer(conversationId, answer, notes, null);
    }

    public PlanDefinition recordPromptAnswer(String conversationId, String answer, String notes, Integer expectedQuestionIndex) {
        PlanDefinition plan = requirePlanConversation(conversationId, "planning answer");
        if (!plan.hasPendingQuestion()) {
            throw new IllegalStateException("No active planning question exists for this conversation");
        }
        int currentQuestionIndex = plan.pendingQuestionIndex() + 1;
        if (expectedQuestionIndex != null && expectedQuestionIndex != currentQuestionIndex) {
            throw new IllegalStateException(
                "Stale planning answer. Expected question " + currentQuestionIndex + " but received " + expectedQuestionIndex
            );
        }
        if (!StringUtils.hasText(answer) && !StringUtils.hasText(notes)) {
            throw new IllegalArgumentException("Planning answer requires an answer");
        }
        String question = plan.currentQuestion();
        appendPlanningAnswer(conversationId, question, answer, notes);
        int nextIndex = plan.pendingQuestionIndex() + 1;
        List<String> pendingQuestions = nextIndex >= plan.pendingQuestions().size()
            ? List.of()
            : plan.pendingQuestions();
        return planRepository.saveDefinition(plan
            .withPendingQuestions(pendingQuestions, pendingQuestions.isEmpty() ? 0 : nextIndex));
    }

    public PlanDefinition markReadyForApproval(String conversationId) {
        PlanDefinition plan = requirePlanMode(conversationId, "plan_ready_for_approval");
        if (plan.hasPendingQuestion()) {
            throw new IllegalStateException("plan_ready_for_approval requires all queued planning questions to be answered");
        }
        validateComplete(plan, "plan_ready_for_approval");
        return planRepository.saveDefinition(plan
            .withStatus(PlanStatus.READY_FOR_APPROVAL)
            .withPlanningTask("approval")
            .withPendingQuestions(List.of(), 0));
    }

    public PlanDefinition approvePlan(String conversationId) {
        PlanDefinition plan = requirePlanConversation(conversationId, "approve plan");
        validateComplete(plan, "approve plan");
        return planRepository.saveDefinition(plan
            .withStatus(PlanStatus.APPROVED)
            .withPendingQuestions(List.of(), 0));
    }

    public PlanDefinition saveAsTask(String conversationId) {
        throw new IllegalStateException("Anonymous chat plans cannot be saved as task templates. Create saved plans from /plans.");
    }

    public PlanDefinition saveAsTask(String conversationId, String taskTitle) {
        throw new IllegalStateException("Anonymous chat plans cannot be saved as task templates. Create saved plans from /plans.");
    }

    public PlanDefinition markExecuting(String conversationId) {
        PlanDefinition plan = requireSavedPlan(conversationId);
        return planRepository.saveDefinition(plan
            .withStatus(PlanStatus.EXECUTING)
            .withPlanningTask(null)
            .withExecutionEvidence(List.of())
            .withValidationFeedback(List.of())
            .withPendingQuestions(List.of(), 0)
            .withFinalMessage(null));
    }

    public PlanDefinition markCompleted(String conversationId) {
        return markCompleted(conversationId, null);
    }

    public PlanDefinition markCompleted(String conversationId, String finalMessage) {
        PlanDefinition plan = requirePlanConversation(conversationId, "mark completed");
        return planRepository.saveDefinition(plan
            .withStatus(PlanStatus.COMPLETED)
            .withPendingQuestions(List.of(), 0)
            .withFinalMessage(finalMessage));
    }

    public PlanDefinition markNeedsReview(String conversationId) {
        PlanDefinition plan = requirePlanConversation(conversationId, "mark needs review");
        return planRepository.saveDefinition(plan
            .withStatus(PlanStatus.NEEDS_REVIEW)
            .withPendingQuestions(List.of(), 0));
    }

    public PlanDefinition recordExecutionReport(
        String conversationId,
        String summary,
        List<String> evidence,
        List<String> deviations,
        List<String> unmetCriteria,
        List<String> artifactPaths
    ) {
        PlanDefinition plan = requirePlanConversation(conversationId, "record execution");
        if (plan.status() != PlanStatus.EXECUTING) {
            throw new IllegalStateException("plan_report is available only while executing a saved plan");
        }
        List<String> entries = new ArrayList<>();
        addLabeled(entries, "Summary", normalize(summary));
        addLabeled(entries, "Evidence", cleanList(evidence));
        addLabeled(entries, "Deviation", cleanList(deviations));
        addLabeled(entries, "Unmet criterion", cleanList(unmetCriteria));
        addLabeled(entries, "Artifact", cleanList(artifactPaths));
        if (entries.isEmpty()) {
            entries.add("Summary: execution reported no details.");
        }
        List<String> updatedEvidence = new ArrayList<>(plan.executionEvidence());
        updatedEvidence.addAll(entries);
        return planRepository.saveDefinition(plan.withExecutionEvidence(updatedEvidence));
    }

    public PlanDefinition recordFallbackExecutionEvidence(String conversationId) {
        PlanDefinition plan = requirePlanConversation(conversationId, "fallback evidence");
        if (!plan.executionEvidence().isEmpty()) {
            return plan;
        }
        return planRepository.saveDefinition(plan.withExecutionEvidence(
            List.of("Deviation: execution returned without a structured completion ledger.")
        ));
    }

    public PlanDefinition recordValidationFeedback(String conversationId, List<String> feedback) {
        PlanDefinition plan = requirePlanConversation(conversationId, "validation feedback");
        return planRepository.saveDefinition(plan.withValidationFeedback(cleanList(feedback)));
    }

    public PlanDefinition updateDraftPlan(
        String conversationId,
        String goal,
        String title,
        String summary,
        String notes,
        List<String> deliverables,
        List<String> steps,
        List<String> acceptanceCriteria
    ) {
        return updateDraftPlan(conversationId, goal, title, summary, notes, deliverables, null, null, null, steps, acceptanceCriteria);
    }

    public PlanDefinition updateDraftPlan(
        String conversationId,
        String goal,
        String title,
        String summary,
        String notes,
        List<String> deliverables,
        List<String> inputs,
        List<String> outputs,
        List<String> assumptions,
        List<String> steps,
        List<String> acceptanceCriteria
    ) {
        PlanDefinition existing = requirePlanMode(conversationId, "plan_update");
        List<PlanFieldDefinition> updatedInputs = existing.kind() == PlanKind.SESSION_PLAN
            ? existing.inputs()
            : (inputs == null ? existing.inputs() : textFieldList(inputs));
        List<PlanFieldDefinition> updatedOutputs = existing.kind() == PlanKind.SESSION_PLAN
            ? existing.outputs()
            : (outputs == null ? existing.outputs() : textFieldList(outputs));
        return planRepository.saveDefinition(new PlanDefinition(
            existing.id(),
            existing.kind(),
            existing.status(),
            choose(title, existing.title()),
            choose(summary, existing.summary()),
            choose(goal, existing.goal()),
            mergeNotes(existing.notes(), notes),
            deliverables == null ? existing.deliverables() : cleanList(deliverables),
            updatedInputs,
            updatedOutputs,
            assumptions == null ? existing.assumptions() : cleanList(assumptions),
            steps == null ? existing.steps() : orderedSteps(steps),
            acceptanceCriteria == null ? existing.validationCriteria() : cleanList(acceptanceCriteria),
            existing.executionEvidence(),
            existing.validationFeedback(),
            existing.promptProfile(),
            existing.planningModel(),
            existing.executionModel(),
            existing.settingsOverrideJson(),
            existing.planningTask(),
            existing.pendingQuestions(),
            existing.pendingQuestionIndex(),
            existing.planStartMessageOrder(),
            existing.finalMessage(),
            existing.conversationId(),
            existing.createdAt(),
            Instant.now()
        ));
    }

    public PlanDefinition saveDraftPlan(
        String conversationId,
        String goal,
        String title,
        String summary,
        String notes,
        List<String> steps,
        List<String> assumptions,
        List<String> acceptanceCriteria
    ) {
        return updateDraftPlan(conversationId, goal, title, summary, notes,
            StringUtils.hasText(title) ? List.of(title.trim()) : null,
            null, null, assumptions, steps, acceptanceCriteria);
    }

    public void exitPlan(String conversationId) {
        planRepository.findDefinition(conversationId).ifPresent(plan -> {
            trimConversation(conversationId, plan.planStartMessageOrder());
            planRepository.deleteDefinition(conversationId);
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  Task template (TASK_TEMPLATE) operations
    // ════════════════════════════════════════════════════════════════

    public List<PlanDefinition> listTasks() {
        return planRepository.findAllDefinitions().stream()
            .filter(def -> def.kind() == PlanKind.TASK_TEMPLATE)
            .toList();
    }

    public PlanDefinition getTask(String id) {
        return planRepository.findDefinition(id)
            .orElseThrow(() -> new IllegalStateException("Task not found: " + id));
    }

    public PlanDefinition saveTask(PlanDefinition task) {
        String id = StringUtils.hasText(task.id()) ? task.id() : UUID.randomUUID().toString();
        String title = normalize(task.title());
        if (title == null) {
            throw new IllegalArgumentException("Task title is required");
        }
        validateFieldNames(task.inputs(), "input");
        validateFieldNames(task.outputs(), "output");
        // Preserve DRAFT and READY_FOR_APPROVAL status; otherwise default to APPROVED
        PlanStatus resolvedStatus = task.status() == PlanStatus.DRAFT
            || task.status() == PlanStatus.READY_FOR_APPROVAL
            ? task.status()
            : PlanStatus.APPROVED;
        return planRepository.saveDefinition(new PlanDefinition(
            id,
            PlanKind.TASK_TEMPLATE,
            resolvedStatus,
            title,
            normalize(task.summary()),
            normalize(task.goal()),
            normalize(task.notes()),
            cleanList(task.deliverables()),
            cleanFields(task.inputs()),
            cleanFields(task.outputs()),
            cleanList(task.assumptions()),
            cleanSteps(task.steps()),
            cleanList(task.validationCriteria()),
            List.of(),
            List.of(),
            normalize(task.promptProfile()),
            normalize(task.planningModel()),
            normalize(task.executionModel()),
            task.settingsOverrideJson(),
            resolvedStatus == PlanStatus.DRAFT || resolvedStatus == PlanStatus.READY_FOR_APPROVAL ? task.planningTask() : null,
            resolvedStatus == PlanStatus.DRAFT || resolvedStatus == PlanStatus.READY_FOR_APPROVAL ? task.pendingQuestions() : List.of(),
            resolvedStatus == PlanStatus.DRAFT || resolvedStatus == PlanStatus.READY_FOR_APPROVAL ? task.pendingQuestionIndex() : 0,
            0,
            null,
            null,
            task.createdAt(),
            task.updatedAt()
        ));
    }

    public PlanDefinition updateSavedTaskFields(String planId, String title, String summary, String goal) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_update_fields");
        return saveTask(task
            .withTitle(choose(title, task.title()))
            .withSummary(choose(summary, task.summary()))
            .withGoal(choose(goal, task.goal())));
    }

    public PlanDefinition setSavedTaskPlanningTask(String planId, String planningTask) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_set_task");
        String normalizedTask = normalizePlanningTask(planningTask);
        if (normalizedTask == null) {
            throw new IllegalArgumentException("saved_plan_set_task requires a current task");
        }
        return saveTask(task.withPlanningTask(normalizedTask));
    }

    public PlanDefinition putSavedTaskTextItem(String planId, String section, Integer key, String text) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_put_item");
        int itemKey = requirePositiveKey(key);
        String normalizedText = normalize(text);
        if (normalizedText == null) {
            throw new IllegalArgumentException("saved_plan_put_item requires text");
        }
        PlanSection planSection = PlanSection.fromToolName(section);
        if (planSection == PlanSection.INPUT || planSection == PlanSection.OUTPUT) {
            throw new IllegalArgumentException("saved_plan_put_item requires field details for input and output sections");
        }
        return saveTask(withSection(task, planSection, itemKey, normalizedText, null, false));
    }

    public PlanDefinition putSavedTaskFieldItem(String planId, String section, Integer key, PlanFieldDefinition field) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_put_item");
        PlanSection planSection = PlanSection.fromToolName(section);
        if (planSection != PlanSection.INPUT && planSection != PlanSection.OUTPUT) {
            throw new IllegalArgumentException("saved_plan_put_item field details are only valid for input and output sections");
        }
        PlanFieldDefinition cleanField = cleanField(field);
        if (cleanField == null) {
            throw new IllegalArgumentException("saved_plan_put_item requires a named input or output");
        }
        return saveTask(withSection(task, planSection, requirePositiveKey(key), null, cleanField, false));
    }

    public PlanDefinition deleteSavedTaskItem(String planId, String section, Integer key) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_delete_item");
        return saveTask(withSection(task, PlanSection.fromToolName(section), requirePositiveKey(key), null, null, true));
    }

    public PlanDefinition askSavedTaskQuestions(String planId, List<String> questions) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_ask_user_questions");
        List<String> cleanQuestions = cleanList(questions);
        if (cleanQuestions.isEmpty()) {
            throw new IllegalArgumentException("saved_plan_ask_user_questions requires at least one question");
        }
        if (cleanQuestions.size() > MAX_QUEUED_QUESTIONS) {
            throw new IllegalArgumentException("saved_plan_ask_user_questions accepts at most five questions");
        }
        return saveTask(task
            .withPlanningTask("saved_plan_clarification_questions")
            .withPendingQuestions(cleanQuestions, 0));
    }

    public PlanDefinition markSavedTaskReadyForApproval(String planId) {
        PlanDefinition task = requireSavedTaskDraft(planId, "saved_plan_ready_for_approval");
        if (task.hasPendingQuestion()) {
            throw new IllegalStateException("saved_plan_ready_for_approval requires all queued questions to be answered");
        }
        validateSavedPlanComplete(task, "saved_plan_ready_for_approval");
        return saveTask(task
            .withStatus(PlanStatus.READY_FOR_APPROVAL)
            .withPlanningTask("approval")
            .withPendingQuestions(List.of(), 0));
    }

    /**
     * Finalize a task template: validate completeness and set status to APPROVED.
     * Only DRAFT and READY_FOR_APPROVAL tasks can be finalized.
     * Already approved tasks are returned unchanged.
     */
    public PlanDefinition finalizeTask(String taskId) {
        PlanDefinition existing = getTask(taskId);
        if (existing.status() == PlanStatus.APPROVED) {
            return existing;
        }
        if (existing.status() != PlanStatus.DRAFT && existing.status() != PlanStatus.READY_FOR_APPROVAL) {
            throw new IllegalStateException(
                "Only DRAFT or READY_FOR_APPROVAL tasks can be finalized. Current status: " + existing.status());
        }
        return saveTask(existing.withStatus(PlanStatus.APPROVED));
    }

    public void deleteTask(String id) {
        planRepository.deleteDefinition(id);
    }

    public PlanDefinition beginDraft(String conversationId, String prePlanningModel, String executionModel) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        Instant now = Instant.now();
        PlanDefinition existing = planRepository.findDefinitionByConversationId(conversationId).orElse(null);
        String draftId = existing != null ? existing.id() : UUID.randomUUID().toString();
        return planRepository.saveDefinition(new PlanDefinition(
            draftId,
            PlanKind.TASK_TEMPLATE,
            PlanStatus.DRAFT,
            "Untitled Task",
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            normalize(prePlanningModel),
            normalize(executionModel),
            null,
            "define_runtime_inputs",
            List.of("What runtime inputs should this reusable task accept?"),
            0,
            0,
            null,
            conversationId,
            existing == null ? now : existing.createdAt(),
            now
        ));
    }

    public Optional<PlanDefinition> activeDraft(String conversationId) {
        return planRepository.findDefinitionByConversationId(conversationId);
    }

    public List<String> listDraftConversationIds() {
        return planRepository.findDraftConversationIds();
    }

    public PlanDefinition setGoal(String conversationId, String goal, boolean isTask) {
        if (isTask) {
            return setTaskGoal(conversationId, goal);
        }
        return setGoal(conversationId, goal);
    }

    public PlanDefinition setTaskGoal(String conversationId, String goal) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_set_goal");
        String normalizedGoal = normalize(goal);
        if (normalizedGoal == null) {
            throw new IllegalArgumentException("task_set_goal requires a goal");
        }
        return saveTaskDraft(draft.withPlanningTask("define_outputs").withGoal(normalizedGoal));
    }

    public PlanDefinition setTask(String conversationId, String planningTask) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_set_task");
        String normalizedTask = normalizePlanningTask(planningTask);
        if (normalizedTask == null) {
            throw new IllegalArgumentException("task_set_task requires a current task");
        }
        return saveTaskDraft(draft.withPlanningTask(normalizedTask));
    }

    public PlanDefinition putTextItem(String conversationId, String section, Integer key, String text) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_put_item");
        int itemKey = requirePositiveKey(key);
        String normalizedText = normalize(text);
        if (normalizedText == null) {
            throw new IllegalArgumentException("task_put_item requires text");
        }
        return saveTaskDraft(withSection(draft, PlanSection.fromToolName(section), itemKey, normalizedText, null, false));
    }

    public PlanDefinition putFieldItem(String conversationId, String section, Integer key, PlanFieldDefinition field) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_put_item");
        int itemKey = requirePositiveKey(key);
        PlanFieldDefinition cleanField = cleanField(field);
        if (cleanField == null) {
            throw new IllegalArgumentException("task_put_item requires a named input or output");
        }
        return saveTaskDraft(withSection(draft, PlanSection.fromToolName(section), itemKey, null, cleanField, false));
    }

    public PlanDefinition deleteTaskItem(String conversationId, String section, Integer key) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_delete_item");
        return saveTaskDraft(withSection(draft, PlanSection.fromToolName(section), requirePositiveKey(key), null, null, true));
    }

    public PlanDefinition askTaskQuestions(String conversationId, List<String> questions) {
        PlanDefinition draft = requireTaskDraft(conversationId, "ask_user_questions");
        List<String> cleanQuestions = cleanList(questions);
        if (cleanQuestions.isEmpty()) {
            throw new IllegalArgumentException("ask_user_questions requires at least one question");
        }
        if (cleanQuestions.size() > MAX_QUEUED_QUESTIONS) {
            throw new IllegalArgumentException("ask_user_questions accepts at most five questions");
        }
        return saveTaskDraft(draft.withPlanningTask("clarification_questions")
            .withPendingQuestions(cleanQuestions, 0));
    }

    public PlanDefinition recordTaskPromptAnswer(String conversationId, String answer, String notes, Integer expectedQuestionIndex) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task answer");
        if (!draft.hasPendingQuestion()) {
            throw new IllegalStateException("No active task question exists for this conversation");
        }
        int currentQuestionIndex = draft.pendingQuestionIndex() + 1;
        if (expectedQuestionIndex != null && expectedQuestionIndex != currentQuestionIndex) {
            throw new IllegalStateException(
                "Stale task answer. Expected question " + currentQuestionIndex + " but received " + expectedQuestionIndex
            );
        }
        if (!StringUtils.hasText(answer) && !StringUtils.hasText(notes)) {
            throw new IllegalArgumentException("Task answer requires an answer");
        }
        int nextIndex = draft.pendingQuestionIndex() + 1;
        List<String> pendingQuestions = nextIndex >= draft.pendingQuestions().size()
            ? List.of()
            : draft.pendingQuestions();
        return saveTaskDraft(draft
            .withNotes(appendAnswerNote(draft.notes(), draft.currentQuestion(), answer, notes))
            .withPendingQuestions(pendingQuestions, pendingQuestions.isEmpty() ? 0 : nextIndex));
    }

    public PlanDefinition markTaskReadyForApproval(String conversationId) {
        PlanDefinition draft = requireTaskDraft(conversationId, "task_ready_for_approval");
        if (draft.hasPendingQuestion()) {
            throw new IllegalStateException("task_ready_for_approval requires all queued questions to be answered");
        }
        validateTaskComplete(draft, "task_ready_for_approval");
        return saveTaskDraft(draft
            .withStatus(PlanStatus.READY_FOR_APPROVAL)
            .withPlanningTask("approval")
            .withPendingQuestions(List.of(), 0));
    }

    public PlanDefinition approveDraft(String conversationId) {
        PlanDefinition draft = requireTaskDraft(conversationId, "approve task");
        validateTaskComplete(draft, "approve task");
        PlanDefinition task = saveTask(draft);
        saveTaskDraft(draft
            .withStatus(PlanStatus.APPROVED)
            .withPlanningTask("approved")
            .withPendingQuestions(List.of(), 0));
        return task;
    }

    // ════════════════════════════════════════════════════════════════
    //  Plan run operations (unified)
    // ════════════════════════════════════════════════════════════════

    public PlanRun startRun(String planId, Map<String, Object> inputValues) {
        return startRun(planId, inputValues, null);
    }

    /**
     * Start a plan run with optional orchestration context. When context contains
     * an agentId, output directories are allocated under the agent's output root
     * instead of the system default.
     */
    public PlanRun startRun(String planId, Map<String, Object> inputValues,
                             OrchestrationTaskContext context) {
        PlanDefinition definition = planRepository.findDefinition(planId)
            .orElseThrow(() -> new IllegalStateException("Plan/task not found: " + planId));
        Map<String, Object> cleanInputs = cleanMap(inputValues);
        List<String> missing = missingRequiredInputs(definition, cleanInputs);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required input(s): " + String.join(", ", missing));
        }
        Instant now = Instant.now();
        String runId = UUID.randomUUID().toString();
        String runDisplayName = context == null ? null : context.runDisplayName();

        String workspaceType = context != null ? context.workspaceType() : null;
        String workspaceKey = context != null ? context.workspaceKey() : null;
        String workspaceIdentity = context != null ? context.workspaceIdentity() : null;

        return planRepository.saveRun(new PlanRun(
            runId,
            definition.id(),
            runDisplayName,
            PlanRunStatus.RUNNING,
            cleanInputs,
            Map.of(),
            definition,
            workspaceType,
            workspaceKey,
            workspaceIdentity,
            List.of(definition.kind() == PlanKind.TASK_TEMPLATE ? "Task run started." : "Plan execution started."),
            List.of(),
            List.of(),
            null,
            null,
            now,
            now,
            now,
            null
        ));
    }

    public PlanRun startChatExecution(String conversationId, String planId, Map<String, Object> inputValues) {
        PlanRun run = startRun(planId, inputValues);
        registerExecutionContext(conversationId, run.id());
        return run;
    }

    public PlanRun startChatExecution(String conversationId, String planId, Map<String, Object> inputValues,
                                      OrchestrationTaskContext context) {
        PlanRun run = startRun(planId, inputValues, context);
        registerExecutionContext(conversationId, run.id());
        return run;
    }

    public void registerExecutionContext(String conversationId, String runId) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(runId)) {
            throw new IllegalArgumentException("conversationId and runId are required");
        }
        PlanRun run = requireRun(runId);
        if (run.status() != PlanRunStatus.RUNNING) {
            throw new IllegalStateException("Plan run is not active: " + runId);
        }
        executionRunsByConversationId.put(conversationId, runId);
        if (chatSessionMetadataRepository != null) {
            chatSessionMetadataRepository.saveActiveTaskRunId(conversationId, runId);
        }
    }

    public void clearExecutionContext(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            executionRunsByConversationId.remove(conversationId);
            if (chatSessionMetadataRepository != null) {
                chatSessionMetadataRepository.clearActiveTaskRunId(conversationId);
            }
        }
    }

    public String runIdForConversation(String conversationId) {
        return activeRunId(conversationId);
    }

    public String runFinalMessage(String runId) {
        return requireRun(runId).finalMessage();
    }

    public PlanRun recordRunReport(String runId, String summary, List<String> evidence) {
        PlanRun run = requireRun(runId);
        if (run.status() != PlanRunStatus.RUNNING) {
            throw new IllegalStateException("report is available only while a run is active");
        }
        List<String> entries = new ArrayList<>(run.executionEvidence());
        String normalizedSummary = normalize(summary);
        if (normalizedSummary != null) {
            entries.add("Summary: " + normalizedSummary);
        }
        for (String value : cleanList(evidence)) {
            entries.add("Evidence: " + value);
        }
        return planRepository.saveRun(run.withExecutionEvidence(entries));
    }

    public PlanRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage, List<String> evidence) {
        return completeRun(runId, outputValues, finalMessage, evidence, List.of());
    }

    public PlanRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage,
                               List<String> evidence, boolean includeTempWithOutput) {
        return completeRun(runId, outputValues, finalMessage, evidence, List.of(), includeTempWithOutput);
    }

    public PlanRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage,
                                List<String> evidence, List<String> deliverableEvidence) {
        return completeRun(runId, outputValues, finalMessage, evidence, deliverableEvidence, false);
    }

    public PlanRun completeRun(String runId, Map<String, Object> outputValues, String finalMessage,
                                List<String> evidence, List<String> deliverableEvidence,
                                boolean includeTempWithOutput) {
        PlanRun run = requireRun(runId);
        if (run.status() != PlanRunStatus.RUNNING) {
            throw new IllegalStateException("complete is available only while a run is active");
        }
        PlanDefinition task = run.planSnapshot();
        Map<String, Object> cleanOutputs = cleanMap(outputValues);
        List<String> missing = missingRequiredOutputs(task, cleanOutputs);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required output(s): " + String.join(", ", missing));
        }

        // Materialize outputs to the output directory

        List<String> entries = new ArrayList<>(run.executionEvidence());
        entries.addAll(cleanList(evidence).stream().map(value -> "Evidence: " + value).toList());
        if (entries.isEmpty()) {
            entries.add("Summary: plan/task completed.");
        }

        PlanRun completed = planRepository.saveRun(run
            .withStatus(PlanRunStatus.COMPLETED)
            .withOutputValues(cleanOutputs)
            .withExecutionEvidence(entries)
            .withDeliverableEvidence(cleanList(deliverableEvidence))
            .withFinalMessage(normalize(finalMessage))
            .withCompletedAt(Instant.now()));

        return completed;
    }

    public PlanRun failRun(String runId, String errorText) {
        PlanRun run = requireRun(runId);
        return planRepository.saveRun(run
            .withStatus(PlanRunStatus.FAILED)
            .withErrorText(normalize(errorText))
            .withCompletedAt(Instant.now()));
    }

    public PlanRun markRunNeedsReview(String runId, String reason) {
        PlanRun run = requireRun(runId);
        List<String> feedback = new ArrayList<>(run.validationFeedback());
        String normalizedReason = normalize(reason);
        if (normalizedReason != null) {
            feedback.add(normalizedReason);
        }
        return planRepository.saveRun(run
            .withStatus(PlanRunStatus.NEEDS_REVIEW)
            .withValidationFeedback(feedback)
            .withCompletedAt(Instant.now()));
    }

    public PlanRun markActiveRunNeedsReview(String conversationId, String reason) {
        String runId = activeRunId(conversationId);
        if (!StringUtils.hasText(runId)) {
            throw new IllegalStateException("No active plan run exists for this conversation");
        }
        try {
            return markRunNeedsReview(runId, reason);
        } finally {
            clearExecutionContext(conversationId);
        }
    }

    public PlanRun failActiveRun(String conversationId, String errorText) {
        String runId = activeRunId(conversationId);
        if (!StringUtils.hasText(runId)) {
            throw new IllegalStateException("No active plan run exists for this conversation");
        }
        try {
            return failRun(runId, errorText);
        } finally {
            clearExecutionContext(conversationId);
        }
    }

    public PlanRun getRun(String runId) {
        return requireRun(runId);
    }

    public List<PlanRun> listRuns(String planId) {
        return planRepository.findRunsByPlanId(planId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Runtime instructions
    // ════════════════════════════════════════════════════════════════

    public String runtimeInstructions(String conversationId) {
        // Check for active task run
        String runId = activeRunId(conversationId);
        if (StringUtils.hasText(runId)) {
            PlanRun run = planRepository.findRun(runId).orElse(null);
            if (run != null && run.status() == PlanRunStatus.RUNNING) {
                return executionInstructions(run);
            }
        }
        PlanDefinition def = planRepository.findDefinition(conversationId).orElse(null);
        if (def == null) {
            def = planRepository.findDefinitionByConversationId(conversationId).orElse(null);
        }
        if (def == null) {
            return "";
        }
        if (def.kind() == PlanKind.TASK_TEMPLATE && def.status() != PlanStatus.APPROVED) {
            return taskDraftInstructions(def);
        }
        if (def.kind() == PlanKind.SESSION_PLAN && def.status() == PlanStatus.EXECUTING) {
            return planExecutionInstructions(def);
        }
        if (def.kind() == PlanKind.SESSION_PLAN && def.status() != PlanStatus.APPROVED
            && def.status() != PlanStatus.NEEDS_REVIEW && def.status() != PlanStatus.COMPLETED
            && def.status() != PlanStatus.CANCELLED) {
            return planningInstructions(def);
        }
        return "";
    }

    public ChatPlanState view(String conversationId) {
        PlanDefinition def = planRepository.findDefinition(conversationId).orElse(null);
        if (def == null) {
            def = planRepository.findDefinitionByConversationId(conversationId).orElse(null);
        }
        if (def == null) {
            return ChatPlanState.normal();
        }
        String approvalMarkdown = def.status() == PlanStatus.READY_FOR_APPROVAL ? approvalMarkdown(def) : null;
        String documentMarkdown = def.hasSavedPlan() ? approvalMarkdown(def) : null;
        return new ChatPlanState(
            mode(conversationId).name(),
            def.status().name(),
            def.planningTask(),
            def.title(),
            def.summary(),
            def.goal(),
            def.notes(),
            def.deliverables(),
            def.kind() == PlanKind.SESSION_PLAN ? List.of() : inputNames(def.inputs()),
            def.kind() == PlanKind.SESSION_PLAN ? List.of() : outputNames(def.outputs()),
            def.assumptions(),
            def.steps().stream().map(PlanStep::text).toList(),
            def.validationCriteria(),
            def.executionEvidence(),
            def.validationFeedback(),
            def.hasPendingQuestion() ? "questions" : null,
            def.currentQuestion(),
            List.of(),
            def.hasPendingQuestion() ? def.pendingQuestionIndex() + 1 : 0,
            def.hasPendingQuestion() ? def.pendingQuestions().size() : 0,
            approvalMarkdown,
            approvalMarkdown == null ? null : markdownRenderer.render(approvalMarkdown),
            documentMarkdown,
            documentMarkdown == null ? null : markdownRenderer.render(documentMarkdown)
        );
    }

    public String approvalMarkdown(String conversationId) {
        PlanDefinition plan = requirePlanConversation(conversationId, "approval markdown");
        return plan.hasSavedPlan() ? approvalMarkdown(plan) : null;
    }

    public String approvalMarkdown(PlanDefinition plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(planTitle(plan)).append("\n\n");
        appendMarkdownValue(builder, "Goal", plan.goal());
        appendMarkdownValue(builder, "Summary", plan.summary());
        appendMarkdownList(builder, "Deliverables", plan.deliverables());
        if (plan.kind() != PlanKind.SESSION_PLAN && !plan.inputs().isEmpty()) {
            builder.append("## Inputs\n\n");
            for (PlanFieldDefinition input : plan.inputs()) {
                builder.append("- ").append(fieldSummary(input)).append("\n");
            }
            builder.append("\n");
        }
        if (plan.kind() != PlanKind.SESSION_PLAN && !plan.outputs().isEmpty()) {
            builder.append("## Outputs\n\n");
            for (PlanFieldDefinition output : plan.outputs()) {
                builder.append("- ").append(fieldSummary(output)).append("\n");
            }
            builder.append("\n");
        }
        appendMarkdownList(builder, "Assumptions", plan.assumptions());
        appendMarkdownValue(builder, "Notes", plan.notes());
        if (!plan.steps().isEmpty()) {
            builder.append("## Execution Steps\n\n");
            for (PlanStep step : plan.steps()) {
                builder.append(step.order()).append(". ").append(step.text()).append("\n");
            }
            builder.append("\n");
        }
        appendMarkdownList(builder, "Validation Criteria", plan.validationCriteria());
        return builder.toString().trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  Prompt instructions (package-private for tool access)
    // ════════════════════════════════════════════════════════════════

    String planningInstructions(PlanDefinition plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
You are Magenta in PLAN mode.

Your job is to turn the user's intent into a clear, approved execution plan. Do not perform implementation work in PLAN mode.

Required workflow:
1. Treat the three backend-seeded opening answers as authoritative seed context: goal, assumptions/details/expectations/approach, and expected deliverables.
2. Set the goal with plan_set_goal and define concrete deliverables with plan_put_item using integer keys.
3. Continue questioning only for missing or ambiguous planning details. Ask domain-specific follow-up questions that clarify tradeoffs, assumptions, steps, deliverables, or validation.
4. Build a structured approach: use plan_put_item with integer keys to add deliverables, steps, assumptions, notes, and validation criteria. Formulate each step with associated assumptions, notes, and validation criteria.
5. When the draft is complete, call plan_ready_for_approval. Do not send a normal message asking for approval, and do not claim approval until the user approves through the planning UI.

Turn contract:
- Stay self-iterating while useful planning work remains available: call as many read-only tools, research tools, and keyed planning edit tools as needed before relinquishing control to the user.
- Every PLAN-mode assistant turn that relinquishes control to the user must move planning forward by ending in one of these states:
  - one specific queued planning question through ask_user_questions,
  - a group of individual questions through ask_user_questions (each question string must be a single, atomic question — do not pack multiple numbered questions into one string),
  - a complete draft marked with plan_ready_for_approval.
- Each user-visible message must be the result of queued questions or approval-ready state. Do not end with free-form planning discussion.
- Do not end a PLAN-mode turn with only a conversational summary, analysis, or draft text.
- If the draft is not ready for approval, ask the next concrete planning question instead of inventing preferences or silently locking assumptions.
- A plan is not ready for approval until user-facing intent and tradeoffs have either been answered by the user or are explicitly confirmed as assumptions for approval.
- Once the draft is ready, call plan_ready_for_approval instead of messaging the user directly.

Tool rules:
- Use plan_set_goal for the goal only AFTER the user has described their goal. Use plan_set_task to update the current planning task when moving between workflow phases.
- Use plan_put_item with section and integer key to add or replace exactly one item in these sections only: deliverable, assumption, note, step, or validation_criterion.
- Use plan_delete_item with section and integer key to remove one keyed item.
- Use assumptions for explicit defaults or choices being locked into the plan.
- Do not define structured inputs or outputs for anonymous chat plans. Use deliverables for user-visible or operational outcomes.
- Research gate: before keyed edits set or revise fact-dependent deliverables, steps, notes, or validation criteria, use available research tools first.
- Use ask_user_questions with 1 to 5 free-response questions. Each question must be a single, distinct question. Do not bundle multiple numbered sub-questions into one question string — split them into separate questions so the UI can show them one at a time. Prefer one focused question when that is enough.
- Use plan_ready_for_approval only after goal, deliverables/outputs, steps, assumptions, and validation criteria are complete enough to execute without guessing.
- Shell and file tools are allowed for planning research only.
- Strive for clarity, detailed specification, and robust implementation/execution steps.

Runtime planning state:
Mode: PLAN
Status: """).append(" ").append(plan.status().name()).append("\n");
        appendValue(builder, "Current planning task", plan.planningTask());
        appendDefinitionState(builder, plan);
        if (plan.hasPendingQuestion()) {
            appendValue(builder, "Pending question", plan.currentQuestion());
            builder.append("Pending question progress: ")
                .append(plan.pendingQuestionIndex() + 1)
                .append("/")
                .append(plan.pendingQuestions().size())
                .append("\n");
        }
        return builder.toString().trim();
    }

    String planExecutionInstructions(PlanDefinition plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("""
You are Magenta executing an approved plan. Use the approved structured plan below as the execution source of truth.

Work through the plan directly. Track each validation criterion explicitly as you work — verify it is met with specific, verifiable evidence (counts, file contents read back, query results, checks performed).

During execution, call plan_report periodically to save evidence incrementally. This protects evidence from context compaction.

When ready to complete, call plan_complete with:
- One evidence entry per validation criterion from the approved plan above, formatted as 'Criterion: <exact criterion text> | Evidence: <specific proof>'
- Artifact paths for any files created — the validator will auto-read these files, so you don't need to duplicate their contents in the evidence entries
- Deviations from the plan, if any
- Unmet criteria, if any
- finalMessage: your intended user-facing completion message, delivered verbatim to the user after validation passes. Include a concise work summary, the outcome for each deliverable, and any notable results. If the deliverable IS a chat message (a written report, drafted content, etc.), this IS the deliverable — write the complete user-facing text here.

Magenta will run a validator pass; if validation fails, use the returned remediation details and continue work before trying plan_complete again.

Approved plan:

""").append(approvalMarkdown(plan)).append("\n\n");
        appendList(builder, "Prior validation feedback to address", plan.validationFeedback());
        builder.append("\n\n").append(workspaceRuntimeContext());
        return builder.toString().trim();
    }

    String taskDraftInstructions(PlanDefinition draft) {
        StringBuilder builder = new StringBuilder("""
            You are Magenta in TASK mode.

            Your job is to create a reusable task definition, not to execute one-off work.
            Tasks use typed runtime inputs, such as <Topic> or <SourceFile>, as placeholders for values supplied when the task runs.
            First confirm proposed input definitions with ask_user_questions before persisting them.
            Define concrete named outputs for workflow chaining. Named outputs are the reusable task's deliverables for downstream workflow binding.

            Required terminal states for every TASK-mode turn:
            - Queue one to five focused questions with ask_user_questions, or
            - Mark a complete draft with task_ready_for_approval.

            Tool rules:
            - Use task_set_goal, task_set_task, task_put_item, task_delete_item, and task_ready_for_approval.
            - task_put_item sections: input, output, assumption, note, step, validation_criterion.
            - Inputs and outputs require name, type, description, required, and schema fields.
            - Do not ask for concrete runtime values during task creation; ask only what the reusable task should accept.

            Runtime task draft:
            """.stripIndent());
        appendValue(builder, "Status", draft.status().name());
        appendValue(builder, "Current task", draft.planningTask());
        appendValue(builder, "Title", draft.title());
        appendValue(builder, "Goal", draft.goal());
        appendValue(builder, "Summary", draft.summary());
        appendList(builder, "Inputs", draft.inputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Outputs", draft.outputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Assumptions", draft.assumptions());
        appendList(builder, "Steps", draft.steps().stream().map(step -> step.order() + ". " + step.text()).toList());
        appendList(builder, "Validation criteria", draft.validationCriteria());
        if (draft.hasPendingQuestion()) {
            appendValue(builder, "Pending question", draft.currentQuestion());
            appendValue(builder, "Pending question progress", (draft.pendingQuestionIndex() + 1) + "/" + draft.pendingQuestions().size());
        }
        return builder.toString().trim();
    }

    String executionInstructions(PlanRun run) {
        StringBuilder builder = new StringBuilder("""
            You are Magenta executing a reusable task in an isolated task-run context.

            Use the concrete runtime input values below. Record useful evidence with task_report while working.
            Complete only by calling task_complete with outputValues keyed exactly by declared output name.
            Missing required declared outputs are rejected.

            Task snapshot:
            """.stripIndent());
        PlanDefinition task = run.planSnapshot();
        appendValue(builder, "Run id", run.id());
        appendValue(builder, "Title", task.title());
        appendValue(builder, "Goal", task.goal());
        appendList(builder, "Inputs", run.inputValues().entrySet().stream()
            .map(entry -> entry.getKey() + ": " + entry.getValue())
            .toList());
        appendList(builder, "Declared outputs", task.outputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Steps", task.steps().stream().map(step -> step.order() + ". " + step.text()).toList());
        appendList(builder, "Validation criteria", task.validationCriteria());
        builder.append("\n\n").append(workspaceRuntimeContext(run));
        return builder.toString().trim();
    }

    // ════════════════════════════════════════════════════════════════
    //  Private helpers
    // ════════════════════════════════════════════════════════════════

    private PlanDefinition requirePlanMode(String conversationId, String action) {
        PlanDefinition plan = requirePlanConversation(conversationId, action);
        if (plan.kind() != PlanKind.SESSION_PLAN || plan.status() == PlanStatus.APPROVED
            || plan.status() == PlanStatus.COMPLETED || plan.status() == PlanStatus.CANCELLED
            || plan.status() == PlanStatus.SAVED_TASK) {
            throw new IllegalStateException(action + " is available only in plan mode");
        }
        return plan;
    }

    private PlanDefinition requirePlanConversation(String conversationId, String action) {
        return planRepository.findDefinition(conversationId)
            .orElseThrow(() -> new IllegalStateException("No plan exists for this conversation"));
    }

    private PlanDefinition requireSavedPlan(String conversationId) {
        PlanDefinition plan = requirePlanConversation(conversationId, "saved plan operations");
        if (!plan.hasSavedPlan()) {
            throw new IllegalStateException("No saved plan exists for this conversation");
        }
        return plan;
    }

    private PlanDefinition requireTaskDraft(String conversationId, String action) {
        PlanDefinition draft = planRepository.findDefinitionByConversationId(conversationId)
            .orElseThrow(() -> new IllegalStateException("No task draft exists for this conversation"));
        if (draft.status() == PlanStatus.APPROVED) {
            throw new IllegalStateException(action + " is not available after task approval");
        }
        return draft;
    }

    private PlanDefinition requireSavedTaskDraft(String planId, String action) {
        PlanDefinition task = getTask(planId);
        if (task.kind() != PlanKind.TASK_TEMPLATE || task.status() == PlanStatus.APPROVED) {
            throw new IllegalStateException(action + " is available only for draft saved plans");
        }
        return task;
    }

    private PlanDefinition saveTaskDraft(PlanDefinition draft) {
        return planRepository.saveDefinition(draft);
    }

    private PlanRun requireRun(String runId) {
        return planRepository.findRun(runId)
            .orElseThrow(() -> new IllegalStateException("Plan run not found: " + runId));
    }

    private String activeRunId(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        String cached = executionRunsByConversationId.get(conversationId);
        if (StringUtils.hasText(cached) && isActiveRun(cached)) {
            return cached;
        }
        if (StringUtils.hasText(cached)) {
            executionRunsByConversationId.remove(conversationId);
        }
        if (chatSessionMetadataRepository == null) {
            return null;
        }
        String stored = chatSessionMetadataRepository.findActiveTaskRunId(conversationId).orElse(null);
        if (!StringUtils.hasText(stored)) {
            return null;
        }
        if (isActiveRun(stored)) {
            executionRunsByConversationId.put(conversationId, stored);
            return stored;
        }
        chatSessionMetadataRepository.clearActiveTaskRunId(conversationId);
        return null;
    }

    private boolean isActiveRun(String runId) {
        return planRepository.findRun(runId)
            .map(run -> run.status() == PlanRunStatus.RUNNING)
            .orElse(false);
    }

    private PlanDefinition withSection(PlanDefinition plan, PlanSection section, int key, String text, PlanFieldDefinition field, boolean delete) {
        return new PlanDefinition(
            plan.id(), plan.kind(), plan.status(),
            plan.title(), plan.summary(), plan.goal(),
            section == PlanSection.NOTE ? keyedNoteText(plan.notes(), key, text, delete) : plan.notes(),
            section == PlanSection.DELIVERABLE ? keyedList(plan.deliverables(), key, text, delete) : plan.deliverables(),
            section == PlanSection.INPUT ? keyedFields(plan.inputs(), key, field, delete) : plan.inputs(),
            section == PlanSection.OUTPUT ? keyedFields(plan.outputs(), key, field, delete) : plan.outputs(),
            section == PlanSection.ASSUMPTION ? keyedList(plan.assumptions(), key, text, delete) : plan.assumptions(),
            section == PlanSection.STEP ? keyedSteps(plan.steps(), key, text, delete) : plan.steps(),
            section == PlanSection.VALIDATION_CRITERION ? keyedList(plan.validationCriteria(), key, text, delete) : plan.validationCriteria(),
            plan.executionEvidence(), plan.validationFeedback(),
            plan.promptProfile(), plan.planningModel(), plan.executionModel(), plan.settingsOverrideJson(),
            nextTaskAfterSection(plan, section),
            plan.pendingQuestions(), plan.pendingQuestionIndex(),
            plan.planStartMessageOrder(), plan.finalMessage(), plan.conversationId(),
            plan.createdAt(), Instant.now()
        );
    }

    private void validateComplete(PlanDefinition plan, String action) {
        if (!StringUtils.hasText(plan.goal())) {
            throw new IllegalArgumentException(action + " requires a goal");
        }
        if (plan.deliverables().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one deliverable");
        }
        if (plan.steps().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one step");
        }
        if (plan.validationCriteria().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one validation criterion");
        }
    }

    private void validateTaskComplete(PlanDefinition draft, String action) {
        if (!StringUtils.hasText(draft.title())) {
            throw new IllegalArgumentException(action + " requires a title");
        }
        if (!StringUtils.hasText(draft.goal())) {
            throw new IllegalArgumentException(action + " requires a goal");
        }
        if (draft.outputs().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one named output");
        }
        if (draft.steps().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one step");
        }
        if (draft.validationCriteria().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one validation criterion");
        }
    }

    private void validateSavedPlanComplete(PlanDefinition draft, String action) {
        if (!StringUtils.hasText(draft.title())) {
            throw new IllegalArgumentException(action + " requires a title");
        }
        if (!StringUtils.hasText(draft.goal())) {
            throw new IllegalArgumentException(action + " requires a goal");
        }
        if (draft.outputs().isEmpty() && draft.deliverables().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one named output or deliverable");
        }
        if (draft.steps().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one step");
        }
        if (draft.validationCriteria().isEmpty()) {
            throw new IllegalArgumentException(action + " requires at least one validation criterion");
        }
    }

    private void validateFieldNames(List<PlanFieldDefinition> fields, String label) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (PlanFieldDefinition field : cleanFields(fields)) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("Duplicate " + label + " name: " + field.name());
            }
            if (field.required() && !StringUtils.hasText(field.description())) {
                throw new IllegalArgumentException(
                    "Required " + label + " '" + field.name() + "' must have a description");
            }
            if (StringUtils.hasText(field.schema())) {
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    mapper.readTree(field.schema());
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                        "Invalid JSON schema for " + label + " '" + field.name() + "': " + e.getMessage());
                }
            }
        }
    }

    private List<String> missingRequiredInputs(PlanDefinition task, Map<String, Object> values) {
        return task.inputs().stream()
            .filter(PlanFieldDefinition::required)
            .filter(field -> !values.containsKey(field.name()) || values.get(field.name()) == null
                || (values.get(field.name()) instanceof String text && !StringUtils.hasText(text)))
            .map(PlanFieldDefinition::name)
            .toList();
    }

    private List<String> missingRequiredOutputs(PlanDefinition task, Map<String, Object> values) {
        return task.outputs().stream()
            .filter(PlanFieldDefinition::required)
            .filter(field -> !values.containsKey(field.name()) || values.get(field.name()) == null
                || (values.get(field.name()) instanceof String text && !StringUtils.hasText(text)))
            .map(PlanFieldDefinition::name)
            .toList();
    }

    private PlanFieldDefinition fieldByName(List<PlanFieldDefinition> fields, String name) {
        if (!StringUtils.hasText(name)) return null;
        return fields.stream().filter(field -> name.equals(field.name())).findFirst().orElse(null);
    }

    private List<String> effectiveDeliverables(PlanDefinition plan) {
        List<String> values = new ArrayList<>(plan.deliverables());
        for (PlanFieldDefinition output : plan.outputs()) {
            String name = output.name();
            if (!values.contains(name)) {
                values.add(name);
            }
        }
        return List.copyOf(values);
    }

    private List<PlanStep> orderedSteps(List<String> steps) {
        List<PlanStep> ordered = new ArrayList<>();
        for (String raw : steps == null ? List.<String>of() : steps) {
            String step = normalize(raw);
            if (step != null) {
                ordered.add(new PlanStep(ordered.size() + 1, step));
            }
        }
        return List.copyOf(ordered);
    }

    // ── Section helpers ──

    private List<String> keyedList(List<String> values, int key, String text, boolean delete) {
        List<String> updated = new ArrayList<>(values);
        int index = key - 1;
        if (delete) {
            if (index < updated.size()) updated.remove(index);
            return List.copyOf(updated);
        }
        while (updated.size() < index) updated.add("");
        if (index < updated.size()) updated.set(index, text);
        else updated.add(text);
        return updated.stream().map(this::normalize).filter(v -> v != null).toList();
    }

    private List<PlanStep> keyedSteps(List<PlanStep> steps, int key, String text, boolean delete) {
        List<PlanStep> updated = new ArrayList<>(steps);
        updated.removeIf(step -> step.order() == key);
        if (!delete) {
            String normalized = normalize(text);
            if (normalized != null) updated.add(new PlanStep(key, normalized));
        }
        return updated.stream().sorted(Comparator.comparingInt(PlanStep::order)).toList();
    }

    private List<PlanFieldDefinition> keyedFields(List<PlanFieldDefinition> values, int key, PlanFieldDefinition field, boolean delete) {
        List<PlanFieldDefinition> updated = new ArrayList<>(values);
        int index = key - 1;
        if (delete) {
            if (index < updated.size()) updated.remove(index);
            return List.copyOf(updated);
        }
        while (updated.size() < index) {
            updated.add(new PlanFieldDefinition("field_" + (updated.size() + 1), PlanFieldType.STRING, false, null, false, null));
        }
        if (index < updated.size()) updated.set(index, field);
        else updated.add(field);
        return cleanFields(updated);
    }

    private String keyedNoteText(String notes, int key, String text, boolean delete) {
        List<String> updated = keyedList(noteLines(notes), key, text, delete);
        return updated.isEmpty() ? null : String.join("\n", updated);
    }

    private List<String> noteLines(String notes) {
        if (!StringUtils.hasText(notes)) return List.of();
        return notes.lines().map(this::normalize).filter(v -> v != null).toList();
    }

    // ── Planning task transitions ──

    private String nextTaskAfterGoal(PlanDefinition plan) {
        return plan.deliverables().isEmpty() && plan.outputs().isEmpty()
            ? "define_deliverables" : "collect_user_guidance";
    }

    private String nextTaskAfterSection(PlanDefinition plan, PlanSection section) {
        return switch (section) {
            case DELIVERABLE, OUTPUT -> "collect_user_guidance";
            case ASSUMPTION, NOTE, INPUT -> "clarify_and_elaborate";
            case STEP -> "build_plan_steps";
            case VALIDATION_CRITERION -> "approval_readiness";
        };
    }

    // ── Title helpers ──

    private String titleFromGoal(String goal) {
        String compact = goal.trim().replaceAll("\\s+", " ");
        if (compact.length() <= 64) return "Plan for " + compact;
        return "Plan for " + compact.substring(0, 64).replaceAll("\\s+\\S*$", "").trim();
    }

    private String planTitle(PlanDefinition plan) {
        if (StringUtils.hasText(plan.title())) return plan.title();
        if (StringUtils.hasText(plan.goal())) return titleFromGoal(plan.goal());
        return "Untitled Plan";
    }

    // ── Answer persistence ──

    private void appendPlanningAnswer(String conversationId, String question, String answer, String notes) {
        StringBuilder message = new StringBuilder();
        message.append("Planning answer\n\n");
        message.append("Question: ").append(question).append("\n\n");
        if (StringUtils.hasText(answer)) {
            message.append("Answer: ").append(answer.trim()).append("\n");
        }
        if (StringUtils.hasText(notes)) {
            message.append("Notes: ").append(notes.trim()).append("\n");
        }
        List<Message> messages = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        messages.add(new UserMessage(message.toString().trim()));
        chatMemoryRepository.saveAll(conversationId, messages);
    }

    private String appendAnswerNote(String notes, String question, String answer, String answerNotes) {
        List<String> lines = new ArrayList<>(noteLines(notes));
        StringBuilder line = new StringBuilder("Answered: ").append(question);
        if (StringUtils.hasText(answer)) line.append(" | ").append(answer.trim());
        if (StringUtils.hasText(answerNotes)) line.append(" | Notes: ").append(answerNotes.trim());
        lines.add(line.toString());
        return String.join("\n", lines);
    }

    // ── Clean/normalize helpers ──

    private int requirePositiveKey(Integer key) {
        if (key == null || key < 1) throw new IllegalArgumentException("A positive integer key is required");
        return key;
    }

    private int requirePositiveKey(Integer key, String toolName) {
        if (key == null || key < 1) throw new IllegalArgumentException(toolName + " requires a positive integer key");
        return key;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream().map(this::normalize).filter(v -> v != null).toList();
    }

    private List<PlanFieldDefinition> cleanFields(List<PlanFieldDefinition> fields) {
        if (fields == null) return List.of();
        return fields.stream().map(this::cleanField).filter(f -> f != null).toList();
    }

    private PlanFieldDefinition cleanField(PlanFieldDefinition field) {
        if (field == null || !StringUtils.hasText(field.name())) return null;
        return new PlanFieldDefinition(field.name().trim(), field.type(), field.array(),
            normalize(field.description()), field.required(),
            normalize(field.schema()));
    }

    private List<PlanStep> cleanSteps(List<PlanStep> steps) {
        if (steps == null) return List.of();
        return steps.stream()
            .filter(step -> step != null && StringUtils.hasText(step.text()))
            .map(step -> new PlanStep(step.order() <= 0 ? 1 : step.order(), step.text().trim()))
            .sorted(Comparator.comparingInt(PlanStep::order))
            .toList();
    }

    private Map<String, Object> cleanMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (StringUtils.hasText(entry.getKey())) {
                clean.put(entry.getKey().trim(), entry.getValue());
            }
        }
        return clean;
    }

    private String normalize(String value) {
        return PlanText.normalize(value);
    }

    private List<String> anonymousOpeningQuestions() {
        return List.of(
            "What is the goal?",
            "What assumptions, details, expectations, constraints, or preferred approach should guide the plan?",
            "What are the expected deliverables?"
        );
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? null : root.getMessage();
        if (!StringUtils.hasText(message) && throwable != null) {
            message = throwable.getMessage();
        }
        return StringUtils.hasText(message) ? message : "unknown allocation error";
    }

    private String normalizePlanningTask(String value) {
        String normalized = normalize(value);
        return "define_deliverables".equals(normalized) ? "define_outputs" : normalized;
    }

    private String choose(String value, String fallback) {
        String normalized = normalize(value);
        return normalized == null ? fallback : normalized;
    }

    private String mergeNotes(String existing, String addition) {
        String cleanExisting = normalize(existing);
        String cleanAddition = normalize(addition);
        if (cleanExisting == null) return cleanAddition;
        if (cleanAddition == null || cleanExisting.contains(cleanAddition)) return cleanExisting;
        return cleanExisting + "\n" + cleanAddition;
    }

    private String fieldSummary(PlanFieldDefinition field) {
        return field.name() + " (" + field.type().wireName() + (field.array() ? "[]" : "")
            + ", required=" + field.required() + "): " + field.description();
    }

    private List<String> inputNames(List<PlanFieldDefinition> fields) {
        return fields.stream().map(PlanFieldDefinition::name).toList();
    }

    private List<String> outputNames(List<PlanFieldDefinition> fields) {
        return fields.stream().map(PlanFieldDefinition::name).toList();
    }

    private List<PlanFieldDefinition> textFieldList(List<String> names) {
        if (names == null) return List.of();
        return names.stream()
            .map(name -> new PlanFieldDefinition(name.trim(), PlanFieldType.STRING, false, null, false, null))
            .toList();
    }

    // ── Conversation trimming ──

    private void trimConversation(String conversationId, int messageCount) {
        List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
        int end = Math.max(0, Math.min(messageCount, messages.size()));
        chatMemoryRepository.saveAll(conversationId, new ArrayList<>(messages.subList(0, end)));
    }

    private void addLabeled(List<String> entries, String label, String value) {
        if (StringUtils.hasText(value)) entries.add(label + ": " + value);
    }

    private void addLabeled(List<String> entries, String label, List<String> values) {
        for (String value : values) entries.add(label + ": " + value);
    }

    // ── Builder helpers ──

    private void appendValue(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) builder.append(label).append(": ").append(value).append("\n");
    }

    private void appendList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) return;
        builder.append(label).append(":\n");
        for (String value : values) builder.append("- ").append(value).append("\n");
    }

    private void appendDefinitionState(StringBuilder builder, PlanDefinition plan) {
        appendValue(builder, "Goal", plan.goal());
        appendValue(builder, "Title", plan.title());
        appendValue(builder, "Summary", plan.summary());
        appendList(builder, "Deliverables", plan.deliverables());
        appendList(builder, "Inputs", plan.inputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Outputs", plan.outputs().stream().map(this::fieldSummary).toList());
        appendList(builder, "Assumptions", plan.assumptions());
        appendValue(builder, "Notes", plan.notes());
        appendSteps(builder, plan.steps());
        appendList(builder, "Validation criteria", plan.validationCriteria());
        appendList(builder, "Validation feedback", plan.validationFeedback());
    }

    private void appendSteps(StringBuilder builder, List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) return;
        builder.append("Steps:\n");
        for (PlanStep step : steps) builder.append(step.order()).append(". ").append(step.text()).append("\n");
    }

    private void appendMarkdownValue(StringBuilder builder, String label, String value) {
        if (StringUtils.hasText(value)) builder.append("## ").append(label).append("\n\n").append(value.trim()).append("\n\n");
    }

    private void appendMarkdownList(StringBuilder builder, String label, List<String> values) {
        if (values == null || values.isEmpty()) return;
        builder.append("## ").append(label).append("\n\n");
        for (String value : values) builder.append("- ").append(value).append("\n");
        builder.append("\n");
    }

    // ── Workspace / Output / Cleanup helpers ──

    /**
     * Materialize output values into the run's output directory and persist
     * artifact metadata. Called during {@link #completeRun}.

    private OutputArtifactContext outputArtifactContext(PlanRun run, PlanDefinition task) {
        String defaultRunType = task.kind() == PlanKind.TASK_TEMPLATE ? "TASK_RUN" : "PLAN_RUN";
        OrchestrationTaskContext taskContext = OrchestrationTaskContextHolder.current();
        if (taskContext != null && taskContext.hasContext()) {
            return new OutputArtifactContext(
                taskContext.agentId(),
                taskContext.jobId(),
                taskContext.jobAssignmentId(),
                taskContext.jobRunId(),
                taskContext.projectId(),
                StringUtils.hasText(taskContext.workspaceType())
                    ? taskContext.workspaceType()
                    : run.workspaceType(),
                StringUtils.hasText(taskContext.workspaceKey())
                    ? taskContext.workspaceKey()
                    : run.workspaceKey(),
                StringUtils.hasText(taskContext.workspaceIdentity())
                    ? taskContext.workspaceIdentity()
                    : run.workspaceIdentity(),
                StringUtils.hasText(taskContext.runType())
                    ? taskContext.runType()
                    : defaultRunType
            );
        }
        return new OutputArtifactContext(
            null, null, null,
            run.workspaceType(), run.workspaceKey(), run.workspaceIdentity(),
            defaultRunType
        );
    }

    private Map<String, PlanFieldType> outputTypeMap(List<PlanFieldDefinition> outputs) {
        Map<String, PlanFieldType> map = new LinkedHashMap<>();
        for (PlanFieldDefinition field : outputs) {
            map.put(field.name(), field.type());
        }
        return map;
    }

    // ── Workspace runtime prompt context ──

    /**
     * Returns filesystem workspace context text to inject into the execution prompt.
     */
    public String workspaceRuntimeContext() {
        return """
            ## Filesystem Workspace Environment

            You are executing on the host filesystem inside the effective workspace.
            The workspace root is available for file operations.

            ### Output Directory

            When completing a task, write or copy all required output files.
            Final output destinations are populated by Magenta after completion.

            ### Python and Virtual Environments

            If your task requires pip packages, create and use a virtual environment:

            ```
            python -m venv .venv
            source .venv/bin/activate
            pip install <packages>
            ```
            """.stripIndent();
    }

    public String workspaceRuntimeContext(PlanRun run) {
        return workspaceRuntimeContext();
    }

    String executionInstructionsWithWorkspace(PlanRun run) {
        return executionInstructions(run);
    }
}
