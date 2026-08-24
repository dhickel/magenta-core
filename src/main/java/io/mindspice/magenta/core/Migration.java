package io.mindspice.magenta.core;

import javax.sql.DataSource;

/**
 * A versioned schema migration. Migrations are applied in version order
 * by {@link MigrationRunner}.
 */
public interface Migration {

    /**
     * The migration version number. Version {@code 1} belongs to the
     * framework fresh schema; extension migrations use contiguous versions
     * starting at {@code 2}.
     */
    int version();

    /**
     * Execute this migration against the given {@link DataSource}.
     * The runner supplies a connection-bound data source and commits the
     * migration bookkeeping in the same transaction.
     *
     * @param dataSource the transaction-bound data source to migrate
     * @throws Exception on migration failure
     */
    void migrate(@NonNull DataSource dataSource) throws Exception;
}
