package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;

/**
 * Captures a cron-based recurrence rule for a job definition.
 * The scheduler evaluates {@code nextFireTime} to determine when the next run
 * should be created.
 */
public record JobRecurrence(
    String id,
    String jobId,
    String cronExpression,
    String timezone,
    Instant nextFireTime,
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {}
