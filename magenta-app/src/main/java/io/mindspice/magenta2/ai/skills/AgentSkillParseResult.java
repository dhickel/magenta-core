package io.mindspice.magenta2.ai.skills;

import java.util.List;

public record AgentSkillParseResult(
    AgentSkillStatus status,
    AgentSkillFrontmatter frontmatter,
    String body,
    List<AgentSkillDiagnostic> diagnostics
) {
    public boolean loadable() {
        return status.loadable();
    }
}
