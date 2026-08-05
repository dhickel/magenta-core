package io.mindspice.core;

import javax.sql.DataSource;

/**
 * A versioned schema migration. Migrations are applied in version order
 * by {@link MigrationRunner}.
 */
public interface Migration {

    /**
     * The migration version number. Versions should be positive integers
     * applied in ascending order.
     */
    int version();

    /**
     * Execute this migration against the given {@link DataSource}.
     * Implementations are responsible for creating their own
     * tables and indexes (use {@code CREATE TABLE IF NOT EXISTS}).
     *
     * @param dataSource the data source to migrate
     * @throws Exception on migration failure
     */
    void migrate(@NonNull DataSource dataSource) throws Exception;
}
