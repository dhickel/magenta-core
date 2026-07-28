package io.mindspice.magenta2.ai.skills;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class AgentSkillAssignmentRepository {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,254})");
    private static final Pattern OPERATION_KEY = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:/-]{0,254})");
    private final JdbcTemplate jdbcTemplate;

    public AgentSkillAssignmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    public AgentSkillAssignment save(
        Long skillId,
        String skillName,
        AgentSkillTargetType targetType,
        String targetId,
        boolean enabled
    ) {
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                insert into agent_skill_assignments (
                    skill_id, skill_name, target_type, target_id, enabled, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict(skill_name, target_type, target_id) do update set
                    skill_id = excluded.skill_id,
                    enabled = excluded.enabled,
                    updated_at = excluded.updated_at
                """,
            skillId,
            skillName,
            targetType.name(),
            targetId,
            enabled ? 1 : 0,
            now.toString(),
            now.toString()
        );
        return find(skillName, targetType, targetId).orElseThrow();
    }

    public Optional<AgentSkillAssignment> find(String skillName, AgentSkillTargetType targetType, String targetId) {
        if (!StringUtils.hasText(skillName) || targetType == null || !StringUtils.hasText(targetId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select *
                from agent_skill_assignments
                where skill_name = ? and target_type = ? and target_id = ?
                """,
            rs -> rs.next() ? Optional.of(toAssignment(rs)) : Optional.empty(),
            skillName,
            targetType.name(),
            targetId
        );
    }

    public List<AgentSkillAssignment> findByTarget(AgentSkillTargetType targetType, String targetId) {
        if (targetType == null || !StringUtils.hasText(targetId)) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
                select *
                from agent_skill_assignments
                where target_type = ? and target_id = ?
                order by skill_name
                """,
            (rs, rowNum) -> toAssignment(rs),
            targetType.name(),
            targetId
        );
    }

    public List<AgentSkillAssignment> findBySkillName(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
                select *
                from agent_skill_assignments
                where skill_name = ?
                order by target_type, target_id
                """,
            (rs, rowNum) -> toAssignment(rs),
            skillName
        );
    }

    public List<String> findEnabledSkillNames(AgentSkillTargetType targetType, String targetId) {
        if (targetType == null || !StringUtils.hasText(targetId)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
            """
                select skill_name
                from agent_skill_assignments
                where target_type = ? and target_id = ? and enabled = 1
                order by skill_name
                """,
            String.class,
            targetType.name(),
            targetId
        );
    }

    public void delete(String skillName, AgentSkillTargetType targetType, String targetId) {
        if (!StringUtils.hasText(skillName) || targetType == null || !StringUtils.hasText(targetId)) {
            return;
        }
        jdbcTemplate.update(
            "delete from agent_skill_assignments where skill_name = ? and target_type = ? and target_id = ?",
            skillName,
            targetType.name(),
            targetId
        );
    }

    private AgentSkillAssignment toAssignment(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number skillId = (Number) rs.getObject("skill_id");
        return new AgentSkillAssignment(
            id == null ? null : id.longValue(),
            skillId == null ? null : skillId.longValue(),
            rs.getString("skill_name"),
            AgentSkillTargetType.valueOf(rs.getString("target_type")),
            rs.getString("target_id"),
            rs.getInt("enabled") == 1,
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    // ── Candidate version persistence ──

    /**
     * Insert a candidate version if not already present (by identity key).
     * Returns the persisted candidate with its assigned id.
     */
    public CandidateVersion insertCandidateVersion(CandidateVersion candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate is required");
        // Reconstructing applies all logical-key, digest, provenance, and authority checks
        // before any value reaches persistence.
        candidate = new CandidateVersion(candidate.id(), candidate.logicalKey(), candidate.scopeType(),
            candidate.authoritativeRootId(), candidate.provenance(), candidate.formatVersion(),
            candidate.contentDigest(), candidate.createdAt());
        Instant now = candidate.createdAt() != null ? candidate.createdAt() : Instant.now();
        jdbcTemplate.update(
            """
                insert or ignore into skill_candidate_versions (
                    logical_key, scope_type, authoritative_root_id, provenance,
                    format_version, content_digest, created_at
                ) values (?, ?, ?, ?, ?, ?, ?)
                """,
            candidate.logicalKey(),
            candidate.scopeType().name(),
            candidate.authoritativeRootId(),
            candidate.provenance().name(),
            candidate.formatVersion(),
            candidate.contentDigest(),
            now.toString()
        );
        return findByCandidateIdentity(candidate).orElseThrow(
            () -> new IllegalStateException("Failed to persist candidate version"));
    }

    public Optional<CandidateVersion> findByCandidateIdentity(CandidateVersion candidate) {
        return jdbcTemplate.query(
            """
                select * from skill_candidate_versions
                where logical_key = ? and scope_type = ? and authoritative_root_id = ?
                  and provenance = ? and format_version = ? and content_digest = ?
                """,
            rs -> rs.next() ? Optional.of(fromCandidateRow(rs)) : Optional.empty(),
            candidate.logicalKey(),
            candidate.scopeType().name(),
            candidate.authoritativeRootId(),
            candidate.provenance().name(),
            candidate.formatVersion(),
            candidate.contentDigest()
        );
    }

    public Optional<CandidateVersion> findCandidateById(Long id) {
        return jdbcTemplate.query(
            "select * from skill_candidate_versions where id = ?",
            rs -> rs.next() ? Optional.of(fromCandidateRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<CandidateVersion> findAllCandidates() {
        return jdbcTemplate.query(
            "select * from skill_candidate_versions order by logical_key, created_at desc, id desc",
            (rs, rowNum) -> fromCandidateRow(rs)
        );
    }

    public List<CandidateVersion> findCandidatesByLogicalKey(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return List.of();
        return jdbcTemplate.query(
            "select * from skill_candidate_versions where logical_key = ? order by created_at desc, id desc",
            (rs, rowNum) -> fromCandidateRow(rs),
            logicalKey
        );
    }

    public List<CandidateVersion> findCandidatesByScope(String logicalKey, SkillScope scope) {
        return jdbcTemplate.query(
            "select * from skill_candidate_versions where logical_key = ? and scope_type = ? order by id",
            (rs, rowNum) -> fromCandidateRow(rs),
            logicalKey,
            scope.name()
        );
    }

    // ── Observation persistence ──

    /** Insert an observation record for a candidate version. */
    public void insertObservation(
        Long candidateVersionId,
        boolean available,
        List<AgentSkillDiagnostic> diagnostics,
        Instant observedAt,
        String scanId
    ) {
        requirePositive(candidateVersionId, "candidateVersionId");
        requireIdentifier(scanId, "scanId", OPERATION_KEY);
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
        String diagnosticsJson = jsonDiagnostics(diagnostics);
        jdbcTemplate.update(
            """
                insert into skill_candidate_observations (
                    candidate_version_id, is_available, diagnostics_json, observed_at, scan_id
                ) values (?, ?, ?, ?, ?)
                """,
            candidateVersionId,
            available ? 1 : 0,
            diagnosticsJson,
            observedAt.toString(),
            scanId
        );
    }

    /** Mark all observations for a scope as unavailable (completed scan cleanup). */
    public int markScopeUnavailable(SkillScope scope, String authoritativeRootId, String excludeScanId) {
        return jdbcTemplate.update(
            """
                update skill_candidate_observations set is_available = 0
                where candidate_version_id in (
                    select id from skill_candidate_versions
                    where scope_type = ? and authoritative_root_id = ?
                ) and scan_id <> ?
                """,
            scope.name(),
            authoritativeRootId,
            excludeScanId
        );
    }

    // ── Exact-version assignment overlay persistence ──

    /**
     * Persist an exact-version assignment overlay. Idempotent when the same
     * operation key is used with matching parameters; rejects contradictory reuse.
     */
    public ResolvedSkill.ExactAssignmentOverlay upsertOverlay(
        Long candidateVersionId,
        String logicalKey,
        AgentSkillTargetType targetType,
        String targetKey,
        boolean enabled,
        String operationKey,
        String actor
    ) {
        requirePositive(candidateVersionId, "candidateVersionId");
        requireIdentifier(logicalKey, "logicalKey", Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?"));
        if (targetType == null) throw new IllegalArgumentException("targetType is required");
        requireIdentifier(targetKey, "targetKey", SAFE_IDENTIFIER);
        if (operationKey != null) requireIdentifier(operationKey, "operationKey", OPERATION_KEY);
        if (actor != null) requireIdentifier(actor, "actor", SAFE_IDENTIFIER);
        CandidateVersion bound = findCandidateById(candidateVersionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown candidate version: " + candidateVersionId));
        if (!bound.logicalKey().equals(logicalKey)) {
            throw new IllegalArgumentException("Candidate logical key does not match overlay");
        }
        Instant now = Instant.now();
        if (operationKey != null && !operationKey.isBlank()) {
            // Check for existing overlay with the same operation key
            List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select * from skill_exact_assignments where operation_key = ?",
                operationKey
            );
            if (!existing.isEmpty()) {
                Map<String, Object> row = existing.get(0);
                Number existingCvId = (Number) row.get("candidate_version_id");
                String existingLogicalKey = (String) row.get("logical_key");
                String existingTargetType = (String) row.get("target_type");
                String existingTargetKey = (String) row.get("target_key");
                Number existingEnabled = (Number) row.get("enabled");
                if (existingCvId.longValue() == candidateVersionId
                    && existingLogicalKey.equals(logicalKey)
                    && existingTargetType.equals(targetType.name())
                    && existingTargetKey.equals(targetKey)
                    && existingEnabled.intValue() == (enabled ? 1 : 0)) {
                    return fetchOverlay((Number) row.get("id")).orElseThrow();
                }
                throw new IllegalArgumentException(
                    "Contradictory reuse of operation key: " + operationKey);
            }
        }
        jdbcTemplate.update(
            """
                insert into skill_exact_assignments (
                    candidate_version_id, logical_key, target_type, target_key,
                    enabled, operation_key, actor, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(logical_key, target_type, target_key) do update set
                    candidate_version_id = excluded.candidate_version_id,
                    enabled = excluded.enabled,
                    operation_key = coalesce(excluded.operation_key, skill_exact_assignments.operation_key),
                    actor = coalesce(excluded.actor, skill_exact_assignments.actor),
                    updated_at = excluded.updated_at
                """,
            candidateVersionId,
            logicalKey,
            targetType.name(),
            targetKey,
            enabled ? 1 : 0,
            operationKey,
            actor,
            now.toString(),
            now.toString()
        );
        return fetchOverlay(logicalKey, targetType, targetKey).orElseThrow();
    }

    public Optional<ResolvedSkill.ExactAssignmentOverlay> fetchOverlay(
        String logicalKey, AgentSkillTargetType targetType, String targetKey) {
        return jdbcTemplate.query(
            "select * from skill_exact_assignments where logical_key = ? and target_type = ? and target_key = ?",
            rs -> rs.next() ? Optional.of(fromOverlayRow(rs)) : Optional.empty(),
            logicalKey, targetType.name(), targetKey
        );
    }

    public List<ResolvedSkill.ExactAssignmentOverlay> findAllOverlays() {
        return jdbcTemplate.query(
            "select * from skill_exact_assignments order by logical_key, target_type, target_key",
            (rs, rowNum) -> fromOverlayRow(rs)
        );
    }

    public List<ResolvedSkill.ExactAssignmentOverlay> findOverlaysByLogicalKey(String logicalKey) {
        if (!StringUtils.hasText(logicalKey)) return List.of();
        return jdbcTemplate.query(
            "select * from skill_exact_assignments where logical_key = ? order by target_type, target_key",
            (rs, rowNum) -> fromOverlayRow(rs),
            logicalKey
        );
    }

    private Optional<ResolvedSkill.ExactAssignmentOverlay> fetchOverlay(Number id) {
        return jdbcTemplate.query(
            "select * from skill_exact_assignments where id = ?",
            rs -> rs.next() ? Optional.of(fromOverlayRow(rs)) : Optional.empty(),
            id.longValue()
        );
    }

    /**
     * Find the most specific overlay for a logical key given a resolution context.
     * Returns empty if no overlay applies.
     */
    public Optional<ResolvedSkill.ExactAssignmentOverlay> findMostSpecificOverlay(
        String logicalKey, SkillResolutionContext context) {
        List<ResolvedSkill.ExactAssignmentOverlay> overlays = findMostSpecificOverlays(logicalKey, context);
        return overlays.size() == 1 ? Optional.of(overlays.getFirst()) : Optional.empty();
    }

    /** Returns all overlays at the winning specificity so invalid ties remain visible. */
    public List<ResolvedSkill.ExactAssignmentOverlay> findMostSpecificOverlays(
        String logicalKey, SkillResolutionContext context) {
        List<List<TargetRef>> levels = List.of(
            refs(new TargetRef(AgentSkillTargetType.CHAT_SESSION, context.chatSessionId())),
            refs(new TargetRef(AgentSkillTargetType.WORKFLOW, context.workflowId()),
                 new TargetRef(AgentSkillTargetType.TASK, context.taskId())),
            refs(new TargetRef(AgentSkillTargetType.JOB, context.jobId())),
            refs(new TargetRef(AgentSkillTargetType.PROJECT, context.projectId())),
            refs(new TargetRef(AgentSkillTargetType.AGENT, context.agentId())));
        for (List<TargetRef> level : levels) {
            List<ResolvedSkill.ExactAssignmentOverlay> found = level.stream()
                .filter(ref -> StringUtils.hasText(ref.key()))
                .map(ref -> fetchOverlay(logicalKey, ref.type(), ref.key()))
                .flatMap(Optional::stream).toList();
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    public Set<String> findApplicableOverlayKeys(SkillResolutionContext context) {
        Set<String> keys = new LinkedHashSet<>();
        for (TargetRef ref : allTargetRefs(context)) {
            if (!StringUtils.hasText(ref.key())) continue;
            keys.addAll(jdbcTemplate.queryForList(
                "select logical_key from skill_exact_assignments where target_type = ? and target_key = ?",
                String.class, ref.type().name(), ref.key()));
        }
        return Set.copyOf(keys);
    }

    public void deleteOverlay(String logicalKey, AgentSkillTargetType targetType, String targetKey) {
        jdbcTemplate.update(
            "delete from skill_exact_assignments where logical_key = ? and target_type = ? and target_key = ?",
            logicalKey, targetType.name(), targetKey
        );
    }

    // ── Legacy conversion diagnostics ──

    public void insertLegacyConversionDiagnostic(
        String agentId,
        String logicalKey,
        String result,
        Long candidateVersionId,
        List<AgentSkillDiagnostic> diagnostics,
        Instant convertedAt
    ) {
        jdbcTemplate.update(
            """
                insert or ignore into skill_legacy_conversion_diagnostics (
                    agent_id, logical_key, result, candidate_version_id,
                    diagnostics_json, converted_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
            agentId,
            logicalKey,
            result,
            candidateVersionId,
            jsonDiagnostics(diagnostics),
            convertedAt.toString()
        );
    }

    public List<Map<String, Object>> findLegacyDiagnosticsByAgent(String agentId) {
        return jdbcTemplate.queryForList(
            "select * from skill_legacy_conversion_diagnostics where agent_id = ? order by logical_key",
            agentId
        );
    }

    // ── Row mappers ──

    private CandidateVersion fromCandidateRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        return new CandidateVersion(
            id == null ? null : id.longValue(),
            rs.getString("logical_key"),
            SkillScope.valueOf(rs.getString("scope_type")),
            rs.getString("authoritative_root_id"),
            SkillProvenance.valueOf(rs.getString("provenance")),
            rs.getInt("format_version"),
            rs.getString("content_digest"),
            instant(rs.getString("created_at"))
        );
    }

    private ResolvedSkill.ExactAssignmentOverlay fromOverlayRow(ResultSet rs) throws SQLException {
        Number id = (Number) rs.getObject("id");
        Number cvId = (Number) rs.getObject("candidate_version_id");
        return new ResolvedSkill.ExactAssignmentOverlay(
            id == null ? null : id.longValue(),
            cvId == null ? null : cvId.longValue(),
            rs.getString("logical_key"),
            AgentSkillTargetType.valueOf(rs.getString("target_type")),
            rs.getString("target_key"),
            rs.getInt("enabled") == 1,
            rs.getString("operation_key"),
            rs.getString("actor"),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private String jsonDiagnostics(List<AgentSkillDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) return "[]";
        if (diagnostics.size() > 32) throw new IllegalArgumentException("Too many diagnostics");
        for (AgentSkillDiagnostic diagnostic : diagnostics) {
            if (diagnostic == null || diagnostic.severity() == null || diagnostic.code() == null
                    || !StringUtils.hasText(diagnostic.message()) || diagnostic.message().length() > 512) {
                throw new IllegalArgumentException("Invalid diagnostic");
            }
            if (StringUtils.hasText(diagnostic.sourcePath())) {
                Path source = Path.of(diagnostic.sourcePath().replace('\\', '/')).normalize();
                if (source.isAbsolute() || source.startsWith("..") || diagnostic.sourcePath().length() > 255) {
                    throw new IllegalArgumentException("Diagnostic source path must be bounded and relative");
                }
            }
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(diagnostics);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Diagnostics cannot be serialized", exception);
        }
    }

    private static void requirePositive(Long value, String label) {
        if (value == null || value < 1) throw new IllegalArgumentException(label + " must be positive");
    }

    private static void requireIdentifier(String value, String label, Pattern pattern) {
        if (!StringUtils.hasText(value) || !pattern.matcher(value).matches()
                || value.startsWith("/") || value.indexOf('\\') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException(label + " is not a valid authority identifier");
        }
    }

    private static List<TargetRef> refs(TargetRef... refs) { return List.of(refs); }

    private static List<TargetRef> allTargetRefs(SkillResolutionContext context) {
        return List.of(new TargetRef(AgentSkillTargetType.CHAT_SESSION, context.chatSessionId()),
            new TargetRef(AgentSkillTargetType.WORKFLOW, context.workflowId()),
            new TargetRef(AgentSkillTargetType.TASK, context.taskId()),
            new TargetRef(AgentSkillTargetType.JOB, context.jobId()),
            new TargetRef(AgentSkillTargetType.PROJECT, context.projectId()),
            new TargetRef(AgentSkillTargetType.AGENT, context.agentId()));
    }

    private record TargetRef(AgentSkillTargetType type, String key) { }

    private void ensureSchema() {
        jdbcTemplate.execute(
            """
                create table if not exists agent_skill_assignments (
                    id integer primary key autoincrement,
                    skill_id integer,
                    skill_name text not null,
                    target_type text not null,
                    target_id text not null,
                    enabled integer not null,
                    created_at text not null,
                    updated_at text not null,
                    unique(skill_name, target_type, target_id)
                )
                """
        );
        jdbcTemplate.execute(
            "create index if not exists idx_agent_skill_assignments_target on agent_skill_assignments(target_type, target_id, enabled)"
        );
        jdbcTemplate.execute(
            "create index if not exists idx_agent_skill_assignments_skill_id on agent_skill_assignments(skill_id)"
        );
    }
}
