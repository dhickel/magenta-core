package io.mindspice.magenta2.ai.chat.task;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDraft(
    String conversationId,
    TaskDraftStatus status,
    String planningTask,
    String title,
    String summary,
    String goal,
    String notes,
    String inputDescription,
    List<TaskFieldDefinition> inputs,
    String outputDescription,
    List<TaskFieldDefinition> outputs,
    List<String> assumptions,
    List<TaskStep> steps,
    List<String> validationCriteria,
    List<String> pendingQuestions,
    int pendingQuestionIndex,
    String prePlanningModel,
    String executionModel,
    String createdTaskId,
    Instant createdAt,
    Instant updatedAt
) {
    public TaskDraft {
        status = status == null ? TaskDraftStatus.DRAFT : status;
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
        pendingQuestions = pendingQuestions == null ? List.of() : List.copyOf(pendingQuestions);
        pendingQuestionIndex = Math.max(0, pendingQuestionIndex);
    }

    public boolean hasPendingQuestion() {
        return !pendingQuestions.isEmpty() && pendingQuestionIndex < pendingQuestions.size();
    }

    public String currentQuestion() {
        return hasPendingQuestion() ? pendingQuestions.get(pendingQuestionIndex) : null;
    }

    // --- Wither helpers ---

    private TaskDraft derive(
        TaskDraftStatus status,
        String planningTask,
        String title,
        String summary,
        String goal,
        String notes,
        String inputDescription,
        List<TaskFieldDefinition> inputs,
        String outputDescription,
        List<TaskFieldDefinition> outputs,
        List<String> assumptions,
        List<TaskStep> steps,
        List<String> validationCriteria,
        List<String> pendingQuestions,
        int pendingQuestionIndex,
        String createdTaskId
    ) {
        return new TaskDraft(
            this.conversationId, status, planningTask, title, summary, goal, notes,
            inputDescription, inputs, outputDescription, outputs, assumptions, steps,
            validationCriteria, pendingQuestions, pendingQuestionIndex,
            this.prePlanningModel, this.executionModel, createdTaskId,
            this.createdAt, Instant.now()
        );
    }

    public TaskDraft withStatus(TaskDraftStatus status) {
        return derive(status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withPlanningTask(String planningTask) {
        return derive(this.status, planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withGoal(String goal) {
        return derive(this.status, this.planningTask, this.title, this.summary, goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withNotes(String notes) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withPendingQuestions(List<String> pendingQuestions, int pendingQuestionIndex) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            pendingQuestions, pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withCreatedTaskId(String createdTaskId) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, createdTaskId);
    }

    public TaskDraft withInputs(List<TaskFieldDefinition> inputs) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withOutputs(List<TaskFieldDefinition> outputs) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, outputs,
            this.assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withAssumptions(List<String> assumptions) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            assumptions, this.steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withSteps(List<TaskStep> steps) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, steps, this.validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }

    public TaskDraft withValidationCriteria(List<String> validationCriteria) {
        return derive(this.status, this.planningTask, this.title, this.summary, this.goal, this.notes,
            this.inputDescription, this.inputs, this.outputDescription, this.outputs,
            this.assumptions, this.steps, validationCriteria,
            this.pendingQuestions, this.pendingQuestionIndex, this.createdTaskId);
    }
}
