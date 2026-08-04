package io.mindspice.magenta2.ai.chat.plan;

public record PlanAnswer(
    String question,
    String answer,
    String notes,
    String createdAt
) {
}
