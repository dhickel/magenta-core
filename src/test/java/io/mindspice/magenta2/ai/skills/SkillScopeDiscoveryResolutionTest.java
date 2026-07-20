package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SkillScopeDiscoveryResolutionTest {

    // ── CandidateVersion tests ──

    @Test
    void candidateIdentityExcludesDisplayNamesAndFilenames() {
        CandidateVersion cv = CandidateVersion.of(
            "my-skill", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2,
            "a".repeat(64), Instant.now()
        );
        assertThat(cv.logicalKey()).isEqualTo("my-skill");
        assertThat(cv.scopeType()).isEqualTo(SkillScope.GLOBAL);
        assertThat(cv.authoritativeRootId()).isEqualTo("global");
        assertThat(cv.provenance()).isEqualTo(SkillProvenance.GLOBAL_PACKAGE);
        assertThat(cv.formatVersion()).isEqualTo(2);
        assertThat(cv.contentDigest()).hasSize(64);
    }

    @Test
    void candidateIdentityKeyIsDeterministic() {
        Instant now = Instant.now();
        CandidateVersion a = CandidateVersion.of(
            "key1", SkillScope.WORKSPACE, "agent/1",
            SkillProvenance.WORKSPACE_FLAT, 1, "a".repeat(64), now
        );
        CandidateVersion b = CandidateVersion.of(
            "key1", SkillScope.WORKSPACE, "agent/1",
            SkillProvenance.WORKSPACE_FLAT, 1, "a".repeat(64), now
        );
        CandidateVersion c = CandidateVersion.of(
            "key1", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "b".repeat(64), now
        );
        assertThat(a.identityKey()).isEqualTo(b.identityKey());
        assertThat(a.identityKey()).isNotEqualTo(c.identityKey());
    }

    @Test
    void candidateRejectsInvalidDigest() {
        assertThatThrownBy(() -> CandidateVersion.of(
            "key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "too-short", Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> CandidateVersion.of(
            "key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "G".repeat(64), Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void candidateRejectsZeroOrNegativeFormatVersion() {
        assertThatThrownBy(() -> CandidateVersion.of(
            "key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 0, "a".repeat(64), Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    // ── SkillScope precedence tests ──

    @Test
    void workspaceHasHighestPrecedence() {
        assertThat(SkillScope.compare(SkillScope.WORKSPACE, SkillScope.GLOBAL)).isNegative();
        assertThat(SkillScope.compare(SkillScope.WORKSPACE, SkillScope.BOUND_AGENT)).isNegative();
        assertThat(SkillScope.compare(SkillScope.WORKSPACE, SkillScope.PROJECT)).isNegative();
    }

    @Test
    void globalHasLowestPrecedence() {
        assertThat(SkillScope.compare(SkillScope.GLOBAL, SkillScope.WORKSPACE)).isPositive();
        assertThat(SkillScope.compare(SkillScope.GLOBAL, SkillScope.BOUND_AGENT)).isPositive();
        assertThat(SkillScope.compare(SkillScope.GLOBAL, SkillScope.PROJECT)).isPositive();
    }

    @Test
    void boundAgentBeatsProjectAndGlobal() {
        assertThat(SkillScope.compare(SkillScope.BOUND_AGENT, SkillScope.PROJECT)).isNegative();
        assertThat(SkillScope.compare(SkillScope.BOUND_AGENT, SkillScope.GLOBAL)).isNegative();
    }

    // ── SkillResolutionState tests ──

    @Test
    void resolvedAndPendingAreLoadable() {
        assertThat(SkillResolutionState.RESOLVED.isLoadable()).isTrue();
        assertThat(SkillResolutionState.PENDING.isLoadable()).isTrue();
        assertThat(SkillResolutionState.DISABLED.isLoadable()).isFalse();
        assertThat(SkillResolutionState.UNAVAILABLE.isLoadable()).isFalse();
        assertThat(SkillResolutionState.STALE.isLoadable()).isFalse();
        assertThat(SkillResolutionState.MALFORMED.isLoadable()).isFalse();
        assertThat(SkillResolutionState.CONFLICT.isLoadable()).isFalse();
    }

    @Test
    void occupiedStatesExcludeNoCandidateAndShadowed() {
        assertThat(SkillResolutionState.NO_CANDIDATE.isOccupied()).isFalse();
        assertThat(SkillResolutionState.SHADOWED.isOccupied()).isFalse();
        assertThat(SkillResolutionState.PENDING.isOccupied()).isTrue();
        assertThat(SkillResolutionState.UNAVAILABLE.isOccupied()).isTrue();
    }

    // ── ResolvedSkill tests ──

    @Test
    void resolvedSkillNoCandidateHasNullOccupant() {
        ResolvedSkill rs = ResolvedSkill.noCandidate("key");
        assertThat(rs.logicalKey()).isEqualTo("key");
        assertThat(rs.state()).isEqualTo(SkillResolutionState.NO_CANDIDATE);
        assertThat(rs.occupiedCandidate()).isNull();
        assertThat(rs.overlay()).isNull();
    }

    @Test
    void resolvedSkillOccupiedStateRequiresCandidate() {
        assertThatThrownBy(() -> ResolvedSkill.pending("key", null))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void resolvedSkillFactoryIncludesOverlay() {
        CandidateVersion cv = CandidateVersion.of(
            "key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        );
        ResolvedSkill.ExactAssignmentOverlay overlay = new ResolvedSkill.ExactAssignmentOverlay(
            null, null, "key", AgentSkillTargetType.AGENT, "agent-1",
            true, "op-1", "test", Instant.now(), Instant.now()
        );
        ResolvedSkill rs = ResolvedSkill.resolved("key", cv, overlay);
        assertThat(rs.state()).isEqualTo(SkillResolutionState.RESOLVED);
        assertThat(rs.overlay()).isNotNull();
        assertThat(rs.overlay().enabled()).isTrue();
    }

    @Test
    void resolvedSkillDisabledStatePreservesOverlay() {
        CandidateVersion cv = CandidateVersion.of(
            "key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        );
        ResolvedSkill.ExactAssignmentOverlay overlay = new ResolvedSkill.ExactAssignmentOverlay(
            null, null, "key", AgentSkillTargetType.AGENT, "agent-1",
            false, "op-1", "test", Instant.now(), Instant.now()
        );
        ResolvedSkill rs = ResolvedSkill.disabled("key", cv, overlay);
        assertThat(rs.state()).isEqualTo(SkillResolutionState.DISABLED);
        assertThat(rs.overlay().enabled()).isFalse();
    }

    // ── Digest tests ──

    @Test
    void digestFlatFileIsStable() {
        byte[] content = "test content\n".getBytes(StandardCharsets.UTF_8);
        String digest1 = SkillScopeDiscoveryService.digestFlatFile(content);
        String digest2 = SkillScopeDiscoveryService.digestFlatFile(content);
        assertThat(digest1).isEqualTo(digest2);
        assertThat(digest1).hasSize(64);
        assertThat(digest1).matches("^[0-9a-f]{64}$");
    }

    @Test
    void digestFlatFileChangesWithContent() {
        String d1 = SkillScopeDiscoveryService.digestFlatFile("a".getBytes(StandardCharsets.UTF_8));
        String d2 = SkillScopeDiscoveryService.digestFlatFile("b".getBytes(StandardCharsets.UTF_8));
        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestPackageTreeIsDeterministic(@TempDir Path pkgDir) throws Exception {
        Path skillMd = pkgDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: test\n---\n# Body\n", StandardCharsets.UTF_8);
        Files.createDirectories(pkgDir.resolve("scripts"));
        Files.writeString(pkgDir.resolve("scripts/run.py"), "print('hello')", StandardCharsets.UTF_8);

        byte[] mdBytes = Files.readAllBytes(skillMd);
        String d1 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);
        String d2 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);
        assertThat(d1).isEqualTo(d2);
        assertThat(d1).hasSize(64);
    }

    @Test
    void digestPackageTreeChangesWhenFileContentChanges(@TempDir Path pkgDir) throws Exception {
        Path skillMd = pkgDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: test\n---\n# V1\n", StandardCharsets.UTF_8);
        Files.writeString(pkgDir.resolve("extra.txt"), "v1", StandardCharsets.UTF_8);
        byte[] mdBytes = Files.readAllBytes(skillMd);

        String d1 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);

        Files.writeString(pkgDir.resolve("extra.txt"), "v2", StandardCharsets.UTF_8);
        String d2 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);

        assertThat(d1).isNotEqualTo(d2);
    }

    @Test
    void digestPackageTreeIgnoresDirectories(@TempDir Path pkgDir) throws Exception {
        Path skillMd = pkgDir.resolve("SKILL.md");
        Files.writeString(skillMd, "---\nname: test\n---\n# Body\n", StandardCharsets.UTF_8);
        Files.createDirectories(pkgDir.resolve("empty-dir"));

        byte[] mdBytes = Files.readAllBytes(skillMd);
        String d1 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);

        Files.createDirectories(pkgDir.resolve("another-empty"));
        String d2 = SkillScopeDiscoveryService.digestPackageTree(pkgDir, mdBytes);
        assertThat(d1).isEqualTo(d2);
    }

    // ── SkillResolutionService tests ──

    @Test
    void specificityOrderIsCorrect() {
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.CHAT_SESSION, AgentSkillTargetType.AGENT)).isNegative();
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.WORKFLOW, AgentSkillTargetType.TASK)).isZero();
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.TASK, AgentSkillTargetType.JOB)).isNegative();
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.JOB, AgentSkillTargetType.PROJECT)).isNegative();
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.PROJECT, AgentSkillTargetType.AGENT)).isNegative();
        assertThat(SkillResolutionService.compareSpecificity(
            AgentSkillTargetType.AGENT, AgentSkillTargetType.CHAT_SESSION)).isPositive();
    }

    // ── AgentSkillTargetType tests ──

    @Test
    void allTargetTypesAreDefined() {
        assertThat(AgentSkillTargetType.values()).containsExactlyInAnyOrder(
            AgentSkillTargetType.AGENT,
            AgentSkillTargetType.PROJECT,
            AgentSkillTargetType.JOB,
            AgentSkillTargetType.TASK,
            AgentSkillTargetType.WORKFLOW,
            AgentSkillTargetType.CHAT_SESSION
        );
    }
}
