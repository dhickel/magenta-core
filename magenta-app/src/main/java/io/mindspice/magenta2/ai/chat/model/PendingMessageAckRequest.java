package io.mindspice.magenta2.ai.chat.model;

import jakarta.validation.constraints.NotBlank;

public record PendingMessageAckRequest(@NotBlank String claimToken) { }
