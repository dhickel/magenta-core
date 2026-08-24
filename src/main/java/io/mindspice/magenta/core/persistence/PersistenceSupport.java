package io.mindspice.magenta.core.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;

/**
 * Shared JDBC mechanics for the framework-owned SQLite repositories.
 *
 * <p>This class deliberately contains no schema creation. Schema ownership
 * belongs to {@code MigrationRunner}; repositories only use the already
 * initialized tables.</p>
 */
final class PersistenceSupport {

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private PersistenceSupport() {}

    @FunctionalInterface
    interface ConnectionWork<T> {
        T execute(Connection connection) throws Exception;
    }

    static <T> T read(DataSource dataSource, ConnectionWork<T> work) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(work, "work");
        try (Connection connection = open(dataSource)) {
            return work.execute(connection);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Persistence operation failed", e);
        }
    }

    static <T> T transaction(DataSource dataSource, ConnectionWork<T> work) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(work, "work");
        try (Connection connection = open(dataSource)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.execute(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | Error e) {
                rollback(connection, e);
                throw e;
            } catch (Exception e) {
                rollback(connection, e);
                throw new RuntimeException("Persistence transaction failed", e);
            } finally {
                restoreAutoCommit(connection, originalAutoCommit);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Persistence transaction failed", e);
        }
    }

    static Connection open(DataSource dataSource) throws SQLException {
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

    static void configureConnection(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product != null && product.toLowerCase().contains("sqlite")) {
            // SQLite enables foreign-key enforcement per connection, not per database.
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
            }
        }
    }

    static void ensureSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT OR IGNORE INTO magenta_core_session_metadata (session_id) VALUES (?)")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    static String toJson(ObjectMapper objectMapper, Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unable to serialize persistence JSON", e);
        }
    }

    static String metadataToJson(ObjectMapper objectMapper, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        return toJson(objectMapper, metadata);
    }

    static Map<String, String> metadataFromJson(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return Map.copyOf(objectMapper.readValue(json, STRING_MAP));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to deserialize persistence metadata", e);
        }
    }

    static String timestamp(Instant instant) {
        return (instant != null ? instant : Instant.now()).toString();
    }

    static Instant parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Be tolerant of SQLite's traditional "yyyy-MM-dd HH:mm:ss" form
            // when a caller supplied a compatible existing row.
            String normalized = value.replace(' ', 'T');
            if (!normalized.endsWith("Z")
                && !normalized.contains("+")
                && normalized.indexOf('T') >= 0) {
                normalized += "Z";
            }
            try {
                return Instant.parse(normalized);
            } catch (DateTimeParseException e) {
                throw new IllegalStateException("Invalid persisted timestamp: " + value, e);
            }
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommit(Connection connection, boolean originalAutoCommit) {
        try {
            if (connection.getAutoCommit() != originalAutoCommit) {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException ignored) {
            // The connection is about to be closed. The original operation is
            // more useful to the caller than a cleanup failure.
        }
    }
}
