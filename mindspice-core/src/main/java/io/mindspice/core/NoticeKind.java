package io.mindspice.core;

/**
 * Kinds of system notices emitted during a turn lifecycle.
 */
public enum NoticeKind {
    COMPACTION("Context was compacted to fit the model window"),
    CONTEXT_EXHAUSTED("Context window is nearly full"),
    TOOL_LOOP_WARNING("Tool call loop guard triggered"),
    REPAIR_APPLIED("Turn repair applied"),
    BUFFER_OVERFLOW("Stream buffer overflow, oldest events dropped");

    private final String defaultMessage;

    NoticeKind(String defaultMessage) {
        this.defaultMessage = defaultMessage;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
