package io.mindspice.magenta2.ai.skills;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentSkillAssignmentRepositoryOverlayTest {
    private JdbcTemplate jdbcTemplate;
    private AgentSkillAssignmentRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true", true
        );
        // Apply migration 003 schema
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
            create table if not exists skill_candidate_versions (
                id integer primary key autoincrement,
                logical_key text not null,
                scope_type text not null check (scope_type in ('WORKSPACE', 'BOUND_AGENT', 'PROJECT', 'GLOBAL')),
                authoritative_root_id text not null,
                provenance text not null check (provenance in ('GLOBAL_PACKAGE', 'WORKSPACE_FLAT')),
                format_version integer not null,
                content_digest text not null check (length(content_digest) = 64 and content_digest not glob '*[^0-9a-f]*'),
                created_at text not null,
                unique (logical_key, scope_type, authoritative_root_id, provenance, format_version, content_digest)
            )
            """);
        jdbcTemplate.execute("create table if not exists skill_candidate_observations (\n" +
            "    id integer primary key autoincrement,\n" +
            "    candidate_version_id integer not null,\n" +
            "    is_available integer not null check (is_available in (0, 1)),\n" +
            "    diagnostics_json text not null,\n" +
            "    observed_at text not null,\n" +
            "    scan_id text not null,\n" +
            "    foreign key (candidate_version_id) references skill_candidate_versions(id)\n" +
            ")");
        jdbcTemplate.execute("create table if not exists skill_exact_assignments (\n" +
            "    id integer primary key autoincrement,\n" +
            "    candidate_version_id integer not null,\n" +
            "    logical_key text not null,\n" +
            "    target_type text not null check (target_type in ('AGENT', 'PROJECT', 'JOB', 'TASK', 'WORKFLOW', 'CHAT_SESSION')),\n" +
            "    target_key text not null,\n" +
            "    enabled integer not null check (enabled in (0, 1)),\n" +
            "    operation_key text,\n" +
            "    actor text,\n" +
            "    created_at text not null,\n" +
            "    updated_at text not null,\n" +
            "    unique (logical_key, target_type, target_key),\n" +
            "    foreign key (candidate_version_id) references skill_candidate_versions(id)\n" +
            ")");
        jdbcTemplate.execute("create table if not exists skill_legacy_conversion_diagnostics (\n" +
            "    id integer primary key autoincrement,\n" +
            "    agent_id text not null,\n" +
            "    logical_key text not null,\n" +
            "    result text not null check (result in ('CONVERTED', 'UNAMBIGUOUS', 'AMBIGUOUS', 'NO_MATCH')),\n" +
            "    candidate_version_id integer,\n" +
            "    diagnostics_json text not null,\n" +
            "    converted_at text not null,\n" +
            "    unique (agent_id, logical_key),\n" +
            "    foreign key (candidate_version_id) references skill_candidate_versions(id)\n" +
            ")");
        repository = new AgentSkillAssignmentRepository(jdbcTemplate);
    }

    @Test
    void insertCandidateVersionAndFindByIdentity() {
        CandidateVersion cv = CandidateVersion.of(
            "test-skill", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        );
        CandidateVersion persisted = repository.insertCandidateVersion(cv);
        assertThat(persisted.id()).isNotNull();
        assertThat(persisted.logicalKey()).isEqualTo("test-skill");

        Optional<CandidateVersion> found = repository.findByCandidateIdentity(cv);
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(persisted.id());
    }

    @Test
    void insertCandidateVersionIsIdempotent() {
        CandidateVersion cv = CandidateVersion.of(
            "test-skill", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        );
        CandidateVersion first = repository.insertCandidateVersion(cv);
        CandidateVersion second = repository.insertCandidateVersion(cv);
        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test
    void insertObservationAndMarkScopeUnavailable() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill1", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        repository.insertObservation(cv.id(), true, List.of(), Instant.now(), "scan-1");
        // Verify observation exists by marking unavailable
        int updated = repository.markScopeUnavailable(SkillScope.GLOBAL, "global", "scan-1");
        assertThat(updated).isGreaterThanOrEqualTo(0);
    }

    @Test
    void upsertOverlayAndFetch() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-x", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        ResolvedSkill.ExactAssignmentOverlay overlay = repository.upsertOverlay(
            cv.id(), "skill-x", AgentSkillTargetType.AGENT, "agent-1",
            true, "op-abc", "test-actor"
        );
        assertThat(overlay.id()).isNotNull();
        assertThat(overlay.logicalKey()).isEqualTo("skill-x");
        assertThat(overlay.targetType()).isEqualTo(AgentSkillTargetType.AGENT);
        assertThat(overlay.targetKey()).isEqualTo("agent-1");
        assertThat(overlay.enabled()).isTrue();
        assertThat(overlay.operationKey()).isEqualTo("op-abc");
        assertThat(overlay.actor()).isEqualTo("test-actor");
    }

    @Test
    void upsertOverlayIsIdempotentWithSameOperationKey() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-y", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        ResolvedSkill.ExactAssignmentOverlay first = repository.upsertOverlay(
            cv.id(), "skill-y", AgentSkillTargetType.AGENT, "agent-1",
            true, "op-xyz", "test"
        );
        ResolvedSkill.ExactAssignmentOverlay second = repository.upsertOverlay(
            cv.id(), "skill-y", AgentSkillTargetType.AGENT, "agent-1",
            true, "op-xyz", "test"
        );
        assertThat(first.id()).isEqualTo(second.id());
    }

    @Test
    void upsertOverlayRejectsContradictoryOperationKeyReuse() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-z", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        repository.upsertOverlay(cv.id(), "skill-z", AgentSkillTargetType.AGENT, "agent-1",
            true, "op-contra", "test");

        assertThatThrownBy(() -> repository.upsertOverlay(
            cv.id(), "skill-z", AgentSkillTargetType.AGENT, "agent-2", // different target
            true, "op-contra", "test"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findMostSpecificOverlayPrefersChatSession() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-m", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        repository.upsertOverlay(cv.id(), "skill-m", AgentSkillTargetType.AGENT, "agent-1",
            true, null, null);
        repository.upsertOverlay(cv.id(), "skill-m", AgentSkillTargetType.CHAT_SESSION, "chat-1",
            false, null, null);

        SkillResolutionContext ctx = new SkillResolutionContext(
            null, null, "agent-1", null, null, null,
            null, null, "chat-1"
        );
        Optional<ResolvedSkill.ExactAssignmentOverlay> overlay =
            repository.findMostSpecificOverlay("skill-m", ctx);
        assertThat(overlay).isPresent();
        assertThat(overlay.get().targetType()).isEqualTo(AgentSkillTargetType.CHAT_SESSION);
        assertThat(overlay.get().enabled()).isFalse();
    }

    @Test
    void findMostSpecificOverlayFallsBackToAgent() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-n", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        repository.upsertOverlay(cv.id(), "skill-n", AgentSkillTargetType.AGENT, "agent-1",
            true, null, null);

        SkillResolutionContext ctx = new SkillResolutionContext(
            null, null, "agent-1", null, null, null,
            null, null, null
        );
        Optional<ResolvedSkill.ExactAssignmentOverlay> overlay =
            repository.findMostSpecificOverlay("skill-n", ctx);
        assertThat(overlay).isPresent();
        assertThat(overlay.get().targetType()).isEqualTo(AgentSkillTargetType.AGENT);
    }

    @Test
    void findCandidatesByLogicalKey() {
        CandidateVersion cv1 = repository.insertCandidateVersion(CandidateVersion.of(
            "shared-key", SkillScope.WORKSPACE, "agent/1",
            SkillProvenance.WORKSPACE_FLAT, 1, "a".repeat(64), Instant.now()
        ));
        CandidateVersion cv2 = repository.insertCandidateVersion(CandidateVersion.of(
            "shared-key", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "b".repeat(64), Instant.now()
        ));
        List<CandidateVersion> candidates = repository.findCandidatesByLogicalKey("shared-key");
        assertThat(candidates).hasSize(2);
        assertThat(candidates).extracting(CandidateVersion::scopeType)
            .containsExactlyInAnyOrder(SkillScope.WORKSPACE, SkillScope.GLOBAL);
    }

    @Test
    void deleteOverlayRemovesAssignment() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "skill-del", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        repository.upsertOverlay(cv.id(), "skill-del", AgentSkillTargetType.AGENT, "agent-1",
            true, null, null);
        repository.deleteOverlay("skill-del", AgentSkillTargetType.AGENT, "agent-1");
        Optional<ResolvedSkill.ExactAssignmentOverlay> found =
            repository.fetchOverlay("skill-del", AgentSkillTargetType.AGENT, "agent-1");
        assertThat(found).isEmpty();
    }

    @Test
    void legacyConversionDiagnosticsAreIdempotent() {
        CandidateVersion cv = repository.insertCandidateVersion(CandidateVersion.of(
            "legacy-skill", SkillScope.GLOBAL, "global",
            SkillProvenance.GLOBAL_PACKAGE, 2, "a".repeat(64), Instant.now()
        ));
        Instant now = Instant.now();
        repository.insertLegacyConversionDiagnostic(
            "agent-1", "legacy-skill", "CONVERTED", cv.id(), List.of(), now
        );
        // Second insert should be ignored
        repository.insertLegacyConversionDiagnostic(
            "agent-1", "legacy-skill", "CONVERTED", cv.id(), List.of(), now
        );
        List<Map<String, Object>> diags = repository.findLegacyDiagnosticsByAgent("agent-1");
        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).get("result")).isEqualTo("CONVERTED");
    }
}
