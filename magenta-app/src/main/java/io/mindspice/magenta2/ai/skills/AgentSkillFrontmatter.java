package io.mindspice.magenta2.ai.skills;

import java.util.Map;

public record AgentSkillFrontmatter(
    String name,
    String description,
    String license,
    String compatibility,
    Map<String, String> metadata,
    String allowedTools
) { }
