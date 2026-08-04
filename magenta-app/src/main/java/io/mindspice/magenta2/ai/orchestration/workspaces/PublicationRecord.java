package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.time.Instant;

/**
 * Immutable publication record matching the {@code output_publications} schema.
 * Built by the repository and consumed by services; never constructed by callers.
 */
public record PublicationRecord(
        String artifactId,
        PublicationOrigin publicationOrigin,
        String workspaceKind,
        String workspaceKey,
        String workspaceIdentity,
        String taskRunId,
        String taskPathKey,
        String nativeRunId,
        Integer attemptNumber,
        String rootRelativePath,
        String stagingPath,
        String operationKey,
        String requestDigest,
        String contentSha256,
        PublicationState publicationState,
        String reconciliationState,
        String failureDetail,
        String expectedSha256,
        String verifiedSha256,
        Long verifiedSize,
        String mediaType,
        String writerOwnerId,
        Long fenceGeneration,
        String logicalName,
        String fileExtension,
        Instant createdAt,
        Instant updatedAt,
        Instant stagedAt,
        Instant publishedAt) {

    /** True when this record is safe to expose to internal byte consumers. */
    public boolean isReadable() {
        return publicationState == PublicationState.PUBLISHED
                && verifiedSha256 != null
                && verifiedSize != null
                && "RECONCILED".equals(reconciliationState);
    }

    /** True when a writer guard must be checked for mutation. */
    public boolean requiresWriterGuard() {
        return publicationOrigin == PublicationOrigin.TASK_RUN
                && writerOwnerId != null && fenceGeneration != null;
    }

    /** Builder for internal service use. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String artifactId;
        private PublicationOrigin publicationOrigin;
        private String workspaceKind;
        private String workspaceKey;
        private String workspaceIdentity;
        private String taskRunId;
        private String taskPathKey;
        private String nativeRunId;
        private Integer attemptNumber;
        private String rootRelativePath;
        private String stagingPath;
        private String operationKey;
        private String requestDigest;
        private String contentSha256;
        private PublicationState publicationState = PublicationState.RESERVED;
        private String reconciliationState;
        private String failureDetail;
        private String expectedSha256;
        private String verifiedSha256;
        private Long verifiedSize;
        private String mediaType;
        private String writerOwnerId;
        private Long fenceGeneration;
        private String logicalName;
        private String fileExtension;
        private Instant createdAt;
        private Instant updatedAt;
        private Instant stagedAt;
        private Instant publishedAt;

        public Builder artifactId(String v) { this.artifactId = v; return this; }
        public Builder publicationOrigin(PublicationOrigin v) { this.publicationOrigin = v; return this; }
        public Builder workspaceKind(String v) { this.workspaceKind = v; return this; }
        public Builder workspaceKey(String v) { this.workspaceKey = v; return this; }
        public Builder workspaceIdentity(String v) { this.workspaceIdentity = v; return this; }
        public Builder taskRunId(String v) { this.taskRunId = v; return this; }
        public Builder taskPathKey(String v) { this.taskPathKey = v; return this; }
        public Builder nativeRunId(String v) { this.nativeRunId = v; return this; }
        public Builder attemptNumber(Integer v) { this.attemptNumber = v; return this; }
        public Builder rootRelativePath(String v) { this.rootRelativePath = v; return this; }
        public Builder stagingPath(String v) { this.stagingPath = v; return this; }
        public Builder operationKey(String v) { this.operationKey = v; return this; }
        public Builder requestDigest(String v) { this.requestDigest = v; return this; }
        public Builder contentSha256(String v) { this.contentSha256 = v; return this; }
        public Builder publicationState(PublicationState v) { this.publicationState = v; return this; }
        public Builder reconciliationState(String v) { this.reconciliationState = v; return this; }
        public Builder failureDetail(String v) { this.failureDetail = v; return this; }
        public Builder expectedSha256(String v) { this.expectedSha256 = v; return this; }
        public Builder verifiedSha256(String v) { this.verifiedSha256 = v; return this; }
        public Builder verifiedSize(Long v) { this.verifiedSize = v; return this; }
        public Builder mediaType(String v) { this.mediaType = v; return this; }
        public Builder writerOwnerId(String v) { this.writerOwnerId = v; return this; }
        public Builder fenceGeneration(Long v) { this.fenceGeneration = v; return this; }
        public Builder logicalName(String v) { this.logicalName = v; return this; }
        public Builder fileExtension(String v) { this.fileExtension = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder updatedAt(Instant v) { this.updatedAt = v; return this; }
        public Builder stagedAt(Instant v) { this.stagedAt = v; return this; }
        public Builder publishedAt(Instant v) { this.publishedAt = v; return this; }

        public PublicationRecord build() {
            Instant now = Instant.now();
            if (createdAt == null) createdAt = now;
            if (updatedAt == null) updatedAt = now;
            return new PublicationRecord(artifactId, publicationOrigin, workspaceKind, workspaceKey,
                    workspaceIdentity, taskRunId, taskPathKey, nativeRunId, attemptNumber,
                    rootRelativePath, stagingPath, operationKey, requestDigest, contentSha256,
                    publicationState, reconciliationState, failureDetail, expectedSha256, verifiedSha256,
                    verifiedSize, mediaType, writerOwnerId, fenceGeneration,
                    logicalName, fileExtension,
                    createdAt, updatedAt, stagedAt, publishedAt);
        }
    }
}
