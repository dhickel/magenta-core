package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactTestSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillResolutionServiceOverlayTest {
    private AgentSkillAssignmentRepository repository;
    private SkillScopeDiscoveryService discovery;
    private SkillResolutionService service;
    private final SkillResolutionContext context = new SkillResolutionContext(
            null, null, "agent-1", null, null, null, null, null, null);

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true));
        OutputArtifactTestSchema.initialize(jdbc);
        repository = new AgentSkillAssignmentRepository(jdbc);
        discovery = mock(SkillScopeDiscoveryService.class);
        service = new SkillResolutionService(discovery, repository);
    }

    @Test
    void normalResolutionAppliesExactOverlayAndDetectsDigestChange() {
        CandidateVersion assigned = repository.insertCandidateVersion(candidate("a".repeat(64), SkillScope.GLOBAL));
        repository.upsertOverlay(assigned.id(), "test-skill", AgentSkillTargetType.AGENT,
                "agent-1", true, "op-1", "tester");

        when(discovery.discoverAll(context)).thenReturn(result(candidate("a".repeat(64), SkillScope.GLOBAL), List.of()));
        assertThat(service.resolveKey("test-skill", context).state()).isEqualTo(SkillResolutionState.RESOLVED);

        when(discovery.discoverAll(context)).thenReturn(result(candidate("b".repeat(64), SkillScope.GLOBAL), List.of()));
        assertThat(service.resolveKey("test-skill", context).state()).isEqualTo(SkillResolutionState.STALE);
    }

    @Test
    void malformedOccupantAndShadowedCandidateRemainExplicit() {
        CandidateVersion workspace = CandidateVersion.of("test-skill", SkillScope.WORKSPACE,
                "agent/agent-1", SkillProvenance.WORKSPACE_FLAT, 1, "c".repeat(64), Instant.now());
        CandidateVersion global = candidate("d".repeat(64), SkillScope.GLOBAL);
        AgentSkillDiagnostic diagnostic = new AgentSkillDiagnostic(AgentSkillDiagnosticSeverity.ERROR,
                AgentSkillDiagnosticCode.SKILL_FRONTMATTER_YAML_INVALID, "malformed frontmatter", "test-skill.md");
        when(discovery.discoverAll(context)).thenReturn(result(workspace, List.of(diagnostic), global));

        ResolvedSkill resolved = service.resolveKey("test-skill", context);
        assertThat(resolved.state()).isEqualTo(SkillResolutionState.MALFORMED);
        assertThat(resolved.shadowedCandidates()).hasSize(1);
        assertThat(resolved.shadowedCandidates().getFirst().scopeType()).isEqualTo(SkillScope.GLOBAL);
    }

    private static CandidateVersion candidate(String digest, SkillScope scope) {
        return CandidateVersion.of("test-skill", scope,
                scope == SkillScope.GLOBAL ? "global" : "agent/agent-1",
                scope == SkillScope.GLOBAL ? SkillProvenance.GLOBAL_PACKAGE : SkillProvenance.WORKSPACE_FLAT,
                scope == SkillScope.GLOBAL ? 2 : 1, digest, Instant.now());
    }

    private static SkillScopeDiscoveryService.ScopeDiscoveryResult result(
            CandidateVersion occupant, List<AgentSkillDiagnostic> diagnostics,
            CandidateVersion... lower) {
        List<CandidateVersion> workspace = occupant.scopeType() == SkillScope.WORKSPACE ? List.of(occupant) : List.of();
        List<CandidateVersion> global = occupant.scopeType() == SkillScope.GLOBAL
                ? List.of(occupant) : List.of(lower);
        SkillScopeDiscoveryService.CandidateObservation observation =
                new SkillScopeDiscoveryService.CandidateObservation(occupant, true, diagnostics,
                        Instant.now(), "scan-1");
        Map<String, SkillScopeDiscoveryService.CandidateObservation> observations =
                Map.of(occupant.identityKey(), observation);
        if (lower.length > 0) {
            SkillScopeDiscoveryService.CandidateObservation shadowObservation =
                    new SkillScopeDiscoveryService.CandidateObservation(lower[0], true, List.of(),
                            Instant.now(), "scan-1");
            observations = Map.of(occupant.identityKey(), observation,
                    lower[0].identityKey(), shadowObservation);
        }
        return new SkillScopeDiscoveryService.ScopeDiscoveryResult(workspace, List.of(), List.of(),
                global, observations, "scan-1", Instant.now());
    }
}
