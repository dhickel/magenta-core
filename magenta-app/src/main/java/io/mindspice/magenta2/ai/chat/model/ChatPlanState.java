package io.mindspice.magenta2.ai.chat.model;

import java.util.List;

public record ChatPlanState(
    String mode,
    String status,
    String planningTask,
    String title,
    String summary,
    String goal,
    String notes,
    List<String> deliverables,
    List<String> inputs,
    List<String> outputs,
    List<String> assumptions,
    List<String> steps,
    List<String> acceptanceCriteria,
    List<String> executionEvidence,
    List<String> validationFeedback,
    String promptType,
    String promptQuestion,
    List<String> promptOptions,
    int promptQuestionIndex,
    int promptQuestionCount,
    String approvalMarkdown,
    String approvalHtml,
    String documentMarkdown,
    String documentHtml
) {
    public ChatPlanState(
        String mode,
        String status,
        String title,
        String summary,
        String goal,
        String notes,
        List<String> steps,
        List<String> acceptanceCriteria,
        List<String> executionEvidence
    ) {
        this(
            mode,
            status,
            null,
            title,
            summary,
            goal,
            notes,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            steps,
            acceptanceCriteria,
            executionEvidence,
            List.of(),
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

    public static ChatPlanState normal() {
        return new ChatPlanState(
            "NORMAL",
            null,
            null,
            null,
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
}
