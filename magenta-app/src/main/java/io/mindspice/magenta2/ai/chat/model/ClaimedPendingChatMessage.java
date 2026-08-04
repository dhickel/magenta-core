package io.mindspice.magenta2.ai.chat.model;

public record ClaimedPendingChatMessage(PendingChatMessage message, String claimToken) { }
