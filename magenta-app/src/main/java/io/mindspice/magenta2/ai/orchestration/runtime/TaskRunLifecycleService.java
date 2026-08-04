package io.mindspice.magenta2.ai.orchestration.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskIdentity;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskPathKey;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunWriteCapability;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Owns idempotent task-run reservations, attempts, lineage, and writer fences. */
@Service
public class TaskRunLifecycleService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final AgentContextService agentContextService;

    public TaskRunLifecycleService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                   WorkspaceDirectoryService workspaceDirectoryService) {
        this(jdbc, objectMapper, workspaceDirectoryService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunLifecycleService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                   WorkspaceDirectoryService workspaceDirectoryService,
                                   @org.springframework.beans.factory.annotation.Autowired(required = false)
                                   AgentContextService agentContextService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper.copy().findAndRegisterModules();
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.agentContextService = agentContextService;
    }

    @Transactional
    public LifecycleResult reserve(LifecycleReservationRequest request) {
        if (request.workspaceKind() == WorkspaceKind.CHAT) {
            throw new IllegalArgumentException("CHAT workspaces are not task-selectable");
        }
        String digest = canonicalDigest(request);
        LifecycleResult existing = beginOperation(RunOperationType.RESERVE, request.operationKey(), digest);
        if (existing != null) return existing;

        Instant now = Instant.now();
        TaskIdentity identity = new TaskIdentity(request.sourceKind(), request.sourceId());
        TaskPathKey pathKey = TaskPathKey.from(identity);
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(
                request.workspaceKind(), request.workspaceKey());
        TaskRunLocation location = workspaceDirectoryService.resolveTaskRunForRead(
                descriptor, identity, pathKey, request.taskRunId());

        jdbc.update("""
                insert into task_run_locations(
                    task_run_id, source_kind, source_id, workspace_type, workspace_key,
                    workspace_identity, task_path_key, root_relative_path, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.taskRunId(), request.sourceKind().token(), request.sourceId(),
                request.workspaceKind().token(), request.workspaceKey(), request.workspaceIdentity(),
                pathKey.serialized(), location.rootRelativePath(), now.toString(), now.toString());
        insertAttempt(request.taskRunId(), 1, "RESERVED", now);
        String nativeRunId = StringUtils.hasText(request.nativeRunId())
                ? request.nativeRunId() : request.taskRunId();
        insertNativeLink(request.taskRunId(), request.sourceKind(), nativeRunId,
                request.assignmentId(), request.workflowChildId(), request.jobWorkItemId(), now);

        LifecycleResult result = new LifecycleResult(request.taskRunId(), nativeRunId,
                request.operationKey(), 1, null, null, nativeStatus(request.sourceKind(), nativeRunId));
        completeOperation(RunOperationType.RESERVE, request.operationKey(), request.taskRunId(), result);
        return result;
    }

    @Transactional
    public LifecycleResult resume(LifecycleResumeRequest request) {
        String digest = canonicalDigest(request);
        LifecycleResult existing = beginOperation(RunOperationType.RESUME, request.operationKey(), digest);
        if (existing != null) return existing;

        RunFacts facts = requireRun(request.taskRunId());
        Integer currentClaims = jdbc.queryForObject("""
                select count(*) from writer_claim_generations
                where task_run_id = ? and is_current = 1
                """, Integer.class, request.taskRunId());
        if (currentClaims != null && currentClaims > 0) {
            throw new IllegalStateException("Run still has a current writer: " + request.taskRunId());
        }
        int nextAttempt = currentAttemptNumber(request.taskRunId()) + 1;
        Instant now = Instant.now();
        jdbc.update("""
                update task_run_attempts set status = 'SUPERSEDED', updated_at = ?
                where task_run_id = ? and attempt_number < ?
                  and status in ('RESERVED', 'RUNNING', 'WAITING', 'INTERRUPTED')
                """, now.toString(), request.taskRunId(), nextAttempt);
        insertAttempt(request.taskRunId(), nextAttempt, "RESERVED", now);

        LifecycleResult result = result(request.taskRunId(), request.operationKey(), nextAttempt, facts);
        completeOperation(RunOperationType.RESUME, request.operationKey(), request.taskRunId(), result);
        return result;
    }

    @Transactional
    public LifecycleResult reserveChild(String parentTaskRunId, String linkType, String linkKey,
                                        String operationKey, TaskSourceKind childKind,
                                        String childSourceId, String childNativeRunId,
                                        String inputDigest) {
        if (!"WORKFLOW_CHILD".equals(linkType) && !"JOB_WORK_ITEM".equals(linkType)) {
            throw new IllegalArgumentException("Unsupported child link type: " + linkType);
        }
        RunFacts parent = requireRun(parentTaskRunId);
        LifecycleResult child = reserve(new LifecycleReservationRequest(
            operationKey, childKind, childSourceId, parent.workspaceKind(), parent.workspaceKey(),
            childNativeRunId, childNativeRunId, findAssignmentId(parentTaskRunId),
            null, null, inputDigest));
        linkChildBinding(parentTaskRunId, child.taskRunId(), linkType);
        jdbc.update("""
                insert into task_run_links(parent_task_run_id, child_task_run_id, link_type, link_key, created_at)
                values (?, ?, ?, ?, ?)
                on conflict(parent_task_run_id, link_type, link_key) do nothing
                """, parentTaskRunId, child.taskRunId(), linkType, linkKey, Instant.now().toString());
        String recordedChild = jdbc.queryForObject("""
                select child_task_run_id from task_run_links
                where parent_task_run_id = ? and link_type = ? and link_key = ?
                """, String.class, parentTaskRunId, linkType, linkKey);
        if (!child.taskRunId().equals(recordedChild)) {
            throw new OperationConflictException("Child link key already owns another task run");
        }
        return child;
    }

    /** Copies the parent binding to both the child task-run and its typed lineage owner. */
    private void linkChildBinding(String parentTaskRunId, String childTaskRunId, String linkType) {
        if (agentContextService == null) {
            return; // isolated lifecycle fixtures do not have the Phase 03 context domain.
        }
        var parent = agentContextService.getOwnerLink("TASK_RUN", parentTaskRunId);
        if (parent.outcome() != AgentContextService.ContextOutcome.AVAILABLE || parent.value() == null) {
            throw new IllegalStateException("Parent task run has no immutable binding owner link");
        }
        String bindingKey = parent.value().bindingKey();
        agentContextService.linkOwner("TASK_RUN", childTaskRunId, bindingKey,
            "child-task-run-binding:" + childTaskRunId);
        agentContextService.linkOwner(linkType, childTaskRunId, bindingKey,
            "child-binding:" + linkType + ":" + childTaskRunId);
        var taskLink = agentContextService.getOwnerLink("TASK_RUN", childTaskRunId);
        var typedLink = agentContextService.getOwnerLink(linkType, childTaskRunId);
        if (taskLink.value() == null || typedLink.value() == null
                || !bindingKey.equals(taskLink.value().bindingKey())
                || !bindingKey.equals(typedLink.value().bindingKey())) {
            throw new IllegalStateException("Child binding owner links are incomplete or incompatible");
        }
    }

    @Transactional
    public LifecycleResult retry(LifecycleLineageRequest request) {
        requireMode(request, RunLineageMode.RETRY_OF);
        return reserveLineage(request, RunOperationType.RETRY);
    }

    @Transactional
    public LifecycleResult replay(LifecycleLineageRequest request) {
        requireMode(request, RunLineageMode.REPLAY_OF);
        return reserveLineage(request, RunOperationType.REPLAY);
    }

    private LifecycleResult reserveLineage(LifecycleLineageRequest request, RunOperationType operationType) {
        String digest = canonicalDigest(request);
        LifecycleResult existing = beginOperation(operationType, request.operationKey(), digest);
        if (existing != null) return existing;
        if (request.parentTaskRunId().equals(request.taskRunId())) {
            throw new IllegalArgumentException("A lineage child must have a distinct taskRunId");
        }

        RunFacts parent = requireRun(request.parentTaskRunId());
        Instant now = Instant.now();
        TaskIdentity identity = new TaskIdentity(parent.sourceKind(), parent.sourceId());
        TaskPathKey pathKey = TaskPathKey.from(identity);
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(
                parent.workspaceKind(), parent.workspaceKey());
        TaskRunLocation location = workspaceDirectoryService.resolveTaskRunForRead(
                descriptor, identity, pathKey, request.taskRunId());
        jdbc.update("""
                insert into task_run_locations(
                    task_run_id, source_kind, source_id, workspace_type, workspace_key,
                    workspace_identity, task_path_key, root_relative_path, created_at, updated_at,
                    lineage_parent_task_run_id, lineage_mode)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.taskRunId(), parent.sourceKind().token(), parent.sourceId(),
                parent.workspaceKind().token(), parent.workspaceKey(),
                parent.workspaceKind().token() + "/" + parent.workspaceKey(),
                pathKey.serialized(), location.rootRelativePath(), now.toString(), now.toString(),
                request.parentTaskRunId(), request.lineageMode().name());
        insertAttempt(request.taskRunId(), 1, "RESERVED", now);
        insertNativeLink(request.taskRunId(), parent.sourceKind(), request.nativeRunId(),
                request.assignmentId(), null, null, now);

        LifecycleResult result = new LifecycleResult(request.taskRunId(), request.nativeRunId(),
                request.operationKey(), 1, request.parentTaskRunId(), request.lineageMode(),
                nativeStatus(parent.sourceKind(), request.nativeRunId()));
        completeOperation(operationType, request.operationKey(), request.taskRunId(), result);
        return result;
    }

    @Transactional
    public TaskRunWriteCapability acquireWriterClaim(TaskRunLocation location,
                                                      int attemptNumber,
                                                      String ownerId) {
        requireText(ownerId, "ownerId");
        int currentAttempt = currentAttemptNumber(location.taskRunId());
        if (attemptNumber != currentAttempt) {
            throw new StaleWriterException("Attempt is not current for run " + location.taskRunId());
        }
        Integer current = jdbc.queryForObject("""
                select count(*) from writer_claim_generations
                where task_run_id = ? and attempt_number = ? and is_current = 1
                """, Integer.class, location.taskRunId(), attemptNumber);
        if (current != null && current > 0) {
            throw new IllegalStateException("Attempt already has a current writer");
        }
        Long maxGeneration = jdbc.queryForObject("""
                select coalesce(max(generation), 0) from writer_claim_generations
                where task_run_id = ?
                """, Long.class, location.taskRunId());
        long generation = (maxGeneration == null ? 0 : maxGeneration) + 1;
        String fenceToken = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("""
                insert into writer_claim_generations(
                    task_run_id, attempt_number, generation, owner_id, fence_token,
                    is_current, created_at)
                values (?, ?, ?, ?, ?, 1, ?)
                """, location.taskRunId(), attemptNumber, generation, ownerId,
                fenceToken, now.toString());
        int updated = jdbc.update("""
                update task_run_attempts set status = 'RUNNING', started_at = coalesce(started_at, ?), updated_at = ?
                where task_run_id = ? and attempt_number = ? and status = 'RESERVED'
                """, now.toString(), now.toString(), location.taskRunId(), attemptNumber);
        if (updated != 1) throw new StaleWriterException("Attempt is not reservable");
        return TaskRunWriteCapability.create(
                location, attemptNumber, ownerId, generation, fenceToken);
    }

    public boolean verifyWriterClaimCurrent(TaskRunWriteCapability capability) {
        if (capability == null) return false;
        Integer count = jdbc.queryForObject("""
                select count(*) from writer_claim_generations
                where task_run_id = ? and attempt_number = ? and generation = ?
                  and owner_id = ? and fence_token = ? and is_current = 1
                """, Integer.class, capability.taskRunId(), capability.attemptNumber(),
                capability.fenceGeneration(), capability.ownerId(), capability.fenceToken());
        return count != null && count == 1;
    }

    public void requireCurrentWriter(TaskRunWriteCapability capability) {
        if (!verifyWriterClaimCurrent(capability)) {
            throw new StaleWriterException("Writer capability is stale");
        }
    }

    /**
     * Mutation-boundary validation bound to the exact trusted run location.
     * Capability currency alone is insufficient because a current capability
     * must never be paired with a sibling or cross-workspace location.
     */
    public void requireCurrentWriter(TaskRunLocation expectedLocation,
                                     TaskRunWriteCapability capability) {
        if (expectedLocation == null || capability == null
                || !capability.matches(expectedLocation)) {
            throw new StaleWriterException("Writer capability does not match task-run location");
        }
        requireCurrentWriter(capability);
    }

    @Transactional
    public void releaseWriterClaim(TaskRunWriteCapability capability) {
        int updated = jdbc.update("""
                update writer_claim_generations set is_current = 0, superseded_at = ?
                where task_run_id = ? and attempt_number = ? and generation = ?
                  and owner_id = ? and fence_token = ? and is_current = 1
                """, Instant.now().toString(), capability.taskRunId(), capability.attemptNumber(),
                capability.fenceGeneration(), capability.ownerId(), capability.fenceToken());
        if (updated != 1) throw new StaleWriterException("Writer capability is stale");
    }

    @Transactional
    public void completeAttempt(TaskRunWriteCapability capability) {
        finishAttempt(capability, "COMPLETED", null);
    }

    @Transactional
    public void waitAttempt(TaskRunWriteCapability capability) {
        finishAttempt(capability, "WAITING", null);
    }

    @Transactional
    public void failAttempt(TaskRunWriteCapability capability, String errorText) {
        finishAttempt(capability, "FAILED", errorText);
    }

    @Transactional
    public void interruptAttempt(TaskRunWriteCapability capability, String errorText) {
        finishAttempt(capability, "INTERRUPTED", errorText);
    }

    @Transactional
    public void interruptCurrentWriterForAssignment(String assignmentId, String reason) {
        if (!StringUtils.hasText(assignmentId)) return;
        Instant now = Instant.now();
        jdbc.update("""
                update writer_claim_generations set is_current = 0, superseded_at = ?
                where is_current = 1 and task_run_id in (
                    select task_run_id from task_run_native_links where assignment_id = ?)
                """, now.toString(), assignmentId);
        jdbc.update("""
                update task_run_attempts set status = 'INTERRUPTED', completed_at = ?,
                    error_text = coalesce(error_text, ?), updated_at = ?
                where status = 'RUNNING' and task_run_id in (
                    select task_run_id from task_run_native_links where assignment_id = ?)
                """, now.toString(), reason, now.toString(), assignmentId);
    }

    @Transactional
    public int interruptWritersForStaleAssignments(String reason) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update writer_claim_generations set is_current = 0, superseded_at = ?
                where is_current = 1 and task_run_id in (
                    select n.task_run_id from task_run_native_links n
                    join work_assignments a on a.id = n.assignment_id
                    where a.status in ('INTERRUPTED', 'CANCELLED') and a.lease_owner is null)
                """, now.toString());
        jdbc.update("""
                update task_run_attempts set status = 'INTERRUPTED', completed_at = ?,
                    error_text = coalesce(error_text, ?), updated_at = ?
                where status = 'RUNNING' and task_run_id in (
                    select n.task_run_id from task_run_native_links n
                    join work_assignments a on a.id = n.assignment_id
                    where a.status in ('INTERRUPTED', 'CANCELLED') and a.lease_owner is null)
                """, now.toString(), reason, now.toString());
        return updated;
    }

    /**
     * Recover a claim only after a new fenced lease owns the linked assignment.
     * The old claim is made non-current before a new resume attempt is opened.
     */
    @Transactional
    public LifecycleResult recoverForAssignment(String taskRunId, String assignmentId,
                                                 String operationKey, String inputDigest) {
        String linked = findAssignmentId(taskRunId);
        if (!assignmentId.equals(linked)) {
            throw new IllegalArgumentException("Task run is not linked to assignment " + assignmentId);
        }
        Instant now = Instant.now();
        jdbc.update("""
                update writer_claim_generations set is_current = 0, superseded_at = ?
                where task_run_id = ? and is_current = 1
                """, now.toString(), taskRunId);
        jdbc.update("""
                update task_run_attempts set status = 'INTERRUPTED', completed_at = ?,
                    error_text = coalesce(error_text, 'Recovered after assignment lease takeover'), updated_at = ?
                where task_run_id = ? and status = 'RUNNING'
                """, now.toString(), now.toString(), taskRunId);
        return resume(new LifecycleResumeRequest(operationKey, taskRunId, inputDigest));
    }

    private void finishAttempt(TaskRunWriteCapability capability, String status, String errorText) {
        if (!verifyWriterClaimCurrent(capability)) {
            throw new StaleWriterException("Writer capability is stale");
        }
        Instant now = Instant.now();
        int updated = jdbc.update("""
                update task_run_attempts set status = ?, completed_at = ?, error_text = ?, updated_at = ?
                where task_run_id = ? and attempt_number = ? and status = 'RUNNING'
                """, status, now.toString(), errorText, now.toString(),
                capability.taskRunId(), capability.attemptNumber());
        if (updated != 1) throw new StaleWriterException("Attempt is no longer running");
        releaseWriterClaim(capability);
    }

    public TaskRunLocation provisionLocation(String taskRunId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select source_kind, source_id, workspace_type, workspace_key, task_path_key
                from task_run_locations where task_run_id = ?
                """, taskRunId);
        WorkspaceKind kind = WorkspaceKind.fromToken(text(row.get("workspace_type")));
        TaskIdentity identity = new TaskIdentity(TaskSourceKind.fromToken(text(row.get("source_kind"))),
                text(row.get("source_id")));
        TaskPathKey expected = TaskPathKey.from(identity);
        if (!expected.serialized().equals(text(row.get("task_path_key")))) {
            throw new IllegalStateException("Persisted task path key does not match task identity");
        }
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForWrite(
                kind, text(row.get("workspace_key")));
        return workspaceDirectoryService.provisionTaskRun(descriptor, identity, expected, taskRunId);
    }

    public String findAssignmentId(String taskRunId) {
        return jdbc.query("select assignment_id from task_run_native_links where task_run_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskRunId);
    }

    public LifecycleResult findById(String taskRunId) {
        RunFacts facts = requireRun(taskRunId);
        Map<String, Object> operation = jdbc.query("""
                select operation_key from task_run_idempotent_ops
                where task_run_id = ? and operation_type is not null
                order by id desc limit 1
                """, rs -> rs.next() ? Map.of("operationKey", rs.getString(1)) : Map.of(), taskRunId);
        return result(taskRunId, String.valueOf(operation.getOrDefault("operationKey", "unavailable")),
                currentAttemptNumber(taskRunId), facts);
    }

    public int currentAttemptNumber(String taskRunId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(attempt_number), 0) from task_run_attempts where task_run_id = ?",
                Integer.class, taskRunId);
        return max == null ? 0 : max;
    }

    public TaskRunLocation resolveLocation(String taskRunId) {
        Map<String, Object> row = jdbc.queryForMap("""
                select source_kind, source_id, workspace_type, workspace_key, task_path_key
                from task_run_locations where task_run_id = ?
                """, taskRunId);
        WorkspaceKind kind = WorkspaceKind.fromToken(text(row.get("workspace_type")));
        TaskIdentity identity = new TaskIdentity(TaskSourceKind.fromToken(text(row.get("source_kind"))),
                text(row.get("source_id")));
        TaskPathKey expected = TaskPathKey.from(identity);
        if (!expected.serialized().equals(text(row.get("task_path_key")))) {
            throw new IllegalStateException("Persisted task path key does not match task identity");
        }
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(
                kind, text(row.get("workspace_key")));
        return workspaceDirectoryService.resolveTaskRunForRead(descriptor, identity, expected, taskRunId);
    }

    private LifecycleResult beginOperation(RunOperationType type, String operationKey, String digest) {
        requireText(operationKey, "operationKey");
        Instant now = Instant.now();
        int inserted = jdbc.update("""
                insert into task_run_idempotent_ops(
                    operation_type, operation_key, request_digest, operation_state,
                    created_at, updated_at)
                values (?, ?, ?, 'RESERVED', ?, ?)
                on conflict(operation_type, operation_key) do nothing
                """, type.name(), operationKey, digest, now.toString(), now.toString());
        if (inserted == 1) return null;
        return existingResult(type, operationKey, digest, findOperation(type, operationKey));
    }

    private LifecycleResult existingResult(RunOperationType type, String operationKey,
                                           String digest, OperationRow existing) {
        if (existing == null) throw new IllegalStateException("Concurrent operation reservation disappeared");
        if (!digest.equals(existing.requestDigest())) {
            throw new OperationConflictException(
                    "Operation key " + operationKey + " reused with different input for " + type);
        }
        if (!"COMPLETED".equals(existing.state()) || !StringUtils.hasText(existing.resultJson())) {
            throw new IllegalStateException("Operation has no stable completed result: " + operationKey);
        }
        try {
            LifecycleResult.LifecycleResultSummary summary = objectMapper.readValue(
                    existing.resultJson(), LifecycleResult.LifecycleResultSummary.class);
            RunFacts facts = requireRun(summary.taskRunId());
            return new LifecycleResult(summary.taskRunId(), summary.nativeRunId(), operationKey,
                    summary.attemptNumber(), summary.lineageParentTaskRunId(), summary.lineageMode(),
                    nativeStatus(facts.sourceKind(), summary.nativeRunId()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored lifecycle result is unreadable", exception);
        }
    }

    private OperationRow findOperation(RunOperationType type, String operationKey) {
        return jdbc.query("""
                select request_digest, operation_state, result_json
                from task_run_idempotent_ops where operation_type = ? and operation_key = ?
                """, rs -> rs.next()
                ? new OperationRow(rs.getString(1), rs.getString(2), rs.getString(3)) : null,
                type.name(), operationKey);
    }

    private void completeOperation(RunOperationType type, String operationKey,
                                   String taskRunId, LifecycleResult result) {
        int updated = jdbc.update("""
                update task_run_idempotent_ops
                set operation_state = 'COMPLETED', task_run_id = ?, result_json = ?, updated_at = ?
                where operation_type = ? and operation_key = ? and operation_state = 'RESERVED'
                """, taskRunId, serialize(result.toSummary()), Instant.now().toString(),
                type.name(), operationKey);
        if (updated != 1) throw new IllegalStateException("Lifecycle operation reservation was lost");
    }

    private void insertAttempt(String taskRunId, int attemptNumber, String status, Instant now) {
        jdbc.update("""
                insert into task_run_attempts(
                    task_run_id, attempt_number, status, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """, taskRunId, attemptNumber, status, now.toString(), now.toString());
    }

    private void insertNativeLink(String taskRunId, TaskSourceKind sourceKind, String nativeRunId,
                                  String assignmentId, String workflowChildId,
                                  String jobWorkItemId, Instant now) {
        jdbc.update("""
                insert into task_run_native_links(
                    task_run_id, native_kind, native_run_id, assignment_id,
                    workflow_child_id, job_work_item_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, taskRunId, sourceKind.token(), nativeRunId, assignmentId,
                workflowChildId, jobWorkItemId, now.toString());
    }

    private RunFacts requireRun(String taskRunId) {
        return jdbc.query("""
                select l.source_kind, l.source_id, l.workspace_type, l.workspace_key,
                       l.lineage_parent_task_run_id, l.lineage_mode,
                       n.native_run_id
                from task_run_locations l
                join task_run_native_links n on n.task_run_id = l.task_run_id
                where l.task_run_id = ?
                """, rs -> {
            if (!rs.next()) throw new IllegalStateException("Task run not found: " + taskRunId);
            return new RunFacts(TaskSourceKind.fromToken(rs.getString(1)), rs.getString(2),
                    WorkspaceKind.fromToken(rs.getString(3)), rs.getString(4), rs.getString(5),
                    parseLineageMode(rs.getString(6)), rs.getString(7));
        }, taskRunId);
    }

    private LifecycleResult result(String taskRunId, String operationKey,
                                   int attemptNumber, RunFacts facts) {
        return new LifecycleResult(taskRunId, facts.nativeRunId(), operationKey, attemptNumber,
                facts.lineageParentTaskRunId(), facts.lineageMode(),
                nativeStatus(facts.sourceKind(), facts.nativeRunId()));
    }

    private String nativeStatus(TaskSourceKind sourceKind, String nativeRunId) {
        if (!StringUtils.hasText(nativeRunId)) return null;
        String table = switch (sourceKind) {
            case TASK -> "plan_runs";
            case WORKFLOW -> "workflow_runs";
            case JOB -> "job_runs";
        };
        if (!tableExists(table)) return null;
        return jdbc.query("select status from " + table + " where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, nativeRunId);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "select count(*) from sqlite_master where type = 'table' and name = ?",
                Integer.class, table);
        return count != null && count == 1;
    }

    static String canonicalDigest(LifecycleReservationRequest request) {
        return digest(RunOperationType.RESERVE.name(), request.sourceKind().name(), request.sourceId(),
                request.workspaceKind().name(), request.workspaceKey(), request.inputDigest());
    }

    static String canonicalDigest(LifecycleResumeRequest request) {
        return digest(RunOperationType.RESUME.name(), request.taskRunId(), request.inputDigest());
    }

    static String canonicalDigest(LifecycleLineageRequest request) {
        return digest(request.lineageMode().name(), request.parentTaskRunId(), request.inputDigest());
    }

    private static String digest(String... fields) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(bytes)) {
                data.writeByte(1);
                for (String field : fields) {
                    byte[] encoded = requireText(field, "digest field").getBytes(StandardCharsets.UTF_8);
                    data.writeInt(encoded.length);
                    data.write(encoded);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to encode lifecycle digest", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public String canonicalInputDigest(Object value) {
        try {
            String json = objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value == null ? Map.of() : value);
            return sha256Hex(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Lifecycle input cannot be canonicalized", exception);
        }
    }

    public static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize lifecycle result", exception);
        }
    }

    private static void requireMode(LifecycleLineageRequest request, RunLineageMode expected) {
        if (request.lineageMode() != expected) {
            throw new IllegalArgumentException("Expected lineage mode " + expected);
        }
    }

    private static RunLineageMode parseLineageMode(String mode) {
        return StringUtils.hasText(mode) ? RunLineageMode.valueOf(mode) : null;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + " is required");
        return value;
    }

    private record OperationRow(String requestDigest, String state, String resultJson) { }
    private record RunFacts(TaskSourceKind sourceKind, String sourceId,
                            WorkspaceKind workspaceKind, String workspaceKey,
                            String lineageParentTaskRunId, RunLineageMode lineageMode,
                            String nativeRunId) { }

    public static final class OperationConflictException extends RuntimeException {
        public OperationConflictException(String message) { super(message); }
    }

    public static final class StaleWriterException extends RuntimeException {
        public StaleWriterException(String message) { super(message); }
    }
}
