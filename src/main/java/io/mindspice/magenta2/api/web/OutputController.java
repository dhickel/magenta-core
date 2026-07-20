package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactAccessContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactPreviewService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactPreviewService.PreviewResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.AuthorizedByteHandle;
import io.mindspice.magenta2.ai.orchestration.workspaces.ArtifactSearchService.SearchResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.PublicationRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService.RetentionBatchResult;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService.RetentionPin;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunRetentionService.RetentionPolicy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

/**
 * Scoped artifact access and lifecycle controller.
 *
 * <p>Replaces the previous open {@code /api/outputs} routes with
 * authorization-first search, detail, preview, lineage, download,
 * and retention endpoints. Every endpoint requires explicit workspace
 * scope parameters that are bound into an {@link ArtifactAccessContext}
 * before any data access.</p>
 */
@RestController
public class OutputController {
    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final int DEFAULT_PAGE_SIZE = 25;

    private final OutputArtifactService outputArtifactService;
    private final OutputArtifactRepository artifactRepository;
    private final ArtifactSearchService searchService;
    private final ArtifactPreviewService previewService;
    private final TaskRunRetentionService retentionService;

    public OutputController(OutputArtifactService outputArtifactService,
                            OutputArtifactRepository artifactRepository,
                            ArtifactSearchService searchService,
                            ArtifactPreviewService previewService,
                            TaskRunRetentionService retentionService) {
        this.outputArtifactService = outputArtifactService;
        this.artifactRepository = artifactRepository;
        this.searchService = searchService;
        this.previewService = previewService;
        this.retentionService = retentionService;
    }

    // ── Artifact detail (authorized metadata) ──

    @GetMapping("/api/artifacts/{artifactId}")
    public ResponseEntity<?> detail(@PathVariable String artifactId,
                                     @RequestParam String workspaceKind,
                                     @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact not found"));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("artifactId", record.artifactId());
        result.put("publicationOrigin", record.publicationOrigin().name());
        result.put("publicationState", record.publicationState().name());
        result.put("logicalName", record.logicalName());
        result.put("mediaType", record.mediaType());
        result.put("fileExtension", record.fileExtension());
        result.put("verifiedSize", record.verifiedSize());
        result.put("contentSha256", record.contentSha256());
        result.put("reconciliationState", record.reconciliationState());
        result.put("taskRunId", record.taskRunId());
        result.put("nativeRunId", record.nativeRunId());
        result.put("attemptNumber", record.attemptNumber());
        result.put("createdAt", record.createdAt() != null ? record.createdAt().toString() : null);
        result.put("publishedAt", record.publishedAt() != null ? record.publishedAt().toString() : null);
        result.put("isReadable", record.isReadable());
        result.put("workspaceIdentity", record.workspaceIdentity());

        // Check retention blockers
        String blocker = retentionService.removalBlocker(artifactId);
        result.put("removalBlocked", blocker != null);
        result.put("removalBlocker", blocker);

        return ResponseEntity.ok(result);
    }

    // ── Search (authorization-first FTS5) ──

    @GetMapping("/api/artifacts/search")
    public ResponseEntity<?> search(@RequestParam String query,
                                     @RequestParam String workspaceKind,
                                     @RequestParam String workspaceKey,
                                     @RequestParam(required = false) String taskRunId,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "25") int pageSize) {
        if (!StringUtils.hasText(query)) {
            return ResponseEntity.badRequest().body(Map.of("error", "query is required"));
        }
        if (!searchService.ftsAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "FTS5 search is not available on this deployment"));
        }

        ArtifactAccessContext access = StringUtils.hasText(taskRunId)
                ? ArtifactAccessContext.forTaskRun(workspaceKind, workspaceKey, taskRunId)
                : ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);

        SearchResult result = searchService.search(query, access, page, pageSize);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("items", result.items());
        response.put("totalCount", result.totalCount());
        response.put("page", result.page());
        response.put("pageSize", result.pageSize());
        response.put("hasMore", result.hasMore());
        response.put("ftsAvailable", result.ftsAvailable());

        return ResponseEntity.ok(response);
    }

    // ── Preview (safe, bounded, explicit state) ──

    @GetMapping("/api/artifacts/{artifactId}/preview")
    public ResponseEntity<?> preview(@PathVariable String artifactId,
                                      @RequestParam String workspaceKind,
                                      @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PreviewResult result = previewService.preview(artifactId, access);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artifactId", artifactId);
        response.put("state", result.state().name());
        response.put("mediaType", result.mediaType());
        response.put("fileSize", result.fileSize());
        response.put("lineCount", result.lineCount());
        response.put("truncated", result.truncated());
        response.put("byteTruncated", result.byteTruncated());
        response.put("lineTruncated", result.lineTruncated());
        response.put("markdownAvailable", result.markdownAvailable());
        response.put("logicalName", result.logicalName());
        response.put("publishedAt", result.publishedAt());

        if (result.content() != null) {
            response.put("content", result.content());
        }
        if (result.safeHtml() != null) {
            response.put("safeHtml", result.safeHtml());
        }

        HttpStatus status = switch (result.state()) {
            case OK -> HttpStatus.OK;
            case MISSING -> HttpStatus.NOT_FOUND;
            case INTEGRITY_FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNSUPPORTED -> HttpStatus.OK; // Still 200, just with unsupported info
            case ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(response);
    }

    // ── Download (authorized byte handle) ──

    @GetMapping("/api/artifacts/{artifactId}/download")
    public ResponseEntity<?> download(@PathVariable String artifactId,
                                       @RequestParam String workspaceKind,
                                       @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);

        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact not found"));
        }
        if (!record.isReadable()) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", "Artifact is not in a readable state: " + record.publicationState()));
        }

        try {
            Optional<AuthorizedByteHandle> handleOpt = searchService.openAuthorizedBytes(
                    artifactId, access, (int) MAX_DOWNLOAD_BYTES);
            if (handleOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(Map.of("error", "Integrity verification failed; artifact is not available for download"));
            }

            AuthorizedByteHandle handle = handleOpt.get();
            String fileName = record.logicalName() != null ? record.logicalName() : artifactId;
            String mediaType = record.mediaType() != null ? record.mediaType() : "application/octet-stream";
            long contentLength = record.verifiedSize() != null ? record.verifiedSize() : 0;

            // Read all bytes through the handle for the response
            byte[] bytes = handle.readAllBytes();

            // Close the handle before returning
            handle.close();

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename(fileName).build().toString())
                    .contentType(MediaType.parseMediaType(mediaType))
                    .contentLength(bytes.length)
                    .body(bytes);

        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to read artifact: " + e.getMessage()));
        }
    }

    // ── Lineage ──

    @GetMapping("/api/artifacts/{artifactId}/lineage")
    public ResponseEntity<?> lineage(@PathVariable String artifactId,
                                      @RequestParam String workspaceKind,
                                      @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        var result = searchService.lineage(artifactId, access);

        if (result.artifactId() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact not found or not authorized"));
        }

        return ResponseEntity.ok(result);
    }

    // ── Retention ──

    @GetMapping("/api/artifacts/{artifactId}/retention")
    public ResponseEntity<?> retentionStatus(@PathVariable String artifactId,
                                              @RequestParam String workspaceKind,
                                              @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact not found"));
        }

        String blocker = retentionService.removalBlocker(artifactId);
        List<RetentionPin> pins = retentionService.listPins(artifactId, null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("artifactId", artifactId);
        response.put("state", record.publicationState().name());
        response.put("removable", blocker == null);
        response.put("blocker", blocker);
        response.put("pins", pins);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/artifacts/{artifactId}/retention/soft-archive")
    public ResponseEntity<?> softArchive(@PathVariable String artifactId,
                                          @RequestParam String workspaceKind,
                                          @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        boolean result = retentionService.softArchive(artifactId, access);
        if (!result) {
            PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
            if (record == null || !access.accepts(record)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Artifact not found"));
            }
            String blocker = retentionService.removalBlocker(artifactId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot archive", "blocker", blocker));
        }
        return ResponseEntity.ok(Map.of("status", "soft-archived", "artifactId", artifactId));
    }

    @PostMapping("/api/artifacts/{artifactId}/retention/hard-expire")
    public ResponseEntity<?> hardExpire(@PathVariable String artifactId,
                                         @RequestParam String workspaceKind,
                                         @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        boolean result = retentionService.hardExpire(artifactId, access);
        if (!result) {
            PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
            if (record == null || !access.accepts(record)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Artifact not found"));
            }
            String blocker = retentionService.removalBlocker(artifactId);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Cannot expire", "blocker", blocker));
        }
        return ResponseEntity.ok(Map.of("status", "hard-expired", "artifactId", artifactId));
    }

    // ── Task-run retention ──

    @PostMapping("/api/runs/{taskRunId}/retention/soft-archive")
    public ResponseEntity<?> softArchiveTaskRun(@PathVariable String taskRunId,
                                                  @RequestParam String workspaceKind,
                                                  @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forTaskRun(workspaceKind, workspaceKey, taskRunId);
        RetentionBatchResult result = retentionService.softArchiveTaskRun(taskRunId, access);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/runs/{taskRunId}/retention/hard-expire")
    public ResponseEntity<?> hardExpireTaskRun(@PathVariable String taskRunId,
                                                 @RequestParam String workspaceKind,
                                                 @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forTaskRun(workspaceKind, workspaceKey, taskRunId);
        RetentionBatchResult result = retentionService.hardExpireTaskRun(taskRunId, access);
        return ResponseEntity.ok(result);
    }

    // ── Retention policies ──

    @GetMapping("/api/runs/{taskRunId}/retention/policy")
    public ResponseEntity<?> getPolicy(@PathVariable String taskRunId,
                                        @RequestParam String workspaceKind,
                                        @RequestParam String workspaceKey,
                                        @RequestParam(required = false) String taskPathKey) {
        RetentionPolicy policy = retentionService.getPolicy(workspaceKind, workspaceKey, taskPathKey);
        return ResponseEntity.ok(policy);
    }

    @PostMapping("/api/runs/{taskRunId}/retention/policy")
    public ResponseEntity<?> setPolicy(@PathVariable String taskRunId,
                                        @RequestParam String workspaceKind,
                                        @RequestParam String workspaceKey,
                                        @RequestParam(required = false) String taskPathKey,
                                        @RequestParam String policyType,
                                        @RequestParam(required = false) Integer retainDays,
                                        @RequestParam(required = false) Integer maxArtifactCount,
                                        @RequestParam(required = false) Integer maxTotalBytes) {
        try {
            RetentionPolicy policy = retentionService.setPolicy(workspaceKind, workspaceKey,
                    taskPathKey, policyType, retainDays, maxArtifactCount, maxTotalBytes);
            return ResponseEntity.ok(policy);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Pins ──

    @GetMapping("/api/artifacts/{artifactId}/pins")
    public ResponseEntity<?> listPins(@PathVariable String artifactId,
                                       @RequestParam String workspaceKind,
                                       @RequestParam String workspaceKey) {
        ArtifactAccessContext access = ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Artifact not found"));
        }
        return ResponseEntity.ok(retentionService.listPins(artifactId, null));
    }

    // ── Listing by workspace ──

    @GetMapping("/api/artifacts")
    public ResponseEntity<?> listByWorkspace(@RequestParam String workspaceKind,
                                              @RequestParam String workspaceKey,
                                              @RequestParam(required = false) String taskRunId,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "50") int pageSize) {
        ArtifactAccessContext access = StringUtils.hasText(taskRunId)
                ? ArtifactAccessContext.forTaskRun(workspaceKind, workspaceKey, taskRunId)
                : ArtifactAccessContext.forWorkspace(workspaceKind, workspaceKey);

        List<PublicationRecord> records;
        if (StringUtils.hasText(taskRunId)) {
            records = artifactRepository.findByTaskRunId(taskRunId).stream()
                    .filter(access::accepts)
                    .skip((long) page * pageSize)
                    .limit(pageSize)
                    .toList();
        } else {
            records = artifactRepository.findPublishedByWorkspace(workspaceKind, workspaceKey).stream()
                    .filter(access::accepts)
                    .skip((long) page * pageSize)
                    .limit(pageSize)
                    .toList();
        }

        return ResponseEntity.ok(records);
    }
}
