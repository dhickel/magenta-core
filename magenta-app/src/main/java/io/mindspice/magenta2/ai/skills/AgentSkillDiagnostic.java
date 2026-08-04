package io.mindspice.magenta2.ai.skills;

public record AgentSkillDiagnostic(
    AgentSkillDiagnosticSeverity severity,
    AgentSkillDiagnosticCode code,
    String message,
    String sourcePath
) { }
