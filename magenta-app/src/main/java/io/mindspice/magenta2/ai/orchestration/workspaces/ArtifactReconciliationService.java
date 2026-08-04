package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.TaskRunLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Deterministic, bounded reconciliation service for {@code output_publications}
 * divergence. Processes persisted candidates by state and stable key, resolves
 * or records unresolved state.
 *
 * <h3>Reconciliation rules</h3>
 * <ul>
 *   <li>RESERVED with stale writer → cleanup staging; record FAILED.</li>
 *   <li>STAGED with verified final bytes → promote to PUBLISHED.</li>
 *   <li>PUBLISHED with missing or changed bytes → INTEGRITY_FAILED.</li>
 *   <li>Final files without rows → unexplained (never adopted).</li>
 *   <li>Stale staging files belonging to terminal records → safe cleanup.</li>
 *   <li>Repeated reconciliation produces the same result.</li>
 * </ul>
 */
@Service
public class ArtifactReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactReconciliationService.class);
    private static final int DEFAULT_BATCH_SIZE = 50;

    private final OutputArtifactRepository repository;
    private final RootRelativePathService rootRelativePathService;
    private final WorkspaceDirectoryService directoryService;
    private final ConfinedPathMutationService mutations;
    private final TaskRunLifecycleService taskRunLifecycleService;

    public ArtifactReconciliationService(OutputArtifactRepository repository,
                                          RootRelativePathService rootRelativePathService,
                                          WorkspaceDirectoryService directoryService,
                                          ConfinedPathMutationService mutations) {
        this(repository, rootRelativePathService, directoryService, mutations, null);
    }

    @Autowired
    public ArtifactReconciliationService(OutputArtifactRepository repository,
                                          RootRelativePathService rootRelativePathService,
                                          WorkspaceDirectoryService directoryService,
                                          ConfinedPathMutationService mutations,
                                          @Autowired(required = false) TaskRunLifecycleService taskRunLifecycleService) {
        this.repository = repository;
        this.rootRelativePathService = rootRelativePathService;
        this.directoryService = directoryService;
        this.mutations = mutations;
        this.taskRunLifecycleService = taskRunLifecycleService;
    }

    /**
     * Run reconciliation over a bounded batch of candidates. Returns the
     * number of records whose state was updated.
     */
    public int reconcileBatch() {
        return reconcileBatch(DEFAULT_BATCH_SIZE);
    }

    public int reconcileBatch(int batchSize) {
        List<PublicationRecord> candidates = repository.findReconciliationCandidates(batchSize);
        int updated = 0;
        for (PublicationRecord record : candidates) {
            try {
                if (reconcileOne(record)) updated++;
            } catch (Exception e) {
                log.error("Reconciliation failed for {}: {}", record.artifactId(), e.getMessage(), e);
                repository.setReconciliationState(record.artifactId(), "UNRESOLVED");
                updated++;
            }
        }
        return updated;
    }

    /**
     * Reconcile a single publication record. Returns true when state was mutated.
     */
    public boolean reconcileOne(PublicationRecord record) {
        return switch (record.publicationState()) {
            case RESERVED -> reconcileReserved(record);
            case STAGED -> reconcileStaged(record);
            case PUBLISHED -> reconcilePublished(record);
            case INTEGRITY_FAILED -> reconcileIntegrityFailed(record);
            case FAILED -> reconcileFailed(record);
        };
    }

    private boolean reconcileReserved(PublicationRecord record) {
        // A reconciler does not possess the opaque TaskRunWriteCapability. It must
        // never convert or clean task-owned publication state on owner/fence facts alone.
        if (record.publicationOrigin() == PublicationOrigin.TASK_RUN) {
            repository.setReconciliationState(record.artifactId(), "UNRESOLVED");
            return true;
        }
        boolean writerAlive = isWriterAlive(record);
        if (writerAlive) {
            // Writer is still active; leave it alone
            repository.setReconciliationState(record.artifactId(), "RECONCILED");
            return true;
        }

        // Stale reservation: clean up staging if it exists
        if (record.stagingPath() != null) {
            try {
                cleanupStagingFile(record);
                repository.setReconciliationState(record.artifactId(), "CLEANUP_PENDING");
            } catch (Exception e) {
                log.warn("Failed to clean staging for {}: {}", record.artifactId(), e.getMessage());
                repository.setReconciliationState(record.artifactId(), "UNRESOLVED");
                return true;
            }
        }

        repository.markFailed(record.artifactId(),
                "Stale reservation with no live writer", "RECONCILED");
        return true;
    }

    private boolean reconcileStaged(PublicationRecord record) {
        if (record.publicationOrigin() == PublicationOrigin.TASK_RUN) {
            repository.setReconciliationState(record.artifactId(), "UNRESOLVED");
            return true;
        }
        // Writer still active → leave it
        boolean writerAlive = isWriterAlive(record);
        if (writerAlive) {
            repository.setReconciliationState(record.artifactId(), "RECONCILED");
            return true;
        }

        // Check if final bytes exist and match
        Path finalPath;
        try {
            finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        } catch (Exception e) {
            repository.markFailed(record.artifactId(),
                    "Final path unresolvable: " + e.getMessage(), "UNRESOLVED");
            return true;
        }

        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            try {
                byte[] bytes = Files.readAllBytes(finalPath);
                String sha = sha256Hex(bytes);
                long size = bytes.length;

                if (record.expectedSha256().equals(sha)) {
                    // Final bytes match: promote
                    repository.compareAndSetState(
                            record.artifactId(), PublicationState.STAGED.name(),
                            PublicationState.PUBLISHED.name(),
                            null, null,
                            null, sha, size, record.mediaType(),
                            "RECONCILED");
                    // Clean staging
                    if (record.stagingPath() != null) {
                        cleanupStagingFile(record);
                    }
                    return true;
                }
                // Mismatch
                repository.markFailed(record.artifactId(),
                        "Staged final bytes do not match expected digest", "UNRESOLVED");
                return true;
            } catch (IOException e) {
                repository.markFailed(record.artifactId(),
                        "Failed to read staged final bytes", "UNRESOLVED");
                return true;
            }
        }

        // No final file yet, check staging
        if (record.stagingPath() != null) {
            Path stagingPath;
            try {
                stagingPath = rootRelativePathService.resolve(record.stagingPath());
            } catch (Exception e) {
                cleanupStagingFile(record);
                repository.markFailed(record.artifactId(),
                        "Staging path unresolvable", "RECONCILED");
                return false;
            }

            if (Files.exists(stagingPath, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(stagingPath, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    byte[] bytes = Files.readAllBytes(stagingPath);
                    String sha = sha256Hex(bytes);
                    if (record.expectedSha256().equals(sha)) {
                        // Staging bytes match expected; promote through create-only confined rename.
                        try {
                            mutations.move(directoryService.dataRoot(), dataRootRelative(stagingPath),
                                    dataRootRelative(finalPath));
                        } catch (IOException e) {
                            log.warn("Cannot promote staged file for {}: {}",
                                    record.artifactId(), e.getMessage());
                            repository.setReconciliationState(record.artifactId(), "UNRESOLVED");
                            return true;
                        }
                        byte[] finalBytes = Files.readAllBytes(finalPath);
                        String finalSha = sha256Hex(finalBytes);
                        long finalSize = finalBytes.length;

                        repository.compareAndSetState(
                                record.artifactId(), PublicationState.STAGED.name(),
                                PublicationState.PUBLISHED.name(),
                                null, null,
                                null, finalSha, finalSize, record.mediaType(),
                                "RECONCILED");
                        cleanupStagingFile(record);
                        return true;
                    }
                } catch (IOException e) {
                    log.warn("Staged bytes unreadable for {}: {}",
                            record.artifactId(), e.getMessage());
                }
            }

            // Staging file not usable
            cleanupStagingFile(record);
            repository.markFailed(record.artifactId(),
                    "Staged material not recoverable", "RECONCILED");
            return true;
        }

        // Neither final nor staging
        repository.markFailed(record.artifactId(),
                "No staged or final bytes available", "RECONCILED");
        return true;
    }

    private boolean reconcilePublished(PublicationRecord record) {
        Path finalPath;
        try {
            finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        } catch (Exception e) {
            repository.markIntegrityFailed(record.artifactId(),
                    "Final path unresolvable", "UNRESOLVED");
            return true;
        }

        if (!Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            repository.markIntegrityFailed(record.artifactId(),
                    "Final file missing", "UNRESOLVED");
            return true;
        }

        if (!Files.isRegularFile(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            repository.markIntegrityFailed(record.artifactId(),
                    "Final path is not a regular file", "UNRESOLVED");
            return true;
        }

        try {
            byte[] bytes = Files.readAllBytes(finalPath);
            String sha = sha256Hex(bytes);
            long size = bytes.length;

            if (record.expectedSha256().equals(sha)
                    && (record.verifiedSize() == null || record.verifiedSize() == size)) {
                // Integrity confirmed
                if (!"RECONCILED".equals(record.reconciliationState())) {
                    repository.compareAndSetState(
                            record.artifactId(), PublicationState.PUBLISHED.name(),
                            PublicationState.PUBLISHED.name(),
                            null, null,
                            null, sha, size, record.mediaType(),
                            "RECONCILED");
                }
                return true;
            }

            repository.markIntegrityFailed(record.artifactId(),
                    "Digest or size mismatch", "UNRESOLVED");
            return true;
        } catch (IOException e) {
            repository.markIntegrityFailed(record.artifactId(),
                    "Failed to read final bytes: " + e.getMessage(), "UNRESOLVED");
            return true;
        }
    }

    private boolean reconcileIntegrityFailed(PublicationRecord record) {
        // Re-verify: if the file is now correct, transition back to PUBLISHED
        Path finalPath;
        try {
            finalPath = rootRelativePathService.resolve(record.rootRelativePath());
        } catch (Exception e) {
            // Still unresolvable
            return false;
        }

        if (!Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }

        try {
            byte[] bytes = Files.readAllBytes(finalPath);
            String sha = sha256Hex(bytes);
            long size = bytes.length;

            if (record.expectedSha256().equals(sha)) {
                // File is now correct
                repository.compareAndSetState(
                        record.artifactId(), PublicationState.INTEGRITY_FAILED.name(),
                        PublicationState.PUBLISHED.name(),
                        null, null,
                        null, sha, size, record.mediaType(),
                        "RECONCILED");
                return true;
            }
        } catch (IOException e) {
            // Still failed
        }

        return false;
    }

    private boolean reconcileFailed(PublicationRecord record) {
        // Clean up any remaining staging
        if (record.stagingPath() != null) {
            try {
                cleanupStagingFile(record);
            } catch (Exception e) {
                log.warn("Failed staging cleanup for FAILED {}: {}",
                        record.artifactId(), e.getMessage());
            }
        }
        if (!"RECONCILED".equals(record.reconciliationState())) {
            repository.setReconciliationState(record.artifactId(), "RECONCILED");
            return true;
        }
        return false;
    }

    // ── Writer aliveness check ──

    private boolean isWriterAlive(PublicationRecord record) {
        if (record.writerOwnerId() == null || record.fenceGeneration() == null) return false;
        if (taskRunLifecycleService == null) return false;

        // Build a minimal capability from the recorded facts and check it
        TaskRunWriteCapability cap = reconstructCapability(record);
        if (cap == null) return false;
        return taskRunLifecycleService.verifyWriterClaimCurrent(cap);
    }

    private TaskRunWriteCapability reconstructCapability(PublicationRecord record) {
        if (record.taskRunId() == null || record.taskPathKey() == null
                || record.attemptNumber() == null) return null;
        try {
            return TaskRunWriteCapability.create(
                    resolveLocation(record),
                    record.attemptNumber(),
                    record.writerOwnerId(),
                    record.fenceGeneration(),
                    ""); // fenceToken unknown from record; verification checks remaining fields
        } catch (Exception e) {
            return null;
        }
    }

    private TaskRunLocation resolveLocation(PublicationRecord record) {
        if (taskRunLifecycleService == null) {
            throw new IllegalStateException("TaskRunLifecycleService is required for location resolution");
        }
        return taskRunLifecycleService.resolveLocation(record.taskRunId());
    }

    // ── Staging cleanup ──

    private void cleanupStagingFile(PublicationRecord record) {
        if (record.stagingPath() == null || record.publicationOrigin() == PublicationOrigin.TASK_RUN) return;
        try {
            Path stagingPath = rootRelativePathService.resolve(record.stagingPath());
            if (Files.exists(stagingPath, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(stagingPath, LinkOption.NOFOLLOW_LINKS)) {
                // Only clean if the staging path is writeable and the record is terminal
                if (record.publicationState() == PublicationState.FAILED
                        || record.publicationState() == PublicationState.PUBLISHED
                        || record.publicationState() == PublicationState.INTEGRITY_FAILED
                        || (record.publicationState() == PublicationState.RESERVED
                            && !isWriterAlive(record))) {
                    mutations.delete(directoryService.dataRoot(), dataRootRelative(stagingPath), false);
                }
            }
        } catch (NoSuchFileException e) {
            // Already gone
        } catch (IOException e) {
            log.warn("Cannot delete staging file for {}: {}", record.artifactId(), e.getMessage());
        }
    }

    private String dataRootRelative(Path path) {
        return directoryService.dataRoot().toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
