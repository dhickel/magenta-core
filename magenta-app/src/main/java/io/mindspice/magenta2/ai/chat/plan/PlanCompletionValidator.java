package io.mindspice.magenta2.ai.chat.plan;

public interface PlanCompletionValidator {
    ValidationResponse validate(ValidationRequest request);

    record ValidationRequest(
        String model,
        String systemPrompt,
        String userInput
    ) {
    }

    record ValidationResponse(
        String model,
        String content
    ) {
    }
}
