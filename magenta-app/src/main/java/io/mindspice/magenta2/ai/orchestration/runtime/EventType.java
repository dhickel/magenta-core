package io.mindspice.magenta2.ai.orchestration.runtime;

public enum EventType {
    INBOX_MESSAGE_RECEIVED,
    SCHEDULE_DUE,
    SCHEDULE_CREATED,
    TASK_STATUS_CHANGED,
    WORKFLOW_STATUS_CHANGED,
    JOB_STATUS_CHANGED,
    MANUAL_USER_EVENT
}
