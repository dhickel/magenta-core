package io.mindspice.magenta2.ai.orchestration.context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.BindingCaptureOperation;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.BindingOwnerLink;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextDefault;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextOperationStatus;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.OwnerLinkOperation;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.AssignmentSubmissionOperation;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationRecord;
import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationState;
import io.mindspice.magenta2.ai.orchestration.workspaces.MaterializationLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captures immutable agent-context bindings and resolves them without
 * substituting defaults, paths, or fallback locations. Every retriable binding
 * capture and owner link has a durable operation key and canonical digest.
 */
@Service
public class AgentContextService {

    private static final Logger log = LoggerFactory.getLogger(AgentContextService.class);
    private static final String BINDING_CAPTURE = "BINDING_CAPTURE";
    private static final String OWNER_LINK = "OWNER_LINK";

    private final AgentContextRepository contextRepository;
    private final AgentMaterializationRepository materializationRepository;
    private final AgentMaterializationService materializationService;
    private final WorkspaceDirectoryService workspaceDirectoryService;

    public AgentContextService(AgentContextRepository contextRepository,
                               AgentMaterializationRepository materializationRepository,
                               AgentMaterializationService materializationService,
                               WorkspaceDirectoryService workspaceDirectoryService) {
        this.contextRepository = Objects.requireNonNull(contextRepository);
        this.materializationRepository = Objects.requireNonNull(materializationRepository);
        this.materializationService = Objects.requireNonNull(materializationService);
        this.workspaceDirectoryService = Objects.requireNonNull(workspaceDirectoryService);
    }

    // ── Defaults ──

    @Transactional
    public ContextDefault initializeDefault(String agentId, MaterializationKey materializationKey,
                                            int materializationGeneration, String reason) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(materializationKey, "materializationKey");
        if (agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        requireReadyMaterialization(agentId, materializationKey, materializationGeneration);

        Instant now = Instant.now();
        ContextDefault defaultRecord = new ContextDefault(agentId, 1, SelectionKind.MATERIALIZATION,
                materializationKey, materializationGeneration, null, reason, now, now);
        contextRepository.insertDefault(defaultRecord);
        return contextRepository.findDefaultByAgent(agentId).orElseThrow();
    }

    @Transactional
    public boolean updateDefault(String agentId, int expectedVersion, MaterializationKey materializationKey,
                                 int materializationGeneration, String reason) {
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(materializationKey);
        requireReadyMaterialization(agentId, materializationKey, materializationGeneration);
        Instant now = Instant.now();
        return contextRepository.updateDefault(agentId, expectedVersion,
                new ContextDefault(agentId, expectedVersion + 1, SelectionKind.MATERIALIZATION,
                        materializationKey, materializationGeneration, null, reason, now, now));
    }

    @Transactional
    public ContextDefault initializeDefaultToPersonal(String agentId, PersonalSelection personalSelection,
                                                      String reason) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(personalSelection, "personalSelection");
        if (agentId.isBlank()) throw new IllegalArgumentException("agentId is required");
        Instant now = Instant.now();
        ContextDefault defaultRecord = new ContextDefault(agentId, 1, SelectionKind.PERSONAL,
                null, null, personalSelection, reason, now, now);
        contextRepository.insertDefault(defaultRecord);
        return contextRepository.findDefaultByAgent(agentId).orElseThrow();
    }

    @Transactional
    public boolean updateDefaultToPersonal(String agentId, int expectedVersion,
                                           PersonalSelection personalSelection, String reason) {
        Objects.requireNonNull(agentId);
        Objects.requireNonNull(personalSelection);
        Instant now = Instant.now();
        return contextRepository.updateDefault(agentId, expectedVersion,
                new ContextDefault(agentId, expectedVersion + 1, SelectionKind.PERSONAL,
                        null, null, personalSelection, reason, now, now));
    }

    public ContextLookup<ContextDefault> getDefault(String agentId) {
        ContextDefault defaultRecord = contextRepository.findDefaultByAgent(agentId).orElse(null);
        return defaultRecord == null
                ? ContextLookup.unavailable() : ContextLookup.available(defaultRecord);
    }

    /** Returns the complete secret-free read model required by context web adapters. */
    public ContextSnapshot snapshot(String agentId) {
        return new ContextSnapshot(getDefault(agentId).value(), listBindings(agentId),
                materializationRepository.findByAgent(agentId));
    }

    public List<MaterializationRecord> materializations(String agentId) {
        return materializationRepository.findByAgent(agentId);
    }

    public List<MaterializationRecord> readyMaterializations(String agentId) {
        return materializationRepository.findByAgentAndState(agentId, MaterializationState.READY);
    }

    /**
     * Applies a context default exactly once for the requested version. A retry
     * with the same canonical request returns {@code REPLAYED}; a divergent
     * retry or stale version is a conflict. The optional operation key is
     * validated at this service boundary rather than ignored by the web layer.
     */
    @Transactional
    public DefaultUpdateResult updateDefault(DefaultUpdateRequest request) {
        Objects.requireNonNull(request, "request");
        requireOperationKey(request.operationKey());
        ContextDefault current = contextRepository.findDefaultByAgent(request.agentId()).orElse(null);
        if (current == null) {
            if (request.expectedVersion() != 0) {
                throw new OperationConflictException("Context default version is stale");
            }
            ContextDefault created = requestedDefault(request, 1);
            try {
                contextRepository.insertDefault(created);
                return new DefaultUpdateResult(ContextOperationStatus.CREATED, created);
            } catch (RuntimeException race) {
                ContextDefault replay = contextRepository.findDefaultByAgent(request.agentId()).orElse(null);
                if (sameDefault(replay, request) && replay.version() == 1) {
                    return new DefaultUpdateResult(ContextOperationStatus.REPLAYED, replay);
                }
                throw race;
            }
        }
        if (sameDefault(current, request) && current.version() == request.expectedVersion() + 1) {
            return new DefaultUpdateResult(ContextOperationStatus.REPLAYED, current);
        }
        if (current.version() != request.expectedVersion()) {
            throw new OperationConflictException("Context default version is stale");
        }
        ContextDefault replacement = requestedDefault(request, current.version() + 1);
        if (contextRepository.updateDefault(request.agentId(), request.expectedVersion(), replacement)) {
            return new DefaultUpdateResult(ContextOperationStatus.CREATED, replacement);
        }
        ContextDefault replay = contextRepository.findDefaultByAgent(request.agentId()).orElse(null);
        if (sameDefault(replay, request) && replay.version() == request.expectedVersion() + 1) {
            return new DefaultUpdateResult(ContextOperationStatus.REPLAYED, replay);
        }
        throw new OperationConflictException("Context default update conflicted");
    }

    /** Resolves the descriptor and materialization lifecycle inside the service boundary. */
    public MaterializationLocation provisionProject(String agentId, String projectId, String operationKey) {
        operationKey(agentId, projectId, operationKey);
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, agentId);
        MaterializationRecord record = materializationService.registerProject(descriptor, projectId);
        MaterializationLocation legacyLocation = materializationService.provision(descriptor, record.materializationKey());
        // Existing binding semantics retain their exact V1 location. A project
        // template, when present, additionally materializes an isolated V2
        // Files generation without retargeting that historical binding.
        materializationService.provisionLatestGenerationIfTemplate(descriptor, record.materializationKey());
        return legacyLocation;
    }

    private ContextDefault requestedDefault(DefaultUpdateRequest request, int version) {
        Instant now = Instant.now();
        if (request.selectionKind() == SelectionKind.MATERIALIZATION) {
            Objects.requireNonNull(request.materializationKey(), "materializationKey");
            Objects.requireNonNull(request.materializationGeneration(), "materializationGeneration");
            requireReadyMaterialization(request.agentId(), request.materializationKey(), request.materializationGeneration());
            return new ContextDefault(request.agentId(), version, SelectionKind.MATERIALIZATION,
                    request.materializationKey(), request.materializationGeneration(), null,
                    request.reason(), now, now);
        }
        if (request.selectionKind() == SelectionKind.PERSONAL) {
            Objects.requireNonNull(request.personalSelection(), "personalSelection");
            return new ContextDefault(request.agentId(), version, SelectionKind.PERSONAL,
                    null, null, request.personalSelection(), request.reason(), now, now);
        }
        throw new IllegalArgumentException("Context selection kind is required");
    }

    private static boolean sameDefault(ContextDefault current, DefaultUpdateRequest request) {
        return current != null && current.selectionKind() == request.selectionKind()
                && Objects.equals(current.materializationKey(), request.materializationKey())
                && Objects.equals(current.materializationGeneration(), request.materializationGeneration())
                && current.personalSelection() == request.personalSelection()
                && Objects.equals(current.reason(), request.reason());
    }

    private static void requireOperationKey(String operationKey) {
        if (operationKey != null && operationKey.length() > 255) {
            throw new IllegalArgumentException("Operation key exceeds 255 characters");
        }
    }

    private static String operationKey(String agentId, String projectId, String operationKey) {
        requireOperationKey(operationKey);
        return operationKey == null || operationKey.isBlank()
                ? "context-materialization:" + agentId + ":" + projectId : operationKey.trim();
    }

    // ── Binding capture ──

    /** Compatibility entry point: the immutable binding key is its operation key. */
    @Transactional
    public ContextBinding captureFromDefault(String agentId, String bindingKey) {
        return captureFromDefault(agentId, bindingKey, bindingKey);
    }

    /** Capture the default selected at this operation boundary exactly once. */
    @Transactional
    public ContextBinding captureFromDefault(String agentId, String bindingKey, String operationKey) {
        String requestDigest = digest(BINDING_CAPTURE, agentId, bindingKey, "DEFAULT");
        ContextBinding replay = replayBindingCapture(operationKey, requestDigest);
        if (replay != null) return replay;
        ContextDefault defaultRecord = contextRepository.findDefaultByAgent(agentId)
                .orElseThrow(() -> new IllegalArgumentException("No context default exists for agent " + agentId));
        return capture(operationKey, requestDigest, agentId, bindingKey, defaultRecord.selectionKind(),

                defaultRecord.materializationKey(), defaultRecord.materializationGeneration(),
                defaultRecord.personalSelection());
    }

    /**
     * Captures a binding on every assignment/chat boundary. An agent without a
     * configured project default uses the server-owned personal HOME selection;
     * it never leaves the owner unbound or accepts caller context facts.
     */
    @Transactional
    public ContextBinding captureDefaultOrPersonalHome(String agentId, String bindingKey, String operationKey) {
        if (contextRepository.findDefaultByAgent(agentId).isPresent()) {
            return captureFromDefault(agentId, bindingKey, operationKey);
        }
        return capturePersonal(agentId, bindingKey, PersonalSelection.HOME, operationKey);
    }

    /** Compatibility entry point: the immutable binding key is its operation key. */
    @Transactional
    public ContextBinding captureMaterialization(String agentId, String bindingKey,
                                                  MaterializationKey materializationKey,
                                                  int materializationGeneration) {
        return captureMaterialization(agentId, bindingKey, materializationKey,
                materializationGeneration, bindingKey);
    }

    @Transactional
    public ContextBinding captureMaterialization(String agentId, String bindingKey,
                                                  MaterializationKey materializationKey,
                                                  int materializationGeneration,
                                                  String operationKey) {
        Objects.requireNonNull(materializationKey, "materializationKey");
        String requestDigest = digest(BINDING_CAPTURE, agentId, bindingKey, "MATERIALIZATION",
                materializationKey.serialized(), Integer.toString(materializationGeneration));
        ContextBinding replay = replayBindingCapture(operationKey, requestDigest);
        if (replay != null) return replay;
        requireReadyMaterialization(agentId, materializationKey, materializationGeneration);
        return capture(operationKey, requestDigest, agentId, bindingKey,
                SelectionKind.MATERIALIZATION, materializationKey, materializationGeneration, null);
    }

    /** Compatibility entry point: the immutable binding key is its operation key. */
    @Transactional
    public ContextBinding capturePersonal(String agentId, String bindingKey,
                                          PersonalSelection personalSelection) {
        return capturePersonal(agentId, bindingKey, personalSelection, bindingKey);
    }

    @Transactional
    public ContextBinding capturePersonal(String agentId, String bindingKey,
                                          PersonalSelection personalSelection, String operationKey) {
        Objects.requireNonNull(personalSelection, "personalSelection");
        String requestDigest = digest(BINDING_CAPTURE, agentId, bindingKey,
                "PERSONAL", personalSelection.name());
        ContextBinding replay = replayBindingCapture(operationKey, requestDigest);
        if (replay != null) return replay;
        return capture(operationKey, requestDigest, agentId, bindingKey,
                SelectionKind.PERSONAL, null, null, personalSelection);
    }

    private ContextBinding capture(String operationKey, String requestDigest, String agentId,
                                   String bindingKey, SelectionKind selectionKind,
                                   MaterializationKey materializationKey,
                                   Integer materializationGeneration,
                                   PersonalSelection personalSelection) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(bindingKey, "bindingKey");
        Objects.requireNonNull(operationKey, "operationKey");
        ContextBinding binding = ContextBinding.forInsert(agentId, bindingKey, selectionKind,
                materializationKey, materializationGeneration, personalSelection, Instant.now());
        BindingCaptureOperation operation = contextRepository.captureBindingIdempotently(
                operationKey, requestDigest, binding);
        if (operation.status() == ContextOperationStatus.CONFLICT) {
            throw new OperationConflictException("Binding capture operation key was reused with a different request");
        }
        return operation.binding();
    }

    private ContextBinding replayBindingCapture(String operationKey, String requestDigest) {
        return contextRepository.findBindingCaptureOperation(operationKey, requestDigest)
                .map(operation -> {
                    if (operation.status() == ContextOperationStatus.CONFLICT) {
                        throw new OperationConflictException(
                                "Binding capture operation key was reused with a different request");
                    }
                    return operation.binding();
                }).orElse(null);
    }

    // ── Assignment submission operation ──

    @Transactional
    public AssignmentSubmissionOperation beginAssignmentSubmission(String operationKey, String requestDigest) {
        return contextRepository.beginAssignmentSubmission(operationKey, requestDigest);
    }

    @Transactional
    public void completeAssignmentSubmission(String operationKey, String requestDigest,
                                             String bindingKey, String assignmentId, String taskRunId) {
        contextRepository.completeAssignmentSubmission(operationKey, requestDigest,
                bindingKey, assignmentId, taskRunId);
    }

    // ── Binding resolution ──

    /**
     * Resolves only the captured materialization snapshot. Missing context is
     * {@code UNAVAILABLE}; a changed generation or invalid ready assertion is
     * {@code STALE}; provisioning, failed, and revoked state is
     * {@code NON_READY}. No result falls back to an agent root or a default.
     */
    public BindingResolution resolveBinding(WorkspaceDescriptor agentDescriptor, String bindingKey) {
        Objects.requireNonNull(agentDescriptor, "agentDescriptor");
        Objects.requireNonNull(bindingKey, "bindingKey");
        if (agentDescriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException("Binding resolution requires an AGENT descriptor");
        }

        ContextBinding binding = contextRepository.findByBindingKey(bindingKey).orElse(null);
        return resolveBinding(agentDescriptor, binding);
    }

    /**
     * Resolve an already captured binding exactly once. Callers that need the
     * binding owner to select an independent execution descriptor must use this
     * overload rather than look it up again through the binding key.
     */
    public BindingResolution resolveBinding(WorkspaceDescriptor agentDescriptor, ContextBinding binding) {
        Objects.requireNonNull(agentDescriptor, "agentDescriptor");
        if (agentDescriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException("Binding resolution requires an AGENT descriptor");
        }
        if (binding == null || !binding.agentId().equals(agentDescriptor.key())) {
            return BindingResolution.unavailable(binding);
        }
        if (binding.selectionKind() == SelectionKind.PERSONAL) {
            return BindingResolution.resolved(binding, null);
        }
        if (binding.selectionKind() != SelectionKind.MATERIALIZATION) {
            return BindingResolution.unavailable(binding);
        }

        MaterializationRecord record = materializationRepository.findByAgentAndKey(
                binding.agentId(), binding.materializationKey()).orElse(null);
        if (record == null) {
            return BindingResolution.unavailable(binding);
        }
        if (record.generation() != binding.materializationGeneration()) {
            return BindingResolution.stale(binding);
        }
        if (record.state() != MaterializationState.READY) {
            return BindingResolution.nonReady(binding);
        }
        try {
            MaterializationLocation location = materializationService.resolveReadyLocation(agentDescriptor,
                    binding.materializationKey(), binding.materializationGeneration());
            return BindingResolution.resolved(binding, location);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            log.debug("Materialization binding is stale during trusted location resolution");
            return BindingResolution.stale(binding);
        }
    }

    public ContextLookup<ContextBinding> getBinding(String bindingKey) {
        ContextBinding binding = contextRepository.findByBindingKey(bindingKey).orElse(null);
        return binding == null ? ContextLookup.unavailable() : ContextLookup.available(binding);
    }

    /** List all bindings for an agent (read-only). */
    public List<ContextBinding> listBindings(String agentId) {
        return contextRepository.findBindingsByAgent(agentId);
    }

    // ── Owner links ──

    /** Compatibility entry point using a deterministic internal operation key. */
    @Transactional
    public boolean linkOwner(String ownerType, String ownerId, String bindingKey) {
        return linkOwner(ownerType, ownerId, bindingKey,
                "owner-link-" + digest(OWNER_LINK, ownerType, ownerId)).status()
                == ContextOperationStatus.CREATED;
    }

    @Transactional
    public OwnerLinkOperation linkOwner(String ownerType, String ownerId, String bindingKey,
                                        String operationKey) {
        Objects.requireNonNull(ownerType, "ownerType");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(bindingKey, "bindingKey");
        Objects.requireNonNull(operationKey, "operationKey");
        String requestDigest = digest(OWNER_LINK, ownerType, ownerId, bindingKey);
        OwnerLinkOperation replay = contextRepository.findOwnerLinkOperation(operationKey, requestDigest)
                .orElse(null);
        if (replay != null) {
            if (replay.status() == ContextOperationStatus.CONFLICT) {
                throw new OperationConflictException("Owner-link operation key was reused with a different request");
            }
            return replay;
        }
        contextRepository.findByBindingKey(bindingKey)
                .orElseThrow(() -> new IllegalArgumentException("Binding not found: " + bindingKey));

        OwnerLinkOperation operation = contextRepository.linkOwnerIdempotently(operationKey, requestDigest,
                new BindingOwnerLink(ownerType, ownerId, bindingKey, Instant.now()));
        if (operation.status() == ContextOperationStatus.CONFLICT) {
            throw new OperationConflictException("Owner-link operation key was reused with a different request");
        }
        return operation;
    }

    public ContextLookup<BindingOwnerLink> getOwnerLink(String ownerType, String ownerId) {
        BindingOwnerLink link = contextRepository.findOwnerLink(ownerType, ownerId).orElse(null);
        return link == null ? ContextLookup.unavailable() : ContextLookup.available(link);
    }

    private void requireReadyMaterialization(String agentId, MaterializationKey materializationKey,
                                             int generation) {
        if (!agentId.equals(materializationKey.agentId())) {
            throw new IllegalArgumentException("Materialization key does not belong to the agent");
        }
        MaterializationRecord record = materializationRepository.findByAgentAndKey(agentId, materializationKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Materialization not found: " + materializationKey.serialized()));
        if (record.state() != MaterializationState.READY) {
            throw new IllegalStateException("Materialization is not READY: " + record.state());
        }
        if (record.generation() != generation) {
            throw new IllegalStateException("Materialization generation mismatch: expected " + generation
                    + " but found " + record.generation());
        }
    }

    private static String digest(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = Objects.requireNonNull(field, "operation digest field")
                        .getBytes(StandardCharsets.UTF_8);
                if (bytes.length > 0xffff) {
                    throw new IllegalArgumentException("Operation field exceeds canonical length");
                }
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record ContextSnapshot(ContextDefault defaultRecord, List<ContextBinding> bindings,
                                  List<MaterializationRecord> materializations) { }

    public record DefaultUpdateRequest(String agentId, int expectedVersion, SelectionKind selectionKind,
                                       MaterializationKey materializationKey, Integer materializationGeneration,
                                       PersonalSelection personalSelection, String reason, String operationKey) {
        public DefaultUpdateRequest {
            if (agentId == null || agentId.isBlank() || expectedVersion < 0 || selectionKind == null) {
                throw new IllegalArgumentException("Context default update is incomplete or invalid");
            }
        }
    }

    public record DefaultUpdateResult(ContextOperationStatus status, ContextDefault defaultRecord) { }

    public enum ContextOutcome { AVAILABLE, UNAVAILABLE, STALE, NON_READY }

    public record ContextLookup<T>(ContextOutcome outcome, T value) {
        static <T> ContextLookup<T> available(T value) {
            return new ContextLookup<>(ContextOutcome.AVAILABLE, Objects.requireNonNull(value));
        }
        static <T> ContextLookup<T> unavailable() {
            return new ContextLookup<>(ContextOutcome.UNAVAILABLE, null);
        }
    }

    public record BindingResolution(ContextOutcome outcome, ContextBinding binding,
                                    MaterializationLocation location) {
        public static BindingResolution resolved(ContextBinding binding, MaterializationLocation location) {
            return new BindingResolution(ContextOutcome.AVAILABLE, binding, location);
        }
        static BindingResolution unavailable(ContextBinding binding) {
            return new BindingResolution(ContextOutcome.UNAVAILABLE, binding, null);
        }
        static BindingResolution stale(ContextBinding binding) {
            return new BindingResolution(ContextOutcome.STALE, binding, null);
        }
        static BindingResolution nonReady(ContextBinding binding) {
            return new BindingResolution(ContextOutcome.NON_READY, binding, null);
        }
    }

    public static final class OperationConflictException extends IllegalStateException {
        public OperationConflictException(String message) {
            super(message);
        }
    }
}
