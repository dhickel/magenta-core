package io.mindspice.magenta2.ai.chat.model;

import jakarta.validation.constraints.NotBlank;

public record PendingMessageRequest(
    @NotBlank String message,
    String model,
    String planningModel,
    ChatSessionSurface surface
) { }
