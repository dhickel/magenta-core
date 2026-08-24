package io.mindspice.magenta.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;

/**
 * Applies version-ordered {@link Migration} instances to a database.
 * Each migration runs in its own transaction: committed on success,
 * rolled back on failure.
 *
 * <p>Current version is tracked in the {@code turnloop_schema_version}
 * table (created automatically if it does not exist).</p>
 *
 * <p>Uses plain JDBC via {@link DataSource#getConnection()} — no Spring
 * transaction management dependency.</p>
 */
public final class MigrationRunner {

    private static final Logger LOG =
        LoggerFactory.getLogger(MigrationRunner.class);

    private static final String ENSURE_VERSION_TABLE =
        """
        CREATE TABLE IF NOT EXISTS turnloop_schema_version (
            version INTEGER NOT NULL,
            applied_at TEXT NOT NULL DEFAULT (datetime('now')),
            description TEXT
        )
        """;

    private static final String SELECT_CURRENT_VERSION =
        "SELECT COALESCE(MAX(version), 0) FROM turnloop_schema_version";

    private static final String INSERT_VERSION =
        "INSERT INTO turnloop_schema_version (version, description) VALUES (?, ?)";

    private final DataSource dataSource;

    public MigrationRunner(@NonNull DataSource dataSource) {
        this.dataSource = dataSource;
        ensureVersionTable();
    }

    // ── Public API ──

    /**
     * Applies all pending migrations in version order. Each migration
     * executes in its own transaction. If a migration fails, the
     * transaction is rolled back and processing stops immediately.
     *
     * @param migrations the migrations to apply (may be unsorted)
     */
    public void apply(@NonNull List<Migration> migrations) {
        List<Migration> sorted = migrations.stream()
            .sorted(Comparator.comparingInt(Migration::version))
            .toList();

        int current = currentVersion();

        for (Migration migration : sorted) {
            if (migration.version() <= current) {
                LOG.debug("Migration v{} already applied — skipping",
                    migration.version());
                continue;
            }

            LOG.info("Applying migration v{} ...", migration.version());

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    migration.migrate(dataSource);

                    try (PreparedStatement ps =
                             conn.prepareStatement(INSERT_VERSION)) {
                        ps.setInt(1, migration.version());
                        ps.setString(2,
                            "Migration v" + migration.version());
                        ps.executeUpdate();
                    }

                    conn.commit();
                    LOG.info("Migration v{} applied successfully",
                        migration.version());
                } catch (Exception e) {
                    conn.rollback();
                    LOG.error("Migration v{} failed — rolled back",
                        migration.version(), e);
                    throw new RuntimeException(
                        "Migration v" + migration.version()
                            + " failed: " + e.getMessage(), e);
                }
            } catch (SQLException e) {
                throw new RuntimeException(
                    "Unable to obtain connection for migration v"
                        + migration.version(), e);
            }
        }
    }

    /**
     * Returns the highest applied migration version, or 0 if none.
     */
    public int currentVersion() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps =
                 conn.prepareStatement(SELECT_CURRENT_VERSION);
             ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1);
            }
            return 0;
        } catch (SQLException e) {
            throw new RuntimeException(
                "Unable to read current schema version", e);
        }
    }

    // ── Internal ──

    private void ensureVersionTable() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps =
                 conn.prepareStatement(ENSURE_VERSION_TABLE)) {
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException(
                "Unable to create turnloop_schema_version table", e);
        }
    }
}
