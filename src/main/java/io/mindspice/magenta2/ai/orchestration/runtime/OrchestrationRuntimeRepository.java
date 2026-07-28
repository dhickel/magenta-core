package io.mindspice.magenta2.ai.orchestration.runtime;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Repository
public class OrchestrationRuntimeRepository {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OrchestrationRuntimeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper.findAndRegisterModules();
        ensureSchema();
    }

    public OrchestrationJob saveJob(OrchestrationJob job) {
        Instant now = Instant.now();
        Instant createdAt = job.createdAt() == null ? now : job.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into orchestration_jobs (
                    id, owner_agent_id, title, summary, default_model,
                    workspace_type, workspace_key, workspace_identity, status, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    owner_agent_id = excluded.owner_agent_id,
                    title = excluded.title,
                    summary = excluded.summary,
                    default_model = excluded.default_model,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    status = excluded.status,
                    updated_at = excluded.updated_at
                """,
            job.id(), job.ownerAgentId(), job.title(), job.summary(), job.defaultModel(),
            job.workspaceType(), job.workspaceKey(), job.workspaceIdentity(), job.status().name(),
            createdAt.toString(), updatedAt.toString()
        );
        return findJob(job.id()).orElseThrow();
    }

    public Optional<OrchestrationJob> findJob(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from orchestration_jobs where id = ?",
            rs -> rs.next() ? Optional.of(toJob(rs)) : Optional.empty(),
            id
        );
    }

    public List<OrchestrationJob> findJobsForAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from orchestration_jobs where owner_agent_id = ? order by updated_at desc",
            (rs, rowNum) -> toJob(rs),
            agentId
        );
    }

    public OrchestrationJobItem saveJobItem(OrchestrationJobItem item) {
        Instant now = Instant.now();
        Instant createdAt = item.createdAt() == null ? now : item.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into orchestration_job_items (
                    id, job_id, item_order, item_type, task_id, workflow_id, model_override, priority,
                    retry_count, continue_on_failure, config_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    item_order = excluded.item_order,
                    item_type = excluded.item_type,
                    task_id = excluded.task_id,
                    workflow_id = excluded.workflow_id,
                    model_override = excluded.model_override,
                    priority = excluded.priority,
                    retry_count = excluded.retry_count,
                    continue_on_failure = excluded.continue_on_failure,
                    config_json = excluded.config_json,
                    updated_at = excluded.updated_at
                """,
            item.id(), item.jobId(), item.itemOrder(), item.itemType().name(), item.taskId(), item.workflowId(),
            item.modelOverride(), item.priority(), item.retryCount(), item.continueOnFailure() ? 1 : 0,
            jsonOrNull(item.config()), createdAt.toString(), updatedAt.toString()
        );
        return findJobItem(item.id()).orElseThrow();
    }

    public Optional<OrchestrationJobItem> findJobItem(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from orchestration_job_items where id = ?",
            rs -> rs.next() ? Optional.of(toJobItem(rs)) : Optional.empty(),
            id
        );
    }

    public List<OrchestrationJobItem> findJobItems(String jobId) {
        return jdbcTemplate.query(
            "select * from orchestration_job_items where job_id = ? order by item_order asc, created_at asc",
            (rs, rowNum) -> toJobItem(rs),
            jobId
        );
    }

    WorkAssignment saveAssignment(WorkAssignment assignment) {
        Instant now = Instant.now();
        Instant createdAt = assignment.createdAt() == null ? now : assignment.createdAt();
        Instant updatedAt = now;
        Optional<WorkAssignment> existing = findAssignment(assignment.id());
        Instant lastProgressAt = progressTimestamp(existing.orElse(null), assignment, now, createdAt);
        Instant lastHeartbeatAt = assignment.lastHeartbeatAt() != null
            ? assignment.lastHeartbeatAt()
            : existing.map(WorkAssignment::lastHeartbeatAt).orElse(null);
        jdbcTemplate.update(
            """
                insert into work_assignments (
                    id, agent_id, job_id, job_item_id, assignment_type, priority, status, model_override,
                    run_display_name, workspace_type, workspace_key, workspace_identity, project_id,
                    current_item_index, checkpoint_json, input_json, output_json, evidence_json,
                    error_text, lease_owner, lease_expires_at, lease_fence,
                    last_progress_at, last_heartbeat_at,
                    created_at, updated_at, started_at, completed_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    agent_id = excluded.agent_id,
                    job_id = excluded.job_id,
                    job_item_id = excluded.job_item_id,
                    assignment_type = excluded.assignment_type,
                    priority = excluded.priority,
                    status = excluded.status,
                    model_override = excluded.model_override,
                    run_display_name = excluded.run_display_name,
                    workspace_type = excluded.workspace_type,
                    workspace_key = excluded.workspace_key,
                    workspace_identity = excluded.workspace_identity,
                    project_id = excluded.project_id,
                    current_item_index = excluded.current_item_index,
                    checkpoint_json = excluded.checkpoint_json,
                    input_json = excluded.input_json,
                    output_json = excluded.output_json,
                    evidence_json = excluded.evidence_json,
                    error_text = excluded.error_text,
                    lease_owner = excluded.lease_owner,
                    lease_expires_at = excluded.lease_expires_at,
                    last_progress_at = excluded.last_progress_at,
                    last_heartbeat_at = excluded.last_heartbeat_at,
                    updated_at = excluded.updated_at,
                    started_at = excluded.started_at,
                    completed_at = excluded.completed_at
                """,
            assignment.id(), assignment.agentId(), assignment.jobId(), assignment.jobItemId(),
            assignment.assignmentType().name(), assignment.priority(), assignment.status().name(),
            assignment.modelOverride(), assignment.runDisplayName(),
            assignment.workspaceType(), assignment.workspaceKey(), assignment.workspaceIdentity(),
            assignment.projectId(), assignment.currentItemIndex(),
            jsonOrNull(assignment.checkpoint()), jsonOrNull(assignment.input()), jsonOrNull(assignment.output()),
            jsonOrNull(assignment.evidence()), assignment.errorText(), assignment.leaseOwner(),
            instant(assignment.leaseExpiresAt()), assignment.leaseFence(),
            instant(lastProgressAt), instant(lastHeartbeatAt),
            createdAt.toString(), updatedAt.toString(),
            instant(assignment.startedAt()), instant(assignment.completedAt())
        );
        return findAssignment(assignment.id()).orElseThrow();
    }

    Optional<WorkAssignment> saveAssignmentIfLeaseOwner(WorkAssignment assignment, String leaseOwner) {
        if (!StringUtils.hasText(assignment.id()) || !StringUtils.hasText(leaseOwner)) {
            return Optional.empty();
        }
        Optional<WorkAssignment> current = findAssignment(assignment.id());
        if (current.isEmpty()) return Optional.empty();
        Instant now = Instant.now();
        Instant lastProgressAt = progressTimestamp(current.get(), assignment, now, current.get().createdAt());
        Instant lastHeartbeatAt = assignment.lastHeartbeatAt() != null
            ? assignment.lastHeartbeatAt()
            : current.get().lastHeartbeatAt();
        int updated = jdbcTemplate.update(
            """
                update work_assignments set
                    agent_id = ?,
                    job_id = ?,
                    job_item_id = ?,
                    assignment_type = ?,
                    priority = ?,
                    status = ?,
                    model_override = ?,
                    run_display_name = ?,
                    workspace_type = ?,
                    workspace_key = ?,
                    workspace_identity = ?,
                    project_id = ?,
                    current_item_index = ?,
                    checkpoint_json = ?,
                    input_json = ?,
                    output_json = ?,
                    evidence_json = ?,
                    error_text = ?,
                    lease_owner = ?,
                    lease_expires_at = ?,
                    last_progress_at = ?,
                    last_heartbeat_at = ?,
                    updated_at = ?,
                    started_at = ?,
                    completed_at = ?
                where id = ? and status in (?, ?) and lease_owner = ? and lease_fence = ?
                """,
            assignment.agentId(), assignment.jobId(), assignment.jobItemId(), assignment.assignmentType().name(),
            assignment.priority(), assignment.status().name(), assignment.modelOverride(), assignment.runDisplayName(),
            assignment.workspaceType(), assignment.workspaceKey(), assignment.workspaceIdentity(),
            assignment.projectId(), assignment.currentItemIndex(), jsonOrNull(assignment.checkpoint()),
            jsonOrNull(assignment.input()), jsonOrNull(assignment.output()), jsonOrNull(assignment.evidence()),
            assignment.errorText(), assignment.leaseOwner(), instant(assignment.leaseExpiresAt()),
            instant(lastProgressAt), instant(lastHeartbeatAt), now.toString(), instant(assignment.startedAt()),
            instant(assignment.completedAt()), assignment.id(), OrchestrationStatus.RUNNING.name(),
            OrchestrationStatus.CANCEL_REQUESTED.name(), leaseOwner, assignment.leaseFence()
        );
        return updated == 1 ? findAssignment(assignment.id()) : Optional.empty();
    }

    public Optional<WorkAssignment> findAssignment(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from work_assignments where id = ?",
            rs -> rs.next() ? Optional.of(toAssignment(rs)) : Optional.empty(),
            id
        );
    }

    public List<WorkAssignment> findAssignmentsForAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from work_assignments where agent_id = ? order by created_at desc",
            (rs, rowNum) -> toAssignment(rs),
            agentId
        );
    }

    public List<WorkAssignment> findQueueAssignmentsForAgent(String agentId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where agent_id = ?
                  and status not in (?, ?, ?, ?)
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            agentId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public List<WorkAssignment> findTerminalAssignmentsForAgent(String agentId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where agent_id = ?
                  and status in (?, ?, ?, ?)
                order by coalesce(completed_at, updated_at, created_at) desc
                """,
            (rs, rowNum) -> toAssignment(rs),
            agentId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public boolean deleteAssignment(String agentId, String assignmentId) {
        jdbcTemplate.update(
            """
                delete from assignment_conversation_links
                where assignment_id in (
                    select id from work_assignments where id = ? and agent_id = ?
                )
                """,
            assignmentId,
            agentId
        );
        int deleted = jdbcTemplate.update(
            "delete from work_assignments where id = ? and agent_id = ?",
            assignmentId,
            agentId
        );
        return deleted == 1;
    }

    @Transactional
    public int purgeTerminalAssignmentHistory(String agentId, Instant cutoff) {
        List<String> assignmentIds = jdbcTemplate.queryForList(
            """
                select id from work_assignments
                where agent_id = ?
                  and status in (?, ?, ?, ?)
                  and coalesce(completed_at, updated_at, created_at) < ?
                """,
            String.class,
            agentId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name(),
            cutoff.toString()
        );
        for (String assignmentId : assignmentIds) {
            jdbcTemplate.update("delete from assignment_conversation_links where assignment_id = ?", assignmentId);
        }
        if (assignmentIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String assignmentId : assignmentIds) {
            deleted += jdbcTemplate.update(
                "delete from work_assignments where id = ? and agent_id = ?",
                assignmentId,
                agentId
            );
        }
        return deleted;
    }

    @Transactional
    public int purgeTerminalAssignmentHistory(Instant cutoff) {
        List<String> assignmentIds = jdbcTemplate.queryForList(
            """
                select id from work_assignments
                where status in (?, ?, ?, ?)
                  and coalesce(completed_at, updated_at, created_at) < ?
                """,
            String.class,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name(),
            cutoff.toString()
        );
        for (String assignmentId : assignmentIds) {
            jdbcTemplate.update("delete from assignment_conversation_links where assignment_id = ?", assignmentId);
        }
        if (assignmentIds.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (String assignmentId : assignmentIds) {
            deleted += jdbcTemplate.update("delete from work_assignments where id = ?", assignmentId);
        }
        return deleted;
    }

    public List<WorkAssignment> findAssignmentsForJob(String jobId) {
        return jdbcTemplate.query(
            "select * from work_assignments where job_id = ? order by created_at desc",
            (rs, rowNum) -> toAssignment(rs),
            jobId
        );
    }

    public List<WorkAssignment> findActiveAssignmentsForJob(String jobId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where job_id = ?
                  and status not in (?, ?, ?, ?)
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            jobId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public List<WorkAssignment> findActiveAssignmentsForProject(String projectId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where project_id = ?
                  and status not in (?, ?, ?, ?)
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            projectId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public List<WorkAssignment> findActiveAssignmentsForProjectAndAgent(String projectId, String agentId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where project_id = ?
                  and agent_id = ?
                  and status not in (?, ?, ?, ?)
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            projectId,
            agentId,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public List<WorkAssignment> findActiveAssignmentsForWorkspaceIdentity(String workspaceIdentity) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where workspace_identity = ?
                  and status not in (?, ?, ?, ?)
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            workspaceIdentity,
            OrchestrationStatus.CANCELLED.name(),
            OrchestrationStatus.FAILED.name(),
            OrchestrationStatus.COMPLETED.name(),
            OrchestrationStatus.NEEDS_REVIEW.name()
        );
    }

    public long countActiveAssignmentsForJob(String jobId) {
        return findActiveAssignmentsForJob(jobId).size();
    }

    public long countActiveAssignmentsForProject(String projectId) {
        return findActiveAssignmentsForProject(projectId).size();
    }

    public long countActiveAssignmentsForProjectAndAgent(String projectId, String agentId) {
        return findActiveAssignmentsForProjectAndAgent(projectId, agentId).size();
    }

    public long countActiveAssignmentsForWorkspaceIdentity(String workspaceIdentity) {
        return findActiveAssignmentsForWorkspaceIdentity(workspaceIdentity).size();
    }

    public List<WorkAssignment> findQueuedAssignments(int limit) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where status = ?
                order by priority desc, created_at asc
                limit ?
                """,
            (rs, rowNum) -> toAssignment(rs),
            OrchestrationStatus.QUEUED.name(),
            limit
        );
    }

    public List<WorkAssignment> findRecoverableAssignments(int limit) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where status in (?, ?)
                order by priority desc, created_at asc
                limit ?
                """,
            (rs, rowNum) -> toAssignment(rs),
            OrchestrationStatus.QUEUED.name(),
            OrchestrationStatus.INTERRUPTED.name(),
            limit
        );
    }

    public List<WorkAssignment> findWaitingAssignmentsForAgent(String agentId) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where agent_id = ? and status = ?
                order by priority desc, created_at asc
                """,
            (rs, rowNum) -> toAssignment(rs),
            agentId,
            OrchestrationStatus.WAITING.name()
        );
    }

    public List<WorkAssignment> findWaitingAssignments(int limit) {
        return jdbcTemplate.query(
            """
                select * from work_assignments
                where status = ?
                order by priority desc, created_at asc
                limit ?
                """,
            (rs, rowNum) -> toAssignment(rs),
            OrchestrationStatus.WAITING.name(),
            limit
        );
    }

    public int markInterruptedAsQueued(String agentId) {
        return jdbcTemplate.update(
            """
                update work_assignments set status = ?, updated_at = ?
                where status = ? and agent_id = ?
                """,
            OrchestrationStatus.QUEUED.name(), Instant.now().toString(),
            OrchestrationStatus.INTERRUPTED.name(), agentId
        );
    }

    @Transactional
    Optional<WorkAssignment> acquireLease(String assignmentId, String leaseOwner, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = ?, lease_expires_at = ?,
                    started_at = coalesce(started_at, ?),
                    last_progress_at = coalesce(last_progress_at, ?),
                    last_heartbeat_at = ?,
                    updated_at = ?
                where id = ? and status in (?, ?) and (lease_expires_at is null or lease_expires_at <= ?)
                """,
            OrchestrationStatus.RUNNING.name(), leaseOwner, leaseExpiresAt.toString(),
            now.toString(), now.toString(), now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.QUEUED.name(), OrchestrationStatus.INTERRUPTED.name(),
            now.toString()
        );
        return updated == 0 ? Optional.empty() : findAssignment(assignmentId);
    }

    int markStaleRunningLeases(Instant now) {
        return jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null, last_progress_at = ?, updated_at = ?
                where status = ? and lease_expires_at is not null and lease_expires_at <= ?
                """,
            OrchestrationStatus.INTERRUPTED.name(), now.toString(), now.toString(),
            OrchestrationStatus.RUNNING.name(), now.toString()
        );
    }

    int markStaleCancelRequestedLeases(Instant now) {
        return jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null,
                    error_text = coalesce(error_text, ?), completed_at = ?, last_progress_at = ?, updated_at = ?
                where status = ? and (lease_expires_at is null or lease_expires_at <= ?)
                """,
            OrchestrationStatus.CANCELLED.name(), "Cancelled after stale cancel request",
            now.toString(), now.toString(), now.toString(),
            OrchestrationStatus.CANCEL_REQUESTED.name(), now.toString()
        );
    }

    Optional<WorkAssignment> requestCancel(String assignmentId) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, error_text = coalesce(error_text, ?), last_progress_at = ?, updated_at = ?
                where id = ? and status = ?
                """,
            OrchestrationStatus.CANCEL_REQUESTED.name(), "Cancel requested",
            now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.RUNNING.name()
        );
        return updated == 1 ? findAssignment(assignmentId) : Optional.empty();
    }

    public void saveAssignmentConversationLink(String assignmentId, String conversationId) {
        if (!StringUtils.hasText(assignmentId) || !StringUtils.hasText(conversationId)) {
            return;
        }
        jdbcTemplate.update(
            """
                insert or ignore into assignment_conversation_links (
                    assignment_id, conversation_id, created_at
                )
                values (?, ?, ?)
                """,
            assignmentId, conversationId, Instant.now().toString()
        );
    }

    public List<String> findAssignmentConversationIds(String assignmentId) {
        if (!StringUtils.hasText(assignmentId)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
            """
                select conversation_id
                from assignment_conversation_links
                where assignment_id = ?
                order by created_at asc, conversation_id asc
                """,
            String.class,
            assignmentId
        );
    }

    public List<String> findLegacyTaskConversationIds(String taskId, Instant windowStart, Instant windowEnd) {
        if (!StringUtils.hasText(taskId) || !tableExists("plan_runs") || !tableExists("ai_chat_session_metadata")) {
            return List.of();
        }
        Instant start = windowStart == null ? Instant.EPOCH : windowStart.minusSeconds(60);
        Instant end = windowEnd == null ? Instant.now().plusSeconds(60) : windowEnd.plusSeconds(300);
        return jdbcTemplate.queryForList(
            """
                select distinct m.conversation_id
                from plan_runs r
                join ai_chat_session_metadata m on m.active_task_run_id = r.id
                where r.plan_id = ?
                  and coalesce(m.updated_at, r.created_at) >= ?
                  and coalesce(m.updated_at, r.created_at) <= ?
                order by coalesce(m.updated_at, r.created_at) asc, m.conversation_id asc
                """,
            String.class,
            taskId,
            start.toString(),
            end.toString()
        );
    }

    boolean revertToQueued(String assignmentId, String leaseOwner) {
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null, updated_at = ?
                where id = ? and status = ? and lease_owner = ?
                """,
            OrchestrationStatus.QUEUED.name(), Instant.now().toString(),
            assignmentId, OrchestrationStatus.RUNNING.name(), leaseOwner
        );
        return updated == 1;
    }

    int extendRunningLease(String assignmentId, String leaseOwner, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
            """
                update work_assignments
                set lease_expires_at = ?, last_heartbeat_at = ?, updated_at = ?
                where id = ? and status = ? and lease_owner = ?
                """,
            leaseExpiresAt.toString(), now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.RUNNING.name(), leaseOwner
        );
    }

    public boolean forceInterruptAssignmentFenced(String assignmentId, String expectedOwner,
                                                   long expectedFence, String reason) {
        if (!StringUtils.hasText(expectedOwner) || expectedFence < 1) return false;
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null,
                    error_text = ?, last_progress_at = ?, updated_at = ?
                where id = ? and lease_owner = ? and lease_fence = ? and status in (?, ?)
                """,
            OrchestrationStatus.INTERRUPTED.name(), reason, now.toString(), now.toString(),
            assignmentId, expectedOwner, expectedFence,
            OrchestrationStatus.RUNNING.name(), OrchestrationStatus.CANCEL_REQUESTED.name()
        );
        return updated == 1;
    }

    public boolean isAssignmentLeaseCurrent(String assignmentId, String owner, long fence) {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from work_assignments
                where id = ? and lease_owner = ? and lease_fence = ?
                  and status in (?, ?)
                """, Integer.class, assignmentId, owner, fence,
                OrchestrationStatus.RUNNING.name(), OrchestrationStatus.CANCEL_REQUESTED.name());
        return count != null && count == 1;
    }

    public InboxMessage saveInboxMessage(InboxMessage message) {
        Instant now = Instant.now();
        Instant createdAt = message.createdAt() == null ? now : message.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into agent_inbox_messages (
                    id, to_agent_id, from_id, message_type, body, metadata_json, read_flag,
                    handled_flag, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    read_flag = excluded.read_flag,
                    handled_flag = excluded.handled_flag,
                    updated_at = excluded.updated_at
                """,
            message.id(), message.toAgentId(), message.fromId(), message.messageType(), message.body(),
            jsonOrNull(message.metadata()), message.read() ? 1 : 0, message.handled() ? 1 : 0,
            createdAt.toString(), updatedAt.toString()
        );
        return findInboxMessage(message.id()).orElseThrow();
    }

    public Optional<InboxMessage> findInboxMessage(String id) {
        return jdbcTemplate.query(
            "select * from agent_inbox_messages where id = ?",
            rs -> rs.next() ? Optional.of(toInboxMessage(rs)) : Optional.empty(),
            id
        );
    }

    public List<InboxMessage> findInboxMessages(String agentId) {
        return jdbcTemplate.query(
            "select * from agent_inbox_messages where to_agent_id = ? order by created_at desc",
            (rs, rowNum) -> toInboxMessage(rs),
            agentId
        );
    }

    public AgentSchedule saveSchedule(AgentSchedule schedule) {
        Instant now = Instant.now();
        Instant createdAt = schedule.createdAt() == null ? now : schedule.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into agent_schedules (
                    id, agent_id, job_id, assignment_template_json, cron_expression, timezone,
                    enabled_flag, next_run_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    agent_id = excluded.agent_id,
                    job_id = excluded.job_id,
                    assignment_template_json = excluded.assignment_template_json,
                    cron_expression = excluded.cron_expression,
                    timezone = excluded.timezone,
                    enabled_flag = excluded.enabled_flag,
                    next_run_at = excluded.next_run_at,
                    updated_at = excluded.updated_at
                """,
            schedule.id(), schedule.agentId(), schedule.jobId(), jsonOrNull(schedule.assignmentTemplate()),
            schedule.cronExpression(), schedule.timezone(), schedule.enabled() ? 1 : 0,
            instant(schedule.nextRunAt()), createdAt.toString(), updatedAt.toString()
        );
        return findSchedule(schedule.id()).orElseThrow();
    }

    public Optional<AgentSchedule> findSchedule(String id) {
        return jdbcTemplate.query(
            "select * from agent_schedules where id = ?",
            rs -> rs.next() ? Optional.of(toSchedule(rs)) : Optional.empty(),
            id
        );
    }

    public List<AgentSchedule> findSchedulesForAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from agent_schedules where agent_id = ? order by created_at desc",
            (rs, rowNum) -> toSchedule(rs),
            agentId
        );
    }

    public List<AgentSchedule> findDueSchedules(Instant now) {
        return jdbcTemplate.query(
            "select * from agent_schedules where enabled_flag = 1 and next_run_at is not null and next_run_at <= ? order by next_run_at asc",
            (rs, rowNum) -> toSchedule(rs),
            now.toString()
        );
    }

    @Transactional
    public boolean deleteScheduleForAgent(String agentId, String scheduleId) {
        jdbcTemplate.update(
            """
                delete from schedule_firings
                where schedule_id in (
                    select id from agent_schedules where id = ? and agent_id = ?
                )
                """,
            scheduleId, agentId
        );
        int deleted = jdbcTemplate.update(
            "delete from agent_schedules where id = ? and agent_id = ?",
            scheduleId, agentId
        );
        return deleted == 1;
    }

    public boolean createScheduleFiring(String id, String scheduleId, Instant dueAt, String assignmentId) {
        Instant now = Instant.now();
        int inserted = jdbcTemplate.update(
            """
                insert or ignore into schedule_firings (
                    id, schedule_id, due_at, assignment_id, created_at
                )
                values (?, ?, ?, ?, ?)
                """,
            id, scheduleId, dueAt.toString(), assignmentId, now.toString()
        );
        return inserted == 1;
    }

    public AgentEventReaction saveReaction(AgentEventReaction reaction) {
        Instant now = Instant.now();
        Instant createdAt = reaction.createdAt() == null ? now : reaction.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into agent_event_reactions (
                    id, agent_id, event_type, filter_json, action_type, assignment_template_json,
                    enabled_flag, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    agent_id = excluded.agent_id,
                    event_type = excluded.event_type,
                    filter_json = excluded.filter_json,
                    action_type = excluded.action_type,
                    assignment_template_json = excluded.assignment_template_json,
                    enabled_flag = excluded.enabled_flag,
                    updated_at = excluded.updated_at
                """,
            reaction.id(), reaction.agentId(), reaction.eventType().name(), jsonOrNull(reaction.filter()),
            reaction.actionType().name(), jsonOrNull(reaction.assignmentTemplate()), reaction.enabled() ? 1 : 0,
            createdAt.toString(), updatedAt.toString()
        );
        return findReaction(reaction.id()).orElseThrow();
    }

    public Optional<AgentEventReaction> findReaction(String id) {
        return jdbcTemplate.query(
            "select * from agent_event_reactions where id = ?",
            rs -> rs.next() ? Optional.of(toReaction(rs)) : Optional.empty(),
            id
        );
    }

    public List<AgentEventReaction> findReactionsForAgent(String agentId) {
        return jdbcTemplate.query(
            "select * from agent_event_reactions where agent_id = ? order by created_at desc",
            (rs, rowNum) -> toReaction(rs),
            agentId
        );
    }

    public List<AgentEventReaction> findEnabledReactions(EventType eventType) {
        return jdbcTemplate.query(
            "select * from agent_event_reactions where enabled_flag = 1 and event_type = ? order by created_at asc",
            (rs, rowNum) -> toReaction(rs),
            eventType.name()
        );
    }

    public boolean deleteReactionForAgent(String agentId, String reactionId) {
        int deleted = jdbcTemplate.update(
            "delete from agent_event_reactions where id = ? and agent_id = ?",
            reactionId, agentId
        );
        return deleted == 1;
    }

    public OrchestrationEvent saveEvent(OrchestrationEvent event) {
        Instant createdAt = event.createdAt() == null ? Instant.now() : event.createdAt();
        jdbcTemplate.update(
            """
                insert into orchestration_events (
                    id, event_type, source_type, source_id, payload_json, created_at, handled_at
                )
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    handled_at = excluded.handled_at
                """,
            event.id(), event.eventType().name(), event.sourceType(), event.sourceId(), jsonOrNull(event.payload()),
            createdAt.toString(), instant(event.handledAt())
        );
        return findEvent(event.id()).orElseThrow();
    }

    public Optional<OrchestrationEvent> findEvent(String id) {
        return jdbcTemplate.query(
            "select * from orchestration_events where id = ?",
            rs -> rs.next() ? Optional.of(toEvent(rs)) : Optional.empty(),
            id
        );
    }

    public List<OrchestrationEvent> findEventsForSource(String sourceType, String sourceId) {
        return jdbcTemplate.query(
            "select * from orchestration_events where source_type = ? and source_id = ? order by created_at desc",
            (rs, rowNum) -> toEvent(rs),
            sourceType,
            sourceId
        );
    }

    @Transactional
    public void purgeAgentOwnedReferences(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return;
        }
        jdbcTemplate.update(
            "delete from schedule_firings where schedule_id in (select id from agent_schedules where agent_id = ?)",
            agentId
        );
        jdbcTemplate.update("delete from agent_schedules where agent_id = ?", agentId);
        jdbcTemplate.update("delete from agent_event_reactions where agent_id = ?", agentId);
        jdbcTemplate.update(
            "delete from agent_inbox_messages where to_agent_id = ? or from_id = ?",
            agentId,
            agentId
        );
        jdbcTemplate.update(
            "delete from orchestration_events where source_type = ? and source_id = ?",
            "agent",
            agentId
        );
        jdbcTemplate.update(
            "delete from work_assignments where job_id in (select id from orchestration_jobs where owner_agent_id = ?)",
            agentId
        );
        jdbcTemplate.update("delete from work_assignments where agent_id = ?", agentId);
        jdbcTemplate.update(
            "delete from orchestration_job_items where job_id in (select id from orchestration_jobs where owner_agent_id = ?)",
            agentId
        );
        jdbcTemplate.update("delete from orchestration_jobs where owner_agent_id = ?", agentId);
    }

    private OrchestrationJob toJob(ResultSet rs) throws SQLException {
        return new OrchestrationJob(
            rs.getString("id"), rs.getString("owner_agent_id"), rs.getString("title"), rs.getString("summary"),
            rs.getString("default_model"), selectionType(rs), selectionKey(rs), selectionIdentity(rs),
            OrchestrationStatus.valueOf(rs.getString("status")), instantValue(rs.getString("created_at")),
            instantValue(rs.getString("updated_at"))
        );
    }

    private OrchestrationJobItem toJobItem(ResultSet rs) throws SQLException {
        return new OrchestrationJobItem(
            rs.getString("id"), rs.getString("job_id"), rs.getInt("item_order"),
            AssignmentType.valueOf(rs.getString("item_type")), rs.getString("task_id"), rs.getString("workflow_id"),
            rs.getString("model_override"), rs.getInt("priority"), rs.getInt("retry_count"),
            rs.getInt("continue_on_failure") == 1, map(rs.getString("config_json")),
            instantValue(rs.getString("created_at")), instantValue(rs.getString("updated_at"))
        );
    }

    private WorkAssignment toAssignment(ResultSet rs) throws SQLException {
        return new WorkAssignment(
            rs.getString("id"), rs.getString("agent_id"), rs.getString("job_id"), rs.getString("job_item_id"),
            AssignmentType.valueOf(rs.getString("assignment_type")), rs.getString("run_display_name"), rs.getInt("priority"),
            OrchestrationStatus.valueOf(rs.getString("status")), rs.getString("model_override"),
            getNullable(rs, "workspace_type"), getNullable(rs, "workspace_key"), getNullable(rs, "workspace_identity"),
            rs.getString("project_id"), rs.getInt("current_item_index"), map(rs.getString("checkpoint_json")),
            map(rs.getString("input_json")), map(rs.getString("output_json")), map(rs.getString("evidence_json")),
            rs.getString("error_text"), rs.getString("lease_owner"), instantValue(rs.getString("lease_expires_at")),
            readLeaseFence(rs),
            instantValue(rs.getString("created_at")), instantValue(rs.getString("updated_at")),
            instantValue(rs.getString("started_at")), instantValue(rs.getString("completed_at")),
            instantValue(rs.getString("last_progress_at")), instantValue(rs.getString("last_heartbeat_at"))
        );
    }

    private long readLeaseFence(ResultSet rs) {
        try {
            return rs.getInt("lease_fence");
        } catch (SQLException e) {
            return 0L;
        }
    }

    private String getNullable(ResultSet rs, String column) throws SQLException {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private InboxMessage toInboxMessage(ResultSet rs) throws SQLException {
        return new InboxMessage(
            rs.getString("id"), rs.getString("to_agent_id"), rs.getString("from_id"),
            rs.getString("message_type"), rs.getString("body"), map(rs.getString("metadata_json")),
            rs.getInt("read_flag") == 1, rs.getInt("handled_flag") == 1,
            instantValue(rs.getString("created_at")), instantValue(rs.getString("updated_at"))
        );
    }

    private AgentSchedule toSchedule(ResultSet rs) throws SQLException {
        return new AgentSchedule(
            rs.getString("id"), rs.getString("agent_id"), rs.getString("job_id"),
            map(rs.getString("assignment_template_json")), rs.getString("cron_expression"), rs.getString("timezone"),
            rs.getInt("enabled_flag") == 1, instantValue(rs.getString("next_run_at")),
            instantValue(rs.getString("created_at")), instantValue(rs.getString("updated_at"))
        );
    }

    private AgentEventReaction toReaction(ResultSet rs) throws SQLException {
        return new AgentEventReaction(
            rs.getString("id"), rs.getString("agent_id"), EventType.valueOf(rs.getString("event_type")),
            map(rs.getString("filter_json")), ReactionActionType.valueOf(rs.getString("action_type")),
            map(rs.getString("assignment_template_json")), rs.getInt("enabled_flag") == 1,
            instantValue(rs.getString("created_at")), instantValue(rs.getString("updated_at"))
        );
    }

    private OrchestrationEvent toEvent(ResultSet rs) throws SQLException {
        return new OrchestrationEvent(
            rs.getString("id"), EventType.valueOf(rs.getString("event_type")), rs.getString("source_type"),
            rs.getString("source_id"), map(rs.getString("payload_json")), instantValue(rs.getString("created_at")),
            instantValue(rs.getString("handled_at"))
        );
    }

    private Map<String, Object> map(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse orchestration JSON", exception);
        }
    }

    private String jsonOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map && map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize orchestration JSON", exception);
        }
    }

    private String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private Instant instantValue(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private String selectionType(ResultSet rs) throws SQLException {
        String type = getNullable(rs, "workspace_type");
        String key = getNullable(rs, "workspace_key");
        if (StringUtils.hasText(type) && StringUtils.hasText(key)) return type;
        return StringUtils.hasText(getNullable(rs, "owner_agent_id")) ? "agent" : null;
    }

    private String selectionKey(ResultSet rs) throws SQLException {
        String type = getNullable(rs, "workspace_type");
        String key = getNullable(rs, "workspace_key");
        if (StringUtils.hasText(type) && StringUtils.hasText(key)) return key;
        return getNullable(rs, "owner_agent_id");
    }

    private String selectionIdentity(ResultSet rs) throws SQLException {
        String type = selectionType(rs);
        String key = selectionKey(rs);
        return StringUtils.hasText(type) && StringUtils.hasText(key) ? type + "/" + key : null;
    }

    private void addTextColumnIfMissing(List<String> columns, String table, String column) {
        if (!columns.contains(column)) {
            jdbcTemplate.execute("alter table " + table + " add column " + column + " text");
        }
    }

    private void backfillAssignmentWorkspaceIdentity() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            """
                select id, workspace_type, workspace_key, agent_id
                from work_assignments
                where workspace_identity is null
                """
        );
        for (Map<String, Object> row : rows) {
            String type = row.get("workspace_type") == null ? null : row.get("workspace_type").toString();
            String key = row.get("workspace_key") == null ? null : row.get("workspace_key").toString();
            String agentId = row.get("agent_id") == null ? null : row.get("agent_id").toString();
            String identity = null;
            if (StringUtils.hasText(type) && StringUtils.hasText(key)) {
                identity = type.trim() + "/" + key.trim();
            } else if (StringUtils.hasText(agentId)) {
                identity = "agent/" + agentId.trim();
            }
            if (identity != null) {
                jdbcTemplate.update(
                    "update work_assignments set workspace_identity = ? where id = ? and workspace_identity is null",
                    identity, row.get("id")
                );
            }
        }
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists orchestration_jobs (
                id text primary key,
                owner_agent_id text not null,
                title text not null,
                summary text,
                default_model text,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                status text not null,
                created_at text not null,
                updated_at text not null
            )
            """);
        List<String> jobColumns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('orchestration_jobs')", String.class);
        addTextColumnIfMissing(jobColumns, "orchestration_jobs", "workspace_type");
        addTextColumnIfMissing(jobColumns, "orchestration_jobs", "workspace_key");
        addTextColumnIfMissing(jobColumns, "orchestration_jobs", "workspace_identity");
        jdbcTemplate.execute("""
            create table if not exists orchestration_job_items (
                id text primary key,
                job_id text not null,
                item_order integer not null,
                item_type text not null,
                task_id text,
                workflow_id text,
                model_override text,
                priority integer not null,
                retry_count integer not null default 0,
                continue_on_failure integer not null default 0,
                config_json text,
                created_at text not null,
                updated_at text not null,
                foreign key(job_id) references orchestration_jobs(id)
            )
            """);
        java.util.List<String> jobItemColumns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('orchestration_job_items')",
            String.class
        );
        if (!jobItemColumns.contains("retry_count")) {
            jdbcTemplate.execute("alter table orchestration_job_items add column retry_count integer not null default 0");
        }
        if (!jobItemColumns.contains("continue_on_failure")) {
            jdbcTemplate.execute("alter table orchestration_job_items add column continue_on_failure integer not null default 0");
        }
        jdbcTemplate.execute("""
            create table if not exists work_assignments (
                id text primary key,
                agent_id text not null,
                job_id text,
                job_item_id text,
                assignment_type text not null,
                priority integer not null,
                status text not null,
                model_override text,
                run_display_name text,
                workspace_type text,
                workspace_key text,
                workspace_identity text,
                project_id text,
                current_item_index integer not null,
                checkpoint_json text,
                input_json text,
                output_json text,
                evidence_json text,
                error_text text,
                lease_owner text,
                lease_expires_at text,
                last_progress_at text,
                last_heartbeat_at text,
                created_at text not null,
                updated_at text not null,
                started_at text,
                completed_at text
            )
            """);
        java.util.List<String> assignmentColumns = jdbcTemplate.queryForList(
            "select name from pragma_table_info('work_assignments')",
            String.class
        );
        if (!assignmentColumns.contains("last_progress_at")) {
            jdbcTemplate.execute("alter table work_assignments add column last_progress_at text");
            jdbcTemplate.execute("update work_assignments set last_progress_at = coalesce(started_at, updated_at, created_at)");
        }
        if (!assignmentColumns.contains("last_heartbeat_at")) {
            jdbcTemplate.execute("alter table work_assignments add column last_heartbeat_at text");
            jdbcTemplate.execute("update work_assignments set last_heartbeat_at = updated_at where status = 'RUNNING'");
        }
        if (!assignmentColumns.contains("workspace_type")) {
            jdbcTemplate.execute("alter table work_assignments add column workspace_type text");
        }
        if (!assignmentColumns.contains("workspace_key")) {
            jdbcTemplate.execute("alter table work_assignments add column workspace_key text");
        }
        if (!assignmentColumns.contains("workspace_identity")) {
            jdbcTemplate.execute("alter table work_assignments add column workspace_identity text");
        }
        if (!assignmentColumns.contains("project_id")) {
            jdbcTemplate.execute("alter table work_assignments add column project_id text");
        }
        if (!assignmentColumns.contains("run_display_name")) {
            jdbcTemplate.execute("alter table work_assignments add column run_display_name text");
        }
        // Compatibility for repository-only tests and pre-runner embeddings. Normal
        // application startup receives this column from ordered migration 002.
        if (!assignmentColumns.contains("lease_fence")) {
            jdbcTemplate.execute("alter table work_assignments add column lease_fence integer not null default 0");
        }
        backfillAssignmentWorkspaceIdentity();
        jdbcTemplate.execute("""
            create index if not exists idx_work_assignments_queue
                on work_assignments(status, priority, created_at)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_work_assignments_project_active
                on work_assignments(project_id, status, created_at)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_work_assignments_workspace_identity_active
                on work_assignments(workspace_identity, status, created_at)
            """);
        jdbcTemplate.execute("""
            create table if not exists assignment_conversation_links (
                assignment_id text not null,
                conversation_id text not null,
                created_at text not null,
                primary key (assignment_id, conversation_id)
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_assignment_conversation_links_assignment
                on assignment_conversation_links(assignment_id, created_at)
            """);
        // Runtime-owned direct-line agent inbox table. Workflow inbox messages stay in inbox_messages.
        jdbcTemplate.execute("""
            create table if not exists agent_inbox_messages (
                id text primary key,
                to_agent_id text not null,
                from_id text,
                message_type text not null,
                body text,
                metadata_json text,
                read_flag integer not null,
                handled_flag integer not null,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_agent_inbox_messages_to
                on agent_inbox_messages (to_agent_id, created_at desc)
            """);
        jdbcTemplate.execute("""
            create table if not exists agent_schedules (
                id text primary key,
                agent_id text not null,
                job_id text,
                assignment_template_json text,
                cron_expression text not null,
                timezone text not null,
                enabled_flag integer not null,
                next_run_at text,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists schedule_firings (
                id text primary key,
                schedule_id text not null,
                due_at text not null,
                assignment_id text not null,
                created_at text not null,
                unique(schedule_id, due_at),
                foreign key(schedule_id) references agent_schedules(id)
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists agent_event_reactions (
                id text primary key,
                agent_id text not null,
                event_type text not null,
                filter_json text,
                action_type text not null,
                assignment_template_json text,
                enabled_flag integer not null,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists orchestration_events (
                id text primary key,
                event_type text not null,
                source_type text,
                source_id text,
                payload_json text,
                created_at text not null,
                handled_at text
            )
            """);
    }

    private Instant progressTimestamp(WorkAssignment existing, WorkAssignment assignment, Instant now, Instant createdAt) {
        if (existing == null) {
            return assignment.lastProgressAt() != null ? assignment.lastProgressAt() : createdAt;
        }
        if (progressChanged(existing, assignment)) {
            return now;
        }
        return assignment.lastProgressAt() != null ? assignment.lastProgressAt() : existing.lastProgressAt();
    }

    private boolean progressChanged(WorkAssignment existing, WorkAssignment assignment) {
        return existing.status() != assignment.status()
            || existing.currentItemIndex() != assignment.currentItemIndex()
            || !existing.checkpoint().equals(assignment.checkpoint())
            || !existing.output().equals(assignment.output())
            || !existing.evidence().equals(assignment.evidence())
            || !java.util.Objects.equals(existing.errorText(), assignment.errorText())
            || !java.util.Objects.equals(existing.completedAt(), assignment.completedAt());
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
            Integer.class,
            tableName
        );
        return count != null && count > 0;
    }

    // ── Phase 02: Fenced lease operations ──

    /**
     * Acquire a lease with monotonic fence increment. Returns the updated
     * assignment with the new fence value, or empty if acquisition fails.
     */
    @Transactional
    public Optional<WorkAssignment> acquireLeaseFenced(String assignmentId, String leaseOwner,
                                                        Instant leaseExpiresAt) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = ?, lease_expires_at = ?,
                    lease_fence = lease_fence + 1,
                    started_at = coalesce(started_at, ?),
                    last_progress_at = coalesce(last_progress_at, ?),
                    last_heartbeat_at = ?,
                    updated_at = ?
                where id = ? and status in (?, ?)
                  and (lease_expires_at is null or lease_expires_at <= ?)
                """,
            OrchestrationStatus.RUNNING.name(), leaseOwner, leaseExpiresAt.toString(),
            now.toString(), now.toString(), now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.QUEUED.name(), OrchestrationStatus.INTERRUPTED.name(),
            now.toString()
        );
        if (updated == 0) return Optional.empty();
        WorkAssignment leased = findAssignment(assignmentId).orElse(null);
        if (leased != null) {
            recordFenceGeneration(assignmentId, leased.leaseFence(), leaseOwner, now);
        }
        return Optional.ofNullable(leased);
    }

    /**
     * Heartbeat with fence check. Only succeeds if the expected owner and fence match.
     */
    public int extendRunningLeaseFenced(String assignmentId, String leaseOwner,
                                         long expectedFence, Instant leaseExpiresAt) {
        Instant now = Instant.now();
        return jdbcTemplate.update(
            """
                update work_assignments
                set lease_expires_at = ?, last_heartbeat_at = ?, updated_at = ?
                where id = ? and status = ? and lease_owner = ? and lease_fence = ?
                """,
            leaseExpiresAt.toString(), now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.RUNNING.name(), leaseOwner, expectedFence
        );
    }

    /**
     * Checkpoint with fence check. Stores progress and updates lease.
     */
    public Optional<WorkAssignment> checkpointAssignmentFenced(
            String assignmentId, String leaseOwner, long expectedFence,
            OrchestrationStatus status, int currentItemIndex,
            Map<String, Object> checkpoint, Map<String, Object> output,
            Map<String, Object> evidence, String errorText, Instant completedAt) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, current_item_index = ?,
                    checkpoint_json = ?, output_json = ?, evidence_json = ?,
                    error_text = coalesce(?, error_text),
                    last_progress_at = ?,
                    lease_expires_at = case when ? is not null then ? else lease_expires_at end,
                    updated_at = ?,
                    completed_at = coalesce(?, completed_at)
                where id = ? and lease_owner = ? and lease_fence = ?
                """,
            status.name(), currentItemIndex,
            jsonOrNull(checkpoint), jsonOrNull(output), jsonOrNull(evidence),
            errorText,
            now.toString(),
            now.toString(), now.toString(),
            now.toString(),
            instant(completedAt),
            assignmentId, leaseOwner, expectedFence
        );
        return updated == 1 ? findAssignment(assignmentId) : Optional.empty();
    }

    /**
     * Cancel with fence check. Only succeeds if running/cancel-requested
     * with matching owner and fence.
     */
    public Optional<WorkAssignment> cancelAssignmentFenced(String assignmentId,
                                                            String leaseOwner,
                                                            long expectedFence) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null,
                    completed_at = ?, last_progress_at = ?, updated_at = ?
                where id = ? and lease_owner = ? and lease_fence = ?
                  and status in (?, ?)
                """,
            OrchestrationStatus.CANCELLED.name(),
            now.toString(), now.toString(), now.toString(),
            assignmentId, leaseOwner, expectedFence,
            OrchestrationStatus.RUNNING.name(), OrchestrationStatus.CANCEL_REQUESTED.name()
        );
        return updated == 1 ? findAssignment(assignmentId) : Optional.empty();
    }

    /**
     * Complete with fence check.
     */
    public Optional<WorkAssignment> completeAssignmentFenced(String assignmentId,
                                                              String leaseOwner,
                                                              long expectedFence) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null,
                    completed_at = ?, last_progress_at = ?, updated_at = ?
                where id = ? and lease_owner = ? and lease_fence = ? and status = ?
                """,
            OrchestrationStatus.COMPLETED.name(),
            now.toString(), now.toString(), now.toString(),
            assignmentId, leaseOwner, expectedFence,
            OrchestrationStatus.RUNNING.name()
        );
        return updated == 1 ? findAssignment(assignmentId) : Optional.empty();
    }

    /**
     * Release lease with fence check, returning to QUEUED.
     */
    public boolean releaseAssignmentFenced(String assignmentId, String leaseOwner,
                                            long expectedFence) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = null, lease_expires_at = null, updated_at = ?
                where id = ? and status = ? and lease_owner = ? and lease_fence = ?
                """,
            OrchestrationStatus.QUEUED.name(), now.toString(),
            assignmentId, OrchestrationStatus.RUNNING.name(), leaseOwner, expectedFence
        );
        return updated == 1;
    }

    /**
     * Takeover stale lease by incrementing fence. Only succeeds when the
     * current lease has expired.
     */
    public Optional<WorkAssignment> takeoverStaleLeaseFenced(String assignmentId,
                                                               String newOwner,
                                                               Instant now) {
        int updated = jdbcTemplate.update(
            """
                update work_assignments
                set status = ?, lease_owner = ?, lease_expires_at = null,
                    lease_fence = lease_fence + 1,
                    last_progress_at = ?, updated_at = ?
                where id = ? and status = ?
                  and (lease_expires_at is null or lease_expires_at <= ?)
                """,
            OrchestrationStatus.INTERRUPTED.name(), newOwner,
            now.toString(), now.toString(),
            assignmentId, OrchestrationStatus.RUNNING.name(), now.toString()
        );
        if (updated != 1) return Optional.empty();
        WorkAssignment assignment = findAssignment(assignmentId).orElse(null);
        if (assignment != null) {
            recordFenceGeneration(assignmentId, assignment.leaseFence(), newOwner, now);
        }
        return Optional.ofNullable(assignment);
    }

    /**
     * Check if a lease fence is current.
     */
    public boolean isLeaseFenceCurrent(String assignmentId, String leaseOwner, long fence) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from work_assignments"
                + " where id = ? and lease_owner = ? and lease_fence = ? and status = ?",
            Integer.class,
            assignmentId, leaseOwner, fence, OrchestrationStatus.RUNNING.name()
        );
        return count != null && count == 1;
    }

    private void recordFenceGeneration(String assignmentId, long generation,
                                        String ownerId, Instant claimedAt) {
        jdbcTemplate.update(
            "insert or ignore into assignment_fence_generations"
                + " (assignment_id, generation, owner_id, claimed_at)"
                + " values (?, ?, ?, ?)",
            assignmentId, generation, ownerId, claimedAt.toString()
        );
    }

    /**
     * Find the latest task run ID linked to an assignment.
     */
    public Optional<String> findTaskRunIdForAssignment(String assignmentId) {
        if (!tableExists("task_run_native_links")) return Optional.empty();
        return jdbcTemplate.query(
            "select task_run_id from task_run_native_links"
                + " where assignment_id = ? order by created_at desc limit 1",
            rs -> rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(),
            assignmentId
        );
    }
}
