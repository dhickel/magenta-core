package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;

/**
 * A project is a durable data-space, membership, and visibility wrapper.
 * ownerAgentId is retained as a nullable legacy compatibility field.
 * archived projects retain historical rows but block new submissions,
 * member additions, and mutation.
 */
public record Project(
    String id,
    String name,
    String description,
    String ownerAgentId,
    String gitRepoUrl,
    String promptProfile,
    String model,
    String settingsOverrideJson,
    boolean archived,
    Instant createdAt,
    Instant updatedAt
) {}
