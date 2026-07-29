package io.mindspice.magenta2.ai.orchestration.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Coordinates atomic queued job reassignment: revoke-and-clone an eligible
 * unstarted assignment in one transaction with idempotent operation persistence.
 *
 * <p>Reassignment is gated: trusted caller, active project, eligible
 * destination member, queued/nonterminal/unleased/no-JobRun source at its
 * expected version. Source attribution, workspace, artifacts, outputs, lease,
 * and checkpoint are never copied or rewritten.</p>
 *
 * <p>Response-loss replay returns the committed result; pre-commit failure
 * leaves no partial reassignment. Never transfers leased, running, or native
 * work.</p>
 */
@Service
public class JobReassignmentService {
    private static final Logger log = LoggerFactory.getLogger(JobReassignmentService.class);
    public static final String ORIGIN_PREFIX = "REASSIGNMENT/";
    private static final String SERVER_ACTOR = "magenta-server";

    private final JdbcTemplate jdbc;
    private final AssignmentService assignmentService;
    private final AgentProfileService agentProfileService;
    private final ProjectService projectService;
    private final ProjectOperationsAccessService projectAccessService;

    public JobReassignmentService(JdbcTemplate jdbc,
                                  AssignmentService assignmentService,
                                  AgentProfileService agentProfileService,
                                  ProjectService projectService,
                                  ProjectOperationsAccessService projectAccessService) {
        this.jdbc = jdbc;
        this.assignmentService = assignmentService;
        this.agentProfileService = agentProfileService;
        this.projectService = projectService;
        this.projectAccessService = projectAccessService;
    }

    /**
     * Computes the canonical request digest for a reassignment operation.
     * Binds server actor, action, scope, source, destination, and expected version.
     */
    public static String reassignmentDigest(String scope, String sourceAssignmentId,
                                             String destinationAgentId, int expectedVersion) {
        String input = String.join("\u001f", SERVER_ACTOR, "REASSIGN", scope, sourceAssignmentId,
            destinationAgentId, String.valueOf(expectedVersion));
        return sha256Hex(input);
    }

    /**
     * Revoke-and-clone a queued assignment to a new destination agent.
     *
     * @param operationKey       unique idempotency key
     * @param scope              server-defined scope (e.g. "project:<id>")
     * @param sourceAssignmentId the existing queued assignment to reassign
     * @param destinationAgentId the member agent receiving the new assignment
     * @param expectedVersion    the expected rowVersion of the source assignment
     * @return the newly created successor assignment
     */
    @Transactional(noRollbackFor = {ReassignmentConflictException.class, ReassignmentFailedException.class})
    public WorkAssignment reassign(String operationKey, String scope,
                                    String sourceAssignmentId,
                                    String destinationAgentId,
                                    int expectedVersion) {
        requireText(operationKey, "operationKey");
        requireText(scope, "scope");
        requireText(sourceAssignmentId, "sourceAssignmentId");
        requireText(destinationAgentId, "destinationAgentId");

        String digest = reassignmentDigest(scope, sourceAssignmentId, destinationAgentId, expectedVersion);

        // ── Idempotency: resolve replay ──
        ReassignmentOperation existing = findOperation(operationKey);
        if (existing != null) return replay(operationKey, digest, existing);

        // ── Reserve operation ──
        Instant now = Instant.now();
        int reserved;
        try {
            reserved = jdbc.update("""
                insert into job_reassignment_operations(
                    operation_key, request_digest, operation_state, scope_text,
                    expected_version, source_assignment_id, destination_agent_id,
                    created_at)
                values (?, ?, 'RESERVED', ?, ?, ?, ?, ?)
                """,
            operationKey, digest, scope, expectedVersion,
            sourceAssignmentId, destinationAgentId, now.toString());
        } catch (DuplicateKeyException concurrentReservation) {
            ReassignmentOperation concurrent = findOperation(operationKey);
            if (concurrent != null) return replay(operationKey, digest, concurrent);
            throw concurrentReservation;
        }
        if (reserved != 1) throw new IllegalStateException("Could not reserve reassignment operation: " + operationKey);

        try {
            // ── Gate: validate caller, project, source, destination ──
            WorkAssignment source = assignmentService.get(sourceAssignmentId);

            // Only queued, nonterminal, unleased, no-JobRun sources are eligible
            if (source.status() == null || source.status().isTerminal()) {
                return failOperation(operationKey, "CONFLICT", "Source is terminal", null);
            }
            if (source.status() != OrchestrationStatus.QUEUED) {
                return failOperation(operationKey, "CONFLICT",
                    "Source must be QUEUED: " + source.status(), null);
            }
            if (source.leaseOwner() != null || source.leaseExpiresAt() != null) {
                return failOperation(operationKey, "CONFLICT",
                    "Source is already leased", null);
            }
            if (!StringUtils.hasText(source.jobId())) {
                return failOperation(operationKey, "CONFLICT",
                    "Source is not a job assignment", null);
            }
            if (source.assignmentType() != AssignmentType.JOB_RUN) {
                return failOperation(operationKey, "CONFLICT",
                    "Source is not a JOB_RUN assignment", null);
            }

            // Check expected version
            if (source.rowVersion() != expectedVersion) {
                return failOperation(operationKey, "CONFLICT",
                    "Version mismatch: expected " + expectedVersion
                        + " but source is at " + source.rowVersion(), null);
            }

            // Check no existing JobRun for this assignment
            Integer runCount = jdbc.queryForObject(
                "select count(*) from job_runs where job_assignment_id = ?",
                Integer.class, source.id());
            if (runCount != null && runCount > 0) {
                return failOperation(operationKey, "CONFLICT",
                    "Source already has a JobRun", null);
            }

            // Gate: active project and a server-defined scope bound to that project.
            String projectId = source.projectId();
            if (!StringUtils.hasText(projectId)) {
                return failOperation(operationKey, "CONFLICT",
                    "Source has no project attribution", null);
            }
            if (!projectScope(projectId).equals(scope)) {
                return failOperation(operationKey, "CONFLICT",
                    "Reassignment scope does not match source project", null);
            }
            if (projectAccessService == null) {
                return failOperation(operationKey, "FAILED",
                    "Project access service unavailable", null);
            }
            try {
                projectAccessService.requireEligibleSubmission(projectId, destinationAgentId);
            } catch (ProjectOperationsAccessService.ProjectAccessDeniedException e) {
                return failOperation(operationKey, "CONFLICT",
                    "Destination not an eligible project member: " + e.getMessage(), null);
            }

            // Gate: destination agent exists and is active
            AgentProfile destProfile;
            try {
                destProfile = agentProfileService.get(destinationAgentId);
                if (destProfile.status() == io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus.DISABLED) {
                    return failOperation(operationKey, "CONFLICT",
                        "Destination agent is disabled", null);
                }
            } catch (RuntimeException e) {
                return failOperation(operationKey, "CONFLICT",
                    "Destination agent not found: " + e.getMessage(), null);
            }

            // ── CAS-revoke source ──
            boolean revoked = jdbc.update("""
                    update work_assignments
                    set status = ?, completed_at = ?, error_text = coalesce(error_text, ?),
                        row_version = row_version + 1, updated_at = ?
                    where id = ? and status = ? and row_version = ?
                        and lease_owner is null
                        and (select count(*) from job_runs where job_assignment_id = ?) = 0
                    """,
                OrchestrationStatus.CANCELLED.name(),
                now.toString(), "Reassigned to " + destinationAgentId, now.toString(),
                source.id(), OrchestrationStatus.QUEUED.name(), expectedVersion,
                source.id()) > 0;
            if (!revoked) {
                return failOperation(operationKey, "CONFLICT",
                    "CAS revoke failed: source changed or already has a JobRun", null);
            }

            // ── Create successor assignment ──
            String successorId = UUID.randomUUID().toString();
            AssignmentRequest request = new AssignmentRequest(
                destinationAgentId,
                source.jobId(),
                null,
                AssignmentType.JOB_RUN,
                null,
                source.priority(),
                source.modelOverride(),
                projectId,
                null,
                null,
                Map.of("jobId", source.jobId(), "reassignedFrom", source.id(),
                    "reassignmentOperation", operationKey),
                "job-reassignment-submission:" + operationKey,
                source.jobId(),
                ORIGIN_PREFIX + operationKey,
                null
            );

            // The successor captures its own destination-agent workspace and immutable context.
            // A failure here must escape so the transaction restores the source to QUEUED.
            WorkAssignment successor = assignmentService.create(successorId, request);

            // ── Persist lineage and complete operation ──
            int completed = jdbc.update("""
                    update job_reassignment_operations
                    set operation_state = 'COMPLETED',
                        successor_assignment_id = ?,
                        successor_task_run_id = ?,
                        lineage_parent_id = ?,
                        terminal_outcome = 'SUCCESS',
                        completed_at = ?
                    where operation_key = ? and operation_state = 'RESERVED'
                    """,
                successor.id(), text(successor.checkpoint().get("taskRunId")), source.id(),
                now.toString(), operationKey);
            if (completed != 1) {
                throw new IllegalStateException("Reassignment operation reservation lost: " + operationKey);
            }

            // ── Work Task provenance ──
            // If the source assignment originated from a Work Task, a successor
            // attempt must be linked atomically. The current WorkTaskService API
            // (submitOperation) creates a full submission rather than an append-only
            // attempt. Per the phase plan: stop this capability rather than add a
            // Task writer. Provenance linking is deferred until the API supports
            // an atomic append-attempt operation in the same transaction.
            if (source.originDefinitionId() != null
                    && source.originDefinitionId().startsWith("WORK_TASK/")) {
                log.info("Reassignment {} has Work Task provenance ({}); "
                    + "atomically appending a successor attempt requires an API "
                    + "not yet available. Provenance linking is skipped.",
                    operationKey, source.originDefinitionId());
            }

            log.info("Reassignment {}: {} -> {} completed, successor={}",
                operationKey, source.id(), destinationAgentId, successor.id());
            return successor;
        } catch (ReassignmentConflictException | ReassignmentFailedException e) {
            throw e;
        }
    }

    private WorkAssignment replay(String operationKey, String digest, ReassignmentOperation existing) {
        if (!digest.equals(existing.requestDigest())) {
            throw new OperationConflictException(
                "Reassignment operation key reused with contradictory intent: " + operationKey);
        }
        if ("COMPLETED".equals(existing.operationState())) {
            if (!StringUtils.hasText(existing.successorAssignmentId())) {
                throw new IllegalStateException("Completed reassignment has no successor: " + operationKey);
            }
            return assignmentService.get(existing.successorAssignmentId());
        }
        if ("FAILED".equals(existing.operationState())) {
            throw new ReassignmentFailedException(
                "Reassignment previously failed: " + operationKey + " / " + existing.failureClass());
        }
        if ("CONFLICT".equals(existing.operationState())) {
            throw new ReassignmentConflictException(
                "Reassignment previously conflicted: " + operationKey);
        }
        throw new IllegalStateException("Reassignment operation is in progress: " + operationKey);
    }

    private static String projectScope(String projectId) {
        return "project:" + projectId;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private ReassignmentOperation findOperation(String operationKey) {
        return jdbc.query("""
                select request_digest, operation_state, successor_assignment_id, failure_class
                from job_reassignment_operations where operation_key = ?
                """,
            rs -> rs.next()
                ? new ReassignmentOperation(rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4))
                : null,
            operationKey);
    }

    private WorkAssignment failOperation(String operationKey, String state, String detail,
                                          String failureClass) {
        int updated = jdbc.update("""
                update job_reassignment_operations
                set operation_state = ?, terminal_outcome = ?, failure_class = ?, completed_at = ?
                where operation_key = ? and operation_state = 'RESERVED'
                """,
            state, state, failureClass, Instant.now().toString(), operationKey);
        if (updated == 0) {
            log.warn("Reassignment operation reservation was lost for fail: {}", operationKey);
        }
        if ("CONFLICT".equals(state)) {
            throw new ReassignmentConflictException(detail);
        }
        throw new ReassignmentFailedException(detail);
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String requireText(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + " is required");
        return value.trim();
    }

    private record ReassignmentOperation(String requestDigest, String operationState,
                                          String successorAssignmentId, String failureClass) {
    }

    public static final class OperationConflictException extends RuntimeException {
        public OperationConflictException(String message) { super(message); }
    }

    public static final class ReassignmentConflictException extends RuntimeException {
        public ReassignmentConflictException(String message) { super(message); }
    }

    public static final class ReassignmentFailedException extends RuntimeException {
        public ReassignmentFailedException(String message) { super(message); }
    }
}
