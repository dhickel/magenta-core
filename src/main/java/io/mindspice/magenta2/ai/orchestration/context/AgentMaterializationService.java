package io.mindspice.magenta2.ai.orchestration.context;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, lifecycle, and provisioning of project materializations.
 *
 * <p>Materializations belong to an AGENT workspace under
 * {@code projects/<stable-key>/}. Project association is metadata and
 * validation only; it never selects a workspace root. Provisioning
 * establishes an empty-or-existing canonical container without importing
 * or populating project content.
 */
@Service
public class AgentMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(AgentMaterializationService.class);

    private final AgentMaterializationRepository materializationRepository;
    private final ProjectRepository projectRepository;
    private final WorkspaceDirectoryService workspaceDirectoryService;

    public AgentMaterializationService(
            AgentMaterializationRepository materializationRepository,
            ProjectRepository projectRepository,
            WorkspaceDirectoryService workspaceDirectoryService) {
        this.materializationRepository = Objects.requireNonNull(materializationRepository);
        this.projectRepository = Objects.requireNonNull(projectRepository);
        this.workspaceDirectoryService = Objects.requireNonNull(workspaceDirectoryService);
    }

    /**
     * Register a project materialization for an agent. The operation is
     * idempotent: same type/key/digest returns the recorded result; a
     * changed digest conflicts.
     *
     * <p>Registration verifies agent existence, project existence, and
     * agent membership in the project. It inserts a {@code PROVISIONING}
     * row with generation 1 and returns the registered record.
     *
     * @param agentDescriptor trusted AGENT descriptor
     * @param projectId       existing project id
     * @return the registered materialization record
     */
    @Transactional
    public MaterializationRecord registerProject(
            WorkspaceDescriptor agentDescriptor, String projectId) {
        validateAgent(agentDescriptor);
        String agentId = agentDescriptor.key();
        Objects.requireNonNull(projectId, "projectId");

        // Verify project exists and agent is a member
        projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (!projectRepository.isMember(projectId, agentId)) {
            throw new IllegalArgumentException(
                    "Agent " + agentId + " is not a member of project " + projectId);
        }

        MaterializationKey key = MaterializationKey.forProject(agentId, projectId);
        String operationType = "REGISTER";
        String operationKey = key.serialized();
        String requestDigest = canonicalDigest(operationType, operationKey, agentId, projectId);

        // Check idempotent operation
        var existing = materializationRepository.findOperation(
                operationType, operationKey, requestDigest);
        if (existing.isPresent()) {
            var result = existing.get();
            if (result.isConflict()) {
                throw new IllegalStateException(
                        "Registration conflict for project " + projectId + " by agent " + agentId);
            }
            // Already completed — return existing materialization
            return materializationRepository.findByAgentAndKey(agentId, key)
                    .orElseThrow(() -> new IllegalStateException(
                            "Registered materialization record missing for completed operation"));
        }

        // Check if materialization row already exists
        var existingMat = materializationRepository.findByAgentAndKey(agentId, key);
        if (existingMat.isPresent()) {
            // Row already exists; record operation result and return
            materializationRepository.insertOperationIfAbsent(
                    operationType, operationKey, requestDigest,
                    "COMPLETED", key.serialized(), null);
            return existingMat.get();
        }

        Instant now = Instant.now();
        MaterializationRecord record = new MaterializationRecord(
                agentId, key, "project", projectId,
                1, MaterializationState.PROVISIONING,
                null, null, now, now);

        if (!materializationRepository.insertIfAbsent(record)) {
            // Race: row inserted by concurrent starter
            return materializationRepository.findByAgentAndKey(agentId, key).orElseThrow();
        }

        // Record idempotent operation
        materializationRepository.insertOperationIfAbsent(
                operationType, operationKey, requestDigest,
                "COMPLETED", key.serialized(), null);

        log.info("Registered project materialization {} for agent {}", key.serialized(), agentId);
        return record;
    }

    /**
     * Provision a registered materialization. Creates the canonical
     * {@code projects/<key>} container underneath the agent workspace
     * using confined mutation primitives. On success transitions from
     * {@code PROVISIONING} to {@code READY} with the next generation.
     * On failure transitions to {@code FAILED}.
     *
     * <p>The operation is idempotent: same type/key/digest converges.
     *
     * @param agentDescriptor    trusted AGENT descriptor
     * @param materializationKey the materialization identity
     * @return the provisioned materialization location
     */
    public MaterializationLocation provision(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey materializationKey) {
        validateAgent(agentDescriptor);
        String agentId = agentDescriptor.key();

        var record = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));

        if (record.state() != MaterializationState.PROVISIONING) {
            if (record.state() == MaterializationState.READY) {
                // Already ready — resolve and return location
                return resolveVerifiedReadyLocation(agentDescriptor, record);
            }
            throw new IllegalStateException(
                    "Cannot provision materialization in state " + record.state());
        }

        String operationType = "PROVISION";
        String operationKey = materializationKey.serialized() + "-" + record.generation();
        String requestDigest = canonicalDigest(operationType, operationKey, agentId);

        var existing = materializationRepository.findOperation(
                operationType, operationKey, requestDigest);
        if (existing.isPresent()) {
            var result = existing.get();
            if (result.isConflict()) {
                throw new IllegalStateException("Provision conflict for " + materializationKey.serialized());
            }
            if (result.isCompleted()) {
                var fresh = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                        .orElseThrow();
                if (fresh.state() == MaterializationState.READY) {
                    return resolveVerifiedReadyLocation(agentDescriptor, fresh);
                }
                throw new IllegalStateException(
                        "Provision operation completed but materialization is not READY");
            }
        }

        // Perform filesystem provisioning outside the CAS check transaction,
        // then CAS to READY.
        String rootRelativePath;
        try {
            rootRelativePath = workspaceDirectoryService.provisionMaterializationRoot(
                    agentDescriptor, materializationKey, record.generation());
        } catch (Exception e) {
            log.error("Provisioning failed for {}: {}", materializationKey.serialized(), e.getMessage());
            // Attempt CAS to FAILED
            boolean failed = materializationRepository.transitionState(
                    agentId, materializationKey, record.generation(),
                    MaterializationState.PROVISIONING, MaterializationState.FAILED,
                    null, truncate(e.getMessage()));
            materializationRepository.insertOperationIfAbsent(
                    operationType, operationKey, requestDigest,
                    "COMPLETED", materializationKey.serialized(), null);
            if (failed) {
                throw new IllegalStateException("Materialization provision failed", e);
            }
            throw new IllegalStateException("Materialization provision became stale", e);
        }

        boolean transitioned = materializationRepository.transitionState(
                agentId, materializationKey, record.generation(),
                MaterializationState.PROVISIONING, MaterializationState.READY,
                rootRelativePath, null);

        if (!transitioned) {
            // CAS lost: another process or retry changed state
            log.warn("CAS transition to READY lost for {}. Filesystem may need reconciliation.",
                    materializationKey.serialized());
            var fresh = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                    .orElseThrow();
            throw new IllegalStateException(
                    "Materialization provision became stale; current state: " + fresh.state());
        }

        materializationRepository.insertOperationIfAbsent(
                operationType, operationKey, requestDigest,
                "COMPLETED", materializationKey.serialized(), null);

        log.info("Provisioned materialization {} -> READY at {}", materializationKey.serialized(), rootRelativePath);

        MaterializationRecord ready = materializationRepository
                .findByAgentAndKey(agentId, materializationKey)
                .orElseThrow(() -> new IllegalStateException("Ready materialization record is missing"));
        return resolveVerifiedReadyLocation(agentDescriptor, ready);
    }

    public MaterializationLocation resolveReadyLocation(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey materializationKey,
            int expectedGeneration) {
        validateAgent(agentDescriptor);
        String agentId = agentDescriptor.key();
        var record = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));
        if (record.state() != MaterializationState.READY) {
            throw new IllegalStateException(
                    "Materialization is not READY: " + record.state());
        }
        if (record.generation() != expectedGeneration) {
            throw new IllegalStateException(
                    "Materialization generation mismatch: expected " + expectedGeneration
                            + " but found " + record.generation());
        }
        return resolveVerifiedReadyLocation(agentDescriptor, record);
    }

    // ── Helpers ──

    private MaterializationLocation resolveVerifiedReadyLocation(
            WorkspaceDescriptor agentDescriptor, MaterializationRecord record) {
        MaterializationLocation location = workspaceDirectoryService.resolveMaterializationLocation(
                agentDescriptor, record.materializationKey(), record.generation());
        if (record.rootRelativePath() == null
                || !record.rootRelativePath().equals(location.rootRelativePath())) {
            throw new IllegalStateException("Materialization location assertion mismatch");
        }
        return location;
    }

    private void validateAgent(WorkspaceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException(
                    "Materializations require an AGENT workspace descriptor");
        }
    }

    private static String canonicalDigest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (bytes.length > 0xffff) {
                    throw new IllegalArgumentException("Operation field exceeds canonical length");
                }
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String message) {
        if (message == null) return null;
        return message.length() <= 4000 ? message : message.substring(0, 4000);
    }

    /** Typed terminal outcomes for callers that reconcile materialization work. */
    public enum MaterializationOutcome { UNAVAILABLE, BUSY, STALE, FAILED, RECONCILED }
}
