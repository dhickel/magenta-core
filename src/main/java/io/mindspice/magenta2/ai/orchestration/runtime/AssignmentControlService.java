package io.mindspice.magenta2.ai.orchestration.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Idempotent control operations over assignment lifecycle.
 * <p>
 * Priority uses narrow CAS over project scope, queued/non-terminal state,
 * lease absence, and expected {@code rowVersion}. Cancellation delegates to
 * the established lifecycle through {@link AssignmentService}.
 * <p>
 * Every mutation persists a canonical digest-bound control operation record
 * so replays are safe and conflicts are visible. This service does not hold
 * lease or fence authority — it writes operator-owned fields only.
 */
@Service
public class AssignmentControlService {
    private static final Logger log = LoggerFactory.getLogger(AssignmentControlService.class);

    private final OrchestrationRuntimeRepository repository;
    private final AssignmentService assignmentService;
    private final ProjectOperationsAccessService projectAccessService;

    public AssignmentControlService(OrchestrationRuntimeRepository repository,
                                    AssignmentService assignmentService,
                                    ProjectOperationsAccessService projectAccessService) {
        this.repository = repository;
        this.assignmentService = assignmentService;
        this.projectAccessService = projectAccessService;
    }

    // ── Priority CAS ──

    /**
     * Atomically update priority when the assignment is queued/not-running,
     * has no active lease, and the caller's expected rowVersion matches.
     * Scoped to the given project (null means un-scoped / agent-only).
     *
     * @param operationKey  caller-supplied key for idempotency; generated if blank
     * @param scope         "agent:<id>" or "project:<id>" for audit
     * @return the updated assignment
     */
    @Transactional
    public WorkAssignment setPriority(String assignmentId, int newPriority,
                                       int expectedRowVersion,
                                       String projectId, String operationKey, String scope) {
        if (!StringUtils.hasText(assignmentId)) {
            throw new IllegalArgumentException("assignmentId is required");
        }
        if (newPriority < 0) {
            throw new IllegalArgumentException("priority must be >= 0");
        }
        if (!StringUtils.hasText(projectId)) {
            throw new IllegalArgumentException("Priority controls require a canonical project scope");
        }
        if (projectAccessService == null || !projectAccessService.isTrustedDeployment()) {
            throw new ProjectOperationsAccessService.ProjectAccessDeniedException(
                "Priority controls require trusted deployment capability");
        }
        String key = StringUtils.hasText(operationKey) ? operationKey.trim()
            : "priority:" + UUID.randomUUID();
        WorkAssignment current = assignmentService.get(assignmentId);
        String resource = "project:" + projectId;
        if (!projectId.equals(current.projectId())) {
            throw new OperationConflictException("Assignment does not belong to project " + projectId);
        }
        if (!resource.equals(scope)) {
            throw new OperationConflictException("Control scope does not match canonical project attribution");
        }
        String actor = "trusted-deployment";
        String digest = digestCanonical("PRIORITY_CAS", actor, resource, assignmentId,
            String.valueOf(newPriority), String.valueOf(expectedRowVersion));

        // Check for existing operation
        var existing = repository.findControlOperation("PRIORITY_CAS", key);
        if (existing.isPresent()) {
            var op = existing.get();
            if (!digest.equals(op.requestDigest())) {
                throw new OperationConflictException(
                    "Priority operation key reused with contradictory intent: " + key);
            }
            if ("COMPLETED".equals(op.operationState())) {
                return assignmentService.get(assignmentId);
            }
            throw new OperationConflictException("Priority operation is already resolved as " + op.operationState());
        }

        repository.insertControlOperation(key, "PRIORITY_CAS", assignmentId, actor, scope,
            "SET_PRIORITY", resource, expectedRowVersion, newPriority, null, digest,
            "RESERVED", null);

        if (current.rowVersion() != expectedRowVersion) {
            repository.completeControlOperation(key, "PRIORITY_CAS", "STALE",
                "expected rowVersion=" + expectedRowVersion + " actual=" + current.rowVersion());
            throw new StaleRevisionException("Expected rowVersion " + expectedRowVersion
                + " but current is " + current.rowVersion());
        }
        if (current.status().isTerminal() || current.status() == OrchestrationStatus.RUNNING
                || current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
            repository.completeControlOperation(key, "PRIORITY_CAS", "CONFLICT",
                "assignment is not queued/leased: " + current.status());
            throw new OperationConflictException(
                "Cannot change priority of assignment in state " + current.status());
        }
        if (StringUtils.hasText(projectId) && !projectId.equals(current.projectId())) {
            repository.completeControlOperation(key, "PRIORITY_CAS", "CONFLICT",
                "project scope mismatch");
            throw new OperationConflictException(
                "Assignment " + assignmentId + " does not belong to project " + projectId);
        }
        if (StringUtils.hasText(current.leaseOwner())) {
            repository.completeControlOperation(key, "PRIORITY_CAS", "CONFLICT",
                "assignment has an active lease owner");
            throw new OperationConflictException(
                "Cannot change priority of leased assignment: " + assignmentId);
        }

        WorkAssignment updated = repository.updateAssignmentPriority(
            assignmentId, projectId, newPriority, expectedRowVersion);
        repository.completeControlOperation(key, "PRIORITY_CAS", "COMPLETED",
            "priority updated to " + newPriority);
        log.info("Priority CAS succeeded: {} → {} on {}", expectedRowVersion,
            updated.rowVersion(), assignmentId);
        return updated;
    }

    // ── Cancel delegation ──

    /**
     * Persist an idempotent cancellation request, then delegate to the
     * established lifecycle through {@link AssignmentService#cancel(String, String)}.
     */
    @Transactional
    public WorkAssignment requestCancel(String agentId, String assignmentId,
                                         String operationKey, String scope) {
        String key = StringUtils.hasText(operationKey) ? operationKey.trim()
            : "cancel:" + UUID.randomUUID();
        if (projectAccessService == null || !projectAccessService.isTrustedDeployment()) {
            throw new ProjectOperationsAccessService.ProjectAccessDeniedException(
                "Cancellation controls require trusted deployment capability");
        }
        WorkAssignment current = assignmentService.get(assignmentId);
        if (!agentId.equals(current.agentId()) || !("agent:" + agentId).equals(scope)) {
            throw new OperationConflictException("Control scope does not match canonical assignment attribution");
        }
        String actor = "trusted-deployment";
        String resource = "agent:" + agentId;

        String digest = digestCanonical("REQUEST_CANCEL", actor, resource, assignmentId);
        var existing = repository.findControlOperation("REQUEST_CANCEL", key);
        if (existing.isPresent()) {
            var op = existing.get();
            if (!digest.equals(op.requestDigest())) {
                throw new OperationConflictException(
                    "Cancel operation key reused with contradictory intent: " + key);
            }
            if ("COMPLETED".equals(op.operationState())) {
                return assignmentService.get(assignmentId);
            }
            throw new OperationConflictException("Cancel operation is already resolved as " + op.operationState());
        }

        repository.insertControlOperation(key, "REQUEST_CANCEL", assignmentId, actor, scope,
            "CANCEL", resource, null, null, null, digest, "RESERVED", null);

        try {
            WorkAssignment result = assignmentService.cancel(agentId, assignmentId);
            repository.completeControlOperation(key, "REQUEST_CANCEL", "COMPLETED",
                "cancellation dispatched; status=" + result.status());
            return result;
        } catch (Exception e) {
            repository.completeControlOperation(key, "REQUEST_CANCEL", "CONFLICT",
                "cancel dispatch failed: " + e.getMessage());
            throw e;
        }
    }

    // ── Digest helpers ──

    static String digestCanonical(String... parts) {
        return sha256Hex(String.join("\u001f", List.of(parts)));
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── Exceptions ──

    public static final class OperationConflictException extends RuntimeException {
        public OperationConflictException(String message) { super(message); }
    }

    public static final class StaleRevisionException extends RuntimeException {
        public StaleRevisionException(String message) { super(message); }
    }
}
