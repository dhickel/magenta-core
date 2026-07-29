package io.mindspice.magenta2.ai.orchestration.runtime;

/** Exclusive schedule execution mode. */
public enum ScheduleMode {
    /** Traditional cron-driven recurring schedule. Requires cronExpression + timezone. */
    CRON,

    /** One-shot schedule that fires at a specific instant. Requires fireAt. Past fireAt rejected. */
    ONE_TIME
}
