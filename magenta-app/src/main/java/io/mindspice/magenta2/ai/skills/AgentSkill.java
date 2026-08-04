package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentSkill(
    Long id,
    String name,
    String directorySlug,
    String description,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools,
    String skillRootRelativePath,
    String skillMdRootRelativePath,
    AgentSkillStatus status,
    List<AgentSkillDiagnostic> diagnostics,
    boolean hasScripts,
    boolean hasReferences,
    boolean hasAssets,
    String contentHash,
    Instant discoveredAt,
    Instant lastScannedAt,
    Instant createdAt,
    Instant updatedAt
) { }
