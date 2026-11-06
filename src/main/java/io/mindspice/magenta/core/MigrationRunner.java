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
import java.util.Locale;
import java.util.Map;
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
    private static final String SCHEMA_ID =
        "io.mindspice:magenta-core:sealed-adts-v1";
    private static final String SCHEMA_RESOURCE = "schema.sql";

    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.ofEntries(
        Map.entry("magenta_core_schema_identity", Set.of(
            "schema_id", "schema_version", "schema_hash", "initialized_at")),
        Map.entry("magenta_core_schema_version", Set.of("version", "applied_at", "description")),
        Map.entry("magenta_core_session_metadata", Set.of(
            "session_id", "title", "model", "favorite", "archived", "origin",
            "metadata_json", "created_at", "updated_at")),
        Map.entry("magenta_core_conversation_memory", Set.of(
            "id", "session_id", "role", "content", "tool_name", "tool_call_id",
            "metadata_json", "created_at")),
        Map.entry("magenta_core_queued_requests", Set.of(
            "message_id", "session_id", "message", "explicit_model",
            "explicit_tool_names_json", "metadata_json", "queue_reason", "enqueued_at",
            "claim_token", "claimed_at", "lease_expires_at")),
        Map.entry("magenta_core_interactions", Set.of(
            "interaction_id", "session_id", "turn_id", "model", "explicit_tool_names_json",
            "metadata_json", "continuation_json", "origin_type", "origin_interaction_id",
            "origin_message_id", "started_at", "completed_at", "context_used_tokens",
            "context_trigger_tokens", "context_max_tokens", "state", "cancellation_reason",
            "created_at", "updated_at", "cancelled_at")),
        Map.entry("magenta_core_interaction_questions", Set.of(
            "interaction_id", "question_index", "question_text")),
        Map.entry("magenta_core_interaction_answers", Set.of(
            "interaction_id", "answer_index", "answer_text")),
        Map.entry("magenta_core_interaction_tool_activities", Set.of(
            "interaction_id", "activity_index", "tool_name", "tool_call_id", "status",
            "failure_category", "summary")),
        Map.entry("magenta_core_audit_log", Set.of(
            "id", "session_id", "event_type", "payload_json", "created_at")));

    private static final Set<String> REQUIRED_INDEXES = Set.of(
        "idx_magenta_core_session_origin", "idx_magenta_core_session_favorite",
        "idx_magenta_core_session_archived", "idx_magenta_core_memory_session",
        "idx_magenta_core_memory_session_role", "idx_magenta_core_queue_session_order",
        "idx_magenta_core_queue_claim_token", "idx_magenta_core_queue_expired_claims",
        "idx_magenta_core_interactions_one_pending", "idx_magenta_core_interactions_session_state",
        "idx_magenta_core_interactions_turn", "idx_magenta_core_interaction_questions_order",
        "idx_magenta_core_interaction_answers_order", "idx_magenta_core_interaction_activities_order",
        "idx_magenta_core_audit_session", "idx_magenta_core_audit_type");

    private static final Map<String, List<String>> REQUIRED_SQL_FRAGMENTS = Map.ofEntries(
        Map.entry("magenta_core_schema_identity", List.of(
            "SCHEMA_IDTEXTPRIMARYKEY", "SCHEMA_VERSIONINTEGERNOTNULL", "SCHEMA_HASHTEXTNOTNULL")),
        Map.entry("magenta_core_schema_version", List.of("VERSIONINTEGERPRIMARYKEY")),
        Map.entry("magenta_core_session_metadata", List.of(
            "SESSION_IDTEXTPRIMARYKEY", "FAVORITEINTEGERNOTNULL", "ARCHIVEDINTEGERNOTNULL")),
        Map.entry("magenta_core_conversation_memory", List.of(
            "REFERENCESMAGENTA_CORE_SESSION_METADATA", "ROLETEXTNOTNULLCHECK", "CHECK((ROLE='TOOL'")),
        Map.entry("magenta_core_queued_requests", List.of(
            "MESSAGE_IDTEXTPRIMARYKEY", "REFERENCESMAGENTA_CORE_SESSION_METADATA",
            "QUEUE_REASONTEXTNOTNULLCHECK", "CLAIM_TOKEN", "CHECK((CLAIM_TOKENISNULL")),
        Map.entry("magenta_core_interactions", List.of(
            "INTERACTION_IDTEXTPRIMARYKEY", "REFERENCESMAGENTA_CORE_SESSION_METADATA",
            "STATETEXTNOTNULLCHECK", "ORIGIN_TYPETEXTNOTNULLCHECK", "CANCELLATION_REASON")),
        Map.entry("magenta_core_interaction_questions", List.of(
            "REFERENCESMAGENTA_CORE_INTERACTIONS", "PRIMARYKEY(INTERACTION_ID,QUESTION_INDEX)")),
        Map.entry("magenta_core_interaction_answers", List.of(
            "REFERENCESMAGENTA_CORE_INTERACTIONS", "PRIMARYKEY(INTERACTION_ID,ANSWER_INDEX)")),
        Map.entry("magenta_core_interaction_tool_activities", List.of(
            "REFERENCESMAGENTA_CORE_INTERACTIONS", "STATUSTEXTNOTNULLCHECK", "FAILURE_CATEGORY",
            "CHECK((STATUS='FAILED'")),
        Map.entry("magenta_core_audit_log", List.of(
            "REFERENCESMAGENTA_CORE_SESSION_METADATA", "PAYLOAD_JSONTEXTNOTNULL")));

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
                rejectIncompatibleExistingSchema(connection);
                if (tableExists(connection, "magenta_core_schema_identity")) {
                    validateCurrentSchemaStructure(connection);
                }
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
                    "Unable to initialize Magenta Core fresh schema: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                    e);
            } finally {
                restoreAutoCommit(connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                "Unable to open connection for Magenta Core schema initialization", e);
        }
    }

    /**
     * Rejects any pre-existing Magenta Core identity before ScriptUtils can
     * execute CREATE IF NOT EXISTS statements. This is deliberately stricter
     * than a hash check after initialization: an old schema must neither be
     * reused nor have new tables installed beside it.
     */
    private void rejectIncompatibleExistingSchema(Connection connection) throws SQLException {
        if (!tableExists(connection, "magenta_core_schema_identity")) {
            if (hasMagentaCoreObject(connection)) {
                throw incompatibleSchema("Magenta Core tables or indexes exist without a schema identity");
            }
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT schema_id, schema_version, schema_hash
            FROM magenta_core_schema_identity
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw incompatibleSchema(
                        "Magenta Core schema identity is present but empty");
                }
                String schemaId = resultSet.getString("schema_id");
                int version = resultSet.getInt("schema_version");
                String hash = resultSet.getString("schema_hash");
                if (!SCHEMA_ID.equals(schemaId)
                    || version != FRESH_SCHEMA_VERSION
                    || !schemaHash.equals(hash)
                    || resultSet.next()) {
                    throw incompatibleSchema(
                        "Existing Magenta Core schema identity does not match "
                            + SCHEMA_ID);
                }
            }
        } catch (SQLException e) {
            throw incompatibleSchema(
                "Magenta Core schema identity table is malformed", e);
        }
    }

    private void validateCurrentSchemaStructure(Connection connection) throws SQLException {
        SQLiteDataSource expectedDataSource = new SQLiteDataSource();
        expectedDataSource.setUrl("jdbc:sqlite::memory:");
        try (Connection expectedConnection = expectedDataSource.getConnection()) {
            configureConnection(expectedConnection);
            ScriptUtils.executeSqlScript(expectedConnection,
                new ClassPathResource(SCHEMA_RESOURCE));
            Map<String, String> expectedObjects = readSqliteObjects(expectedConnection);
            Map<String, String> actualObjects = readSqliteObjects(connection);
            Set<String> missing = new HashSet<>(expectedObjects.keySet());
            missing.removeAll(actualObjects.keySet());
            Set<String> unexpected = new HashSet<>(actualObjects.keySet());
            unexpected.removeAll(expectedObjects.keySet());
            Set<String> changed = new HashSet<>();
            for (String name : expectedObjects.keySet()) {
                if (actualObjects.containsKey(name)
                    && !Objects.equals(expectedObjects.get(name), actualObjects.get(name))) {
                    changed.add(name);
                }
            }
            if (!missing.isEmpty() || !changed.isEmpty()
                || (!unexpected.isEmpty() && schemaVersion(connection) <= FRESH_SCHEMA_VERSION)) {
                throw incompatibleSchema(
                    "Framework schema definitions differ; missing=" + missing
                        + ", changed=" + changed + ", unexpected=" + unexpected);
            }
        }

        for (String tableName : REQUIRED_COLUMNS.keySet()) {
            if (!tableExists(connection, tableName)) {
                throw incompatibleSchema("Required table is missing: " + tableName);
            }
            Set<String> columns = new HashSet<>();
            try (ResultSet resultSet = connection.getMetaData().getColumns(
                null, null, tableName, null)) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!columns.containsAll(REQUIRED_COLUMNS.get(tableName))) {
                Set<String> missing = new HashSet<>(REQUIRED_COLUMNS.get(tableName));
                missing.removeAll(columns);
                throw incompatibleSchema(
                    "Required columns are missing from " + tableName + ": " + missing);
            }
        }

        Set<String> indexes = new HashSet<>();
        for (String tableName : REQUIRED_COLUMNS.keySet()) {
            try (ResultSet resultSet = connection.getMetaData().getIndexInfo(
                null, null, tableName, false, false)) {
                while (resultSet.next()) {
                    String name = resultSet.getString("INDEX_NAME");
                    if (name != null) indexes.add(name);
                }
            }
        }
        if (!indexes.containsAll(REQUIRED_INDEXES)) {
            Set<String> missing = new HashSet<>(REQUIRED_INDEXES);
            missing.removeAll(indexes);
            throw incompatibleSchema("Required indexes are missing: " + missing);
        }

        for (Map.Entry<String, List<String>> entry : REQUIRED_SQL_FRAGMENTS.entrySet()) {
            String sql;
            try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
                statement.setString(1, entry.getKey());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw incompatibleSchema("Required table definition is missing: " + entry.getKey());
                    }
                    sql = resultSet.getString(1);
                }
            }
            if (sql == null) {
                throw incompatibleSchema("Required table definition is null: " + entry.getKey());
            }
            String normalized = sql.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
            for (String fragment : entry.getValue()) {
                if (!normalized.contains(fragment)) {
                    throw incompatibleSchema(
                        "Required constraint is missing from " + entry.getKey()
                            + ": " + fragment);
                }
            }
        }
    }

    private Map<String, String> readSqliteObjects(Connection connection) throws SQLException {
        Map<String, String> objects = new java.util.HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT type, name, sql
            FROM sqlite_master
            WHERE (name GLOB 'magenta_core_*' OR name GLOB 'idx_magenta_core_*')
              AND type IN ('table', 'index')
            ORDER BY type, name
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    objects.put(resultSet.getString("type") + ":"
                        + resultSet.getString("name"), resultSet.getString("sql"));
                }
            }
        }
        return Map.copyOf(objects);
    }

    private int schemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COALESCE(MAX(version), 0) FROM magenta_core_schema_version");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(
            null, null, tableName, new String[] {"TABLE"})) {
            return resultSet.next();
        }
    }

    private boolean hasMagentaCoreObject(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT 1
            FROM sqlite_master
            WHERE type IN ('table', 'index')
              AND (name GLOB 'magenta_core_*' OR name GLOB 'idx_magenta_core_*')
            LIMIT 1
            """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private IllegalStateException incompatibleSchema(String detail) {
        return new IllegalStateException(
            "Incompatible existing Magenta Core schema: " + detail
                + ". Refusing to reuse, rewrite, or delete existing tables");
    }

    private IllegalStateException incompatibleSchema(String detail, Throwable cause) {
        return new IllegalStateException(
            "Incompatible existing Magenta Core schema: " + detail
                + ". Refusing to reuse, rewrite, or delete existing tables", cause);
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
