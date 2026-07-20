package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementKind;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementOutcome;
import io.mindspice.magenta2.ai.orchestration.runtime.RunMeasurementRepository.MeasurementCompleteness;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Read-model service for truthful runtime summaries, health aggregates,
 * and audit composition.
 *
 * <p>Native run repositories remain authoritative for status; this service
 * normalizes only read semantics. Summaries are rebuildable from source
 * facts and must never become status authority.
 */
@Service
public class RunSummaryService {
    private static final Logger log = LoggerFactory.getLogger(RunSummaryService.class);

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_HISTORY = 1000;

    private final JdbcTemplate jdbc;
    private final RunMeasurementRepository measurementRepo;
    private final TaskRunLifecycleService lifecycleService;

    public RunSummaryService(JdbcTemplate jdbc, RunMeasurementRepository measurementRepo,
                              TaskRunLifecycleService lifecycleService) {
        this.jdbc = jdbc;
        this.measurementRepo = measurementRepo;
        this.lifecycleService = lifecycleService;
    }

    // ── Status Mapping ──

    /**
     * Map a raw native status string to the normalized read-model status.
     * Unknown or legacy states map to UNAVAILABLE and remain visible rather
     * than entering terminal denominators.
     */
    public static NormalizedRunStatus normalizeStatus(String rawStatus) {
        if (!StringUtils.hasText(rawStatus)) return NormalizedRunStatus.UNAVAILABLE;
        return switch (rawStatus.toUpperCase()) {
            case "COMPLETED" -> NormalizedRunStatus.SUCCEEDED;
            case "FAILED" -> NormalizedRunStatus.FAILED;
            case "CANCELLED" -> NormalizedRunStatus.CANCELLED;
            case "NEEDS_REVIEW" -> NormalizedRunStatus.PARTIAL;
            case "INTERRUPTED" -> NormalizedRunStatus.INTERRUPTED;
            case "QUEUED", "RUNNING", "WAITING", "PAUSED", "CANCEL_REQUESTED" -> NormalizedRunStatus.ACTIVE;
            default -> NormalizedRunStatus.UNAVAILABLE;
        };
    }

    public static NormalizedRunStatus normalizeStatus(TaskRunLifecycleService life, String taskRunId) {
        try {
            LifecycleResult result = life.findById(taskRunId);
            return result.nativeStatus() == null
                    ? NormalizedRunStatus.UNAVAILABLE
                    : normalizeStatus(result.nativeStatus());
        } catch (Exception e) {
            log.warn("Cannot resolve status for {}: {}", taskRunId, e.getMessage());
            return NormalizedRunStatus.UNAVAILABLE;
        }
    }

    // ── Summary Rebuilding ──

    /**
     * Rebuild (upsert) a summary from authoritative native facts and measurements.
     * Idempotent; the persisted summary is a cache that can be replaced from source.
     */
    @Transactional
    public RunSummary rebuildSummary(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) throw new IllegalArgumentException("taskRunId is required");

        // Gather native facts
        LifecycleResult life;
        Map<String, Object> location;
        try {
            life = lifecycleService.findById(taskRunId);
            location = jdbc.queryForMap("""
                    select workspace_type, workspace_key, source_kind,
                           lineage_parent_task_run_id, lineage_mode
                    from task_run_locations where task_run_id = ?
                    """, taskRunId);
        } catch (Exception e) {
            log.warn("Missing native facts for {}: {}", taskRunId, e.getMessage());
            return upsertUnavailable(taskRunId);
        }

        NormalizedRunStatus status = normalizeStatus(life.nativeStatus());
        int attemptCount = lifecycleService.currentAttemptNumber(taskRunId);
        int modelCallCount = measurementRepo.countByTaskRunAndKind(taskRunId, MeasurementKind.MODEL);
        int toolCallCount = measurementRepo.countByTaskRunAndKind(taskRunId, MeasurementKind.TOOL);

        // Elapsed derivation
        ElapsedResult elapsed = deriveElapsed(taskRunId, status);

        RunSummaryCompleteness completeness;
        if (elapsed != null && elapsed.completeness() != null) {
            completeness = elapsed.completeness();
        } else {
            completeness = status.isTerminal() ? RunSummaryCompleteness.COMPLETE : RunSummaryCompleteness.PARTIAL;
        }

        Instant now = Instant.now();
        RunSummary summary = new RunSummary(
                taskRunId, status, attemptCount, modelCallCount, toolCallCount,
                elapsed != null ? elapsed.wallElapsedMs() : null,
                elapsed != null ? elapsed.activeElapsedMs() : null,
                completeness,
                text(location.get("workspace_type")),
                text(location.get("workspace_key")),
                text(location.get("source_kind")),
                text(location.get("lineage_parent_task_run_id")),
                parseLineageModeStr(text(location.get("lineage_mode"))),
                now, now);

        upsertSummary(summary);
        return summary;
    }

    private RunSummary upsertUnavailable(String taskRunId) {
        Map<String, Object> location = null;
        try {
            location = jdbc.queryForMap("""
                    select workspace_type, workspace_key, source_kind,
                           lineage_parent_task_run_id, lineage_mode
                    from task_run_locations where task_run_id = ?
                    """, taskRunId);
        } catch (Exception ignored) { /* run may not exist yet */ }

        RunSummary summary = new RunSummary(taskRunId, NormalizedRunStatus.UNAVAILABLE,
                0, 0, 0, null, null, RunSummaryCompleteness.UNAVAILABLE,
                location != null ? text(location.get("workspace_type")) : "agent",
                location != null ? text(location.get("workspace_key")) : "unknown",
                location != null ? text(location.get("source_kind")) : "task",
                location != null ? text(location.get("lineage_parent_task_run_id")) : null,
                location != null ? parseLineageModeStr(text(location.get("lineage_mode"))) : null,
                Instant.now(), Instant.now());
        upsertSummary(summary);
        return summary;
    }

    private void upsertSummary(RunSummary summary) {
        jdbc.update("""
                insert into run_summaries (
                    task_run_id, normalized_status, attempt_count, model_call_count,
                    tool_call_count, wall_elapsed_ms, active_elapsed_ms, completeness,
                    workspace_type, workspace_key, source_kind,
                    lineage_parent_task_run_id, lineage_mode,
                    created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(task_run_id) do update set
                    normalized_status = excluded.normalized_status,
                    attempt_count = excluded.attempt_count,
                    model_call_count = excluded.model_call_count,
                    tool_call_count = excluded.tool_call_count,
                    wall_elapsed_ms = excluded.wall_elapsed_ms,
                    active_elapsed_ms = excluded.active_elapsed_ms,
                    completeness = excluded.completeness,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    source_kind = excluded.source_kind,
                    lineage_parent_task_run_id = excluded.lineage_parent_task_run_id,
                    lineage_mode = excluded.lineage_mode,
                    updated_at = excluded.updated_at
                """,
                summary.taskRunId(), summary.normalizedStatus().name(),
                summary.attemptCount(), summary.modelCallCount(), summary.toolCallCount(),
                summary.wallElapsedMs(), summary.activeElapsedMs(),
                summary.completeness().name(),
                summary.workspaceType(), summary.workspaceKey(), summary.sourceKind(),
                summary.lineageParentTaskRunId(), summary.lineageMode(),
                summary.createdAt().toString(), summary.updatedAt().toString());
    }

    private ElapsedResult deriveElapsed(String taskRunId, NormalizedRunStatus status) {
        try {
            List<Map<String, Object>> attempts = jdbc.queryForList("""
                    select attempt_number, status, started_at, completed_at, created_at
                    from task_run_attempts where task_run_id = ?
                    order by attempt_number asc
                    """, taskRunId);

            if (attempts.isEmpty()) return null;

            // Wall elapsed: from earliest creation to latest terminal or recovery completion
            Instant wallStart = null;
            Instant wallEnd = null;
            long totalActiveMs = 0;
            boolean anyGap = false;

            for (Map<String, Object> attempt : attempts) {
                Instant created = parse(text(attempt.get("created_at")));
                Instant completed = parse(text(attempt.get("completed_at")));
                Instant started = parse(text(attempt.get("started_at")));
                String attStatus = text(attempt.get("status"));

                if (wallStart == null || (created != null && created.isBefore(wallStart))) {
                    wallStart = created;
                }
                if (completed != null) {
                    if (wallEnd == null || completed.isAfter(wallEnd)) wallEnd = completed;
                } else if (created != null && !"SUPERSEDED".equals(attStatus)) {
                    if (wallEnd == null || created.isAfter(wallEnd)) wallEnd = created;
                }

                // Active elapsed per attempt
                if (started != null && completed != null && started.isBefore(completed)) {
                    totalActiveMs += Duration.between(started, completed).toMillis();
                } else if (started != null && !"SUPERSEDED".equals(attStatus)) {
                    // Non-terminal attempt still running
                    anyGap = true;
                    if (completed == null && status.isTerminal()) anyGap = true;
                }
            }

            if (wallStart == null) return null;
            Long wallMs = wallEnd != null ? Duration.between(wallStart, wallEnd).toMillis() : null;
            Long activeMs = totalActiveMs > 0 ? totalActiveMs : null;

            RunSummaryCompleteness completeness;
            if (!status.isTerminal()) {
                completeness = RunSummaryCompleteness.PARTIAL;
            } else if (wallMs != null) {
                completeness = RunSummaryCompleteness.COMPLETE;
            } else {
                completeness = RunSummaryCompleteness.UNAVAILABLE;
            }
            return new ElapsedResult(wallMs != null ? wallMs.intValue() : null,
                    activeMs != null ? activeMs.intValue() : null, completeness);
        } catch (Exception e) {
            log.warn("Elapsed derivation failed for {}: {}", taskRunId, e.getMessage());
            return null;
        }
    }

    // ── Reads ──

    public Optional<RunSummary> findSummary(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return Optional.empty();
        return jdbc.query("select * from run_summaries where task_run_id = ?",
                rs -> rs.next() ? Optional.of(summaryFromRow(rs)) : Optional.empty(), taskRunId);
    }

    public List<RunSummary> findSummariesByWorkspace(String workspaceType, String workspaceKey,
                                                      String after, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        if (StringUtils.hasText(after)) {
            return jdbc.query("""
                    select * from run_summaries
                    where workspace_type = ? and workspace_key = ?
                      and updated_at < ?
                    order by updated_at desc limit ?
                    """, (rs, rowNum) -> summaryFromRow(rs),
                    workspaceType, workspaceKey, after, boundedLimit);
        }
        return jdbc.query("""
                select * from run_summaries
                where workspace_type = ? and workspace_key = ?
                order by updated_at desc limit ?
                """, (rs, rowNum) -> summaryFromRow(rs),
                workspaceType, workspaceKey, boundedLimit);
    }

    // ── Health Aggregates ──

    /**
     * Compute bounded health for a workspace. Active and unavailable runs
     * are reported separately and excluded from terminal denominators.
     */
    public RunHealth computeHealth(String workspaceType, String workspaceKey) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select normalized_status, count(*) as cnt
                from run_summaries
                where workspace_type = ? and workspace_key = ?
                group by normalized_status
                """, workspaceType, workspaceKey);

        Map<NormalizedRunStatus, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            NormalizedRunStatus status = NormalizedRunStatus.valueOf(text(row.get("normalized_status")));
            Long count = ((Number) row.get("cnt")).longValue();
            counts.put(status, count);
        }

        long activeCount = counts.getOrDefault(NormalizedRunStatus.ACTIVE, 0L);
        long unavailableCount = counts.getOrDefault(NormalizedRunStatus.UNAVAILABLE, 0L);
        long legacyCount = 0; // no specific legacy counter yet

        // Terminal denominator: succeeded + failed + cancelled + timed_out + interrupted + partial + abandoned
        long terminalDenominator = 0;
        long terminalNumerator = 0;
        Map<NormalizedRunStatus, Long> terminalBreakdown = new LinkedHashMap<>();

        for (NormalizedRunStatus s : NormalizedRunStatus.terminalValues()) {
            long c = counts.getOrDefault(s, 0L);
            terminalBreakdown.put(s, c);
            terminalDenominator += c;
            if (s == NormalizedRunStatus.SUCCEEDED) terminalNumerator += c;
        }

        // Median / P95 elapsed from terminal runs
        List<Integer> wallSamples = sampleWallElapsed(workspaceType, workspaceKey);
        Integer medianWallMs = percentile(wallSamples, 0.5);
        Integer p95WallMs = percentile(wallSamples, 0.95);

        return new RunHealth(terminalNumerator, terminalDenominator,
                activeCount, unavailableCount, legacyCount,
                terminalBreakdown, medianWallMs, p95WallMs,
                wallSamples.size());
    }

    private List<Integer> sampleWallElapsed(String workspaceType, String workspaceKey) {
        return jdbc.queryForList("""
                select wall_elapsed_ms from run_summaries
                where workspace_type = ? and workspace_key = ?
                  and normalized_status not in ('ACTIVE', 'UNAVAILABLE')
                  and wall_elapsed_ms is not null
                order by wall_elapsed_ms asc
                limit ?
                """, Integer.class, workspaceType, workspaceKey, MAX_HISTORY);
    }

    private static Integer percentile(List<Integer> sorted, double pct) {
        if (sorted.isEmpty()) return null;
        int idx = (int) Math.ceil(pct * sorted.size()) - 1;
        return sorted.get(Math.min(Math.max(idx, 0), sorted.size() - 1));
    }

    // ── Audit Composition ──

    /**
     * Compose a bounded per-run audit read model from authorized workspace
     * selection, canonical run location identity, attempts and fenced claims,
     * exact activated skill snapshot/trust identifiers, artifact publication
     * and producer links, measurements, and schedule/reaction causation.
     *
     * <p>Audit joins only through persisted exact identifiers. It never infers
     * skill-to-output attribution from temporal proximity or shared run
     * membership alone.
     */
    public Optional<RunAudit> composeAudit(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return Optional.empty();

        RunSummary summary = findSummary(taskRunId).orElse(null);
        if (summary == null) return Optional.empty();

        // Attempts
        List<AttemptFact> attempts = attemptFacts(taskRunId);

        // Measurements
        List<RunMeasurementRepository.RunMeasurement> measurements =
                measurementRepo.findByTaskRun(taskRunId);

        // Skill activations for this task run
        List<SkillActivationFact> skillActivations = skillActivationFacts(taskRunId);

        // Artifact publication links
        List<ArtifactFact> artifactFacts = artifactFacts(taskRunId);

        // Schedule/reaction causation
        List<CausationFact> causationFacts = causationFacts(taskRunId);

        return Optional.of(new RunAudit(taskRunId, summary, attempts, measurements,
                skillActivations, artifactFacts, causationFacts));
    }

    private List<AttemptFact> attemptFacts(String taskRunId) {
        try {
            return jdbc.query("""
                    select attempt_number, status, started_at, completed_at, error_text
                    from task_run_attempts where task_run_id = ?
                    order by attempt_number asc
                    """, (rs, rowNum) -> new AttemptFact(
                            rs.getInt("attempt_number"),
                            rs.getString("status"),
                            parseInstant(rs.getString("started_at")),
                            parseInstant(rs.getString("completed_at")),
                            rs.getString("error_text")),
                    taskRunId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<SkillActivationFact> skillActivationFacts(String taskRunId) {
        try {
            return jdbc.query("""
                    select a.id, a.snapshot_id, a.logical_key, a.activation_context,
                           a.activated_at, s.snapshot_digest,
                           t.decision, t.operation_key as trust_operation_key
                    from skill_activations a
                    join skill_snapshots s on s.id = a.snapshot_id
                    left join skill_trust_decisions t on t.snapshot_id = a.snapshot_id
                        and t.decision = 'APPROVE'
                    where a.task_run_id = ?
                    order by a.activated_at asc
                    """, (rs, rowNum) -> new SkillActivationFact(
                            rs.getLong("id"),
                            rs.getLong("snapshot_id"),
                            rs.getString("logical_key"),
                            rs.getString("activation_context"),
                            parseInstant(rs.getString("activated_at")),
                            rs.getString("snapshot_digest"),
                            rs.getString("decision"),
                            rs.getString("trust_operation_key")),
                    taskRunId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ArtifactFact> artifactFacts(String taskRunId) {
        try {
            return jdbc.query("""
                    select artifact_id, logical_name, publication_state,
                           content_sha256, attempt_number, published_at,
                           workspace_kind, workspace_key
                    from output_publications
                    where task_run_id = ? and publication_origin = 'TASK_RUN'
                    order by published_at asc, artifact_id asc
                    """, (rs, rowNum) -> new ArtifactFact(
                            rs.getString("artifact_id"),
                            rs.getString("logical_name"),
                            rs.getString("publication_state"),
                            rs.getString("content_sha256"),
                            boxedIntNullable(rs, "attempt_number"),
                            parseInstant(rs.getString("published_at")),
                            rs.getString("workspace_kind"),
                            rs.getString("workspace_key")),
                    taskRunId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<CausationFact> causationFacts(String taskRunId) {
        List<CausationFact> facts = new ArrayList<>();
        try {
            // Check schedule firings via assignment links
            List<Map<String, Object>> scheduleLinks = jdbc.queryForList("""
                    select sf.id as firing_id, sf.schedule_id, sf.created_at as fired_at
                    from schedule_firings sf
                    join task_run_native_links l on l.assignment_id = sf.assignment_id
                    where l.task_run_id = ?
                    """, taskRunId);
            for (Map<String, Object> row : scheduleLinks) {
                facts.add(new CausationFact("SCHEDULE",
                        text(row.get("schedule_id")),
                        text(row.get("firing_id")),
                        parseInstant(text(row.get("fired_at"))),
                        null));
            }
        } catch (Exception ignored) { /* table may not exist yet */ }

        try {
            // Check event/reaction links
            List<Map<String, Object>> eventLinks = jdbc.queryForList("""
                    select e.id as event_id, e.event_type, e.created_at as event_at,
                           r.id as reaction_id
                    from orchestration_events e
                    join agent_event_reactions r on r.event_type = e.event_type
                    join task_run_native_links l on l.assignment_id is not null
                    where l.task_run_id = ?
                    """, taskRunId);
            for (Map<String, Object> row : eventLinks) {
                facts.add(new CausationFact("REACTION",
                        text(row.get("reaction_id")),
                        text(row.get("event_id")),
                        parseInstant(text(row.get("event_at"))),
                        text(row.get("event_type"))));
            }
        } catch (Exception ignored) { /* table may not exist yet */ }

        return facts;
    }

    // ── Utils ──

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static Instant parse(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private static Instant parseInstant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private static Integer boxedIntNullable(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int v = rs.getInt(column);
        return rs.wasNull() ? null : v;
    }

    private static RunLineageMode parseLineageMode(String value) {
        return StringUtils.hasText(value) ? RunLineageMode.valueOf(value) : null;
    }

    private static String parseLineageModeStr(String value) {
        RunLineageMode mode = parseLineageMode(value);
        return mode != null ? mode.name() : null;
    }

    private RunSummary summaryFromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RunSummary(
                rs.getString("task_run_id"),
                NormalizedRunStatus.valueOf(rs.getString("normalized_status")),
                rs.getInt("attempt_count"),
                rs.getInt("model_call_count"),
                rs.getInt("tool_call_count"),
                boxedIntNullable(rs, "wall_elapsed_ms"),
                boxedIntNullable(rs, "active_elapsed_ms"),
                RunSummaryCompleteness.valueOf(rs.getString("completeness")),
                rs.getString("workspace_type"),
                rs.getString("workspace_key"),
                rs.getString("source_kind"),
                rs.getString("lineage_parent_task_run_id"),
                parseLineageModeStr(rs.getString("lineage_mode")),
                parseInstant(rs.getString("created_at")),
                parseInstant(rs.getString("updated_at")));
    }

    // ── Value Types ──

    public enum NormalizedRunStatus {
        SUCCEEDED, FAILED, CANCELLED, TIMED_OUT,
        INTERRUPTED, PARTIAL, ABANDONED, UNAVAILABLE,
        ACTIVE;

        public boolean isTerminal() {
            return this != ACTIVE && this != UNAVAILABLE;
        }

        public static List<NormalizedRunStatus> terminalValues() {
            return List.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT,
                    INTERRUPTED, PARTIAL, ABANDONED);
        }
    }

    public enum RunSummaryCompleteness { COMPLETE, PARTIAL, UNAVAILABLE }

    public record RunSummary(
            String taskRunId,
            NormalizedRunStatus normalizedStatus,
            int attemptCount,
            int modelCallCount,
            int toolCallCount,
            Integer wallElapsedMs,
            Integer activeElapsedMs,
            RunSummaryCompleteness completeness,
            String workspaceType,
            String workspaceKey,
            String sourceKind,
            String lineageParentTaskRunId,
            String lineageMode,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record RunHealth(
            long succeededCount,
            long terminalDenominator,
            long activeCount,
            long unavailableCount,
            long legacyCount,
            Map<NormalizedRunStatus, Long> terminalBreakdown,
            Integer medianWallMs,
            Integer p95WallMs,
            int sampleCount
    ) {}

    public record RunAudit(
            String taskRunId,
            RunSummary summary,
            List<AttemptFact> attempts,
            List<RunMeasurementRepository.RunMeasurement> measurements,
            List<SkillActivationFact> skillActivations,
            List<ArtifactFact> artifactFacts,
            List<CausationFact> causationFacts
    ) {}

    public record AttemptFact(
            int attemptNumber,
            String status,
            Instant startedAt,
            Instant completedAt,
            String errorText
    ) {}

    public record SkillActivationFact(
            long activationId,
            long snapshotId,
            String logicalKey,
            String activationContext,
            Instant activatedAt,
            String snapshotDigest,
            String trustDecision,
            String trustOperationKey
    ) {}

    public record ArtifactFact(
            String artifactId,
            String logicalName,
            String publicationState,
            String contentSha256,
            Integer attemptNumber,
            Instant publishedAt,
            String workspaceKind,
            String workspaceKey
    ) {}

    public record CausationFact(
            String causationKind, // SCHEDULE or REACTION
            String sourceId,
            String firingOrEventId,
            Instant firedAt,
            String eventType
    ) {}

    private record ElapsedResult(Integer wallElapsedMs, Integer activeElapsedMs,
                                  RunSummaryCompleteness completeness) {}
}
