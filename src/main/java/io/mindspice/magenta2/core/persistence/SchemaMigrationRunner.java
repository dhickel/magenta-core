package io.mindspice.magenta2.core.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

/** Sole checksum-protected owner of ordered SQL migrations. */
@Component("schemaMigrationRunner")
@DependsOnDatabaseInitialization
public final class SchemaMigrationRunner {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationRunner.class);
    private static final String MIGRATION_PATH = "classpath*:db/migrations/*.sql";
    private static final String FRESH_SCHEMA_PATH = "classpath:schema.sql";
    private static final Pattern MIGRATION_NAME = Pattern.compile("^(\\d{3})-([a-z0-9]+(?:-[a-z0-9]+)*)\\.sql$");
    private static final List<String> FOUNDATION_TABLES = List.of(
            "task_run_locations", "task_run_attempts", "task_run_idempotent_ops",
            "task_run_native_links", "task_run_writer_claims");
    private static final Set<String> FOUNDATION_INDEXES = Set.of(
            "idx_task_run_locations_workspace", "idx_task_run_locations_source",
            "idx_task_run_attempts_status", "idx_task_run_native_links_assignment",
            "idx_task_run_native_links_workflow_child", "idx_task_run_native_links_job_work_item");
    private static final Map<String, List<String>> FOUNDATION_COLUMNS = Map.of(
            "task_run_locations", List.of("task_run_id", "source_kind", "source_id", "workspace_type",
                    "workspace_key", "workspace_identity", "task_path_key", "root_relative_path",
                    "created_at", "updated_at"),
            "task_run_attempts", List.of("task_run_id", "attempt_number", "status", "started_at",
                    "completed_at", "error_text", "created_at", "updated_at"),
            "task_run_idempotent_ops", List.of("operation_key", "request_digest", "task_run_id",
                    "result_json", "created_at", "updated_at"),
            "task_run_native_links", List.of("task_run_id", "native_kind", "native_run_id",
                    "assignment_id", "workflow_child_id", "job_work_item_id", "created_at"),
            "task_run_writer_claims", List.of("task_run_id", "attempt_number", "claim_generation",
                    "owner_id", "fence_token", "created_at"));

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final ResourcePatternResolver resourceResolver;

    @Autowired
    public SchemaMigrationRunner(DataSource dataSource,
                                 ResourcePatternResolver resourceResolver) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.resourceResolver = resourceResolver == null
                ? new PathMatchingResourcePatternResolver(Thread.currentThread().getContextClassLoader())
                : resourceResolver;
        runMigrations();
    }

    public List<MigrationRecord> appliedMigrations() {
        return jdbcTemplate.query(
                "select version, resource_name, checksum, applied_at from schema_migrations order by version",
                (rs, row) -> new MigrationRecord(rs.getInt(1), rs.getString(2), rs.getString(3),
                        Instant.parse(rs.getString(4))));
    }

    public record MigrationRecord(int version, String resourceName, String checksum, Instant appliedAt) { }

    private void runMigrations() {
        List<MigrationResource> resources = discoverResources();
        validateDiscovery(resources);
        try (Connection connection = dataSource.getConnection()) {
            runLocked(connection, resources);
        } catch (SQLException exception) {
            throw new MigrationException("Failed to run schema migrations", exception);
        }
    }

    private void runLocked(Connection connection, List<MigrationResource> resources) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (!originalAutoCommit) {
            connection.commit();
            connection.setAutoCommit(true);
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("pragma busy_timeout = 30000");
            statement.execute("begin immediate");
            try {
                initializeFreshSchema(connection);
                ensureMigrationTable(connection);
                validateMigrationTable(connection);
                Map<Integer, AppliedMigration> applied = readApplied(connection);
                validateBookkeeping(resources, applied);
                for (MigrationResource resource : resources) {
                    if (applied.containsKey(resource.version())) {
                        continue;
                    }
                    log.info("Applying migration {} (version {})", resource.name(), resource.version());
                    ScriptUtils.executeSqlScript(connection, new EncodedResource(
                            new ByteArrayResource(resource.bytes(), resource.name()), StandardCharsets.UTF_8));
                    validateOwnedSchema(connection, resource.version());
                    insertApplied(connection, resource);
                }
                validateOwnedSchema(connection, resources.isEmpty() ? 0 : resources.get(resources.size() - 1).version());
                statement.execute("commit");
            } catch (Throwable failure) {
                try {
                    statement.execute("rollback");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                if (failure instanceof MigrationException migrationException) {
                    throw migrationException;
                }
                throw new MigrationException("Migration transaction failed", failure);
            }
        } finally {
            if (!originalAutoCommit) {
                connection.setAutoCommit(false);
            }
        }
    }

    private List<MigrationResource> discoverResources() {
        Resource[] discovered;
        try {
            discovered = resourceResolver.getResources(MIGRATION_PATH);
        } catch (IOException exception) {
            throw new MigrationException("Failed to discover migration resources", exception);
        }
        List<MigrationResource> migrations = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Resource resource : discovered) {
            String name = resource.getFilename();
            if (name == null || !names.add(name)) {
                throw new MigrationException("Duplicate or unnamed migration resource: " + name);
            }
            Matcher matcher = MIGRATION_NAME.matcher(name);
            if (!matcher.matches()) {
                throw new MigrationException("Invalid migration resource name: " + name);
            }
            byte[] bytes = readBytes(resource, name);
            migrations.add(new MigrationResource(Integer.parseInt(matcher.group(1)), name,
                    bytes, sha256(bytes)));
        }
        migrations.sort(Comparator.comparingInt(MigrationResource::version));
        return List.copyOf(migrations);
    }

    private void validateDiscovery(List<MigrationResource> resources) {
        Set<Integer> versions = new HashSet<>();
        int expected = 1;
        for (MigrationResource resource : resources) {
            if (!versions.add(resource.version())) {
                throw new MigrationException("Duplicate migration version: " + resource.version());
            }
            if (resource.version() != expected) {
                throw new MigrationException("Migration version gap: expected " + expected
                        + " but found " + resource.version());
            }
            expected++;
        }
    }

    private void initializeFreshSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     select count(*) from sqlite_master
                     where type = 'table' and name not like 'sqlite_%'
                     """)) {
            if (!rows.next() || rows.getInt(1) != 0) {
                return;
            }
        }
        Resource schema = resourceResolver.getResource(FRESH_SCHEMA_PATH);
        if (schema == null || !schema.exists()) {
            throw new MigrationException("Fresh schema resource is unavailable: " + FRESH_SCHEMA_PATH);
        }
        log.info("Initializing fresh schema from {}", FRESH_SCHEMA_PATH);
        ScriptUtils.executeSqlScript(connection,
                new EncodedResource(schema, StandardCharsets.UTF_8));
    }

    private void ensureMigrationTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    create table if not exists schema_migrations (
                        version integer primary key,
                        resource_name text not null unique,
                        checksum text not null check (length(checksum) = 64),
                        applied_at text not null
                    )
                    """);
        }
    }

    private void validateMigrationTable(Connection connection) throws SQLException {
        List<String> columns = tableColumns(connection, "schema_migrations");
        if (!columns.equals(List.of("version", "resource_name", "checksum", "applied_at"))) {
            throw new MigrationException("Incompatible schema_migrations table: " + columns);
        }
        String sql = tableSql(connection, "schema_migrations");
        if (!normalized(sql).contains("resource_name text not null unique")
                || !normalized(sql).contains("check (length(checksum) = 64)")) {
            throw new MigrationException("Incompatible schema_migrations constraints");
        }
    }

    private Map<Integer, AppliedMigration> readApplied(Connection connection) throws SQLException {
        Map<Integer, AppliedMigration> applied = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "select version, resource_name, checksum from schema_migrations order by version")) {
            while (rows.next()) {
                int version = rows.getInt(1);
                if (applied.put(version, new AppliedMigration(rows.getString(2), rows.getString(3))) != null) {
                    throw new MigrationException("Duplicate applied migration version: " + version);
                }
            }
        }
        return applied;
    }

    private void validateBookkeeping(List<MigrationResource> resources,
                                     Map<Integer, AppliedMigration> applied) {
        Map<Integer, MigrationResource> expected = new HashMap<>();
        resources.forEach(resource -> expected.put(resource.version(), resource));
        int contiguous = 1;
        for (Map.Entry<Integer, AppliedMigration> entry : applied.entrySet()) {
            if (entry.getKey() != contiguous++) {
                throw new MigrationException("Applied migration versions are not a contiguous prefix");
            }
            MigrationResource resource = expected.get(entry.getKey());
            if (resource == null) {
                throw new MigrationException("Unknown applied migration version " + entry.getKey());
            }
            AppliedMigration actual = entry.getValue();
            if (!resource.name().equals(actual.name())) {
                throw new MigrationException("Applied resource name mismatch for version " + entry.getKey());
            }
            if (!resource.checksum().equals(actual.checksum())) {
                throw new MigrationException("Checksum drift for applied migration " + resource.name());
            }
        }
    }

    private void insertApplied(Connection connection, MigrationResource resource) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into schema_migrations(version, resource_name, checksum, applied_at) values (?, ?, ?, ?)")) {
            statement.setInt(1, resource.version());
            statement.setString(2, resource.name());
            statement.setString(3, resource.checksum());
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void validateOwnedSchema(Connection connection, int latestVersion) throws SQLException {
        if (latestVersion < 1) {
            return;
        }
        for (String table : FOUNDATION_TABLES) {
            List<String> actual = tableColumns(connection, table);
            if (!actual.equals(FOUNDATION_COLUMNS.get(table))) {
                throw new MigrationException("Incompatible partial table " + table + ": " + actual);
            }
        }
        String locations = normalized(tableSql(connection, "task_run_locations"));
        if (!locations.contains("workspace_type in ('agent', 'user')")
                || !locations.contains("root_relative_path = 'workspace/'")) {
            throw new MigrationException("task_run_locations is missing confinement constraints");
        }
        String attempts = normalized(tableSql(connection, "task_run_attempts"));
        if (!attempts.contains("primary key (task_run_id, attempt_number)")
                || !attempts.contains("references task_run_locations(task_run_id) on delete cascade")) {
            throw new MigrationException("task_run_attempts is missing key or ownership constraints");
        }
        String operations = normalized(tableSql(connection, "task_run_idempotent_ops"));
        if (!operations.contains("operation_key text primary key")
                || !operations.contains("length(request_digest) = 64")
                || !operations.contains("references task_run_locations(task_run_id)")) {
            throw new MigrationException("task_run_idempotent_ops is missing digest or ownership constraints");
        }
        String links = normalized(tableSql(connection, "task_run_native_links"));
        if (!links.contains("unique (native_kind, native_run_id)")
                || !links.contains("references work_assignments(id)")
                || !links.contains("workflow_child_id is null or native_kind = 'workflow'")
                || !links.contains("job_work_item_id is null or native_kind = 'job'")) {
            throw new MigrationException("task_run_native_links is missing explicit linkage constraints");
        }
        String claims = normalized(tableSql(connection, "task_run_writer_claims"));
        if (!claims.contains("primary key (task_run_id, attempt_number, claim_generation)")
                || !claims.contains("claim_generation > 0")
                || !claims.contains("fence_token text not null unique")
                || !claims.contains("references task_run_attempts(task_run_id, attempt_number) on delete cascade")) {
            throw new MigrationException("task_run_writer_claims is missing monotonic fence constraints");
        }
        Set<String> indexes = objectNames(connection, "index", "idx_task_run_%");
        if (!indexes.containsAll(FOUNDATION_INDEXES)) {
            throw new MigrationException("Missing migration-owned indexes: "
                    + FOUNDATION_INDEXES.stream().filter(index -> !indexes.contains(index)).toList());
        }
    }

    private Set<String> objectNames(Connection connection, String type, String likePattern)
            throws SQLException {
        Set<String> names = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select name from sqlite_master where type = ? and name like ?")) {
            statement.setString(1, type);
            statement.setString(2, likePattern);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    names.add(rows.getString(1));
                }
            }
        }
        return names;
    }

    private List<String> tableColumns(Connection connection, String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("pragma table_info('" + table + "')")) {
            while (rows.next()) {
                columns.add(rows.getString("name"));
            }
        }
        return columns;
    }

    private String tableSql(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select sql from sqlite_master where type = 'table' and name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new MigrationException("Missing migration-owned table: " + table);
                }
                return rows.getString(1);
            }
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static byte[] readBytes(Resource resource, String name) {
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new MigrationException("Failed to read migration resource: " + name, exception);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new MigrationException("SHA-256 is unavailable", exception);
        }
    }

    private record MigrationResource(int version, String name, byte[] bytes, String checksum) { }
    private record AppliedMigration(String name, String checksum) { }

    public static final class MigrationException extends RuntimeException {
        public MigrationException(String message) { super(message); }
        public MigrationException(String message, Throwable cause) { super(message, cause); }
    }
}
