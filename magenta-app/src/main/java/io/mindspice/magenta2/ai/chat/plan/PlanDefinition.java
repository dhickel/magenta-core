package io.mindspice.magenta2.ai.chat.plan;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Unified plan/task definition. A {@code PlanDefinition} with {@code kind=SESSION_PLAN}
 * uses its {@code id} as the conversation id. A {@code kind=TASK_TEMPLATE} uses a
 * UUID id and tracks its drafting conversation via {@code conversationId}.
 *
 * <p>Draft state fields ({@code planningTask}, {@code pendingQuestions},
 * {@code pendingQuestionIndex}, {@code planStartMessageOrder}) apply only during
 * drafting; they are cleared on finalization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanDefinition(
    String id,
    PlanKind kind,
    PlanStatus status,
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
    List<String> executionEvidence,
    List<String> validationFeedback,
    String promptProfile,
    String planningModel,
    String executionModel,
    String settingsOverrideJson,
    // Draft state (cleared after finalization)
    String planningTask,
    List<String> pendingQuestions,
    int pendingQuestionIndex,
    int planStartMessageOrder,
    String finalMessage,
    // Optional conversation id for TASK_TEMPLATE drafts
    String conversationId,
    Instant createdAt,
    Instant updatedAt
) {
    public PlanDefinition {
        deliverables = deliverables == null ? List.of() : List.copyOf(deliverables);
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
        executionEvidence = executionEvidence == null ? List.of() : List.copyOf(executionEvidence);
        validationFeedback = validationFeedback == null ? List.of() : List.copyOf(validationFeedback);
        pendingQuestions = pendingQuestions == null ? List.of() : List.copyOf(pendingQuestions);
        pendingQuestionIndex = Math.max(0, pendingQuestionIndex);
    }

    public boolean hasSavedPlan() {
        return goal != null && !goal.isBlank()
            && steps != null && !steps.isEmpty()
            && ((deliverables != null && !deliverables.isEmpty()) || (outputs != null && !outputs.isEmpty()))
            && validationCriteria != null && !validationCriteria.isEmpty();
    }

    public boolean hasPendingQuestion() {
        return pendingQuestions != null
            && !pendingQuestions.isEmpty()
            && pendingQuestionIndex >= 0
            && pendingQuestionIndex < pendingQuestions.size();
    }

    public String currentQuestion() {
        return hasPendingQuestion() ? pendingQuestions.get(pendingQuestionIndex) : null;
    }

    // --- Wither helpers (package-private, used by PlanService) ---

    PlanDefinition withStatus(PlanStatus newStatus) {
        return new PlanDefinition(id, kind, newStatus, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withPlanningTask(String newPlanningTask) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, newPlanningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withGoal(String newGoal) {
        return new PlanDefinition(id, kind, status, title, summary, newGoal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withTitle(String newTitle) {
        return new PlanDefinition(id, kind, status, newTitle, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withPendingQuestions(List<String> newQuestions, int newIndex) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, newQuestions, newIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withExecutionEvidence(List<String> newEvidence) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            newEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withValidationFeedback(List<String> newFeedback) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, newFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withFinalMessage(String newFinalMessage) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, newFinalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withNotes(String newNotes) {
        return new PlanDefinition(id, kind, status, title, summary, goal, newNotes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withSummary(String newSummary) {
        return new PlanDefinition(id, kind, status, title, newSummary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withInputs(List<PlanFieldDefinition> newInputs) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, newInputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withOutputs(List<PlanFieldDefinition> newOutputs) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, newOutputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withAssumptions(List<String> newAssumptions) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, newAssumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withSteps(List<PlanStep> newSteps) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, newSteps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withValidationCriteria(List<String> newCriteria) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, newCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withDeliverables(List<String> newDeliverables) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            newDeliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, conversationId, createdAt, Instant.now());
    }

    PlanDefinition withConversationId(String newConversationId) {
        return new PlanDefinition(id, kind, status, title, summary, goal, notes,
            deliverables, inputs, outputs, assumptions, steps, validationCriteria,
            executionEvidence, validationFeedback, promptProfile, planningModel, executionModel,
            settingsOverrideJson, planningTask, pendingQuestions, pendingQuestionIndex,
            planStartMessageOrder, finalMessage, newConversationId, createdAt, Instant.now());
    }
}
