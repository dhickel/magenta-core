package io.mindspice.magenta2.ai.chat.plan;

import java.util.List;

public record PlanPrompt(
    String type,
    String question,
    List<String> options
) {
    public PlanPrompt {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public static PlanPrompt none() {
        return new PlanPrompt(null, null, List.of());
    }

    public boolean active() {
        return type != null && !type.isBlank() && question != null && !question.isBlank();
    }
}
