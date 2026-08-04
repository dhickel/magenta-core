package io.mindspice.magenta2.api.web;

/**
 * A simple SSE event payload carrying an event name and its data object.
 * Extracted from duplicate private records in TaskController and WorkflowController.
 */
public record SsePayload(String name, Object data) {
}
