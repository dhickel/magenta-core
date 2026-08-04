package io.mindspice.magenta2.ai.chat.task;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskRun(
    String id,
    String taskId,
    TaskRunStatus status,
    Map<String, Object> inputValues,
    Map<String, Object> outputValues,
    TaskDefinition taskSnapshot,
    List<String> executionEvidence,
    List<String> validationFeedback,
    String finalMessage,
    String errorText,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt
) {
    public TaskRun {
        inputValues = inputValues == null ? Map.of() : Map.copyOf(inputValues);
        outputValues = outputValues == null ? Map.of() : Map.copyOf(outputValues);
        executionEvidence = executionEvidence == null ? List.of() : List.copyOf(executionEvidence);
        validationFeedback = validationFeedback == null ? List.of() : List.copyOf(validationFeedback);
    }

    // --- Wither helpers ---

    private TaskRun derive(
        TaskRunStatus status,
        Map<String, Object> outputValues,
        List<String> executionEvidence,
        List<String> validationFeedback,
        String finalMessage,
        String errorText,
        Instant startedAt,
        Instant completedAt
    ) {
        return new TaskRun(
            this.id, this.taskId, status, this.inputValues, outputValues, this.taskSnapshot,
            executionEvidence, validationFeedback, finalMessage, errorText,
            this.createdAt, Instant.now(), startedAt, completedAt
        );
    }

    public TaskRun withStatus(TaskRunStatus status) {
        return derive(status, this.outputValues, this.executionEvidence, this.validationFeedback,
            this.finalMessage, this.errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withOutputValues(Map<String, Object> outputValues) {
        return derive(this.status, outputValues, this.executionEvidence, this.validationFeedback,
            this.finalMessage, this.errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withExecutionEvidence(List<String> executionEvidence) {
        return derive(this.status, this.outputValues, executionEvidence, this.validationFeedback,
            this.finalMessage, this.errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withValidationFeedback(List<String> validationFeedback) {
        return derive(this.status, this.outputValues, this.executionEvidence, validationFeedback,
            this.finalMessage, this.errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withFinalMessage(String finalMessage) {
        return derive(this.status, this.outputValues, this.executionEvidence, this.validationFeedback,
            finalMessage, this.errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withErrorText(String errorText) {
        return derive(this.status, this.outputValues, this.executionEvidence, this.validationFeedback,
            this.finalMessage, errorText, this.startedAt, this.completedAt);
    }

    public TaskRun withStartedAt(Instant startedAt) {
        return derive(this.status, this.outputValues, this.executionEvidence, this.validationFeedback,
            this.finalMessage, this.errorText, startedAt, this.completedAt);
    }

    public TaskRun withCompletedAt(Instant completedAt) {
        return derive(this.status, this.outputValues, this.executionEvidence, this.validationFeedback,
            this.finalMessage, this.errorText, this.startedAt, completedAt);
    }
}
