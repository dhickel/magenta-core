package io.mindspice.magenta2.ai.chat.task;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskDefinition(
    String id,
    @NotBlank String title,
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
    Instant createdAt,
    Instant updatedAt
) {
    public TaskDefinition {
        inputs = inputs == null ? List.of() : List.copyOf(inputs);
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        assumptions = assumptions == null ? List.of() : List.copyOf(assumptions);
        steps = steps == null ? List.of() : List.copyOf(steps);
        validationCriteria = validationCriteria == null ? List.of() : List.copyOf(validationCriteria);
    }
}
