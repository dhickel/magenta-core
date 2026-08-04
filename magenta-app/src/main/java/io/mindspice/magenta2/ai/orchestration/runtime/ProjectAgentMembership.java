package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;

/**
 * Links an agent to a project. Each agent may belong to multiple projects.
 */
public record ProjectAgentMembership(
    String id,
    String projectId,
    String agentId,
    String role,
    Instant joinedAt
) {}
