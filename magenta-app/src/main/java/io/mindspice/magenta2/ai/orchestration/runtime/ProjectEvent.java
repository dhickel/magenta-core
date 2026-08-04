package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;

/**
 * Immutable append-only event scoped to a project.
 */
public record ProjectEvent(
    String id,
    String projectId,
    String type,
    String payloadJson,
    Instant createdAt
) {}
