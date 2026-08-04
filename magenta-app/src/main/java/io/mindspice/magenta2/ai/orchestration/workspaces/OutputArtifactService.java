package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.TaskRunLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Publishes output artifacts through trusted run-local output locations with
 * create-only final placement, persisted idempotency, integrity facts, and
 * typed task-run / chat entry points.
 *
 * <h3>Publication contract</h3>
 * <ul>
 *   <li>Task publication accepts a trusted {@link RunOutputLocation} and
 *       live {@link TaskRunWriteCapability}; never an arbitrary path.</li>
 *   <li>Chat publication is confined to {@code workspace/chat/<id>/outputs/}.</li>
 *   <li>Same operation key + same request digest ⇒ resume/return recorded artifact.</li>
 *   <li>Same key + different digest ⇒ conflict.</li>
 *   <li>Final placement is create-only; never {@code REPLACE_EXISTING}.</li>
 *   <li>Published bytes are immutable through application mutation APIs.</li>
 * </ul>
 */
@Service
public class OutputArtifactService {
    private static final Logger log = LoggerFactory.getLogger(OutputArtifactService.class);
    /** Digest version byte for publication request canonical encoding. */
    private static final byte DIGEST_VERSION = 2;
    private static final int MAX_SUFFIX_ATTEMPTS = 1_000;
    private static final Pattern SAFE_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    private final OutputArtifactRepository repository;
    private final WorkspaceDirectoryService directoryService;
    private final RootRelativePathService rootRelativePathService;
    private final ConfinedPathMutationService mutations;
    private final ObjectMapper objectMapper;
    private final TaskRunLifecycleService taskRunLifecycleService;

    /** Legacy constructor kept for callers that do not inject ConfinedPathMutationService. */
    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 RootRelativePathService rootRelativePathService,
                                 ObjectMapper objectMapper) {
        this(repository, directoryService, rootRelativePathService,
                new ConfinedPathMutationService(), objectMapper, null);
    }

    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 RootRelativePathService rootRelativePathService,
                                 ConfinedPathMutationService mutations,
                                 ObjectMapper objectMapper) {
        this(repository, directoryService, rootRelativePathService, mutations, objectMapper, null);
    }

    @Autowired
    public OutputArtifactService(OutputArtifactRepository repository,
                                 WorkspaceDirectoryService directoryService,
                                 RootRelativePathService rootRelativePathService,
                                 ConfinedPathMutationService mutations,
                                 ObjectMapper objectMapper,
                                 @Autowired(required = false) TaskRunLifecycleService taskRunLifecycleService) {
        this.repository = repository;
        this.directoryService = directoryService;
        this.rootRelativePathService = rootRelativePathService;
        this.mutations = mutations;
        this.objectMapper = objectMapper;
        this.taskRunLifecycleService = taskRunLifecycleService;
    }

    // ── Typed publication entry points ──

    /**
     * Publish bytes as a task-run artifact. Accepts only a trusted
     * {@link RunOutputLocation} and a live {@link TaskRunWriteCapability}.
     *
     * @param location    the trusted run-local output location
     * @param capability  the live writer capability (fence-checked at every mutation)
     * @param operationKey the caller's unique idempotency key
     * @param logicalName  the desired display name (extension included)
     * @param mediaType    the MIME media type or content hint
     * @param bytes        the content to publish
     * @param nativeRunId  the native run id for attribution
     * @param attemptNumber the current attempt number
     * @return the publication record in its current safe state
     */
    public PublicationRecord publishTaskRunBytes(
            RunOutputLocation location,
            TaskRunWriteCapability capability,
            String operationKey,
            String logicalName,
            String mediaType,
            byte[] bytes,
            String nativeRunId,
            int attemptNumber) throws IOException {
        requireLocation(location, "location");
        requireCapability(capability, "capability");
        requireText(operationKey, "operationKey");
        requireText(logicalName, "logicalName");
        if (bytes == null) bytes = new byte[0];

        requireCurrentWriter(location, capability);

        String contentSha256 = sha256Hex(bytes);
        String requestDigest = buildRequestDigest(
                PublicationOrigin.TASK_RUN, location, capability,
                logicalName, contentSha256, mediaType);

        // Idempotency: check for existing operation
        PublicationRecord existing = repository.findByOperationKey(
                PublicationOrigin.TASK_RUN, operationKey).orElse(null);
        if (existing != null) {
            if (!requestDigest.equals(existing.requestDigest())) {
                throw new TaskRunLifecycleService.OperationConflictException(
                        "Operation key " + operationKey + " reused with different input");
            }
            // Same digest: return or resume
            if (existing.publicationState() == PublicationState.PUBLISHED
                    && existing.isReadable()) {
                return existing;
            }
            return resumeExisting(location, capability, existing, bytes, contentSha256, mediaType);
        }

        return publishNew(location, capability, operationKey, requestDigest,
                logicalName, mediaType, bytes, contentSha256, nativeRunId, attemptNumber);
    }

    /**
     * Publish a source file as a task-run artifact. The source must be confined
     * beneath the data root. The file is copied into the run output location.
     */
    public PublicationRecord publishTaskRunFile(
            RunOutputLocation location,
            TaskRunWriteCapability capability,
            String operationKey,
            String logicalName,
            String mediaType,
            Path sourcePath,
            String nativeRunId,
            int attemptNumber) throws IOException {
        requireLocation(location, "location");
        requireCapability(capability, "capability");
        requireText(operationKey, "operationKey");
        if (sourcePath == null) throw new IllegalArgumentException("sourcePath is required");

        requireCurrentWriter(location, capability);

        Path realSource = requireConfinedRealSource(sourcePath);
        byte[] bytes = Files.readAllBytes(realSource);
        if (logicalName == null || logicalName.isBlank()) {
            logicalName = realSource.getFileName().toString();
        }

        return publishTaskRunBytes(location, capability, operationKey,
                logicalName, mediaType, bytes, nativeRunId, attemptNumber);
    }

    /**
     * Publish bytes as a chat (non-task) artifact. Confined to the chat
     * workspace's {@code outputs/} directory.
     */
    public PublicationRecord publishChatBytes(
            String conversationId,
            String operationKey,
            String logicalName,
            String mediaType,
            byte[] bytes) throws IOException {
        requireText(conversationId, "conversationId");
        requireText(operationKey, "operationKey");
        requireText(logicalName, "logicalName");
        if (bytes == null) bytes = new byte[0];

        WorkspaceDescriptor descriptor = directoryService.resolveForWrite(
                WorkspaceKind.CHAT, conversationId);
        Path outputsDir = directoryService.outputs(descriptor);

        String contentSha256 = sha256Hex(bytes);
        String requestDigest = buildChatRequestDigest(
                conversationId, logicalName, contentSha256, mediaType);

        PublicationRecord existing = repository.findByOperationKey(
                PublicationOrigin.CHAT, operationKey).orElse(null);
        if (existing != null) {
            if (!requestDigest.equals(existing.requestDigest())) {
                throw new TaskRunLifecycleService.OperationConflictException(
                        "Operation key " + operationKey + " reused with different input");
            }
            if (existing.publicationState() == PublicationState.PUBLISHED) {
                return existing;
            }
            return resumeChatPublication(descriptor, existing, bytes, contentSha256, mediaType);
        }

        return publishChatNew(descriptor, outputsDir, operationKey, requestDigest,
                logicalName, mediaType, bytes, contentSha256);
    }

    // ── Idempotency helpers ──

    private PublicationRecord resumeExisting(
            RunOutputLocation location, TaskRunWriteCapability capability,
            PublicationRecord existing, byte[] bytes, String contentSha256,
            String mediaType) throws IOException {
        return switch (existing.publicationState()) {
            case RESERVED -> {
                // Reservation exists but staging may need completion
                yield stageThenPromote(location, capability, existing, bytes, contentSha256, mediaType);
            }
            case STAGED -> {
                // Staged but not yet promoted
                yield promoteToFinal(location, capability, existing, bytes, contentSha256, mediaType);
            }
            case PUBLISHED -> existing; // Already done
            case FAILED -> throw new IllegalStateException(
                    "Publication already failed: " + existing.failureDetail());
            case INTEGRITY_FAILED -> throw new IllegalStateException(
                    "Publication integrity failed; requires reconciliation");
        };
    }

    private PublicationRecord publishNew(
            RunOutputLocation location, TaskRunWriteCapability capability,
            String operationKey, String requestDigest,
            String logicalName, String mediaType,
            byte[] bytes, String contentSha256,
            String nativeRunId, int attemptNumber) throws IOException {
        String normalized = normalizeLogicalName(logicalName);
        String artifactId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<PublicationRecord> reservations = new ArrayList<>();
        for (int suffix = 0; suffix < MAX_SUFFIX_ATTEMPTS; suffix++) {
            String finalName = suffixName(normalized, suffix);
            Path finalPath = location.confinedPath().resolve(finalName);
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) continue;
            reservations.add(PublicationRecord.builder()
                    .artifactId(artifactId)
                    .publicationOrigin(PublicationOrigin.TASK_RUN)
                    .workspaceKind(location.workspaceKind().token())
                    .workspaceKey(location.workspaceKey())
                    .workspaceIdentity(location.workspaceIdentity())
                    .taskRunId(location.taskRunId())
                    .taskPathKey(location.taskPathKey().serialized())
                    .nativeRunId(nativeRunId)
                    .attemptNumber(attemptNumber)
                    .rootRelativePath(location.rootRelativePath() + "/" + finalName)
                    .operationKey(operationKey)
                    .requestDigest(requestDigest)
                    .contentSha256(contentSha256)
                    .publicationState(PublicationState.RESERVED)
                    .expectedSha256(contentSha256)
                    .logicalName(finalName)
                    .fileExtension(extractExtension(finalName))
                    .mediaType(mediaType)
                    .writerOwnerId(capability.ownerId())
                    .fenceGeneration(capability.fenceGeneration())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        PublicationRecord record = repository.reserveFirstAvailable(reservations);
        if (!requestDigest.equals(record.requestDigest())) {
            throw new TaskRunLifecycleService.OperationConflictException(
                    "Operation key " + operationKey + " reused with different input");
        }
        return resumeExisting(location, capability, record, bytes, contentSha256, mediaType);
    }

    // ── Staging and promotion ──

    private PublicationRecord stageThenPromote(
            RunOutputLocation location, TaskRunWriteCapability capability,
            PublicationRecord record, byte[] bytes, String contentSha256,
            String mediaType) throws IOException {
        requireCurrentWriter(location, capability);

        // Determine staging path
        String stagingRel = record.stagingPath() != null ? record.stagingPath()
                : location.rootRelativePath() + "/.staging-" + record.artifactId();

        Path confinedOutputDir = location.confinedPath();
        Path stagingFile = confinedOutputDir.resolve(Path.of(stagingRel).getFileName().toString());
        String stagingRelative = dataRootRelative(stagingFile);
        if (record.stagingPath() == null && !repository.updateStagingPath(record.artifactId(),
                capability.ownerId(), capability.fenceGeneration(), stagingRelative)) {
            throw new TaskRunLifecycleService.StaleWriterException(
                    "Writer capability is stale while recording staging ownership");
        }
        requireCurrentWriter(location, capability);
        if (!Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
            mutations.write(directoryService.dataRoot(), stagingRelative, bytes,
                    ConfinedPathMutationService.WriteMode.CREATE_NEW, true);
        }

        // Verify staged bytes
        byte[] staged = mutations.read(directoryService.dataRoot(),
                dataRootRelative(stagingFile));
        String stagedSha = sha256Hex(staged);
        if (!contentSha256.equals(stagedSha)) {
            throw new IllegalStateException(
                    "Staged content digest mismatch for " + record.artifactId());
        }

        long stagedSize = staged.length;

        requireCurrentWriter(location, capability);
        boolean updated = repository.updateStagedFacts(record.artifactId(),
                capability.ownerId(), capability.fenceGeneration(), stagingRelative,
                stagedSha, stagedSize, mediaType);
        if (!updated && !repository.findByOperationKey(
                PublicationOrigin.TASK_RUN, record.operationKey())
                .map(r -> r.publicationState() == PublicationState.STAGED)
                .orElse(false)) {
            throw new TaskRunLifecycleService.StaleWriterException(
                    "Writer capability is stale for staging " + record.artifactId());
        }

        // Update the in-memory state
        record = repository.findById(record.artifactId()).orElse(record);

        return promoteToFinal(location, capability, record, bytes, contentSha256, mediaType);
    }

    private PublicationRecord promoteToFinal(
            RunOutputLocation location, TaskRunWriteCapability capability,
            PublicationRecord record, byte[] bytes, String contentSha256,
            String mediaType) throws IOException {
        if (record.publicationState() == PublicationState.PUBLISHED) {
            return record;
        }
        requireCurrentWriter(location, capability);

        String finalRelative = record.rootRelativePath();
        Path finalFile = rootRelativePathService.resolve(finalRelative);
        Path confinedOutputDir = location.confinedPath();

        // Create-only final placement: atomic move from staging if possible,
        // or write through confined mutation
        if (record.stagingPath() != null) {
            Path stagingFile = rootRelativePathService.resolve(record.stagingPath());
            if (Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    // Try same-filesystem atomic move with create-only semantics
                    mutations.move(directoryService.dataRoot(),
                            dataRootRelative(stagingFile),
                            dataRootRelative(finalFile));
                    // Successfully moved
                } catch (FileAlreadyExistsException e) {
                    // Final path already occupied — collision that must not be overwritten
                    markFailed(record, "Final path collision during promotion",
                            "UNRESOLVED");
                    throw new FileAlreadyExistsException(
                            "Final artifact path already exists: " + finalRelative);
                } catch (IOException e) {
                    // Fallback: write through confined mutation (create-new only)
                    boolean finalExists = Files.exists(finalFile, LinkOption.NOFOLLOW_LINKS);
                    if (finalExists) {
                        markFailed(record, "Final path collision during fallback promotion",
                                "UNRESOLVED");
                        throw new FileAlreadyExistsException(
                                "Final artifact path already exists: " + finalRelative);
                    }
                    mutations.write(directoryService.dataRoot(),
                            dataRootRelative(finalFile),
                            bytes,
                            ConfinedPathMutationService.WriteMode.CREATE_NEW,
                            true);
                }
            } else {
                // Staging file missing: write bytes directly to final
                mutations.write(directoryService.dataRoot(),
                        dataRootRelative(finalFile),
                        bytes,
                        ConfinedPathMutationService.WriteMode.CREATE_NEW,
                        true);
            }
        } else {
            // No staging record: write bytes directly to final (create-only)
            mutations.write(directoryService.dataRoot(),
                    dataRootRelative(finalFile),
                    bytes,
                    ConfinedPathMutationService.WriteMode.CREATE_NEW,
                    true);
        }

        // Verify final bytes
        byte[] finalBytes = mutations.read(directoryService.dataRoot(),
                dataRootRelative(finalFile));
        String finalSha = sha256Hex(finalBytes);
        long finalSize = finalBytes.length;
        String finalMedia = mediaType != null ? mediaType : probeMediaType(finalRelative);

        if (!contentSha256.equals(finalSha)) {
            markFailed(record, "Final byte verification failed: digest mismatch", "UNRESOLVED");
            throw new IllegalStateException("Final bytes do not match expected digest for "
                    + record.artifactId());
        }

        // The database transition is guarded by the same live owner/fence as placement.
        requireCurrentWriter(location, capability);
        final PublicationRecord finalRecord = record;
        if (!repository.compareAndSetState(
                finalRecord.artifactId(), PublicationState.STAGED.name(),
                PublicationState.PUBLISHED.name(),
                capability.ownerId(), capability.fenceGeneration(),
                null, finalSha, finalSize, finalMedia,
                "RECONCILED")) {
            throw new TaskRunLifecycleService.StaleWriterException(
                    "Writer capability became stale before publication commit");
        }
        PublicationRecord published = repository.findById(finalRecord.artifactId())
                .orElseThrow(() -> new IllegalStateException("Publication record lost: "
                        + finalRecord.artifactId()));

        // Clean up staging file if it still exists
        String stagingRel = published.stagingPath();
        if (stagingRel != null) {
            Path stagingFile = rootRelativePathService.resolve(stagingRel);
            try {
                if (Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
                    requireCurrentWriter(location, capability);
                    mutations.delete(directoryService.dataRoot(),
                            dataRootRelative(stagingFile), false);
                }
            } catch (IOException e) {
                log.warn("Failed to clean staging file: {}", stagingRel, e);
            }
        }

        return published;
    }

    private PublicationRecord publishChatNew(
            WorkspaceDescriptor descriptor, Path outputsDir,
            String operationKey, String requestDigest,
            String logicalName, String mediaType,
            byte[] bytes, String contentSha256) throws IOException {
        String normalized = normalizeLogicalName(logicalName);
        String artifactId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        List<PublicationRecord> reservations = new ArrayList<>();
        for (int suffix = 0; suffix < MAX_SUFFIX_ATTEMPTS; suffix++) {
            String finalName = suffixName(normalized, suffix);
            Path finalPath = outputsDir.resolve(finalName);
            if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) continue;
            reservations.add(PublicationRecord.builder()
                    .artifactId(artifactId)
                    .publicationOrigin(PublicationOrigin.CHAT)
                    .workspaceKind(WorkspaceKind.CHAT.token())
                    .workspaceKey(descriptor.key())
                    .workspaceIdentity(WorkspaceKind.CHAT.token() + "/" + descriptor.key())
                    .rootRelativePath(dataRootRelative(finalPath))
                    .operationKey(operationKey)
                    .requestDigest(requestDigest)
                    .contentSha256(contentSha256)
                    .publicationState(PublicationState.RESERVED)
                    .expectedSha256(contentSha256)
                    .logicalName(finalName)
                    .fileExtension(extractExtension(finalName))
                    .mediaType(mediaType)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }
        PublicationRecord record = repository.reserveFirstAvailable(reservations);
        if (!requestDigest.equals(record.requestDigest())) {
            throw new TaskRunLifecycleService.OperationConflictException(
                    "Operation key " + operationKey + " reused with different input");
        }
        return resumeChatPublication(descriptor, record, bytes, contentSha256, mediaType);
    }

    private PublicationRecord stageThenPromoteChat(
            WorkspaceDescriptor descriptor, Path outputsDir,
            PublicationRecord record, byte[] bytes, String contentSha256,
            String mediaType) throws IOException {
        String stagingName = ".staging-" + record.artifactId();
        Path stagingFile = outputsDir.resolve(stagingName);
        String stagingRelative = dataRootRelative(stagingFile);
        if (record.stagingPath() == null
                && !repository.updateStagingPath(record.artifactId(), null, null, stagingRelative)) {
            throw new IllegalStateException("Chat staging ownership could not be recorded");
        }
        if (!Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
            mutations.write(directoryService.dataRoot(), stagingRelative, bytes,
                    ConfinedPathMutationService.WriteMode.CREATE_NEW, true);
        }

        byte[] staged = mutations.read(directoryService.dataRoot(), stagingRelative);
        String stagedSha = sha256Hex(staged);
        long stagedSize = staged.length;

        if (!contentSha256.equals(stagedSha)) {
            throw new IllegalStateException("Staged content digest mismatch");
        }

        if (!repository.updateStagedFacts(record.artifactId(), null, null, stagingRelative,
                stagedSha, stagedSize, mediaType)) {
            throw new IllegalStateException("Chat staged facts could not be committed");
        }

        record = repository.findById(record.artifactId()).orElse(record);

        return promoteChatToFinal(descriptor, outputsDir, record, bytes, contentSha256, mediaType);
    }

    private PublicationRecord promoteChatToFinal(
            WorkspaceDescriptor descriptor, Path outputsDir,
            PublicationRecord record, byte[] bytes, String contentSha256,
            String mediaType) throws IOException {
        if (record.publicationState() == PublicationState.PUBLISHED) return record;

        String finalRelative = record.rootRelativePath();
        Path finalFile = rootRelativePathService.resolve(finalRelative);

        if (record.stagingPath() != null) {
            Path stagingFile = rootRelativePathService.resolve(record.stagingPath());
            if (Files.exists(stagingFile, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    mutations.move(directoryService.dataRoot(),
                            dataRootRelative(stagingFile),
                            dataRootRelative(finalFile));
                } catch (FileAlreadyExistsException e) {
                    markFailed(record, "Final path collision", "UNRESOLVED");
                    throw e;
                }
            } else {
                mutations.write(directoryService.dataRoot(),
                        dataRootRelative(finalFile),
                        bytes,
                        ConfinedPathMutationService.WriteMode.CREATE_NEW,
                        true);
            }
        } else {
            mutations.write(directoryService.dataRoot(),
                    dataRootRelative(finalFile),
                    bytes,
                    ConfinedPathMutationService.WriteMode.CREATE_NEW,
                    true);
        }

        byte[] finalBytes = mutations.read(directoryService.dataRoot(),
                dataRootRelative(finalFile));
        String finalSha = sha256Hex(finalBytes);
        long finalSize = finalBytes.length;
        String finalMedia = mediaType != null ? mediaType : probeMediaType(finalRelative);

        if (!contentSha256.equals(finalSha)) {
            markFailed(record, "Final byte verification failed", "UNRESOLVED");
            throw new IllegalStateException("Final bytes do not match expected digest");
        }

        repository.compareAndSetState(
                record.artifactId(), PublicationState.STAGED.name(),
                PublicationState.PUBLISHED.name(),
                null, null,
                null, finalSha, finalSize, finalMedia,
                "RECONCILED");

        return repository.findById(record.artifactId())
                .orElseThrow(() -> new IllegalStateException("Publication record lost"));
    }

    private PublicationRecord resumeChatPublication(
            WorkspaceDescriptor descriptor, PublicationRecord existing,
            byte[] bytes, String contentSha256, String mediaType) throws IOException {
        Path outputsDir = directoryService.outputs(descriptor);
        return switch (existing.publicationState()) {
            case RESERVED -> stageThenPromoteChat(descriptor, outputsDir, existing, bytes, contentSha256, mediaType);
            case STAGED -> promoteChatToFinal(descriptor, outputsDir, existing, bytes, contentSha256, mediaType);
            case PUBLISHED -> existing;
            default -> throw new IllegalStateException("Unresumable publication state: " + existing.publicationState());
        };
    }

    // ── Name allocation ──

    private String allocateFinalName(RunOutputLocation location, String normalizedName) {
        String base = normalizedName;
        for (int i = 0; i < MAX_SUFFIX_ATTEMPTS; i++) {
            String candidate = suffixName(base, i);
            if (!repository.existsByFinalPath(
                    location.workspaceKind().token(), location.workspaceKey(),
                    location.rootRelativePath() + "/" + candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot allocate unique final name for " + normalizedName);
    }

    private String allocateChatName(WorkspaceDescriptor descriptor, Path outputsDir,
                                     String normalizedName) throws IOException {
        String base = normalizedName;
        for (int i = 0; i < MAX_SUFFIX_ATTEMPTS; i++) {
            String candidate = suffixName(base, i);
            Path candidatePath = outputsDir.resolve(candidate);
            if (!Files.exists(candidatePath, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot allocate unique final name for " + normalizedName);
    }

    private static String suffixName(String name, int attempt) {
        if (attempt == 0) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return base + "-" + (attempt + 1) + ext;
    }

    // ── Digest construction ──

    /**
     * Build the versioned canonical request digest for a TASK_RUN publication.
     * Encoding: version byte (0x02), then per-field 4-byte big-endian length
     * prefix followed by UTF-8 bytes. Fields: scope, workspace_identity,
     * task_run_id, owner_id, fence_generation, normalized_name, content_sha256,
     * media_intent.
     */
    static String buildRequestDigest(
            PublicationOrigin origin,
            RunOutputLocation location,
            TaskRunWriteCapability capability,
            String logicalName,
            String contentSha256,
            String mediaType) {
        String normalized = normalizeLogicalName(logicalName);
        return digestBytes(DIGEST_VERSION,
                origin.name(),
                location.workspaceIdentity(),
                location.taskRunId(),
                capability.ownerId(),
                String.valueOf(capability.fenceGeneration()),
                normalized,
                contentSha256,
                mediaType == null ? "" : mediaType);
    }

    static String buildChatRequestDigest(
            String conversationId,
            String logicalName,
            String contentSha256,
            String mediaType) {
        String normalized = normalizeLogicalName(logicalName);
        return digestBytes(DIGEST_VERSION,
                PublicationOrigin.CHAT.name(),
                WorkspaceKind.CHAT.token() + "/" + conversationId,
                normalized,
                contentSha256,
                mediaType == null ? "" : mediaType);
    }

    private static String digestBytes(byte version, String... fields) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(bos)) {
                dos.writeByte(version);
                for (String field : fields) {
                    byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
                    dos.writeInt(encoded.length);
                    dos.write(encoded);
                }
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bos.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode publication digest", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Integrity verification ──

    /**
     * Verify a published artifact's integrity. Returns true if the final bytes
     * match the expected SHA-256 and size. If not, marks the artifact as
     * INTEGRITY_FAILED and returns false.
     */
    public boolean verifyIntegrity(String artifactId) throws IOException {
        PublicationRecord record = repository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        return verifyIntegrity(record);
    }

    public boolean verifyIntegrity(PublicationRecord record) throws IOException {
        if (record.publicationState() != PublicationState.PUBLISHED) return false;
        try {
            Path file = rootRelativePathService.resolve(record.rootRelativePath());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                repository.markIntegrityFailed(record.artifactId(),
                        "File missing or not a regular file", "UNRESOLVED");
                return false;
            }
            byte[] bytes = Files.readAllBytes(file);
            String sha = sha256Hex(bytes);
            long size = bytes.length;
            if (!record.expectedSha256().equals(sha)
                    || (record.verifiedSize() != null && record.verifiedSize() != size)) {
                repository.markIntegrityFailed(record.artifactId(),
                        "Digest or size mismatch", "UNRESOLVED");
                return false;
            }
            return true;
        } catch (IOException e) {
            repository.markIntegrityFailed(record.artifactId(),
                    "Integrity check failed: " + e.getMessage(), "UNRESOLVED");
            return false;
        }
    }

    // ── Byte access gating ──

    /**
     * Read published artifact bytes. Only PUBLISHED records with verified
     * integrity expose bytes. INTEGRITY_FAILED, missing, or nonterminal
     * records throw.
     */
    public byte[] readPublishedBytes(String artifactId) throws IOException {
        PublicationRecord record = repository.findById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Artifact not found: " + artifactId));
        if (!record.isReadable()) {
            throw new IllegalArgumentException("Artifact is not in a readable state: "
                    + record.publicationState());
        }
        Path file = rootRelativePathService.resolve(record.rootRelativePath());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            repository.markIntegrityFailed(artifactId, "Bytes missing on read", "UNRESOLVED");
            throw new NoSuchFileException("Artifact bytes are missing: " + record.rootRelativePath());
        }
        byte[] bytes = mutations.read(directoryService.dataRoot(), dataRootRelative(file));
        String digest = sha256Hex(bytes);
        if (!record.expectedSha256().equals(digest)
                || !record.verifiedSha256().equals(digest)
                || record.verifiedSize() != bytes.length) {
            repository.markIntegrityFailed(artifactId, "Digest or size mismatch on read", "UNRESOLVED");
            throw new IllegalArgumentException("Artifact failed current integrity verification");
        }
        return bytes;
    }

    // ── Query methods ──

    public Optional<PublicationRecord> findById(String artifactId) {
        return repository.findById(artifactId);
    }

    public Optional<PublicationRecord> findByOperationKey(PublicationOrigin origin, String operationKey) {
        return repository.findByOperationKey(origin, operationKey);
    }

    public List<PublicationRecord> findByTaskRunId(String taskRunId) {
        return repository.findByTaskRunId(taskRunId);
    }

    public List<PublicationRecord> findPublishedByWorkspace(String workspaceKind, String workspaceKey) {
        return repository.findPublishedByWorkspace(workspaceKind, workspaceKey);
    }

    public List<PublicationRecord> findReconciliationCandidates(int limit) {
        return repository.findReconciliationCandidates(limit);
    }

    // ── Legacy compatibility (kept for existing callers) ──

    /**
     * @deprecated Legacy materialization path kept for existing callers.
     * New callers must use {@link #publishTaskRunBytes} or {@link #publishChatBytes}.
     */
    @Deprecated
    public RunOutputArtifact materialize(String runId, String planId,
                                          String outputName, PlanFieldType outputType,
                                          Object value, Path outputDir) throws IOException {
        return materialize(runId, planId, outputName, outputType, value, outputDir, OutputArtifactContext.EMPTY);
    }

    @Deprecated
    public synchronized RunOutputArtifact materialize(String runId, String planId,
                                                      String outputName, PlanFieldType outputType,
                                                      Object value, Path outputDir,
                                                      OutputArtifactContext context) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        requireId(outputName, "outputName");
        if (outputType == null) outputType = PlanFieldType.STRING;
        if (value == null) throw new IllegalArgumentException("Output value is null");

        Path realOutputDir = requireConfinedOutputDirectory(outputDir);

        return switch (outputType) {
            case FILE_PATH -> materializeFilePath(runId, planId, outputName, value, realOutputDir, context);
            case USER_MESSAGE -> materializeUserMessage(runId, planId, outputName, value, realOutputDir, context);
            case JSON -> materializeJson(runId, planId, outputName, value, realOutputDir, context);
            case STRING, NUMBER -> materializeText(runId, planId, outputName, value, realOutputDir, context);
        };
    }

    @Deprecated
    public List<RunOutputArtifact> materializeAll(String runId, String planId,
                                                   Map<String, Object> outputValues,
                                                   Map<String, PlanFieldType> outputTypes,
                                                   Path outputDir) throws IOException {
        return materializeAll(runId, planId, outputValues, outputTypes, outputDir, OutputArtifactContext.EMPTY);
    }

    @Deprecated
    public List<RunOutputArtifact> materializeAll(String runId, String planId,
                                                  Map<String, Object> outputValues,
                                                  Map<String, PlanFieldType> outputTypes,
                                                  Path outputDir,
                                                  OutputArtifactContext context) throws IOException {
        List<RunOutputArtifact> artifacts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : outputValues.entrySet()) {
            String name = entry.getKey();
            PlanFieldType type = outputTypes.getOrDefault(name, PlanFieldType.STRING);
            artifacts.add(materialize(runId, planId, name, type, entry.getValue(), outputDir, context));
        }
        return artifacts;
    }

    @Deprecated
    public List<RunOutputArtifact> artifactsForRun(String runId) {
        return repository.findArtifactsByRunId(runId);
    }

    @Deprecated
    public List<RunOutputArtifact> artifactsForPlan(String planId) {
        return repository.findArtifactsByPlanId(planId);
    }

    @Deprecated
    public List<RunOutputArtifact> query(String runId, String planId, String artifactType, Integer limit) {
        return query(OutputArtifactQuery.of(null, null, null, null, null, null, null, null, runId, planId, null, artifactType, limit));
    }

    @Deprecated
    public List<RunOutputArtifact> query(OutputArtifactQuery query) {
        return repository.findArtifacts(query);
    }

    @Deprecated
    public int backfillAttribution(String runId, OutputArtifactContext context) {
        return repository.backfillArtifactAttribution(runId, context);
    }

    @Deprecated
    public Path dataRoot() { return directoryService.dataRoot(); }

    @Deprecated
    public RunOutputArtifact getArtifact(String artifactId) {
        return repository.findArtifactById(artifactId)
                .orElseThrow(() -> new IllegalArgumentException("Output artifact not found: " + artifactId));
    }

    @Deprecated
    public String loadContent(String artifactId, long maxBytes) throws IOException {
        RunOutputArtifact artifact = getArtifact(artifactId);
        return Files.readString(resolveArtifactFile(artifact, maxBytes));
    }

    @Deprecated
    public Path resolveArtifactFile(String artifactId) {
        return resolveArtifactFile(getArtifact(artifactId));
    }

    @Deprecated
    public Path resolveArtifactFile(String artifactId, long maxBytes) throws IOException {
        return resolveArtifactFile(getArtifact(artifactId), maxBytes);
    }

    @Deprecated
    public Path resolveArtifactFile(RunOutputArtifact artifact) {
        if (artifact == null) throw new IllegalArgumentException("artifact is required");
        String filePath = artifact.filePath();
        if (!StringUtils.hasText(filePath)) throw new IllegalArgumentException("Artifact has no file path");
        Path resolved = rootRelativePathService.resolve(filePath);
        Path path;
        try { path = resolved.toRealPath(); }
        catch (IOException e) { throw new IllegalArgumentException("Artifact file does not exist: " + filePath, e); }
        if (!path.startsWith(directoryService.dataRoot()))
            throw new IllegalArgumentException("Artifact path escapes data root: " + filePath);
        if (Files.isDirectory(path)) throw new IllegalArgumentException("Artifact is a directory: " + filePath);
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Artifact file does not exist: " + filePath);
        return path;
    }

    @Deprecated
    public Path resolveArtifactFile(RunOutputArtifact artifact, long maxBytes) throws IOException {
        Path path = resolveArtifactFile(artifact);
        long size = Files.size(path);
        if (size > maxBytes) throw new IllegalArgumentException("Artifact file too large: " + size);
        return path;
    }

    @Deprecated
    public synchronized RunOutputArtifact publishExistingFile(String runId, String planId,
                                                              String outputName, String artifactType,
                                                              Path sourcePath, Path outputDir,
                                                              OutputArtifactContext context) throws IOException {
        requireId(runId, "runId");
        requireId(planId, "planId");
        requireId(outputName, "outputName");
        if (sourcePath == null) throw new IllegalArgumentException("sourcePath is required");
        if (outputDir == null) throw new IllegalArgumentException("outputDir is required");
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realSource = requireConfinedRealSource(sourcePath);
        Path realOutputDir = requireConfinedOutputDirectory(outputDir);
        String fileName = realSource.getFileName().toString();
        Path destination = realOutputDir.resolve(fileName).normalize();
        if (!destination.startsWith(realOutputDir))
            throw new IllegalArgumentException("Published output path escapes output directory");
        if (!realSource.startsWith(realOutputDir))
            destination = copyToUniqueDestination(realSource, realOutputDir, fileName);
        else
            destination = realSource;
        String resolvedType = StringUtils.hasText(artifactType) ? artifactType.trim()
                : inferArtifactType(destination.getFileName().toString());
        return saveArtifact(runId, planId, outputName, resolvedType,
                destination.getFileName().toString(), destination, null, context);
    }

    // ── Failure helpers ──

    private PublicationRecord markFailed(PublicationRecord record, String detail, String reconciliation) {
        repository.markFailed(record.artifactId(), detail, reconciliation);
        return repository.findById(record.artifactId()).orElse(record);
    }

    // ── Internal helpers ──

    private void requireCurrentWriter(RunOutputLocation location,
                                       TaskRunWriteCapability capability) {
        if (taskRunLifecycleService == null) return; // test-compatibility
        if (location.workspaceKind() == WorkspaceKind.CHAT) return;
        taskRunLifecycleService.requireCurrentWriter(
                taskRunLifecycleService.resolveLocation(location.taskRunId()),
                capability);
        // Also verify the location matches
        if (!capability.matches(taskRunLifecycleService.resolveLocation(location.taskRunId()))) {
            throw new TaskRunLifecycleService.StaleWriterException(
                    "Writer capability does not match task-run location");
        }
    }

    private Path requireConfinedRealSource(Path sourcePath) throws IOException {
        if (sourcePath == null) throw new IllegalArgumentException("sourcePath is required");
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        boolean existsWithoutFollow = Files.exists(sourcePath, LinkOption.NOFOLLOW_LINKS);
        Path realSource;
        try {
            realSource = sourcePath.toRealPath();
        } catch (NoSuchFileException e) {
            if (existsWithoutFollow) {
                throw new IllegalArgumentException(
                        "Source file is a broken symlink: " + sourcePath, e);
            }
            throw new IllegalArgumentException("Source file does not exist: " + sourcePath, e);
        }
        if (!realSource.startsWith(realDataRoot))
            throw new IllegalArgumentException("Source file escapes data root: " + sourcePath);
        return realSource;
    }

    private Path requireConfinedOutputDirectory(Path outputDir) throws IOException {
        if (outputDir == null) throw new IllegalArgumentException("outputDir is required");
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        Path realOutputDir = Files.createDirectories(outputDir).toRealPath();
        if (!realOutputDir.startsWith(realDataRoot))
            throw new IllegalArgumentException("Output directory escapes data root: " + outputDir);
        return realOutputDir;
    }

    private String dataRootRelative(Path path) {
        return directoryService.dataRoot().relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    static String normalizeLogicalName(String name) {
        String cleaned = name.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_");
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals(".."))
            throw new IllegalArgumentException("Invalid logical name: " + name);
        if (!SAFE_NAME.matcher(cleaned).matches()) cleaned = "output_" + cleaned;
        return cleaned;
    }

    static String extractExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : null;
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String probeMediaType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        if (lower.endsWith(".csv")) return "text/csv";
        if (lower.endsWith(".log")) return "text/plain";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        try { return Files.probeContentType(Path.of(path)); }
        catch (IOException e) { return "application/octet-stream"; }
    }

    // ── Legacy materialization helpers ──

    private RunOutputArtifact materializeFilePath(String runId, String planId,
                                                   String outputName, Object value,
                                                   Path outputDir,
                                                   OutputArtifactContext context) throws IOException {
        String sourcePathStr = value.toString().trim();
        if (!StringUtils.hasText(sourcePathStr))
            throw new IllegalArgumentException("Source file path is blank");
        Path sourcePath;
        Path realDataRoot = directoryService.dataRoot().toRealPath();
        if (!sourcePathStr.contains("/") && !sourcePathStr.contains("\\"))
            sourcePath = outputDir.resolve(sourcePathStr).normalize();
        else if (!Path.of(sourcePathStr).isAbsolute())
            sourcePath = resolveRunOutputPath(outputDir, sourcePathStr);
        else {
            Path absolute = Path.of(sourcePathStr).toAbsolutePath().normalize();
            if (!absolute.startsWith(realDataRoot))
                throw new IllegalArgumentException("Absolute file_path output escapes data root: " + sourcePathStr);
            sourcePath = absolute;
        }
        Path realSourcePath = requireConfinedRealSource(sourcePath);
        String fileName = sourcePath.getFileName().toString();
        Path destPath = outputDir.resolve(fileName);
        boolean sourceEqualsDest = sourceEqualsDestination(sourcePath, realSourcePath, destPath);
        if (sourceEqualsDest && artifactFileNameExists(runId, fileName))
            destPath = copyToUniqueDestination(realSourcePath, outputDir, sanitize(outputName) + "_" + fileName);
        else if (!sourceEqualsDest)
            destPath = copyToUniqueDestination(realSourcePath, outputDir, destPath.getFileName().toString());
        return saveArtifact(runId, planId, outputName, "file_path",
                destPath.getFileName().toString(), destPath, null, context);
    }

    private RunOutputArtifact materializeUserMessage(String runId, String planId,
                                                      String outputName, Object value,
                                                      Path outputDir,
                                                      OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".md";
        String content = value.toString();
        Path filePath = writeStringToUniqueFile(outputDir, fileName, content);
        return saveArtifact(runId, planId, outputName, "user_message",
                filePath.getFileName().toString(), filePath, null, context);
    }

    private RunOutputArtifact materializeJson(String runId, String planId,
                                               String outputName, Object value,
                                               Path outputDir,
                                               OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".json";
        String jsonContent;
        try {
            jsonContent = value instanceof String
                    ? objectMapper.writeValueAsString(objectMapper.readTree((String) value))
                    : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Output '" + outputName + "' is not valid JSON: " + e.getMessage(), e);
        }
        Path filePath = writeStringToUniqueFile(outputDir, fileName, jsonContent);
        return saveArtifact(runId, planId, outputName, "json",
                filePath.getFileName().toString(), filePath, jsonContent, context);
    }

    private RunOutputArtifact materializeText(String runId, String planId,
                                               String outputName, Object value,
                                               Path outputDir,
                                               OutputArtifactContext context) throws IOException {
        String fileName = sanitize(outputName) + ".txt";
        String content = value.toString();
        Path filePath = writeStringToUniqueFile(outputDir, fileName, content);
        return saveArtifact(runId, planId, outputName, "text",
                filePath.getFileName().toString(), filePath, null, context);
    }

    private RunOutputArtifact saveArtifact(String runId, String planId,
                                            String outputName, String artifactType,
                                            String fileName, Path filePath,
                                            String contentJson,
                                            OutputArtifactContext context) {
        OutputArtifactContext resolvedContext = context == null ? OutputArtifactContext.EMPTY : context;
        String storedFilePath = rootRelativePathService.store(filePath);
        return repository.saveArtifact(new RunOutputArtifact(
                UUID.randomUUID().toString(), runId, planId, resolvedContext.agentId(),
                resolvedContext.jobId(), resolvedContext.jobAssignmentId(), resolvedContext.jobRunId(),
                resolvedContext.projectId(), resolvedContext.workspaceType(), resolvedContext.workspaceKey(),
                resolvedContext.workspaceIdentity(), resolvedContext.runType(),
                outputName, artifactType, fileName, storedFilePath, contentJson, Instant.now()));
    }

    private boolean sourceEqualsDestination(Path sourcePath, Path realSourcePath, Path destPath) throws IOException {
        if (sourcePath.toAbsolutePath().normalize().equals(destPath.toAbsolutePath().normalize())) return true;
        if (!Files.exists(destPath, LinkOption.NOFOLLOW_LINKS)) return false;
        try { return realSourcePath.equals(destPath.toRealPath()); }
        catch (NoSuchFileException e) { return false; }
    }

    private Path resolveRunOutputPath(Path outputDir, String relativePath) {
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        if (!StringUtils.hasText(relativePath)) return normalizedOutputDir;
        String cleaned = relativePath.replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.contains("//"))
            throw new IllegalArgumentException("Output path escapes output directory");
        Path relative = Path.of(cleaned).normalize();
        if (relative.isAbsolute() || relative.startsWith(".."))
            throw new IllegalArgumentException("Output path escapes output directory");
        Path resolved = normalizedOutputDir.resolve(relative).normalize();
        if (!resolved.startsWith(normalizedOutputDir))
            throw new IllegalArgumentException("Output path escapes output directory");
        return resolved;
    }

    private boolean artifactFileNameExists(String runId, String fileName) {
        return repository.findArtifactsByRunId(runId).stream()
                .map(RunOutputArtifact::fileName)
                .anyMatch(fileName::equals);
    }

    private Path writeStringToUniqueFile(Path outputDir, String desiredFileName, String content) throws IOException {
        IOException lastError = null;
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 1_000; attempt++) {
            Path candidate = outputDir.resolve(uniqueFileName(desiredFileName, attempt)).normalize();
            if (!candidate.toAbsolutePath().normalize().startsWith(normalizedOutputDir))
                throw new IllegalArgumentException("Output path escapes output directory");
            try {
                return Files.writeString(candidate, content, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
            } catch (FileAlreadyExistsException e) { lastError = e; }
        }
        throw new IOException("Could not allocate unique output file", lastError);
    }

    private Path copyToUniqueDestination(Path source, Path outputDir, String desiredFileName) throws IOException {
        IOException lastError = null;
        Path normalizedOutputDir = outputDir.toAbsolutePath().normalize();
        for (int attempt = 0; attempt < 1_000; attempt++) {
            Path candidate = outputDir.resolve(uniqueFileName(desiredFileName, attempt)).normalize();
            if (!candidate.toAbsolutePath().normalize().startsWith(normalizedOutputDir))
                throw new IllegalArgumentException("Output path escapes output directory");
            try {
                Files.copy(source, candidate);
                return candidate;
            } catch (FileAlreadyExistsException e) { lastError = e; }
        }
        throw new IOException("Could not allocate unique output file", lastError);
    }

    private String uniqueFileName(String fileName, int attempt) {
        if (attempt == 0) return fileName;
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        return base + "-" + (attempt + 1) + ext;
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_");
    }

    private String inferArtifactType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".md")) return "user_message";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".txt")) return "text";
        if (lower.endsWith(".csv")) return "text";
        if (lower.endsWith(".log")) return "text";
        return "file_path";
    }

    private void requireId(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + " is required");
    }

    private static void requireText(String value, String label) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException(label + " is required");
    }

    private static void requireLocation(RunOutputLocation location, String label) {
        Objects.requireNonNull(location, label);
    }

    private static void requireCapability(TaskRunWriteCapability capability, String label) {
        Objects.requireNonNull(capability, label);
    }
}
