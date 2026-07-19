package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class WorkspaceRepository {
    private final JdbcTemplate jdbcTemplate;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        ensureSchema();
    }

    /** @return the underlying JdbcTemplate for backward-compatible component construction */
    public JdbcTemplate jdbcTemplate() {
        return jdbcTemplate;
    }

    public Optional<Workspace> findById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from workspaces where id = ?",
            rs -> rs.next() ? Optional.of(toWorkspace(rs)) : Optional.empty(),
            id
        );
    }

    public Optional<Workspace> findByOwner(WorkspaceOwnerType ownerType, String ownerId) {
        return jdbcTemplate.query(
            "select * from workspaces where owner_type = ? and owner_id = ? order by created_at limit 1",
            rs -> rs.next() ? Optional.of(toWorkspace(rs)) : Optional.empty(),
            ownerType.name(),
            ownerId
        );
    }

    public List<Workspace> findAll(WorkspaceOwnerType ownerType, String ownerId, int limit) {
        StringBuilder sql = new StringBuilder("select * from workspaces where 1 = 1");
        List<Object> args = new ArrayList<>();
        if (ownerType != null) {
            sql.append(" and owner_type = ?");
            args.add(ownerType.name());
        }
        if (StringUtils.hasText(ownerId)) {
            sql.append(" and owner_id = ?");
            args.add(ownerId.trim());
        }
        sql.append(" order by updated_at desc limit ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> toWorkspace(rs), args.toArray());
    }

    public Workspace save(Workspace workspace) {
        Instant now = Instant.now();
        Instant createdAt = workspace.createdAt() == null ? now : workspace.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into workspaces (
                    id, owner_type, owner_id, root_relative_path, display_name,
                    metadata_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    owner_type = excluded.owner_type,
                    owner_id = excluded.owner_id,
                    root_relative_path = excluded.root_relative_path,
                    display_name = excluded.display_name,
                    metadata_json = excluded.metadata_json,
                    updated_at = excluded.updated_at
                """,
            workspace.id(),
            workspace.ownerType().name(),
            workspace.ownerId(),
            workspace.rootRelativePath(),
            workspace.displayName(),
            workspace.metadataJson(),
            createdAt.toString(),
            updatedAt.toString()
        );
        return findById(workspace.id()).orElseThrow();
    }

    public void deleteByOwner(WorkspaceOwnerType ownerType, String ownerId) {
        if (ownerType == null || !StringUtils.hasText(ownerId)) {
            return;
        }
        List<String> workspaceIds = jdbcTemplate.query(
            "select id from workspaces where owner_type = ? and owner_id = ?",
            (rs, rowNum) -> rs.getString("id"),
            ownerType.name(),
            ownerId
        );
        if (workspaceIds.isEmpty()) {
            return;
        }
        for (String workspaceId : workspaceIds) {
            jdbcTemplate.update("delete from workspace_links where workspace_id = ?", workspaceId);
        }
        jdbcTemplate.update("delete from workspaces where owner_type = ? and owner_id = ?", ownerType.name(), ownerId);
    }

    public List<WorkspaceLink> links(String workspaceId) {
        return jdbcTemplate.query(
            "select * from workspace_links where workspace_id = ? order by label",
            (rs, rowNum) -> toLink(rs),
            workspaceId
        );
    }

    public WorkspaceLink saveLink(WorkspaceLink link) {
        Instant now = Instant.now();
        Instant createdAt = link.createdAt() == null ? now : link.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into workspace_links (
                    id, workspace_id, label, link_type, target, readable, writable, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    label = excluded.label,
                    link_type = excluded.link_type,
                    target = excluded.target,
                    readable = excluded.readable,
                    writable = excluded.writable,
                    updated_at = excluded.updated_at
                """,
            link.id(),
            link.workspaceId(),
            link.label(),
            link.linkType().name(),
            link.target(),
            link.readable() ? 1 : 0,
            link.writable() ? 1 : 0,
            createdAt.toString(),
            updatedAt.toString()
        );
        return findLink(link.id()).orElseThrow();
    }

    public Optional<WorkspaceLink> findLink(String id) {
        return jdbcTemplate.query(
            "select * from workspace_links where id = ?",
            rs -> rs.next() ? Optional.of(toLink(rs)) : Optional.empty(),
            id
        );
    }

    public void deleteLink(String workspaceId, String linkId) {
        jdbcTemplate.update("delete from workspace_links where workspace_id = ? and id = ?", workspaceId, linkId);
    }

    // ── WorkspaceRoot — deprecated, delegates to workspaces ──

    /**
     * @deprecated Use {@link #findById(String)} instead.
     */
    @Deprecated
    public Optional<WorkspaceRoot> findRootById(String id) {
        return findById(id).map(this::toRootFromWorkspace);
    }

    /**
     * @deprecated Use {@link #findByOwner(WorkspaceOwnerType, String)} instead.
     */
    @Deprecated
    public Optional<WorkspaceRoot> findRootByOwner(WorkspaceOwnerType ownerType, String ownerId) {
        return findByOwner(ownerType, ownerId).map(this::toRootFromWorkspace);
    }

    /**
     * @deprecated Use {@link #save(Workspace)} instead.
     */
    @Deprecated
    public WorkspaceRoot saveRoot(WorkspaceRoot root) {
        Workspace saved = save(new Workspace(
            root.id(), root.ownerType(), root.ownerId(),
            root.rootRelativePath(), root.displayName(),
            root.metadataJson(), root.createdAt(), root.updatedAt()
        ));
        return toRootFromWorkspace(saved);
    }

    private WorkspaceRoot toRootFromWorkspace(Workspace workspace) {
        return new WorkspaceRoot(
            workspace.id(), workspace.ownerType(), workspace.ownerId(),
            workspace.rootRelativePath(), workspace.displayName(),
            workspace.metadataJson(), workspace.createdAt(), workspace.updatedAt()
        );
    }

    // ── WorkspaceLease ──

    public Optional<WorkspaceLease> findLeaseById(String id) {
        if (!StringUtils.hasText(id)) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
            "select * from workspace_leases where id = ?",
            rs -> rs.next() ? Optional.of(toLease(rs)) : Optional.empty(),
            id
        );
    }

    public Optional<WorkspaceLease> findActiveWritableLease(String workspaceId) {
        return jdbcTemplate.query(
            """
                select * from workspace_leases
                where workspace_id = ? and mode = 'WRITE' and released_at is null
                order by created_at desc limit 1
                """,
            rs -> rs.next() ? Optional.of(toLease(rs)) : Optional.empty(),
            workspaceId
        );
    }

    public List<WorkspaceLease> findActiveLeases(String holderType, String holderId) {
        return jdbcTemplate.query(
            """
                select * from workspace_leases
                where holder_type = ? and holder_id = ? and released_at is null
                order by created_at desc
                """,
            (rs, rowNum) -> toLease(rs),
            holderType,
            holderId
        );
    }

    public List<WorkspaceLease> findActiveLeases(String workspaceId) {
        return jdbcTemplate.query(
            """
                select * from workspace_leases
                where workspace_id = ? and released_at is null
                order by created_at desc
                """,
            (rs, rowNum) -> toLease(rs),
            workspaceId
        );
    }

    public WorkspaceLease saveLease(WorkspaceLease lease) {
        Instant now = Instant.now();
        Instant createdAt = lease.createdAt() == null ? now : lease.createdAt();
        Instant updatedAt = now;
        jdbcTemplate.update(
            """
                insert into workspace_leases (
                    id, workspace_id, holder_type, holder_id, mode,
                    expires_at, release_requested, released_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(id) do update set
                    workspace_id = excluded.workspace_id,
                    holder_type = excluded.holder_type,
                    holder_id = excluded.holder_id,
                    mode = excluded.mode,
                    expires_at = excluded.expires_at,
                    release_requested = excluded.release_requested,
                    released_at = excluded.released_at,
                    updated_at = excluded.updated_at
                """,
            lease.id(),
            lease.workspaceId(),
            lease.holderType(),
            lease.holderId(),
            lease.mode().name(),
            lease.expiresAt() != null ? lease.expiresAt().toString() : null,
            lease.releaseRequested() ? 1 : 0,
            lease.releasedAt() != null ? lease.releasedAt().toString() : null,
            createdAt.toString(),
            updatedAt.toString()
        );
        return findLeaseById(lease.id()).orElseThrow();
    }

    /**
     * Atomically insert a writable lease. Returns the saved lease if the
     * insert succeeded, or {@link Optional#empty()} if an active writable
     * lease already exists for the workspace.
     *
     * <p>Relies on the unique partial index
     * {@code idx_workspace_leases_active_write} to enforce at most one
     * active WRITE lease per workspace at the database level.
     */
    public Optional<WorkspaceLease> insertWritableLease(WorkspaceLease lease) {
        Instant now = Instant.now();
        Instant createdAt = lease.createdAt() == null ? now : lease.createdAt();
        Instant updatedAt = now;
        int rows = jdbcTemplate.update(
            """
                insert into workspace_leases (
                    id, workspace_id, holder_type, holder_id, mode,
                    expires_at, release_requested, released_at, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict(workspace_id) where mode = 'WRITE' and released_at is null
                do nothing
                """,
            lease.id(),
            lease.workspaceId(),
            lease.holderType(),
            lease.holderId(),
            lease.mode().name(),
            lease.expiresAt() != null ? lease.expiresAt().toString() : null,
            lease.releaseRequested() ? 1 : 0,
            lease.releasedAt() != null ? lease.releasedAt().toString() : null,
            createdAt.toString(),
            updatedAt.toString()
        );
        if (rows == 0) {
            return Optional.empty();
        }
        return findLeaseById(lease.id());
    }

    public void releaseLease(String leaseId) {
        jdbcTemplate.update(
            "update workspace_leases set released_at = ?, updated_at = ? where id = ?",
            Instant.now().toString(),
            Instant.now().toString(),
            leaseId
        );
    }

    public void releaseLeasesByWorkspaceId(String workspaceId) {
        if (!StringUtils.hasText(workspaceId)) {
            return;
        }
        Instant now = Instant.now();
        jdbcTemplate.update(
            "update workspace_leases set released_at = ?, updated_at = ? where workspace_id = ? and released_at is null",
            now.toString(),
            now.toString(),
            workspaceId
        );
    }

    public int releaseExpiredLeases(Instant now) {
        return jdbcTemplate.update(
            """
                update workspace_leases
                set released_at = ?, updated_at = ?
                where released_at is null and expires_at is not null and expires_at <= ?
                """,
            now.toString(), now.toString(), now.toString()
        );
    }

    private Instant instant(String value) {
        return StringUtils.hasText(value) ? Instant.parse(value) : null;
    }

    private Workspace toWorkspace(ResultSet rs) throws SQLException {
        return new Workspace(
            rs.getString("id"),
            WorkspaceOwnerType.valueOf(rs.getString("owner_type")),
            rs.getString("owner_id"),
            rs.getString("root_relative_path"),
            rs.getString("display_name"),
            rs.getString("metadata_json"),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private WorkspaceLink toLink(ResultSet rs) throws SQLException {
        return new WorkspaceLink(
            rs.getString("id"),
            rs.getString("workspace_id"),
            rs.getString("label"),
            WorkspaceLinkType.valueOf(rs.getString("link_type")),
            rs.getString("target"),
            rs.getInt("readable") == 1,
            rs.getInt("writable") == 1,
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private WorkspaceRoot toRoot(ResultSet rs) throws SQLException {
        return new WorkspaceRoot(
            rs.getString("id"),
            WorkspaceOwnerType.valueOf(rs.getString("owner_type")),
            rs.getString("owner_id"),
            rs.getString("root_relative_path"),
            rs.getString("display_name"),
            rs.getString("metadata_json"),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private WorkspaceLease toLease(ResultSet rs) throws SQLException {
        return new WorkspaceLease(
            rs.getString("id"),
            rs.getString("workspace_id"),
            rs.getString("holder_type"),
            rs.getString("holder_id"),
            LeaseMode.valueOf(rs.getString("mode")),
            instant(rs.getString("expires_at")),
            rs.getInt("release_requested") != 0,
            instant(rs.getString("released_at")),
            instant(rs.getString("created_at")),
            instant(rs.getString("updated_at"))
        );
    }

    private void ensureSchema() {
        jdbcTemplate.execute("""
            create table if not exists workspaces (
                id text primary key,
                owner_type text not null,
                owner_id text not null,
                root_relative_path text not null,
                display_name text not null,
                metadata_json text,
                created_at text not null,
                updated_at text not null
            )
            """);
        jdbcTemplate.execute("""
            create unique index if not exists idx_workspaces_owner
                on workspaces(owner_type, owner_id)
            """);
        jdbcTemplate.execute("""
            create table if not exists workspace_links (
                id text primary key,
                workspace_id text not null,
                label text not null,
                link_type text not null,
                target text not null,
                readable integer not null,
                writable integer not null,
                created_at text not null,
                updated_at text not null,
                foreign key(workspace_id) references workspaces(id)
            )
            """);

        // Migrate: workspace_roots was a duplicate of workspaces.
        // Copy any data from workspace_roots into workspaces, then drop workspace_roots.
        migrateWorkspaceRootsToWorkspaces();

        jdbcTemplate.execute("""
            create table if not exists workspace_leases (
                id text primary key,
                workspace_id text not null,
                holder_type text not null,
                holder_id text not null,
                mode text not null,
                expires_at text,
                release_requested integer not null default 0,
                released_at text,
                created_at text not null,
                updated_at text not null,
                foreign key(workspace_id) references workspaces(id)
            )
            """);
        jdbcTemplate.execute("""
            create unique index if not exists idx_workspace_leases_active_write
                on workspace_leases(workspace_id)
                where mode = 'WRITE' and released_at is null
            """);
        jdbcTemplate.execute("""
            create index if not exists idx_workspace_leases_active_holder
                on workspace_leases(holder_type, holder_id)
                where released_at is null
            """);
    }

    private void migrateWorkspaceRootsToWorkspaces() {
        if (!tableExists("workspace_roots")) {
            return;
        }

        jdbcTemplate.update("""
            insert or ignore into workspaces (id, owner_type, owner_id, root_relative_path,
                display_name, metadata_json, created_at, updated_at)
            select id, owner_type, owner_id, root_relative_path,
                display_name, metadata_json, created_at, updated_at
            from workspace_roots
            """);

        if (workspaceLeasesReferenceWorkspaceRoots()) {
            recreateWorkspaceLeasesForWorkspaces();
        }
        jdbcTemplate.execute("drop table if exists workspace_roots");
    }

    private void recreateWorkspaceLeasesForWorkspaces() {
        String releaseRequested = columnExists("workspace_leases", "release_requested")
            ? "release_requested"
            : "0";
        jdbcTemplate.execute("drop table if exists workspace_leases_migrated");
        jdbcTemplate.execute("""
            create table workspace_leases_migrated (
                id text primary key,
                workspace_id text not null,
                holder_type text not null,
                holder_id text not null,
                mode text not null,
                expires_at text,
                release_requested integer not null default 0,
                released_at text,
                created_at text not null,
                updated_at text not null,
                foreign key(workspace_id) references workspaces(id)
            )
            """);
        jdbcTemplate.execute("""
            insert into workspace_leases_migrated (
                id, workspace_id, holder_type, holder_id, mode,
                expires_at, release_requested, released_at, created_at, updated_at
            )
            select id, workspace_id, holder_type, holder_id, mode,
                expires_at, %s, released_at, created_at, updated_at
            from workspace_leases
            """.formatted(releaseRequested));
        jdbcTemplate.execute("drop table workspace_leases");
        jdbcTemplate.execute("alter table workspace_leases_migrated rename to workspace_leases");
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        if (!table.matches("[a-zA-Z0-9_]+") || !column.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table/column identifier");
        }
        if (!columnExists(table, column)) {
            jdbcTemplate.execute(ddl);
        }
    }

    private boolean tableExists(String table) {
        if (!table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table identifier");
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from sqlite_master where type = 'table' and name = ?",
            Integer.class,
            table
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String table, String column) {
        if (!table.matches("[a-zA-Z0-9_]+") || !column.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Unsupported table/column identifier");
        }
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from pragma_table_info(?) where name = ?",
            Integer.class,
            table,
            column
        );
        return count != null && count > 0;
    }

    private boolean workspaceLeasesReferenceWorkspaceRoots() {
        if (!tableExists("workspace_leases")) {
            return false;
        }
        return jdbcTemplate.query(
            "select \"table\" from pragma_foreign_key_list('workspace_leases') where \"from\" = 'workspace_id'",
            (rs, rowNum) -> rs.getString(1)
        ).stream().anyMatch("workspace_roots"::equals);
    }

    /**
     * Backward-compatible delegate to OutputArtifactRepository.
     * @deprecated Use {@link OutputArtifactRepository#findArtifactsByRunId(String)}.
     */
    @Deprecated
    public java.util.List<RunOutputArtifact> findArtifactsByRunId(String runId) {
        return new OutputArtifactRepository(jdbcTemplate).findArtifactsByRunId(runId);
    }

    /**
     * Backward-compatible delegate to OutputArtifactRepository.
     * @deprecated Use {@link OutputArtifactRepository#saveArtifact(RunOutputArtifact)}.
     */
    @Deprecated
    public RunOutputArtifact saveArtifact(RunOutputArtifact artifact) {
        return new OutputArtifactRepository(jdbcTemplate).saveArtifact(artifact);
    }

    /**
     * Backward-compatible delegate to OutputArtifactRepository.
     * @deprecated Use {@link OutputArtifactRepository#findArtifacts(OutputArtifactQuery)}.
     */
    @Deprecated
    public java.util.List<RunOutputArtifact> findArtifacts(OutputArtifactQuery query) {
        return new OutputArtifactRepository(jdbcTemplate).findArtifacts(query);
    }
}
