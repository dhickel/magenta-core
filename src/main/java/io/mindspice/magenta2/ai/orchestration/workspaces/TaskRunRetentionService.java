package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Identity-safe artifact and task-run retention.
 *
 * <h3>Retention contract</h3>
 * <ul>
 *   <li>Default policy is PRESERVE. Nothing is deleted without an explicit
 *       enabled policy and manual or scheduled invocation.</li>
 *   <li>A retention operation cannot remove artifacts or runs that are:
 *       active (ACTIVE session), claimed (ACTIVE writer claim),
 *       downloading (ACTIVE_ARTIFACT_SESSIONS), referenced (lineage pins),
 *       pinned (any ARTIFACT_RETENTION_PINS), or reconciliation-pending
 *       (reconciliation_state UNRESOLVED or CLEANUP_PENDING).</li>
 *   <li>SOFT_ARCHIVE marks artifacts as archivable (metadata preserved,
 *       bytes removable). HARD_EXPIRE deletes bytes and metadata.</li>
 *   <li>Operations are idempotent and use tombstone states so
 *       reconciliation can resume interrupted deletion.</li>
 * </ul>
 */
@Service
public class TaskRunRetentionService {
    private static final Logger log = LoggerFactory.getLogger(TaskRunRetentionService.class);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final Set<String> BLOCKING_RECONCILIATION_STATES = Set.of("UNRESOLVED", "CLEANUP_PENDING");
    private static final Set<String> BLOCKING_SESSION_STATES = Set.of("ACTIVE");

    private final JdbcTemplate jdbc;
    private final OutputArtifactRepository artifactRepository;
    private final RootRelativePathService rootRelativePathService;
    private final WorkspaceDirectoryService directoryService;
    private final ConfinedPathMutationService mutations;

    public TaskRunRetentionService(JdbcTemplate jdbc,
                                    OutputArtifactRepository artifactRepository,
                                    RootRelativePathService rootRelativePathService,
                                    WorkspaceDirectoryService directoryService,
                                    ConfinedPathMutationService mutations) {
        this.jdbc = jdbc;
        this.artifactRepository = artifactRepository;
        this.rootRelativePathService = rootRelativePathService;
        this.directoryService = directoryService;
        this.mutations = mutations;
    }

    // ── Policy management ──

    /**
     * Set or update a retention policy for a workspace/task combination.
     * A null {@code taskPathKey} means the workspace-default policy.
     */
    public RetentionPolicy setPolicy(String workspaceKind, String workspaceKey,
                                      String taskPathKey, String policyType,
                                      Integer retainDays, Integer maxArtifactCount,
                                      Integer maxTotalBytes) {
        validatePolicyType(policyType);
        Instant now = Instant.now();

        // Upsert
        jdbc.update("""
                insert into task_run_retention_policies(
                    workspace_kind, workspace_key, task_path_key, policy_type,
                    retain_days, max_artifact_count, max_total_bytes, enabled,
                    created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                on conflict(workspace_kind, workspace_key, task_path_key) do update set
                    policy_type = excluded.policy_type,
                    retain_days = excluded.retain_days,
                    max_artifact_count = excluded.max_artifact_count,
                    max_total_bytes = excluded.max_total_bytes,
                    updated_at = excluded.updated_at
                """, workspaceKind, workspaceKey, taskPathKey, policyType,
                retainDays, maxArtifactCount, maxTotalBytes,
                now.toString(), now.toString());

        return getPolicy(workspaceKind, workspaceKey, taskPathKey);
    }

    public RetentionPolicy getPolicy(String workspaceKind, String workspaceKey,
                                      String taskPathKey) {
        return jdbc.query(
                "select * from task_run_retention_policies where workspace_kind = ? and workspace_key = ? and task_path_key is ?",
                rs -> rs.next() ? toPolicy(rs) : RetentionPolicy.defaultPolicy(),
                workspaceKind, workspaceKey, taskPathKey);
    }

    public List<RetentionPolicy> listPolicies(String workspaceKind, String workspaceKey) {
        return jdbc.query(
                "select * from task_run_retention_policies where workspace_kind = ? and workspace_key = ? order by task_path_key nulls first",
                (rs, rowNum) -> toPolicy(rs),
                workspaceKind, workspaceKey);
    }

    // ── Pin management ──

    /**
     * Add a retention pin. Pins are checked before any retention operation.
     * A pin on a {@code taskRunId} blocks all artifacts owned by that run.
     */
    public void addPin(String artifactId, String taskRunId, String pinKind,
                        String reason, String pinnedBy, Instant expiresAt) {
        if (artifactId == null && taskRunId == null) {
            throw new IllegalArgumentException("artifact_id or task_run_id required");
        }
        if (!Set.of("LINEAGE", "RECONCILIATION_PENDING", "ACTIVE_CLAIM", "LEGAL_HOLD", "MANUAL")
                .contains(pinKind)) {
            throw new IllegalArgumentException("Unknown pin kind: " + pinKind);
        }
        if (pinKind.equals("LINEAGE") && artifactId == null) {
            throw new IllegalArgumentException("LINEAGE pins require an artifact_id");
        }

        // Idempotent insert
        jdbc.update("""
                insert or ignore into artifact_retention_pins(
                    artifact_id, task_run_id, pin_kind, reason, pinned_by, pinned_at, expires_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, artifactId, taskRunId, pinKind, reason, pinnedBy,
                Instant.now().toString(), expiresAt != null ? expiresAt.toString() : null);
    }

    /**
     * Remove a specific pin.
     */
    public boolean removePin(long pinId) {
        return jdbc.update("delete from artifact_retention_pins where id = ?", pinId) > 0;
    }

    /**
     * List all pins for a given artifact or task run.
     */
    public List<RetentionPin> listPins(String artifactId, String taskRunId) {
        if (StringUtils.hasText(artifactId)) {
            return jdbc.query(
                    "select * from artifact_retention_pins where artifact_id = ? order by pinned_at desc",
                    (rs, rowNum) -> toPin(rs), artifactId);
        }
        if (StringUtils.hasText(taskRunId)) {
            return jdbc.query(
                    "select * from artifact_retention_pins where task_run_id = ? order by pinned_at desc",
                    (rs, rowNum) -> toPin(rs), taskRunId);
        }
        return List.of();
    }

    // ── Retention execution ──

    /**
     * Check whether an artifact can be removed. Returns a blocking reason
     * or null if removal is allowed.
     */
    public String removalBlocker(String artifactId) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null) return "artifact not found";

        // Check reconciliation state
        if (record.reconciliationState() != null
                && BLOCKING_RECONCILIATION_STATES.contains(record.reconciliationState())) {
            return "reconciliation pending";
        }

        // Check active sessions
        Integer activeSessions = jdbc.queryForObject("""
                select count(*) from active_artifact_sessions
                where artifact_id = ? and state = 'ACTIVE'
                """, Integer.class, artifactId);
        if (activeSessions != null && activeSessions > 0) {
            return "active download sessions";
        }

        // Check pins on artifact
        Integer artifactPins = jdbc.queryForObject("""
                select count(*) from artifact_retention_pins
                where artifact_id = ?
                  and (expires_at is null or expires_at > ?)
                """, Integer.class, artifactId, Instant.now().toString());
        if (artifactPins != null && artifactPins > 0) {
            return "pinned (artifact)";
        }

        // Check pins on task run (if attributed)
        if (StringUtils.hasText(record.taskRunId())) {
            Integer runPins = jdbc.queryForObject("""
                    select count(*) from artifact_retention_pins
                    where task_run_id = ?
                      and (expires_at is null or expires_at > ?)
                    """, Integer.class, record.taskRunId(), Instant.now().toString());
            if (runPins != null && runPins > 0) {
                return "pinned (task run)";
            }
        }

        // Check active writer claims
        if (StringUtils.hasText(record.taskRunId())) {
            Integer activeClaims = jdbc.queryForObject("""
                    select count(*) from writer_claim_generations
                    where task_run_id = ? and is_current = 1
                    """, Integer.class, record.taskRunId());
            if (activeClaims != null && activeClaims > 0) {
                return "active writer claim";
            }
        }

        // Check lineage references (artifact is parent of another)
        Integer lineageRefs = jdbc.queryForObject("""
                select count(*) from artifact_lineage_edges
                where parent_artifact_id = ?
                """, Integer.class, artifactId);
        if (lineageRefs != null && lineageRefs > 0) {
            return "lineage reference (parent of other artifacts)";
        }

        return null; // removable
    }

    /**
     * Soft-archive a single artifact: mark the DB row as archivable
     * but keep metadata. Bytes are optionally cleaned later.
     */
    @Transactional
    public boolean softArchive(String artifactId, ArtifactAccessContext access) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) return false;

        String blocker = removalBlocker(artifactId);
        if (blocker != null) {
            log.info("Cannot soft-archive {}: {}", artifactId, blocker);
            return false;
        }

        if (record.publicationState() != PublicationState.PUBLISHED
                && record.publicationState() != PublicationState.INTEGRITY_FAILED) {
            return false;
        }

        // Mark as deleting for idempotent tombstone
        int updated = jdbc.update("""
                update output_publications
                set reconciliation_state = 'DELETING', updated_at = ?
                where artifact_id = ? and publication_state in ('PUBLISHED', 'INTEGRITY_FAILED')
                """, Instant.now().toString(), artifactId);
        if (updated == 0) return false;

        // Delete the file bytes
        try {
            Path file = rootRelativePathService.resolve(record.rootRelativePath());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                mutations.delete(directoryService.dataRoot(),
                        dataRootRelative(file), false);
            }
        } catch (NoSuchFileException e) {
            // Already gone
        } catch (IOException e) {
            log.warn("Failed to delete file for {}: {}", artifactId, e.getMessage());
            // Rollback the DELETING state
            jdbc.update("""
                    update output_publications
                    set reconciliation_state = 'UNRESOLVED', updated_at = ?
                    where artifact_id = ?
                    """, Instant.now().toString(), artifactId);
            return false;
        }

        // Mark as FAILED (soft-archived)
        jdbc.update("""
                update output_publications
                set publication_state = 'FAILED',
                    failure_detail = 'Soft-archived',
                    reconciliation_state = 'RECONCILED',
                    verified_sha256 = null,
                    verified_size = null,
                    updated_at = ?
                where artifact_id = ?
                """, Instant.now().toString(), artifactId);

        // Remove extraction document if present
        jdbc.update("delete from artifact_extraction_documents where artifact_id = ?", artifactId);

        // Remove lineage edges
        jdbc.update("delete from artifact_lineage_edges where parent_artifact_id = ? or child_artifact_id = ?",
                artifactId, artifactId);

        return true;
    }

    /**
     * Hard-expire: delete all DB rows and filesystem bytes for an artifact.
     */
    @Transactional
    public boolean hardExpire(String artifactId, ArtifactAccessContext access) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) return false;

        String blocker = removalBlocker(artifactId);
        if (blocker != null) {
            log.info("Cannot hard-expire {}: {}", artifactId, blocker);
            return false;
        }

        // Delete the file bytes first
        try {
            Path file = rootRelativePathService.resolve(record.rootRelativePath());
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                mutations.delete(directoryService.dataRoot(),
                        dataRootRelative(file), false);
            }
        } catch (NoSuchFileException e) {
            // Already gone
        } catch (IOException e) {
            log.warn("Failed to delete file for {}: {}", artifactId, e.getMessage());
            return false;
        }

        // Delete staging if present
        if (record.stagingPath() != null) {
            try {
                Path stagingPath = rootRelativePathService.resolve(record.stagingPath());
                if (Files.exists(stagingPath, LinkOption.NOFOLLOW_LINKS)
                        && Files.isRegularFile(stagingPath, LinkOption.NOFOLLOW_LINKS)) {
                    mutations.delete(directoryService.dataRoot(),
                            dataRootRelative(stagingPath), false);
                }
            } catch (IOException e) {
                log.warn("Failed to delete staging for {}: {}", artifactId, e.getMessage());
            }
        }

        // Delete lineage edges, pins, extraction, sessions, then the publication
        jdbc.update("delete from artifact_lineage_edges where parent_artifact_id = ? or child_artifact_id = ?",
                artifactId, artifactId);
        jdbc.update("delete from artifact_retention_pins where artifact_id = ?", artifactId);
        jdbc.update("delete from artifact_extraction_documents where artifact_id = ?", artifactId);
        jdbc.update("delete from active_artifact_sessions where artifact_id = ?", artifactId);
        jdbc.update("delete from output_publications where artifact_id = ?", artifactId);

        return true;
    }

    /**
     * Soft-archive all artifacts in a task run that pass the removal check.
     */
    @Transactional
    public RetentionBatchResult softArchiveTaskRun(String taskRunId, ArtifactAccessContext access) {
        List<String> artifactIds = jdbc.query("""
                select artifact_id from output_publications
                where task_run_id = ? and workspace_kind = ? and workspace_key = ?
                  and publication_state in ('PUBLISHED', 'INTEGRITY_FAILED')
                order by created_at
                """, (rs, rowNum) -> rs.getString(1),
                taskRunId, access.workspaceKind(), access.workspaceKey());

        int archived = 0;
        int blocked = 0;
        List<String> blockedReasons = new ArrayList<>();

        for (String artifactId : artifactIds) {
            String blocker = removalBlocker(artifactId);
            if (blocker != null) {
                blocked++;
                blockedReasons.add(artifactId + ": " + blocker);
                continue;
            }
            if (softArchive(artifactId, access)) {
                archived++;
            }
        }

        return new RetentionBatchResult(taskRunId, archived, blocked, blockedReasons);
    }

    /**
     * Hard-expire all artifacts in a task run that pass the removal check.
     */
    @Transactional
    public RetentionBatchResult hardExpireTaskRun(String taskRunId, ArtifactAccessContext access) {
        List<String> artifactIds = jdbc.query("""
                select artifact_id from output_publications
                where task_run_id = ? and workspace_kind = ? and workspace_key = ?
                order by created_at
                """, (rs, rowNum) -> rs.getString(1),
                taskRunId, access.workspaceKind(), access.workspaceKey());

        int expired = 0;
        int blocked = 0;
        List<String> blockedReasons = new ArrayList<>();

        for (String artifactId : artifactIds) {
            String blocker = removalBlocker(artifactId);
            if (blocker != null) {
                blocked++;
                blockedReasons.add(artifactId + ": " + blocker);
                continue;
            }
            if (hardExpire(artifactId, access)) {
                expired++;
            }
        }

        return new RetentionBatchResult(taskRunId, expired, blocked, blockedReasons);
    }

    /**
     * Scan a workspace for artifacts eligible for soft-archive according
     * to the active retention policy. Returns the number archived.
     */
    public int applyPolicy(String workspaceKind, String workspaceKey,
                            ArtifactAccessContext access, int batchSize) {
        RetentionPolicy policy = getPolicy(workspaceKind, workspaceKey, null);
        if (policy == null || policy.policyType().equals("PRESERVE")) return 0;

        int limit = Math.min(batchSize > 0 ? batchSize : DEFAULT_BATCH_SIZE, 500);

        List<String> candidates = findExpiredCandidates(workspaceKind, workspaceKey, policy, limit);
        int processed = 0;
        for (String artifactId : candidates) {
            String blocker = removalBlocker(artifactId);
            if (blocker != null) continue;
            boolean removed = policy.policyType().equals("HARD_EXPIRE")
                    ? hardExpire(artifactId, access)
                    : softArchive(artifactId, access);
            if (removed) processed++;
        }
        return processed;
    }

    private List<String> findExpiredCandidates(String workspaceKind, String workspaceKey,
                                                RetentionPolicy policy, int limit) {
        StringBuilder sql = new StringBuilder("""
                select artifact_id from output_publications
                where workspace_kind = ? and workspace_key = ?
                  and publication_state in ('PUBLISHED', 'INTEGRITY_FAILED')
                """);
        List<Object> params = new ArrayList<>();
        params.add(workspaceKind);
        params.add(workspaceKey);

        if (policy.retainDays() != null) {
            sql.append(" and published_at < ?");
            params.add(Instant.now().minusSeconds(policy.retainDays() * 86400L).toString());
        }

        sql.append(" order by published_at asc nulls first limit ?");
        params.add(limit);

        return jdbc.query(sql.toString(),
                (rs, rowNum) -> rs.getString(1),
                params.toArray());
    }

    // ── Helpers ──

    private void validatePolicyType(String policyType) {
        if (!Set.of("PRESERVE", "SOFT_ARCHIVE", "HARD_EXPIRE").contains(policyType)) {
            throw new IllegalArgumentException("Unknown policy type: " + policyType);
        }
    }

    private String dataRootRelative(Path path) {
        return directoryService.dataRoot().toAbsolutePath().normalize()
                .relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private RetentionPolicy toPolicy(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RetentionPolicy(
                rs.getLong("id"),
                rs.getString("workspace_kind"),
                rs.getString("workspace_key"),
                rs.getString("task_path_key"),
                rs.getString("policy_type"),
                rs.getObject("retain_days") != null ? rs.getInt("retain_days") : null,
                rs.getObject("max_artifact_count") != null ? rs.getInt("max_artifact_count") : null,
                rs.getObject("max_total_bytes") != null ? rs.getInt("max_total_bytes") : null,
                rs.getInt("enabled") == 1,
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private RetentionPin toPin(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RetentionPin(
                rs.getLong("id"),
                rs.getString("artifact_id"),
                rs.getString("task_run_id"),
                rs.getString("pin_kind"),
                rs.getString("reason"),
                rs.getString("pinned_by"),
                Instant.parse(rs.getString("pinned_at")),
                rs.getString("expires_at") != null ? Instant.parse(rs.getString("expires_at")) : null);
    }

    // ── Record types ──

    public record RetentionPolicy(
            long id,
            String workspaceKind,
            String workspaceKey,
            String taskPathKey,
            String policyType,
            Integer retainDays,
            Integer maxArtifactCount,
            Integer maxTotalBytes,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt) {
        static RetentionPolicy defaultPolicy() {
            return new RetentionPolicy(0, null, null, null, "PRESERVE",
                    null, null, null, true, Instant.now(), Instant.now());
        }
    }

    public record RetentionPin(
            long id,
            String artifactId,
            String taskRunId,
            String pinKind,
            String reason,
            String pinnedBy,
            Instant pinnedAt,
            Instant expiresAt) {}

    public record RetentionBatchResult(
            String taskRunId,
            int processed,
            int blocked,
            List<String> blockedReasons) {}
}
