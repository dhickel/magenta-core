package io.mindspice.magenta2.ai.orchestration.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Persistence for typed model and tool invocation measurements.
 *
 * <p>Each actual invocation is a separate row. Retries never overwrite
 * or flatten earlier calls. Reservation before invocation returns the
 * invocation identity; completion is idempotent and must not mutate
 * immutable correlation fields.
 */
@Repository
public class RunMeasurementRepository {

    private final JdbcTemplate jdbc;

    public RunMeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Reservation ──

    /**
     * Reserve a measurement row before invocation. Returns the invocation ID
     * to use for later completion. If the same invocation ID already exists
     * with matching identity (taskRunId, attempt, kind, callId), returns the
     * existing row; conflicting identity is rejected.
     */
    public RunMeasurement reserve(RunMeasurement measurement) {
        requireValidReservation(measurement);
        Instant now = Instant.now();
        jdbc.update("""
                insert or ignore into run_measurements (
                    invocation_id, task_run_id, attempt_number, kind, call_id,
                    correlation_id, outcome, completeness, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, 'UNAVAILABLE', 'INCOMPLETE', ?, ?)
                """,
                measurement.invocationId(), measurement.taskRunId(),
                measurement.attemptNumber(), measurement.kind().name(),
                measurement.callId(), measurement.correlationId(),
                now.toString(), now.toString());
        return findById(measurement.invocationId())
                .orElseThrow(() -> new IllegalStateException("Failed to reserve measurement"));
    }

    /**
     * Complete a previously reserved measurement idempotently.
     * Immutable correlation fields (taskRunId, attemptNumber, kind, callId,
     * correlationId) must match the existing row. Completion is idempotent.
     */
    public RunMeasurement complete(RunMeasurement measurement) {
        if (!StringUtils.hasText(measurement.invocationId())) {
            throw new IllegalArgumentException("invocationId is required");
        }
        RunMeasurement existing = findById(measurement.invocationId())
                .orElseThrow(() -> new IllegalStateException("No reserved measurement for: "
                        + measurement.invocationId()));
        if (!existing.taskRunId().equals(measurement.taskRunId())
                || existing.attemptNumber() != measurement.attemptNumber()
                || existing.kind() != measurement.kind()
                || !existing.callId().equals(measurement.callId())) {
            throw new IllegalArgumentException("Measurement identity mismatch for invocation "
                    + measurement.invocationId());
        }
        Instant now = Instant.now();
        jdbc.update("""
                update run_measurements set
                    provider = ?, model_name = ?, tool_name = ?,
                    outcome = ?, started_at = ?, completed_at = ?,
                    elapsed_ms = ?, input_token_count = ?, output_token_count = ?,
                    total_token_count = ?, timeout_ms = ?, exit_code = ?,
                    truncation_kind = ?, interruption_kind = ?,
                    rejection_reason = ?, failure_detail = ?,
                    completeness = ?, updated_at = ?
                where invocation_id = ?
                """,
                measurement.provider(), measurement.modelName(), measurement.toolName(),
                measurement.outcome().name(), toString(measurement.startedAt()),
                toString(measurement.completedAt()),
                measurement.elapsedMs(), measurement.inputTokenCount(),
                measurement.outputTokenCount(), measurement.totalTokenCount(),
                measurement.timeoutMs(), measurement.exitCode(),
                measurement.truncationKind(), measurement.interruptionKind(),
                measurement.rejectionReason(), measurement.failureDetail(),
                measurement.completeness().name(), now.toString(),
                measurement.invocationId());
        return findById(measurement.invocationId()).orElseThrow();
    }

    // ── Reads ──

    public Optional<RunMeasurement> findById(String invocationId) {
        if (!StringUtils.hasText(invocationId)) return Optional.empty();
        return jdbc.query("select * from run_measurements where invocation_id = ?",
                rs -> rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(),
                invocationId);
    }

    public List<RunMeasurement> findByTaskRun(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbc.query("""
                select * from run_measurements
                where task_run_id = ?
                order by created_at asc
                """, (rs, rowNum) -> fromRow(rs), taskRunId);
    }

    public List<RunMeasurement> findByTaskRunAndAttempt(String taskRunId, int attemptNumber) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbc.query("""
                select * from run_measurements
                where task_run_id = ? and attempt_number = ?
                order by created_at asc
                """, (rs, rowNum) -> fromRow(rs), taskRunId, attemptNumber);
    }

    public List<RunMeasurement> findByTaskRunAndKind(String taskRunId, MeasurementKind kind) {
        if (!StringUtils.hasText(taskRunId) || kind == null) return List.of();
        return jdbc.query("""
                select * from run_measurements
                where task_run_id = ? and kind = ?
                order by created_at asc
                """, (rs, rowNum) -> fromRow(rs), taskRunId, kind.name());
    }

    public int countByTaskRun(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return 0;
        Integer count = jdbc.queryForObject(
                "select count(*) from run_measurements where task_run_id = ?",
                Integer.class, taskRunId);
        return count == null ? 0 : count;
    }

    public int countByTaskRunAndKind(String taskRunId, MeasurementKind kind) {
        if (!StringUtils.hasText(taskRunId) || kind == null) return 0;
        Integer count = jdbc.queryForObject(
                "select count(*) from run_measurements where task_run_id = ? and kind = ?",
                Integer.class, taskRunId, kind.name());
        return count == null ? 0 : count;
    }

    /**
     * Find measurements for a workspace, bounded by page size and stable cursor.
     */
    public List<RunMeasurement> findByWorkspace(String workspaceType, String workspaceKey,
                                                 MeasurementKind kind, String after,
                                                 int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                select m.* from run_measurements m
                join run_summaries s on s.task_run_id = m.task_run_id
                where s.workspace_type = ? and s.workspace_key = ?
                """);
        params.add(workspaceType);
        params.add(workspaceKey);
        if (kind != null) {
            sql.append(" and m.kind = ?");
            params.add(kind.name());
        }
        List<RunMeasurement> rows = null;
        if (StringUtils.hasText(after)) {
            sql.append(" and m.created_at > ? order by m.created_at asc limit ?");
            params.add(after);
            params.add(Math.min(limit, 200));
            rows = jdbc.query(sql.toString(), (rs, rowNum) -> fromRow(rs), params.toArray());
        }

        if (rows == null || rows.isEmpty()) {
            // fresh scan even when after cursor matched nothing
            StringBuilder fresh = new StringBuilder("""
                select m.* from run_measurements m
                join run_summaries s on s.task_run_id = m.task_run_id
                where s.workspace_type = ? and s.workspace_key = ?
                """);
            List<Object> freshParams = new ArrayList<>();
            freshParams.add(workspaceType);
            freshParams.add(workspaceKey);
            if (kind != null) {
                fresh.append(" and m.kind = ?");
                freshParams.add(kind.name());
            }
            fresh.append(" order by m.created_at asc limit ?");
            freshParams.add(Math.min(limit, 200));
            return jdbc.query(fresh.toString(), (rs, rowNum) -> fromRow(rs), freshParams.toArray());
        }
        return rows;
    }

    // ── Aggregation ──

    public List<Integer> elapsedSamples(String taskRunId, MeasurementKind kind,
                                         MeasurementOutcome outcome) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbc.queryForList("""
                select elapsed_ms from run_measurements
                where task_run_id = ? and kind = ? and outcome = ?
                  and elapsed_ms is not null
                order by elapsed_ms asc
                """, Integer.class, taskRunId, kind.name(), outcome.name());
    }

    public List<MeasurementOutcomeCount> outcomeCountsByKind(String taskRunId) {
        if (!StringUtils.hasText(taskRunId)) return List.of();
        return jdbc.query("""
                select kind, outcome, count(*) as cnt
                from run_measurements where task_run_id = ?
                group by kind, outcome order by kind, outcome
                """, (rs, rowNum) -> new MeasurementOutcomeCount(
                        MeasurementKind.valueOf(rs.getString("kind")),
                        MeasurementOutcome.valueOf(rs.getString("outcome")),
                        rs.getInt("cnt")),
                taskRunId);
    }

    // ── Mapping ──

    private RunMeasurement fromRow(ResultSet rs) throws SQLException {
        return new RunMeasurement(
                rs.getString("invocation_id"),
                rs.getString("task_run_id"),
                rs.getInt("attempt_number"),
                MeasurementKind.valueOf(rs.getString("kind")),
                rs.getString("call_id"),
                rs.getString("correlation_id"),
                rs.getString("provider"),
                rs.getString("model_name"),
                rs.getString("tool_name"),
                MeasurementOutcome.valueOf(rs.getString("outcome")),
                instant(rs.getString("started_at")),
                instant(rs.getString("completed_at")),
                boxedInt(rs, "elapsed_ms"),
                boxedInt(rs, "input_token_count"),
                boxedInt(rs, "output_token_count"),
                boxedInt(rs, "total_token_count"),
                boxedInt(rs, "timeout_ms"),
                boxedInt(rs, "exit_code"),
                rs.getString("truncation_kind"),
                rs.getString("interruption_kind"),
                rs.getString("rejection_reason"),
                rs.getString("failure_detail"),
                MeasurementCompleteness.valueOf(rs.getString("completeness")),
                instant(rs.getString("created_at")),
                instant(rs.getString("updated_at"))
        );
    }

    private static Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private static String toString(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Integer boxedInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void requireValidReservation(RunMeasurement m) {
        if (!StringUtils.hasText(m.invocationId()))
            throw new IllegalArgumentException("invocationId is required");
        if (!StringUtils.hasText(m.taskRunId()))
            throw new IllegalArgumentException("taskRunId is required");
        if (m.attemptNumber() < 1)
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        if (m.kind() == null)
            throw new IllegalArgumentException("kind is required");
        if (!StringUtils.hasText(m.callId()))
            throw new IllegalArgumentException("callId is required");
    }

    // ── Value types ──

    public enum MeasurementKind { MODEL, TOOL }

    public enum MeasurementOutcome {
        SUCCEEDED, FAILED, TIMED_OUT, REJECTED,
        INTERRUPTED, TRUNCATED, DISABLED, UNAVAILABLE
    }

    public enum MeasurementCompleteness { COMPLETE, INCOMPLETE, UNAVAILABLE }

    public record MeasurementOutcomeCount(MeasurementKind kind,
                                          MeasurementOutcome outcome,
                                          int count) {}

    public record RunMeasurement(
            String invocationId,
            String taskRunId,
            int attemptNumber,
            MeasurementKind kind,
            String callId,
            String correlationId,
            String provider,
            String modelName,
            String toolName,
            MeasurementOutcome outcome,
            Instant startedAt,
            Instant completedAt,
            Integer elapsedMs,
            Integer inputTokenCount,
            Integer outputTokenCount,
            Integer totalTokenCount,
            Integer timeoutMs,
            Integer exitCode,
            String truncationKind,
            String interruptionKind,
            String rejectionReason,
            String failureDetail,
            MeasurementCompleteness completeness,
            Instant createdAt,
            Instant updatedAt
    ) {
        /** Minimal reservation shape: identity fields only. */
        public static RunMeasurement reserve(
                String invocationId, String taskRunId, int attemptNumber,
                MeasurementKind kind, String callId, String correlationId) {
            return new RunMeasurement(invocationId, taskRunId, attemptNumber, kind,
                    callId, correlationId, null, null, null,
                    MeasurementOutcome.UNAVAILABLE, null, null,
                    null, null, null, null, null, null,
                    null, null, null, null,
                    MeasurementCompleteness.INCOMPLETE, null, null);
        }

        /** Completion shape that preserves immutable identity. */
        public RunMeasurement complete(MeasurementOutcome outcome,
                                        MeasurementCompleteness completeness,
                                        Instant startedAt, Instant completedAt,
                                        Integer elapsedMs, String provider,
                                        String modelName, String toolName,
                                        Integer inputTokenCount, Integer outputTokenCount,
                                        Integer totalTokenCount,
                                        Integer timeoutMs, Integer exitCode,
                                        String truncationKind, String interruptionKind,
                                        String rejectionReason, String failureDetail) {
            return new RunMeasurement(invocationId, taskRunId, attemptNumber, kind,
                    callId, correlationId, provider, modelName, toolName,
                    outcome != null ? outcome : MeasurementOutcome.UNAVAILABLE,
                    startedAt, completedAt, elapsedMs,
                    inputTokenCount, outputTokenCount, totalTokenCount,
                    timeoutMs, exitCode, truncationKind, interruptionKind,
                    rejectionReason, failureDetail,
                    completeness != null ? completeness : MeasurementCompleteness.INCOMPLETE,
                    createdAt, Instant.now());
        }
    }
}
