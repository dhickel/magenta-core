package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Authorization-first artifact search, extraction, and byte access.
 *
 * <h3>Authorization contract</h3>
 * <ul>
 *   <li>Every method accepts {@link ArtifactAccessContext} before loading any data.</li>
 *   <li>Search SQL embeds authorization predicates directly; rows are never
 *       post-filtered from an unrestricted result set.</li>
 *   <li>Byte access revalidates metadata, state, digest, and authorization
 *       before opening any file handle.</li>
 * </ul>
 *
 * <h3>FTS5 contract</h3>
 * <ul>
 *   <li>FTS5 is probed at service construction. Unsupported deployments expose
 *       {@link #ftsAvailable()} = {@code false} and searches return unavailable.</li>
 *   <li>Extraction/backfill and rebuild are resumable and digest-keyed.</li>
 * </ul>
 */
@Service
public class ArtifactSearchService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactSearchService.class);
    private static final int MAX_EXTRACT_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final int MAX_EXTRACT_LINES = 50_000;
    private static final int DEFAULT_PAGE_SIZE = 25;

    private final JdbcTemplate jdbc;
    private final OutputArtifactRepository artifactRepository;
    private final RootRelativePathService rootRelativePathService;
    private final WorkspaceDirectoryService directoryService;
    private final ConfinedPathMutationService mutations;
    private final ArtifactExtractionRepository extractionRepo;
    private final boolean ftsAvailable;

    /**
     * Production constructor: Spring injects the extraction repository.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ArtifactSearchService(JdbcTemplate jdbc,
                                  OutputArtifactRepository artifactRepository,
                                  RootRelativePathService rootRelativePathService,
                                  WorkspaceDirectoryService directoryService,
                                  ConfinedPathMutationService mutations,
                                  ArtifactExtractionRepository extractionRepo) {
        this.jdbc = jdbc;
        this.artifactRepository = artifactRepository;
        this.rootRelativePathService = rootRelativePathService;
        this.directoryService = directoryService;
        this.mutations = mutations;
        this.extractionRepo = extractionRepo;
        this.ftsAvailable = probeFts5();
        if (!ftsAvailable) {
            log.warn("SQLite FTS5 is not available; artifact search is unavailable");
        }
    }

    /**
     * Direct-construction signature retained for explicit test compatibility.
     * New atomicity tests use the Spring-managed repository rather than this path.
     */
    public ArtifactSearchService(JdbcTemplate jdbc,
                                  OutputArtifactRepository artifactRepository,
                                  RootRelativePathService rootRelativePathService,
                                  WorkspaceDirectoryService directoryService,
                                  ConfinedPathMutationService mutations) {
        this(jdbc, artifactRepository, rootRelativePathService, directoryService, mutations,
                new ArtifactExtractionRepository(jdbc));
    }

    /** Whether FTS5-backed full-text search is available. */
    public boolean ftsAvailable() {
        return ftsAvailable;
    }

    // ── FTS5 probe ──

    private boolean probeFts5() {
        try {
            jdbc.execute("select fts5(?1)");
            return true;
        } catch (Exception compileFailure) {
            try {
                // Runtime check: try to create a private temporary FTS5 table
                String probeTable = "fts5_probe_" + UUID.randomUUID().toString().replace("-", "");
                jdbc.execute("create virtual table if not exists " + probeTable
                        + " using fts5(content)");
                jdbc.execute("drop table if exists " + probeTable);
                return true;
            } catch (Exception runtimeFailure) {
                return false;
            }
        }
    }

    // ── Extraction ──

    /**
     * Extract text from a published artifact for FTS indexing.
     * Only readable artifacts with supported text media types are extracted.
     */
    public Optional<ExtractionRecord> extractText(String artifactId, ArtifactAccessContext access) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) return Optional.empty();
        if (!record.isReadable()) return Optional.empty();

        String media = record.mediaType();
        if (media == null) return Optional.empty();

        ExtractionRecord result;
        if (!isTextMedia(media)) {
            result = ExtractionRecord.unsupported(artifactId, record.contentSha256(), media);
        } else {
            try {
                Path file = rootRelativePathService.resolve(record.rootRelativePath());
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }

                long fileSize = Files.size(file);
                boolean truncated = fileSize > MAX_EXTRACT_BYTES;
                byte[] bytes;
                if (truncated) {
                    try (InputStream in = Files.newInputStream(file)) {
                        bytes = in.readNBytes(MAX_EXTRACT_BYTES);
                    }
                } else {
                    bytes = Files.readAllBytes(file);
                }

                String text = new String(bytes, StandardCharsets.UTF_8);
                int lineCount = 0;
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\n') lineCount++;
                }
                if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') lineCount++;

                String languageHint = languageHint(media);
                result = truncated
                        ? ExtractionRecord.truncated(artifactId, record.contentSha256(), text, bytes.length, lineCount, languageHint)
                        : ExtractionRecord.extracted(artifactId, record.contentSha256(), text, bytes.length, lineCount, languageHint);
            } catch (Exception e) {
                log.warn("Extraction failed for artifact {}: {}", artifactId,
                        e.getClass().getSimpleName());
                result = ExtractionRecord.failed(artifactId, record.contentSha256(), "");
            }
        }

        upsertExtraction(result);
        return Optional.of(result);
    }

    private void upsertExtraction(ExtractionRecord record) {
        try {
            extractionRepo.synchronizeExtraction(record, ftsAvailable);
        } catch (Exception e) {
            log.warn("Extraction persistence failed for artifact {}: {}",
                    record.artifactId(), e.getClass().getSimpleName());
            throw new RuntimeException(
                    "Extraction persistence failed for artifact " + record.artifactId(), e);
        }
    }

    // ── Backfill ──

    /**
     * Backfill extraction for all published, readable artifacts in the
     * authorized scope that do not yet have an extraction document.
     * Returns the number of artifacts processed.
     */
    public int backfill(ArtifactAccessContext access, int batchSize) {
        List<String> candidates = jdbc.query("""
                select op.artifact_id from output_publications op
                where op.publication_state = 'PUBLISHED'
                  and op.reconciliation_state = 'RECONCILED'
                  and op.verified_sha256 is not null
                  and op.workspace_kind = ? and op.workspace_key = ?
                  and (op.task_run_id = ? or ? is null)
                  and op.artifact_id not in (
                      select artifact_id from artifact_extraction_documents)
                order by op.published_at desc
                limit ?
                """,
                (rs, rowNum) -> rs.getString(1),
                access.workspaceKind(), access.workspaceKey(),
                access.taskRunId(), access.taskRunId(),
                batchSize);

        int processed = 0;
        for (String artifactId : candidates) {
            try {
                var result = extractText(artifactId, access);
                if (result.isPresent()) {
                    var state = result.get().extractionState();
                    if ("EXTRACTED".equals(state) || "TRUNCATED".equals(state)) {
                        processed++;
                    }
                }
            } catch (Exception e) {
                log.warn("Backfill extraction failed for {}: {}",
                        artifactId, e.getClass().getSimpleName());
            }
        }
        return processed;
    }

    // ── Rebuild ──

    /**
     * Rebuild the FTS index for the authorized workspace scope.
     * Deletes all existing extraction documents for the scope, then backfills.
     */
    public int rebuildIndex(ArtifactAccessContext access, int batchSize) {
        if (!ftsAvailable) return 0;

        extractionRepo.removeScopedDocuments(
                access.workspaceKind(), access.workspaceKey(),
                access.taskRunId(), ftsAvailable);

        return backfill(access, batchSize);
    }

    // ── Search ──

    /**
     * Search artifacts by full-text query. Authorization is applied inside
     * the SQL before counts/snippets are computed. Results are bounded to a
     * single page plus a capability state.
     */
    public SearchResult search(String query, ArtifactAccessContext access, int page, int pageSize) {
        if (!ftsAvailable) {
            return SearchResult.unavailable();
        }
        if (!StringUtils.hasText(query)) {
            return SearchResult.empty();
        }

        int limit = Math.max(1, Math.min(pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE, 100));
        int offset = Math.max(0, page) * limit;

        // Build FTS5 query with authorization embedded
        // Join: fts -> extraction_docs -> output_publications (authorized)
        String sql;
        Object[] params;
        boolean hasTaskRun = StringUtils.hasText(access.taskRunId());

        if (hasTaskRun) {
            sql = """
                    select op.artifact_id, op.logical_name, op.media_type,
                           op.content_sha256, op.verified_size, op.published_at,
                           ed.extraction_state, ed.line_count,
                           snippet(artifact_extraction_fts, 2, '<mark>', '</mark>', '...', 32) as snippet_text
                    from artifact_extraction_fts fts
                    join artifact_extraction_documents ed on ed.id = fts.rowid
                    join output_publications op on op.artifact_id = ed.artifact_id
                    where artifact_extraction_fts match ?
                      and op.publication_state = 'PUBLISHED'
                      and op.reconciliation_state = 'RECONCILED'
                      and op.workspace_kind = ? and op.workspace_key = ? and op.task_run_id = ?
                    order by rank
                    limit ? offset ?
                    """;
            params = new Object[]{query, access.workspaceKind(), access.workspaceKey(),
                    access.taskRunId(), limit, offset};
        } else {
            sql = """
                    select op.artifact_id, op.logical_name, op.media_type,
                           op.content_sha256, op.verified_size, op.published_at,
                           ed.extraction_state, ed.line_count,
                           snippet(artifact_extraction_fts, 2, '<mark>', '</mark>', '...', 32) as snippet_text
                    from artifact_extraction_fts fts
                    join artifact_extraction_documents ed on ed.id = fts.rowid
                    join output_publications op on op.artifact_id = ed.artifact_id
                    where artifact_extraction_fts match ?
                      and op.publication_state = 'PUBLISHED'
                      and op.reconciliation_state = 'RECONCILED'
                      and op.workspace_kind = ? and op.workspace_key = ?
                    order by rank
                    limit ? offset ?
                    """;
            params = new Object[]{query, access.workspaceKind(), access.workspaceKey(), limit, offset};
        }

        List<SearchResultItem> items = jdbc.query(sql, (rs, rowNum) -> new SearchResultItem(
                rs.getString("artifact_id"),
                rs.getString("logical_name"),
                rs.getString("media_type"),
                rs.getString("content_sha256"),
                rs.getObject("verified_size") != null ? rs.getLong("verified_size") : -1L,
                rs.getString("published_at"),
                rs.getString("extraction_state"),
                rs.getObject("line_count") != null ? rs.getInt("line_count") : 0,
                rs.getString("snippet_text")
        ), params);

        // Count total matching rows (authorized)
        int total = countTotal(query, access);

        return new SearchResult(items, total, page, limit, items.size() == limit, ftsAvailable);
    }

    private int countTotal(String query, ArtifactAccessContext access) {
        String sql;
        Object[] params;
        boolean hasTaskRun = StringUtils.hasText(access.taskRunId());
        if (hasTaskRun) {
            sql = """
                    select count(*) from artifact_extraction_fts fts
                    join artifact_extraction_documents ed on ed.id = fts.rowid
                    join output_publications op on op.artifact_id = ed.artifact_id
                    where artifact_extraction_fts match ?
                      and op.publication_state = 'PUBLISHED'
                      and op.reconciliation_state = 'RECONCILED'
                      and op.workspace_kind = ? and op.workspace_key = ? and op.task_run_id = ?
                    """;
            params = new Object[]{query, access.workspaceKind(), access.workspaceKey(), access.taskRunId()};
        } else {
            sql = """
                    select count(*) from artifact_extraction_fts fts
                    join artifact_extraction_documents ed on ed.id = fts.rowid
                    join output_publications op on op.artifact_id = ed.artifact_id
                    where artifact_extraction_fts match ?
                      and op.publication_state = 'PUBLISHED'
                      and op.reconciliation_state = 'RECONCILED'
                      and op.workspace_kind = ? and op.workspace_key = ?
                    """;
            params = new Object[]{query, access.workspaceKind(), access.workspaceKey()};
        }
        Integer count = jdbc.queryForObject(sql, Integer.class, params);
        return count != null ? count : 0;
    }

    // ── Authorized byte access ──

    /**
     * Open an authorized, bounded byte handle for a published artifact.
     * Revalidates metadata/state/digest and authorization before opening.
     * The returned handle is {@link AutoCloseable} and unregisters on close.
     *
     * @return an {@link AuthorizedByteHandle} or {@code empty} if not authorized/readable
     */
    public Optional<AuthorizedByteHandle> openAuthorizedBytes(String artifactId,
                                                               ArtifactAccessContext access,
                                                               int maxBytes) throws IOException {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) return Optional.empty();
        if (!record.isReadable()) return Optional.empty();

        Path file = rootRelativePathService.resolve(record.rootRelativePath());
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            artifactRepository.markIntegrityFailed(artifactId, "Bytes missing on authorized open", "UNRESOLVED");
            return Optional.empty();
        }

        // Revalidate integrity
        byte[] verifyBytes;
        try {
            verifyBytes = Files.readAllBytes(file);
        } catch (IOException e) {
            artifactRepository.markIntegrityFailed(artifactId, "Failed to read for digest verification", "UNRESOLVED");
            return Optional.empty();
        }
        String digest = sha256Hex(verifyBytes);
        if (!record.expectedSha256().equals(digest)
                || !record.verifiedSha256().equals(digest)
                || record.verifiedSize() != verifyBytes.length) {
            artifactRepository.markIntegrityFailed(artifactId, "Digest/size mismatch at byte open", "UNRESOLVED");
            return Optional.empty();
        }

        // Register an active session
        String sessionToken = UUID.randomUUID().toString();
        String ownerIdentity = access.workspaceKind() + "/" + access.workspaceKey();
        Instant now = Instant.now();
        jdbc.update("""
                insert into active_artifact_sessions(
                    artifact_id, session_token, owner_identity, bytes_read, max_bytes,
                    state, created_at, last_activity)
                values (?, ?, ?, 0, ?, 'ACTIVE', ?, ?)
                """, artifactId, sessionToken, ownerIdentity,
                maxBytes > 0 ? maxBytes : null,
                now.toString(), now.toString());

        // Open the file with no-follow
        InputStream stream;
        try {
            stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // Clean up session on failure
            closeSession(sessionToken, "CANCELLED");
            throw e;
        }

        return Optional.of(new AuthorizedByteHandle(
                artifactId, sessionToken, stream, file, record, access, jdbc, maxBytes));
    }

    private void closeSession(String sessionToken, String state) {
        jdbc.update("""
                update active_artifact_sessions
                set state = ?, completed_at = ?
                where session_token = ? and state = 'ACTIVE'
                """, state, Instant.now().toString(), sessionToken);
    }

    // ── Lineage ──

    /**
     * Record a producer-declared lineage edge. Validates endpoints, cycles,
     * duplicates, and cross-workspace rules.
     */
    public LineageEdge recordLineage(String parentArtifactId, String childArtifactId,
                                      String lineageKind, String producerWorkspaceKind,
                                      String producerWorkspaceKey, String producerTaskRunId,
                                      ArtifactAccessContext access) {
        if (parentArtifactId.equals(childArtifactId)) {
            throw new IllegalArgumentException("Self-referencing lineage edge rejected");
        }

        PublicationRecord parent = artifactRepository.findById(parentArtifactId).orElse(null);
        PublicationRecord child = artifactRepository.findById(childArtifactId).orElse(null);
        if (parent == null || child == null) {
            throw new IllegalArgumentException("Parent or child artifact not found");
        }
        if (!access.accepts(parent) || !access.accepts(child)) {
            throw new IllegalArgumentException("Lineage edge not authorized for this scope");
        }

        // Validate lineage kind
        if (!VALID_LINEAGE_KINDS.contains(lineageKind)) {
            throw new IllegalArgumentException("Unknown lineage kind: " + lineageKind);
        }

        // Check for duplicate
        Integer existing = jdbc.queryForObject("""
                select count(*) from artifact_lineage_edges
                where parent_artifact_id = ? and child_artifact_id = ? and lineage_kind = ?
                """, Integer.class, parentArtifactId, childArtifactId, lineageKind);
        if (existing != null && existing > 0) {
            throw new IllegalArgumentException("Duplicate lineage edge");
        }

        // Check for cycles (child cannot already be an ancestor of parent)
        if (hasAncestor(childArtifactId, parentArtifactId)) {
            throw new IllegalArgumentException("Lineage cycle detected");
        }

        String declarationDigest = sha256Hex((parentArtifactId + "/" + childArtifactId + "/"
                + lineageKind + "/" + producerWorkspaceKind + "/" + producerWorkspaceKey).getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();

        jdbc.update("""
                insert into artifact_lineage_edges(
                    parent_artifact_id, child_artifact_id, lineage_kind,
                    producer_workspace_kind, producer_workspace_key,
                    producer_task_run_id, declaration_digest, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, parentArtifactId, childArtifactId, lineageKind,
                producerWorkspaceKind, producerWorkspaceKey,
                producerTaskRunId, declarationDigest, now.toString());

        // Auto-pin the parent for lineage
        ensureLineagePin(parentArtifactId);

        return new LineageEdge(parentArtifactId, childArtifactId, lineageKind,
                producerWorkspaceKind, producerWorkspaceKey, producerTaskRunId, now);
    }

    /**
     * Get lineage edges for an artifact (both parents and children).
     */
    public LineageResult lineage(String artifactId, ArtifactAccessContext access) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return LineageResult.unauthorized();
        }

        List<LineageEdge> parents = jdbc.query("""
                select parent_artifact_id, child_artifact_id, lineage_kind,
                       producer_workspace_kind, producer_workspace_key,
                       producer_task_run_id, created_at
                from artifact_lineage_edges
                where child_artifact_id = ?
                order by created_at
                """, (rs, rowNum) -> new LineageEdge(
                rs.getString("parent_artifact_id"),
                rs.getString("child_artifact_id"),
                rs.getString("lineage_kind"),
                rs.getString("producer_workspace_kind"),
                rs.getString("producer_workspace_key"),
                rs.getString("producer_task_run_id"),
                Instant.parse(rs.getString("created_at"))
        ), artifactId);

        List<LineageEdge> children = jdbc.query("""
                select parent_artifact_id, child_artifact_id, lineage_kind,
                       producer_workspace_kind, producer_workspace_key,
                       producer_task_run_id, created_at
                from artifact_lineage_edges
                where parent_artifact_id = ?
                order by created_at
                """, (rs, rowNum) -> new LineageEdge(
                rs.getString("parent_artifact_id"),
                rs.getString("child_artifact_id"),
                rs.getString("lineage_kind"),
                rs.getString("producer_workspace_kind"),
                rs.getString("producer_workspace_key"),
                rs.getString("producer_task_run_id"),
                Instant.parse(rs.getString("created_at"))
        ), artifactId);

        return new LineageResult(artifactId, parents, children);
    }

    private boolean hasAncestor(String parentId, String targetId) {
        // Depth-first search to detect cycles (targetId is ancestor of parentId?)
        List<String> visited = new ArrayList<>();
        return hasAncestorRecursive(targetId, parentId, visited, 10);
    }

    private boolean hasAncestorRecursive(String current, String target, List<String> visited, int depth) {
        if (depth <= 0) return false;
        if (current.equals(target)) return true;
        if (visited.contains(current)) return false;
        visited.add(current);
        List<String> parents = jdbc.query(
                "select parent_artifact_id from artifact_lineage_edges where child_artifact_id = ?",
                (rs, rowNum) -> rs.getString(1), current);
        for (String parent : parents) {
            if (hasAncestorRecursive(parent, target, visited, depth - 1)) return true;
        }
        return false;
    }

    private void ensureLineagePin(String artifactId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from artifact_retention_pins
                where artifact_id = ? and pin_kind = 'LINEAGE'
                """, Integer.class, artifactId);
        if (count == null || count == 0) {
            jdbc.update("""
                    insert into artifact_retention_pins(
                        artifact_id, pin_kind, reason, pinned_at)
                    values (?, 'LINEAGE', 'Auto-pin: lineage edge dependency', ?)
                    """, artifactId, Instant.now().toString());
        }
    }

    // ── Helpers ──

    private static boolean isTextMedia(String media) {
        if (media == null) return false;
        String lower = media.toLowerCase();
        return lower.startsWith("text/")
                || lower.equals("application/json")
                || lower.equals("application/xml")
                || lower.equals("application/javascript")
                || lower.contains("markdown")
                || lower.contains("csv");
    }

    private static String languageHint(String media) {
        if (media == null) return null;
        String lower = media.toLowerCase();
        if (lower.contains("markdown")) return "markdown";
        if (lower.contains("json")) return "json";
        if (lower.contains("xml") || lower.contains("html")) return "xml";
        if (lower.contains("csv")) return "csv";
        if (lower.contains("javascript")) return "javascript";
        return "text";
    }

    private static final Set<String> VALID_LINEAGE_KINDS = Set.of("DERIVED_FROM", "GENERATED_BY", "TRANSFORMED_FROM");

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Record types ──

    public record ExtractionRecord(
            String artifactId,
            String contentSha256,
            String extractedText,
            int byteCount,
            int lineCount,
            String languageHint,
            String extractionState,
            String extractedAt) {
        static ExtractionRecord extracted(String id, String sha, String text, int bytes, int lines, String lang) {
            return new ExtractionRecord(id, sha, text, bytes, lines, lang, "EXTRACTED", Instant.now().toString());
        }
        static ExtractionRecord truncated(String id, String sha, String text, int bytes, int lines, String lang) {
            return new ExtractionRecord(id, sha, text, bytes, lines, lang, "TRUNCATED", Instant.now().toString());
        }
        static ExtractionRecord unsupported(String id, String sha, String media) {
            return new ExtractionRecord(id, sha, null, 0, 0, null, "UNSUPPORTED", Instant.now().toString());
        }
        static ExtractionRecord failed(String id, String sha, String reason) {
            return new ExtractionRecord(id, sha, null, 0, 0, null, "FAILED", Instant.now().toString());
        }
    }

    public record SearchResultItem(
            String artifactId,
            String logicalName,
            String mediaType,
            String contentSha256,
            long verifiedSize,
            String publishedAt,
            String extractionState,
            int lineCount,
            String snippet) {}

    public record SearchResult(
            List<SearchResultItem> items,
            int totalCount,
            int page,
            int pageSize,
            boolean hasMore,
            boolean ftsAvailable) {
        static SearchResult empty() {
            return new SearchResult(List.of(), 0, 0, DEFAULT_PAGE_SIZE, false, true);
        }
        static SearchResult unavailable() {
            return new SearchResult(List.of(), 0, 0, 0, false, false);
        }
    }

    public record LineageEdge(
            String parentArtifactId,
            String childArtifactId,
            String lineageKind,
            String producerWorkspaceKind,
            String producerWorkspaceKey,
            String producerTaskRunId,
            Instant createdAt) {}

    public record LineageResult(
            String artifactId,
            List<LineageEdge> parents,
            List<LineageEdge> children) {
        static LineageResult unauthorized() {
            return new LineageResult(null, List.of(), List.of());
        }
    }

    // ── Authorized byte handle ──

    /**
     * AutoCloseable byte handle for authorized artifact access.
     * Revalidates metadata/state/digest at construction, registers a
     * bounded read session, opens with no-follow, and unregisters on close.
     */
    public static class AuthorizedByteHandle implements AutoCloseable, Closeable {
        private final String artifactId;
        private final String sessionToken;
        private final InputStream inputStream;
        private final Path path;
        private final PublicationRecord record;
        private final ArtifactAccessContext access;
        private final JdbcTemplate jdbc;
        private final int maxBytes;
        private long bytesRead;
        private boolean closed;

        AuthorizedByteHandle(String artifactId, String sessionToken,
                             InputStream inputStream, Path path,
                             PublicationRecord record, ArtifactAccessContext access,
                             JdbcTemplate jdbc, int maxBytes) {
            this.artifactId = artifactId;
            this.sessionToken = sessionToken;
            this.inputStream = inputStream;
            this.path = path;
            this.record = record;
            this.access = access;
            this.jdbc = jdbc;
            this.maxBytes = maxBytes;
            this.bytesRead = 0;
            this.closed = false;
        }

        public InputStream inputStream() { return inputStream; }
        public String artifactId() { return artifactId; }
        public PublicationRecord record() { return record; }
        public Path path() { return path; }
        public long bytesRead() { return bytesRead; }
        public int maxBytes() { return maxBytes; }

        public byte[] readAllBytes() throws IOException {
            if (closed) throw new IOException("Handle is closed");
            if (maxBytes > 0 && bytesRead >= maxBytes) {
                return new byte[0];
            }
            byte[] data;
            if (maxBytes > 0) {
                int remaining = maxBytes - (int) bytesRead;
                data = inputStream.readNBytes(remaining);
            } else {
                data = inputStream.readAllBytes();
            }
            bytesRead += data.length;
            updateSession();
            return data;
        }

        public byte[] readNBytes(int len) throws IOException {
            if (closed) throw new IOException("Handle is closed");
            int allowed = maxBytes > 0 ? Math.min(len, maxBytes - (int) bytesRead) : len;
            if (allowed <= 0) return new byte[0];
            byte[] data = inputStream.readNBytes(allowed);
            bytesRead += data.length;
            updateSession();
            return data;
        }

        private void updateSession() {
            jdbc.update("""
                    update active_artifact_sessions
                    set bytes_read = ?, last_activity = ?
                    where session_token = ? and state = 'ACTIVE'
                    """, bytesRead, Instant.now().toString(), sessionToken);
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                inputStream.close();
            } finally {
                jdbc.update("""
                        update active_artifact_sessions
                        set state = 'COMPLETED', bytes_read = ?, completed_at = ?, last_activity = ?
                        where session_token = ? and state = 'ACTIVE'
                        """, bytesRead, Instant.now().toString(), Instant.now().toString(), sessionToken);
            }
        }
    }
}
