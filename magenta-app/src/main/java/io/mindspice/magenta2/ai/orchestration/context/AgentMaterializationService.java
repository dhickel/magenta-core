package io.mindspice.magenta2.ai.orchestration.context;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.GenerationLifecycleState;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.GenerationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.RootKind;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectMaterializationTemplate;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectMaterializationTemplateService;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationGenerationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, lifecycle, and provisioning of project materializations
 * and their immutable generation rows.
 *
 * <p>Materializations belong to an AGENT workspace. The logical head
 * (materialization key) is in {@code projects/<stable-key>/} for legacy
 * V1 or {@code materializations/v2/<key>/gen-<N>/} for V2 generations.
 * Project association is metadata and validation only; it never selects
 * a workspace root. Provisioning establishes an empty-or-existing
 * canonical container without importing or populating project content.
 */
@Service
public class AgentMaterializationService {

    private static final Logger log = LoggerFactory.getLogger(AgentMaterializationService.class);

    private final AgentMaterializationRepository materializationRepository;
    private final ProjectRepository projectRepository;
    private final ProjectMaterializationTemplateService templateService;
    private final WorkspaceDirectoryService workspaceDirectoryService;

    public AgentMaterializationService(
            AgentMaterializationRepository materializationRepository,
            ProjectRepository projectRepository,
            ProjectMaterializationTemplateService templateService,
            WorkspaceDirectoryService workspaceDirectoryService) {
        this.materializationRepository = Objects.requireNonNull(materializationRepository);
        this.projectRepository = Objects.requireNonNull(projectRepository);
        this.templateService = Objects.requireNonNull(templateService);
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

    // ── Immutable generation rows (Subphase A / Phase 05) ──

    /**
     * Allocate a new immutable {@code GENERATION_V2} generation for a
     * registered materialization. Snapshot the chosen template revision
     * and its directories into the generation row. The generation is
     * monotonic per {@code (agentId, projectKey)} and starts at 1.
     *
     * @param agentDescriptor trusted AGENT descriptor
     * @param materializationKey the registered materialization identity
     * @param template           the template revision to snapshot
     * @return the newly inserted generation record in PROVISIONING state
     */
    @Transactional
    public GenerationRecord allocateGeneration(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey materializationKey,
            ProjectMaterializationTemplate template) {
        validateAgent(agentDescriptor);
        String agentId = agentDescriptor.key();
        Objects.requireNonNull(materializationKey, "materializationKey");
        Objects.requireNonNull(template, "template");

        // Verify the materialization (logical head) exists
        var record = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));

        // Verify template belongs to the same project
        if (!record.sourceId().equals(template.projectId())) {
            throw new IllegalArgumentException(
                    "Template project " + template.projectId()
                            + " does not match materialization source " + record.sourceId());
        }

        String projectKey = materializationKey.serialized();
        java.util.List<String> templateDirectories =
                ProjectMaterializationTemplateService.parseValidatedDirectories(
                        template.directoriesSnapshot());
        String canonicalTemplateSnapshot =
                ProjectMaterializationTemplateService.toSnapshotJson(templateDirectories);
        int nextGen = materializationRepository.findLatestGenerationNumber(agentId, projectKey) + 1;

        Instant now = Instant.now();
        GenerationRecord genRecord = new GenerationRecord(
                agentId, projectKey, nextGen,
                RootKind.GENERATION_V2,
                null, // rootLocation set during provisioning
                GenerationLifecycleState.PROVISIONING,
                1, // lifecycleVersion
                template.revision(),
                canonicalTemplateSnapshot,
                null, // failureDetail
                now, now);

        if (!materializationRepository.insertGeneration(genRecord)) {
            throw new IllegalStateException(
                    "Generation " + nextGen + " already exists for " + projectKey);
        }

        log.info("Allocated generation {} for {} -> PROVISIONING", nextGen, projectKey);
        return genRecord;
    }

    /**
     * Provision an allocated generation. Creates the canonical V2
     * generation root and every directory from the template snapshot
     * using confined create-only primitives. On success CAS from
     * {@code PROVISIONING} to {@code READY}. On failure CAS to
     * {@code FAILED} with bounded failure detail.
     *
     * @param agentDescriptor trusted AGENT descriptor
     * @param generation      the allocated generation in PROVISIONING state
     * @return the provisioned generation location
     */
    @Transactional
    public MaterializationGenerationLocation provisionGeneration(
            WorkspaceDescriptor agentDescriptor,
            GenerationRecord generation) {
        validateAgent(agentDescriptor);
        String agentId = agentDescriptor.key();
        Objects.requireNonNull(generation, "generation");

        if (generation.lifecycleState() != GenerationLifecycleState.PROVISIONING) {
            if (generation.lifecycleState() == GenerationLifecycleState.READY) {
                return resolveVerifiedReadyGenerationLocation(agentDescriptor, generation);
            }
            throw new IllegalStateException(
                    "Cannot provision generation in state " + generation.lifecycleState());
        }

        if (generation.rootKind() != RootKind.GENERATION_V2) {
            throw new IllegalArgumentException(
                    "Only GENERATION_V2 roots can be provisioned through this path");
        }

        if (!agentId.equals(generation.agentId())) {
            throw new IllegalArgumentException("Generation does not belong to the AGENT descriptor");
        }

        String projectKey = generation.projectKey();
        int gen = generation.generation();

        String rootRelativePath;
        try {
            // 1. Create the V2 generation root directory
            rootRelativePath = workspaceDirectoryService.provisionGenerationV2Root(
                    agentDescriptor, projectKey, gen);

            // 2. Resolve the location for directory provisioning
            MaterializationGenerationLocation provisionedLoc =
                    workspaceDirectoryService.resolveGenerationV2Location(
                            agentDescriptor, projectKey, gen);

            // 3. Parse the template directories snapshot and provision every directory
            java.util.List<String> directories =
                    ProjectMaterializationTemplateService.parseValidatedDirectories(
                            generation.templateDirectoriesSnapshot());
            workspaceDirectoryService.provisionGenerationDirectories(provisionedLoc, directories);
        } catch (Exception e) {
            log.error("Generation provisioning failed for {} gen {}: {}", projectKey, gen, e.getMessage());
            boolean failed = materializationRepository.casTransitionGenerationLifecycle(
                    agentId, projectKey, gen,
                    generation.lifecycleVersion(),
                    GenerationLifecycleState.PROVISIONING,
                    GenerationLifecycleState.FAILED,
                    null, truncate(e.getMessage()));
            if (failed) {
                throw new IllegalStateException("Generation provision failed", e);
            }
            throw new IllegalStateException("Generation provision became stale", e);
        }

        boolean transitioned = materializationRepository.casTransitionGenerationLifecycle(
                agentId, projectKey, gen,
                generation.lifecycleVersion(),
                GenerationLifecycleState.PROVISIONING,
                GenerationLifecycleState.READY,
                rootRelativePath, null);

        if (!transitioned) {
            log.warn("CAS transition to READY lost for {} gen {}. Filesystem may need reconciliation.",
                    projectKey, gen);
            var fresh = materializationRepository.findGeneration(agentId, projectKey, gen)
                    .orElseThrow();
            throw new IllegalStateException(
                    "Generation provision became stale; current state: " + fresh.lifecycleState());
        }

        log.info("Provisioned generation {} gen {} -> READY at {}", projectKey, gen, rootRelativePath);

        GenerationRecord ready = materializationRepository
                .findGeneration(agentId, projectKey, gen)
                .orElseThrow(() -> new IllegalStateException("Ready generation record is missing"));
        return resolveVerifiedReadyGenerationLocation(agentDescriptor, ready);
    }

    /**
     * Resolve a ready generation location from the stored record.
     * Revalidates key agreement, generation number, and location assertion.
     */
    public MaterializationGenerationLocation resolveReadyGenerationLocation(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey materializationKey,
            int generation) {
        validateAgent(agentDescriptor);
        if (!agentDescriptor.key().equals(materializationKey.agentId())) {
            throw new IllegalArgumentException("Materialization key does not belong to the AGENT descriptor");
        }
        String agentId = agentDescriptor.key();
        String projectKey = materializationKey.serialized();
        var record = materializationRepository.findGeneration(agentId, projectKey, generation)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Generation not found: " + projectKey + " gen " + generation));
        if (record.lifecycleState() != GenerationLifecycleState.READY) {
            throw new IllegalStateException(
                    "Generation is not READY: " + record.lifecycleState());
        }
        return resolveVerifiedReadyGenerationLocation(agentDescriptor, record);
    }

    // ── Template-to-generation wiring ──

    /**
     * Allocate and provision a generation using the project's latest
     * template revision. This is the member materialization entry point:
     * when a member agent captures a project materialization, the latest
     * template is snapshotted into a new immutable generation and its
     * directories are immediately provisioned.
     *
     * @param agentDescriptor    trusted AGENT descriptor
     * @param materializationKey the registered materialization identity
     * @return the provisioned ready generation location
     * @throws IllegalArgumentException if no template exists for the project
     */
    @Transactional
    public MaterializationGenerationLocation allocateAndProvisionForProject(
            WorkspaceDescriptor agentDescriptor,
            MaterializationKey materializationKey) {
        validateAgent(agentDescriptor);
        Objects.requireNonNull(materializationKey, "materializationKey");

        var record = materializationRepository.findByAgentAndKey(
                agentDescriptor.key(), materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));

        // Find the latest template revision for this project
        ProjectMaterializationTemplate template =
                templateService.findLatestByProjectId(record.sourceId())
                        .orElseThrow(() -> new IllegalStateException(
                                "No template exists for project " + record.sourceId()));

        GenerationRecord genRecord = allocateGeneration(
                agentDescriptor, materializationKey, template);
        return provisionGeneration(agentDescriptor, genRecord);
    }

    /**
     * Provision a new V2 generation when the project has a template. Existing
     * V1 context bindings remain untouched; this method is the additive Files
     * projection bridge and never falls back from V2 to a legacy root.
     */
    @Transactional
    public java.util.Optional<MaterializationGenerationLocation> provisionLatestGenerationIfTemplate(
            WorkspaceDescriptor agentDescriptor, MaterializationKey materializationKey) {
        validateAgent(agentDescriptor);
        Objects.requireNonNull(materializationKey, "materializationKey");
        var record = materializationRepository.findByAgentAndKey(agentDescriptor.key(), materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));
        ProjectMaterializationTemplate template = templateService.findLatestByProjectId(record.sourceId())
                .orElse(null);
        if (template == null) {
            return java.util.Optional.empty();
        }
        var existing = materializationRepository.findGenerationsByHead(
                agentDescriptor.key(), materializationKey.serialized()).stream()
                .filter(g -> g.rootKind() == RootKind.GENERATION_V2
                        && g.lifecycleState() == GenerationLifecycleState.READY
                        && Integer.valueOf(template.revision()).equals(g.templateRevision())
                        && template.directoriesSnapshot().equals(g.templateDirectoriesSnapshot()))
                .findFirst();
        if (existing.isPresent()) {
            return java.util.Optional.of(resolveVerifiedReadyGenerationLocation(
                    agentDescriptor, existing.get()));
        }
        return java.util.Optional.of(allocateAndProvisionForProject(agentDescriptor, materializationKey));
    }

    // ── Project Files read-only resolution ──

    /**
     * Project Files authorization result: controlled outcome for every state
     * the Files tab must render. Callers map this to HTTP status and fragments.
     */
    public sealed interface FilesAuthorization {
        /** Authorized for read-only browse; location is verified and confined. */
        record Authorized(MaterializationGenerationLocation location, GenerationRecord generation) implements FilesAuthorization {}
        /** Project is archived. */
        record ProjectArchived() implements FilesAuthorization {}
        /** Project member not found. */
        record NoMember() implements FilesAuthorization {}
        /** Member has not registered a project materialization. */
        record NoMaterialization() implements FilesAuthorization {}
        /** Materialization is in a non-READY state (PROVISIONING, FAILED, REVOKED). */
        record NotReady(MaterializationState state) implements FilesAuthorization {}
        /** The requested generation does not match the current ready generation. */
        record StaleGeneration(int expected, int actual) implements FilesAuthorization {}
        /** No READY generation exists for this materialization. */
        record NoReadyGeneration() implements FilesAuthorization {}
        /** Readiness assertion (resolved location vs stored root) failed. */
        record LocationMismatch() implements FilesAuthorization {}
    }

    /**
     * Authorize project Files read access for a single member.
     *
     * <p>Revalidates project state (not archived), current member
     * access, exact project source, ready generation with expected generation
     * number, and confined read-only path resolution. One-member view only —
     * never infer member or workspace.</p>
     *
     * @param agentId           the current member's agent id
     * @param projectId         the exact project id
     * @param expectedGeneration the generation number the caller expects (from the Files tab URL parameter)
     * @return controlled authorization outcome
     */
    public FilesAuthorization authorizeProjectFiles(
            String agentId, String projectId, int expectedGeneration) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(projectId, "projectId");
        if (expectedGeneration < 0) {
            throw new IllegalArgumentException("expectedGeneration must be >= 0");
        }

        var project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.archived()) {
            return new FilesAuthorization.ProjectArchived();
        }
        if (!projectRepository.isMember(projectId, agentId)) {
            return new FilesAuthorization.NoMember();
        }

        // Find the materialization record for this exact member/project
        var matOpt = materializationRepository.findByAgent(agentId).stream()
                .filter(r -> "project".equals(r.sourceKind()) && projectId.equals(r.sourceId()))
                .findFirst();
        if (matOpt.isEmpty()) {
            return new FilesAuthorization.NoMaterialization();
        }
        MaterializationRecord matRecord = matOpt.get();

        // Check materialization state
        if (matRecord.state() != MaterializationState.READY) {
            return new FilesAuthorization.NotReady(matRecord.state());
        }

        // Find the latest READY generation for this head
        String projectKey = matRecord.materializationKey().serialized();
        var generations = materializationRepository.findGenerationsByHead(agentId, projectKey);
        var readyGen = generations.stream()
                .filter(g -> g.lifecycleState() == GenerationLifecycleState.READY)
                .findFirst();
        if (readyGen.isEmpty()) {
            return new FilesAuthorization.NoReadyGeneration();
        }
        GenerationRecord genRecord = readyGen.get();

        // Validate generation matches expected
        if (expectedGeneration != 0 && genRecord.generation() != expectedGeneration) {
            return new FilesAuthorization.StaleGeneration(expectedGeneration, genRecord.generation());
        }

        // Resolve the workspace descriptor and materialization location
        WorkspaceDescriptor agentDescriptor;
        try {
            agentDescriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, agentId);
        } catch (RuntimeException e) {
            return new FilesAuthorization.NoMaterialization();
        }

        // Resolve and verify the generation location
        MaterializationGenerationLocation location;
        try {
            location = resolveVerifiedReadyGenerationLocation(agentDescriptor, genRecord);
        } catch (RuntimeException e) {
            return new FilesAuthorization.LocationMismatch();
        }

        return new FilesAuthorization.Authorized(location, genRecord);
    }

    /**
     * Find the latest READY generation number for a member's project,
     * or 0 if none exists. Used by the Files tab selector to display
     * the current generation for each member.
     */
    public int latestReadyGenerationNumber(String agentId, String projectId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(projectId, "projectId");
        var matOpt = materializationRepository.findByAgent(agentId).stream()
                .filter(r -> "project".equals(r.sourceKind()) && projectId.equals(r.sourceId()))
                .findFirst();
        if (matOpt.isEmpty()) return 0;
        String projectKey = matOpt.get().materializationKey().serialized();
        var generations = materializationRepository.findGenerationsByHead(agentId, projectKey);
        return generations.stream()
                .filter(g -> g.lifecycleState() == GenerationLifecycleState.READY)
                .mapToInt(GenerationRecord::generation)
                .findFirst()
                .orElse(0);
    }

    // ── Helpers ──

    private MaterializationGenerationLocation resolveVerifiedReadyGenerationLocation(
            WorkspaceDescriptor agentDescriptor, GenerationRecord record) {
        MaterializationGenerationLocation location =
                workspaceDirectoryService.resolveGenerationV2Location(
                        agentDescriptor, record.projectKey(), record.generation());
        if (record.rootLocation() == null
                || !record.rootLocation().equals(location.rootRelativePath())) {
            throw new IllegalStateException("Generation location assertion mismatch");
        }
        return location;
    }

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
