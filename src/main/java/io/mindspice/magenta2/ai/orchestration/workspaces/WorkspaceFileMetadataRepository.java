package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class WorkspaceFileMetadataRepository {
    public static final String NOTE_LABEL = "note";

    private final JdbcTemplate jdbcTemplate;

    public WorkspaceFileMetadataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
        ensureSystemLabel(NOTE_LABEL, "Note");
    }

    public WorkspaceFileLabel ensureLabel(String slug, String displayName, boolean system) {
        String normalizedSlug = normalizeSlug(slug);
        Optional<WorkspaceFileLabel> existing = findLabel(normalizedSlug);
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                insert into workspace_file_labels (
                    id, slug, display_name, color, system_flag, metadata_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, '{}', ?, ?)
                """,
            UUID.randomUUID().toString(),
            normalizedSlug,
            StringUtils.hasText(displayName) ? displayName.trim() : normalizedSlug,
            null,
            system ? 1 : 0,
            now.toString(),
            now.toString()
        );
        return findLabel(normalizedSlug).orElseThrow();
    }

    public WorkspaceFileLabelAssignment addLabel(
        String workspaceIdentity,
        String fileRelativePath,
        String labelSlug
    ) {
        WorkspaceFileLabel label = ensureLabel(labelSlug, labelSlug, false);
        Optional<WorkspaceFileLabelAssignment> existing = findAnnotation(
            workspaceIdentity, normalizePath(fileRelativePath), label.id());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            """
                insert into workspace_file_annotations (
                    id, workspace_identity, file_relative_path,
                    label_id, metadata_json, created_at, updated_at
                )
                values (?, ?, ?, ?, '{}', ?, ?)
                """,
            UUID.randomUUID().toString(),
            workspaceIdentity,
            normalizePath(fileRelativePath),
            label.id(),
            now.toString(),
            now.toString()
        );
        return findAnnotation(workspaceIdentity, normalizePath(fileRelativePath), label.id()).orElseThrow();
    }

    public int removeLabel(String workspaceIdentity, String fileRelativePath, String labelSlug) {
        Optional<WorkspaceFileLabel> label = findLabel(normalizeSlug(labelSlug));
        if (label.isEmpty()) {
            return 0;
        }
        return jdbcTemplate.update(
            """
                delete from workspace_file_annotations
                where workspace_identity = ? and file_relative_path = ? and label_id = ?
                """,
            workspaceIdentity,
            normalizePath(fileRelativePath),
            label.orElseThrow().id()
        );
    }

    public List<WorkspaceFileLabelAssignment> labelsForPath(String workspaceIdentity, String fileRelativePath) {
        return jdbcTemplate.query(
            """
                select a.*, l.id label_pk, l.slug, l.display_name, l.color, l.system_flag,
                    l.metadata_json label_metadata_json, l.created_at label_created_at, l.updated_at label_updated_at
                from workspace_file_annotations a
                join workspace_file_labels l on l.id = a.label_id
                where a.workspace_identity = ? and a.file_relative_path = ?
                order by l.system_flag desc, l.slug
                """,
            (rs, rowNum) -> toAssignment(rs),
            workspaceIdentity,
            normalizePath(fileRelativePath)
        );
    }

    public List<WorkspaceFileLabelAssignment> labelsForSubtree(String workspaceIdentity, String subtreeRootRelativePath) {
        String normalized = normalizePath(subtreeRootRelativePath);
        String escaped = escapeLike(normalized);
        return jdbcTemplate.query(
            """
                select a.*, l.id label_pk, l.slug, l.display_name, l.color, l.system_flag,
                    l.metadata_json label_metadata_json, l.created_at label_created_at, l.updated_at label_updated_at
                from workspace_file_annotations a
                join workspace_file_labels l on l.id = a.label_id
                where a.workspace_identity = ? and (a.file_relative_path = ? or a.file_relative_path like ? escape '\\')
                order by a.file_relative_path, l.slug
                """,
            (rs, rowNum) -> toAssignment(rs),
            workspaceIdentity,
            normalized,
            escaped + "/%"
        );
    }

    public void moveSubtree(String workspaceIdentity, String sourceRootRelativePath, String targetRootRelativePath) {
        List<WorkspaceFileLabelAssignment> assignments = labelsForSubtree(workspaceIdentity, sourceRootRelativePath);
        Instant now = Instant.now();
        for (WorkspaceFileLabelAssignment assignment : assignments) {
            String movedPath = replacePrefix(assignment.fileRelativePath(), sourceRootRelativePath, targetRootRelativePath);
            jdbcTemplate.update(
                """
                    update workspace_file_annotations
                    set file_relative_path = ?, updated_at = ?
                    where id = ?
                    """,
                movedPath,
                now.toString(),
                assignment.id()
            );
        }
    }

    public void copySubtree(String workspaceIdentity, String sourceRootRelativePath, String targetRootRelativePath) {
        List<WorkspaceFileLabelAssignment> assignments = labelsForSubtree(workspaceIdentity, sourceRootRelativePath);
        Instant now = Instant.now();
        for (WorkspaceFileLabelAssignment assignment : assignments) {
            String copiedPath = replacePrefix(assignment.fileRelativePath(), sourceRootRelativePath, targetRootRelativePath);
            jdbcTemplate.update(
                """
                    insert or ignore into workspace_file_annotations (
                        id, workspace_identity, file_relative_path,
                        label_id, metadata_json, created_at, updated_at
                    )
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                UUID.randomUUID().toString(),
                workspaceIdentity,
                copiedPath,
                assignment.label().id(),
                StringUtils.hasText(assignment.metadataJson()) ? assignment.metadataJson() : "{}",
                now.toString(),
                now.toString()
            );
        }
    }

    public int deleteSubtree(String workspaceIdentity, String subtreeRootRelativePath) {
        String normalized = normalizePath(subtreeRootRelativePath);
        String escaped = escapeLike(normalized);
        return jdbcTemplate.update(
            """
                delete from workspace_file_annotations
                where workspace_identity = ? and (file_relative_path = ? or file_relative_path like ? escape '\\')
                """,
            workspaceIdentity,
            normalized,
            escaped + "/%"
        );
    }

    private Optional<WorkspaceFileLabel> findLabel(String slug) {
        return jdbcTemplate.query(
            "select * from workspace_file_labels where slug = ?",
            rs -> rs.next() ? Optional.of(toLabel(rs)) : Optional.empty(),
            slug
        );
    }

    private Optional<WorkspaceFileLabelAssignment> findAnnotation(String workspaceIdentity, String fileRelativePath, String labelId) {
        return jdbcTemplate.query(
            """
                select a.*, l.id label_pk, l.slug, l.display_name, l.color, l.system_flag,
                    l.metadata_json label_metadata_json, l.created_at label_created_at, l.updated_at label_updated_at
                from workspace_file_annotations a
                join workspace_file_labels l on l.id = a.label_id
                where a.workspace_identity = ? and a.file_relative_path = ? and a.label_id = ?
                """,
            rs -> rs.next() ? Optional.of(toAssignment(rs)) : Optional.empty(),
            workspaceIdentity,
            fileRelativePath,
            labelId
        );
    }

    private void ensureSystemLabel(String slug, String displayName) {
        ensureLabel(slug, displayName, true);
    }

    private WorkspaceFileLabelAssignment toAssignment(ResultSet rs) throws SQLException {
        return new WorkspaceFileLabelAssignment(
            rs.getString("id"),
            rs.getString("workspace_identity"),
            rs.getString("file_relative_path"),
            new WorkspaceFileLabel(
                rs.getString("label_pk"),
                rs.getString("slug"),
                rs.getString("display_name"),
                rs.getString("color"),
                rs.getInt("system_flag") != 0,
                rs.getString("label_metadata_json"),
                Instant.parse(rs.getString("label_created_at")),
                Instant.parse(rs.getString("label_updated_at"))
            ),
            rs.getString("metadata_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private WorkspaceFileLabel toLabel(ResultSet rs) throws SQLException {
        return new WorkspaceFileLabel(
            rs.getString("id"),
            rs.getString("slug"),
            rs.getString("display_name"),
            rs.getString("color"),
            rs.getInt("system_flag") != 0,
            rs.getString("metadata_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }

    private String replacePrefix(String path, String sourcePrefix, String targetPrefix) {
        String normalizedPath = normalizePath(path);
        String normalizedSource = normalizePath(sourcePrefix);
        String normalizedTarget = normalizePath(targetPrefix);
        if (normalizedPath.equals(normalizedSource)) {
            return normalizedTarget;
        }
        return normalizedTarget + normalizedPath.substring(normalizedSource.length());
    }

    private String normalizeSlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new IllegalArgumentException("label slug is required");
        }
        String normalized = slug.trim().toLowerCase().replace(' ', '-');
        if (!normalized.matches("[a-z0-9][a-z0-9._-]*")) {
            throw new IllegalArgumentException("label slug is invalid");
        }
        return normalized;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("file path is required");
        }
        return path.trim().replace('\\', '/');
    }

    /**
     * Escape {@code %}, {@code _}, and the backslash character for SQL LIKE.
     */
    private String escapeLike(String value) {
        if (value == null) return null;
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists workspace_file_labels (
                id text primary key,
                slug text not null unique,
                display_name text not null,
                color text,
                system_flag integer not null default 0,
                metadata_json text not null default '{}',
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create table if not exists workspace_file_annotations (
                id text primary key,
                workspace_identity text not null,
                file_relative_path text not null,
                label_id text not null,
                metadata_json text not null default '{}',
                created_at text not null,
                updated_at text not null,
                unique(workspace_identity, file_relative_path, label_id),
                foreign key(label_id) references workspace_file_labels(id)
            )
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_workspace_file_annotations_path
                on workspace_file_annotations(workspace_identity, file_relative_path)
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_workspace_file_annotations_label
                on workspace_file_annotations(label_id)
            """);
    }
}
