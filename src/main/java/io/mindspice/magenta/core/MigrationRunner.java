package io.mindspice.magenta.core;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The single schema initialization and migration owner for Magenta Core.
 *
 * <p>Construction installs the checked-in fresh schema in one transaction and
 * records its identity. The builder calls {@link #apply(List)} after creating
 * this runner; consequently repository constructors never create tables or
 * indexes. Historical tables, if present in the same SQLite database, are
 * deliberately outside this runner's read and write set.</p>
 *
 * <p>Migration callbacks receive a connection-bound data source so their work
 * and bookkeeping share one transaction. Version one is reserved for the
 * fresh schema; later caller migrations must be contiguous.</p>
 */
public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);

    private static final int FRESH_SCHEMA_VERSION = 1;
    private static final String SCHEMA_ID = "io.mindspice:magenta-core";
    private static final String SCHEMA_RESOURCE = "schema.sql";

    private static final String INSERT_IDENTITY = """
        INSERT OR IGNORE INTO magenta_core_schema_identity
            (schema_id, schema_version, schema_hash)
        VALUES (?, ?, ?)
        """;
    private static final String SELECT_IDENTITY = """
        SELECT schema_version, schema_hash
        FROM magenta_core_schema_identity
        WHERE schema_id = ?
        """;
    private static final String INSERT_VERSION = """
        INSERT OR IGNORE INTO magenta_core_schema_version
            (version, description)
        VALUES (?, ?)
        """;
    private static final String SELECT_CURRENT_VERSION =
        "SELECT COALESCE(MAX(version), 0) FROM magenta_core_schema_version";

    private final DataSource dataSource;
    private final String schemaHash;

    public MigrationRunner(@NonNull DataSource dataSource) {
        this.dataSource = prepareDataSource(
            Objects.requireNonNull(dataSource, "dataSource"));
        this.schemaHash = readSchemaHash();
        initializeFreshSchema();
    }

    /**
     * Applies caller-supplied migrations after the fresh schema. Each
     * migration and its bookkeeping row commit or roll back together.
     *
     * @param migrations migrations in any order; version one is already owned
     *                   by the fresh schema
     */
    public void apply(@NonNull List<Migration> migrations) {
        Objects.requireNonNull(migrations, "migrations");
        List<Migration> sorted = migrations.stream()
            .peek(migration -> Objects.requireNonNull(migration, "migration"))
            .sorted(Comparator.comparingInt(Migration::version))
            .toList();
        validateMigrationVersions(sorted);

        int current = currentVersion();
        for (Migration migration : sorted) {
            if (migration.version() <= current) {
                LOG.debug("Migration v{} already applied — skipping",
                    migration.version());
                continue;
            }
            if (migration.version() != current + 1) {
                throw new IllegalArgumentException(
                    "Missing migration version " + (current + 1)
                        + " before v" + migration.version());
            }
            applyOne(migration);
            current = migration.version();
        }
    }

    /** Returns the highest applied fresh-schema or caller migration version. */
    public int currentVersion() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(SELECT_CURRENT_VERSION);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Unable to read Magenta Core schema version", e);
        }
    }

    private void initializeFreshSchema() {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource(SCHEMA_RESOURCE));
                recordAndValidateIdentity(connection);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_VERSION)) {
                    statement.setInt(1, FRESH_SCHEMA_VERSION);
                    statement.setString(2, "Magenta Core fresh schema");
                    statement.executeUpdate();
                }
                connection.commit();
                LOG.debug("Magenta Core fresh schema initialized");
            } catch (Exception e) {
                rollback(connection, e);
                throw new IllegalStateException(
                    "Unable to initialize Magenta Core fresh schema", e);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Unable to open connection for Magenta Core schema initialization", e);
        }
    }

    private void recordAndValidateIdentity(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_IDENTITY)) {
            statement.setString(1, SCHEMA_ID);
            statement.setInt(2, FRESH_SCHEMA_VERSION);
            statement.setString(3, schemaHash);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement(SELECT_IDENTITY)) {
            statement.setString(1, SCHEMA_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SQLException("Magenta Core schema identity row was not created");
                }
                int version = resultSet.getInt(1);
                String persistedHash = resultSet.getString(2);
                if (version != FRESH_SCHEMA_VERSION || !schemaHash.equals(persistedHash)) {
                    throw new SQLException(
                        "Magenta Core schema identity does not match the checked-in schema");
                }
            }
        }
    }

    private void applyOne(Migration migration) {
        LOG.info("Applying Magenta Core migration v{}", migration.version());
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                // Suppress close() calls made by migration code so the runner
                // retains ownership of the transaction connection.
                DataSource transactionDataSource =
                    new SingleConnectionDataSource(connection, true);
                migration.migrate(transactionDataSource);
                try (PreparedStatement statement = connection.prepareStatement(INSERT_VERSION)) {
                    statement.setInt(1, migration.version());
                    statement.setString(2, "Migration v" + migration.version());
                    statement.executeUpdate();
                }
                connection.commit();
                LOG.info("Magenta Core migration v{} applied", migration.version());
            } catch (Exception e) {
                rollback(connection, e);
                throw new IllegalStateException(
                    "Magenta Core migration v" + migration.version() + " failed", e);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Unable to open connection for Magenta Core migration v"
                    + migration.version(), e);
        }
    }

    private void validateMigrationVersions(List<Migration> migrations) {
        Set<Integer> versions = new HashSet<>();
        for (Migration migration : migrations) {
            int version = migration.version();
            if (version <= FRESH_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                    "Migration versions must be greater than " + FRESH_SCHEMA_VERSION);
            }
            if (!versions.add(version)) {
                throw new IllegalArgumentException(
                    "Duplicate migration version " + version);
            }
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = dataSource.getConnection();
        try {
            configureConnection(connection);
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    private static void configureConnection(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("sqlite")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
        }
    }

    private static DataSource prepareDataSource(DataSource dataSource) {
        if (dataSource instanceof SQLiteDataSource sqliteDataSource) {
            // These settings are per-connection in the Xerial driver.
            sqliteDataSource.setEnforceForeignKeys(true);
            sqliteDataSource.setBusyTimeout(5000);
            sqliteDataSource.setTransactionMode("IMMEDIATE");
        }
        return dataSource;
    }

    private String readSchemaHash() {
        try (InputStream input = new ClassPathResource(SCHEMA_RESOURCE).getInputStream()) {
            byte[] bytes = input.readAllBytes();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                "Unable to read Magenta Core schema resource", e);
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection) {
        try {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ignored) {
            // The connection is about to be closed; preserve the root failure.
        }
    }
}
