package io.mindspice.magenta2.ai.orchestration.runtime;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Manages append-only project materialization template revisions.
 *
 * <p>Templates are immutable once created. Every edit produces a new
 * revision with the next monotonic revision number. The service
 * validates normalized directory snapshots before persistence.
 *
 * <p>Directories-only model: every path must be a normal relative
 * directory path. Blank, absolute, dot, traversal ({@code ..}),
 * backslash, duplicate, prefix-conflict, and directive-prefixed
 * paths are rejected.
 */
@Service
public class ProjectMaterializationTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ProjectMaterializationTemplateService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final ProjectRepository projectRepository;

    public ProjectMaterializationTemplateService(
            JdbcTemplate jdbcTemplate,
            ProjectRepository projectRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.projectRepository = projectRepository;
    }

    /**
     * Create revision 1 of a materialization template for a project.
     * Validates, normalizes, deduplicates, sorts, and serializes the
     * directory paths into a canonical JSON snapshot.
     *
     * @param projectId      existing project id
     * @param directoryPaths set of relative directory paths
     * @return the created template revision
     */
    @Transactional
    public ProjectMaterializationTemplate create(String projectId, Collection<String> directoryPaths) {
        requireProjectExists(projectId);
        String snapshot = validateAndSerialize(directoryPaths);

        // Verify no template exists yet — templates are append-only from rev 1
        Integer existingRevision = jdbcTemplate.queryForObject(
                "select max(revision) from project_materialization_templates where project_id = ?",
                Integer.class, projectId);
        if (existingRevision != null) {
            throw new IllegalStateException(
                    "Template already exists for project " + projectId + "; use createRevision");
        }

        return insertRevision(projectId, 1, snapshot);
    }

    /**
     * Create a new revision of an existing template. Validates,
     * normalizes, deduplicates, sorts, and serializes the directory
     * paths into a canonical JSON snapshot, then reads the current
     * maximum revision and inserts the next one.
     *
     * @param projectId      existing project id
     * @param directoryPaths set of relative directory paths
     * @return the newly created template revision
     */
    @Transactional
    public ProjectMaterializationTemplate createRevision(String projectId, Collection<String> directoryPaths) {
        requireProjectExists(projectId);
        String snapshot = validateAndSerialize(directoryPaths);

        Integer latestRevision = jdbcTemplate.queryForObject(
                "select max(revision) from project_materialization_templates where project_id = ?",
                Integer.class, projectId);
        if (latestRevision == null) {
            throw new IllegalStateException(
                    "No template exists for project " + projectId + "; use create first");
        }

        return insertRevision(projectId, latestRevision + 1, snapshot);
    }

    public Optional<ProjectMaterializationTemplate> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "select * from project_materialization_templates where id = ?",
                rs -> rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(),
                id);
    }

    public List<ProjectMaterializationTemplate> findByProjectId(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return List.of();
        }
        return jdbcTemplate.query(
                "select * from project_materialization_templates where project_id = ? order by revision desc",
                (rs, rowNum) -> fromRow(rs), projectId);
    }

    public Optional<ProjectMaterializationTemplate> findLatestByProjectId(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "select * from project_materialization_templates where project_id = ? order by revision desc limit 1",
                rs -> rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(),
                projectId);
    }

    public Optional<ProjectMaterializationTemplate> findByProjectAndRevision(String projectId, int revision) {
        if (!StringUtils.hasText(projectId) || revision < 1) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                "select * from project_materialization_templates where project_id = ? and revision = ?",
                rs -> rs.next() ? Optional.of(fromRow(rs)) : Optional.empty(),
                projectId, revision);
    }

    /**
     * Return the latest revision number for a project, or empty if no
     * template exists.
     */
    public Optional<Integer> getCurrentRevision(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return Optional.empty();
        }
        Integer max = jdbcTemplate.queryForObject(
                "select max(revision) from project_materialization_templates where project_id = ?",
                Integer.class, projectId);
        return max == null ? Optional.empty() : Optional.of(max);
    }

    /**
     * Template revisions are immutable audit records and cannot be deleted.
     * A replacement layout is represented by a later revision instead.
     */
    public boolean deleteById(String id) {
        throw new UnsupportedOperationException(
                "Materialization template revisions are append-only and cannot be deleted");
    }

    // ── Directory validation and serialization ──

    /**
     * Validate a set of relative directory paths and return a normalized,
     * sorted, deduplicated list.
     *
     * <p>Rejects: null, empty, blank, absolute ({@code /}), dot ({@code .}),
     * traversal ({@code ..}), backslash ({@code \}), control characters,
     * directive prefix ({@code !}), empty components ({@code //}),
     * duplicate normalized values, and ancestor/descendant prefix conflicts.
     *
     * @param paths raw directory path set
     * @return immutable normalized sorted list
     * @throws IllegalArgumentException on any invalid path
     */
    public static List<String> validateAndNormalizeDirectories(Collection<String> paths) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("Directory paths must not be null or empty");
        }

        List<String> normalized = new ArrayList<>(paths.size());
        for (String raw : paths) {
            String path = validateSingleDirectory(raw);
            normalized.add(path);
        }

        // Deduplicate preserving insertion order
        Set<String> unique = new LinkedHashSet<>(normalized);
        if (unique.size() != normalized.size()) {
            throw new IllegalArgumentException(
                    "Duplicate directory paths after normalization");
        }

        // Check prefix conflicts: no path may be an ancestor of another
        List<String> list = new ArrayList<>(unique);
        for (int i = 0; i < list.size(); i++) {
            String prefix = list.get(i) + "/";
            for (int j = 0; j < list.size(); j++) {
                if (i != j && list.get(j).startsWith(prefix)) {
                    throw new IllegalArgumentException(
                            "Prefix conflict: '" + list.get(i) + "' is an ancestor of '" + list.get(j) + "'");
                }
            }
        }

        Collections.sort(list);
        return Collections.unmodifiableList(list);
    }

    /**
     * Serialize a list of validated directory paths to a JSON array string.
     */
    public static String toSnapshotJson(List<String> directories) {
        if (directories == null) {
            throw new IllegalArgumentException("directories must not be null");
        }
        try {
            return objectMapper.writeValueAsString(directories);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize directory snapshot", e);
        }
    }

    /**
     * Parse a snapshot JSON array to a list of directory paths.
     */
    public static List<String> parseDirectories(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return List.of();
        }
        try {
            List<String> dirs = objectMapper.readValue(snapshotJson, new TypeReference<List<String>>() {});
            return dirs == null ? List.of() : Collections.unmodifiableList(dirs);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse directory snapshot", e);
        }
    }

    /**
     * Parse a persisted snapshot and prove it is the canonical result of the
     * directory-only validator. This is deliberately repeated at the
     * filesystem boundary: database contents are facts to validate, never
     * path authority.
     */
    public static List<String> parseValidatedDirectories(String snapshotJson) {
        List<String> parsed = parseDirectories(snapshotJson);
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Directory snapshot must not be empty");
        }
        Set<String> supplied = new LinkedHashSet<>(parsed);
        if (supplied.size() != parsed.size()) {
            throw new IllegalArgumentException("Directory snapshot contains duplicate paths");
        }
        List<String> normalized = validateAndNormalizeDirectories(supplied);
        if (!parsed.equals(normalized) || !snapshotJson.equals(toSnapshotJson(normalized))) {
            throw new IllegalArgumentException("Directory snapshot is not canonical");
        }
        return normalized;
    }

    /**
     * Compute a SHA-256 hex digest of the snapshot for idempotency
     * comparison. Equal canonical snapshots produce equal digests.
     */
    public static String digest(String snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── helpers ──

    private String validateAndSerialize(Collection<String> directoryPaths) {
        List<String> validated = validateAndNormalizeDirectories(directoryPaths);
        return toSnapshotJson(validated);
    }

    private ProjectMaterializationTemplate insertRevision(
            String projectId, int revision, String directoriesSnapshot) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                insert into project_materialization_templates (
                    id, project_id, revision, directories_snapshot, created_at
                ) values (?, ?, ?, ?, ?)
                """,
                id, projectId, revision, directoriesSnapshot, now.toString());
        log.info("Created template revision {} for project {} (rev {})", id, projectId, revision);
        return new ProjectMaterializationTemplate(id, projectId, revision, directoriesSnapshot, now);
    }

    private void requireProjectExists(String projectId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    /**
     * Validate a single relative directory path.
     *
     * @return normalized path (no leading/trailing whitespace, no trailing slash)
     * @throws IllegalArgumentException if invalid
     */
    private static String validateSingleDirectory(String path) {
        if (path == null) {
            throw new IllegalArgumentException("Directory path must not be null");
        }

        String trimmed = path.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Directory path must not be blank");
        }

        // Reject backslash
        if (trimmed.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Directory path must not contain backslash: '" + trimmed + "'");
        }

        // Reject control characters
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException(
                        "Directory path must not contain control characters: '" + trimmed + "'");
            }
        }

        // Reject absolute paths
        if (trimmed.startsWith("/")) {
            throw new IllegalArgumentException("Directory path must be relative, not absolute: '" + trimmed + "'");
        }

        // Reject directive prefix (paths starting with !)
        if (trimmed.startsWith("!")) {
            throw new IllegalArgumentException(
                    "Directory path must not use directive prefix '!': '" + trimmed + "'");
        }

        // Normalize: strip trailing slashes
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        // Must not be empty after stripping slashes
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Directory path must not resolve to empty after normalization");
        }

        // Validate each component
        String[] components = trimmed.split("/");
        for (String component : components) {
            if (component.isEmpty()) {
                throw new IllegalArgumentException(
                        "Directory path must not contain empty components: '" + trimmed + "'");
            }
            if (".".equals(component)) {
                throw new IllegalArgumentException(
                        "Directory path must not contain '.' component: '" + trimmed + "'");
            }
            if ("..".equals(component)) {
                throw new IllegalArgumentException(
                        "Directory path must not contain '..' traversal: '" + trimmed + "'");
            }
            if (!component.equals(component.trim())) {
                throw new IllegalArgumentException(
                        "Directory path component must not have leading/trailing whitespace: '" + trimmed + "'");
            }
        }

        return trimmed;
    }

    private ProjectMaterializationTemplate fromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ProjectMaterializationTemplate(
                rs.getString("id"),
                rs.getString("project_id"),
                rs.getInt("revision"),
                rs.getString("directories_snapshot"),
                Instant.parse(rs.getString("created_at")));
    }
}
