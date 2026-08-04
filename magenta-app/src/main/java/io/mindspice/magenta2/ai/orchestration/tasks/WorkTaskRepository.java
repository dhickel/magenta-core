package io.mindspice.magenta2.ai.orchestration.tasks;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns persistence for {@link WorkTaskDefinition} and {@link WorkTaskAttempt}.
 * Reads are bounded and deterministic; writes are transactional.
 */
@Repository
public class WorkTaskRepository {

    private final JdbcTemplate jdbcTemplate;

    public WorkTaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ── WorkTaskDefinition ──

    public Optional<WorkTaskDefinition> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, title, description, project_id, pin, default_priority,
                       preferred_agent_id, promoted_plan_id, designated_attempt_id,
                       definition_version, created_at, updated_at
                from work_task_definitions
                where id = ?
                """,
            rs -> rs.next() ? Optional.of(definitionFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<WorkTaskDefinition> findAll() {
        return jdbcTemplate.query(
            """
                select id, title, description, project_id, pin, default_priority,
                       preferred_agent_id, promoted_plan_id, designated_attempt_id,
                       definition_version, created_at, updated_at
                from work_task_definitions
                order by updated_at desc, title asc
                limit 100
                """,
            (rs, rowNum) -> definitionFromRow(rs)
        );
    }

    public List<WorkTaskDefinition> findByProject(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
                select id, title, description, project_id, pin, default_priority,
                       preferred_agent_id, promoted_plan_id, designated_attempt_id,
                       definition_version, created_at, updated_at
                from work_task_definitions
                where project_id = ?
                order by pin desc, updated_at desc, title asc
                limit 100
                """,
            (rs, rowNum) -> definitionFromRow(rs),
            projectId
        );
    }

    public Optional<WorkTaskDefinition> findByPromotedPlanId(String planId) {
        if (!StringUtils.hasText(planId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select id, title, description, project_id, pin, default_priority,
                       preferred_agent_id, promoted_plan_id, designated_attempt_id,
                       definition_version, created_at, updated_at
                from work_task_definitions
                where promoted_plan_id = ?
                """,
            rs -> rs.next() ? Optional.of(definitionFromRow(rs)) : Optional.empty(),
            planId
        );
    }

    @Transactional
    public WorkTaskDefinition save(WorkTaskDefinition task) {
        Instant now = Instant.now();
        Instant createdAt = task.createdAt() == null ? now : task.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into work_task_definitions (
                    id, title, description, project_id, pin, default_priority,
                    preferred_agent_id, promoted_plan_id, designated_attempt_id,
                    definition_version, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
            task.id(), task.title(), task.description(),
            task.projectId(),
            task.pin() ? 1 : 0,
            task.defaultPriority(),
            task.preferredAgentId(),
            task.promotedPlanId(),
            task.designatedAttemptId(),
            task.definitionVersion(),
            createdAt.toString(), updatedAt.toString()
        );
        return findById(task.id()).orElseThrow();
    }

    @Transactional
    public int updateProjectIdIfNull(String taskId, String projectId) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(projectId)) {
            return 0;
        }
        return jdbcTemplate.update(
            """
                update work_task_definitions
                set project_id = ?, updated_at = ?
                where id = ? and project_id is null
                """,
            projectId, Instant.now().toString(), taskId
        );
    }

    @Transactional
    public int setPromotedPlanId(String taskId, String planId, int expectedVersion) {
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(planId)) {
            return 0;
        }
        // The unique index on promoted_plan_id enforces one-to-one
        return jdbcTemplate.update(
            """
                update work_task_definitions
                set promoted_plan_id = ?,
                    definition_version = definition_version + 1,
                    updated_at = ?
                where id = ? and promoted_plan_id is null
                  and definition_version = ?
                """,
            planId, Instant.now().toString(), taskId, expectedVersion
        );
    }

    @Transactional
    public int casUpdateFields(String taskId, int expectedVersion,
                                String title, String description,
                                Integer defaultPriority, Boolean pin,
                                String preferredAgentId) {
        if (!StringUtils.hasText(taskId)) {
            return 0;
        }
        Instant now = Instant.now();
        return jdbcTemplate.update(
            """
                update work_task_definitions
                set title = coalesce(?, title),
                    description = coalesce(?, description),
                    default_priority = coalesce(?, default_priority),
                    pin = coalesce(?, pin),
                    preferred_agent_id = coalesce(?, preferred_agent_id),
                    definition_version = definition_version + 1,
                    updated_at = ?
                where id = ? and definition_version = ?
                """,
            title, description,
            defaultPriority,
            pin != null ? (pin ? 1 : 0) : null,
            preferredAgentId,
            now.toString(), taskId, expectedVersion
        );
    }

    @Transactional
    public void delete(String id) {
        if (StringUtils.hasText(id)) {
            jdbcTemplate.update("delete from work_task_operations where task_id = ?", id);
            jdbcTemplate.update("delete from work_task_attempts where task_id = ?", id);
            jdbcTemplate.update("delete from work_task_definitions where id = ?", id);
        }
    }

    public boolean exists(String id) {
        if (!StringUtils.hasText(id)) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from work_task_definitions where id = ?",
            Integer.class, id
        );
        return count != null && count > 0;
    }

    public int nextAttemptOrder(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return 1;
        }
        Integer max = jdbcTemplate.queryForObject(
            "select coalesce(max(attempt_order), 0) from work_task_attempts where task_id = ?",
            Integer.class, taskId
        );
        return (max == null ? 0 : max) + 1;
    }

    // ── WorkTaskAttempt ──

    /** Atomically appends an exact attempt and designates it for display. */
    @Transactional
    public WorkTaskAttempt appendAndDesignateAttempt(WorkTaskAttempt attempt) {
        Instant createdAt = attempt.createdAt() == null ? Instant.now() : attempt.createdAt();
        jdbcTemplate.update(
            """
                insert into work_task_attempts (
                    id, task_id, assignment_id, attempt_order,
                    predecessor_attempt_id, created_at
                ) values (?, ?, ?, ?, ?, ?)
                """,
            attempt.id(), attempt.taskId(), attempt.assignmentId(), attempt.attemptOrder(),
            attempt.predecessorAttemptId(), createdAt.toString()
        );
        int updated = jdbcTemplate.update(
            """
                update work_task_definitions
                set designated_attempt_id = ?, updated_at = ?
                where id = ?
                """,
            attempt.id(), Instant.now().toString(), attempt.taskId()
        );
        if (updated != 1) {
            throw new IllegalStateException("Task disappeared while appending attempt");
        }
        return attempt;
    }

    public Optional<WorkTaskAttempt> findAttemptById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from work_task_attempts where id = ?",
            rs -> rs.next() ? Optional.of(attemptFromRow(rs)) : Optional.empty(),
            id
        );
    }

    public List<WorkTaskAttempt> findAttemptsByTask(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
                select * from work_task_attempts
                where task_id = ?
                order by attempt_order desc
                """,
            (rs, rowNum) -> attemptFromRow(rs),
            taskId
        );
    }

    public Optional<WorkTaskAttempt> findLatestAttempt(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select wta.* from work_task_definitions wtd
                join work_task_attempts wta on wta.id = wtd.designated_attempt_id
                where wtd.id = ?
                """,
            rs -> rs.next() ? Optional.of(attemptFromRow(rs)) : Optional.empty(), taskId
        );
    }

    /**
     * Find the designated (latest) attempt with a non-terminal assignment.
     * Returns empty if all attempts' assignments are terminal or none exist.
     */
    public Optional<WorkTaskAttempt> findActiveAttempt(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select wta.* from work_task_attempts wta
                join work_assignments wa on wa.id = wta.assignment_id
                where wta.task_id = ?
                  and wa.status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW')
                order by wta.attempt_order desc
                limit 1
                """,
            rs -> rs.next() ? Optional.of(attemptFromRow(rs)) : Optional.empty(),
            taskId
        );
    }

    /**
     * Count non-terminal assignments for a task (should be 0 or 1).
     */
    public int countActiveAttempts(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
                select count(*) from work_task_attempts wta
                join work_assignments wa on wa.id = wta.assignment_id
                where wta.task_id = ?
                  and wa.status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW')
                """,
            Integer.class, taskId
        );
        return count == null ? 0 : count;
    }

    // ── WorkTaskOperations ──

    @Transactional
    public boolean reserveOperation(String operationType, String operationKey,
                                     String requestDigest) {
        return jdbcTemplate.update(
            """
                insert into work_task_operations (
                    operation_type, operation_key, request_digest,
                    operation_state, created_at
                )
                values (?, ?, ?, 'RESERVED', ?)
                on conflict(operation_type, operation_key) do nothing
                """,
            operationType, operationKey, requestDigest, Instant.now().toString()
        ) > 0;
    }

    @Transactional
    public int completeOperation(String operationType, String operationKey,
                                  String requestDigest, String taskId, String resultJson) {
        return jdbcTemplate.update(
            """
                update work_task_operations
                set operation_state = 'COMPLETED',
                    task_id = ?, result_json = ?
                where operation_type = ? and operation_key = ?
                  and operation_state = 'RESERVED'
                  and request_digest = ?
                """,
            taskId, resultJson, operationType, operationKey, requestDigest
        );
    }

    public Optional<String> findCompletedTaskId(String operationType, String operationKey,
                                                  String requestDigest) {
        return jdbcTemplate.query(
            """
                select task_id from work_task_operations
                where operation_type = ? and operation_key = ?
                  and operation_state = 'COMPLETED'
                  and request_digest = ?
                """,
            rs -> rs.next()
                ? Optional.ofNullable(rs.getString("task_id"))
                : Optional.empty(),
            operationType, operationKey, requestDigest
        );
    }

    /**
     * Completed operation lookup returning both task_id and result_json.
     * Returns empty if no matching completed operation exists.
     */
    public Optional<CompletedOperation> findCompletedOperation(String operationType,
            String operationKey, String requestDigest) {
        if (!StringUtils.hasText(operationType) || !StringUtils.hasText(operationKey)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select task_id, result_json from work_task_operations
                where operation_type = ? and operation_key = ?
                  and operation_state = 'COMPLETED'
                  and request_digest = ?
                """,
            rs -> rs.next()
                ? Optional.of(new CompletedOperation(
                    rs.getString("task_id"), rs.getString("result_json")))
                : Optional.empty(),
            operationType, operationKey, requestDigest
        );
    }

    /**
     * Check if any operation (RESERVED or COMPLETED) exists for this key.
     * Returns the stored digest if found.
     */
    public Optional<String> findOperationDigest(String operationType, String operationKey) {
        if (!StringUtils.hasText(operationType) || !StringUtils.hasText(operationKey)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            """
                select request_digest from work_task_operations
                where operation_type = ? and operation_key = ?
                """,
            rs -> rs.next()
                ? Optional.of(rs.getString("request_digest"))
                : Optional.empty(),
            operationType, operationKey
        );
    }

    /** Completed operation result. */
    public record CompletedOperation(String taskId, String resultJson) {}

    // ── Row mapping ──

    private WorkTaskDefinition definitionFromRow(ResultSet rs) throws SQLException {
        return new WorkTaskDefinition(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("project_id"),
            rs.getInt("pin") == 1,
            rs.getInt("default_priority"),
            rs.getString("preferred_agent_id"),
            rs.getString("promoted_plan_id"),
            rs.getString("designated_attempt_id"),
            rs.getInt("definition_version"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private WorkTaskAttempt attemptFromRow(ResultSet rs) throws SQLException {
        return new WorkTaskAttempt(
            rs.getString("id"),
            rs.getString("task_id"),
            rs.getString("assignment_id"),
            rs.getInt("attempt_order"),
            rs.getString("predecessor_attempt_id"),
            Instant.parse(rs.getString("created_at"))
        );
    }
}
