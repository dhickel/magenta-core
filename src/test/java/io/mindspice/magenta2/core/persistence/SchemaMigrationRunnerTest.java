package io.mindspice.magenta2.core.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.sqlite.SQLiteDataSource;

class SchemaMigrationRunnerTest {
    @Test
    void freshApplicationRerunAndChecksumProtection() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            Resource migration = foundation();
            SchemaMigrationRunner first = run(dataSource, migration);
            assertEquals(List.of(1), first.appliedMigrations().stream()
                    .map(SchemaMigrationRunner.MigrationRecord::version).toList());
            assertTrue(tableExists(new JdbcTemplate(dataSource), "task_run_locations"));

            run(dataSource, migration);
            assertEquals(1, new JdbcTemplate(dataSource).queryForObject(
                    "select count(*) from schema_migrations", Integer.class));

            Resource drifted = resource("001-task-run-foundation.sql",
                    migration.getContentAsByteArray(), "\n-- drift\n");
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, drifted));
        }
    }

    @Test
    void discoveryRejectsInvalidDuplicateAndGappedResourcesBeforeMutation() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, resource("notes.sql", "select 1")));
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, resource("001-first.sql", "select 1"),
                            resource("001-second.sql", "select 1")));
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, foundation(), resource("003-gap.sql", "select 1")));
            assertFalse(tableExists(new JdbcTemplate(dataSource), "schema_migrations"));
        }
    }

    @Test
    void unknownOrNonContiguousBookkeepingFailsClosed() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            createBookkeeping(jdbc);
            jdbc.update("insert into schema_migrations values (2, '002-ghost.sql', ?, ?)",
                    "0".repeat(64), java.time.Instant.now().toString());
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, foundation()));
        }
    }

    @Test
    void incompatiblePartialLegacyStructureIsNotBlessed() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("create table task_run_locations(task_run_id text primary key)");
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, foundation()));
            assertFalse(tableExists(jdbc, "schema_migrations"),
                    "failed bootstrap and migration must roll back atomically");
            assertFalse(tableExists(jdbc, "task_run_attempts"));
        }
    }

    @Test
    void interruptedMigrationRollsBackAndRestartConverges() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            byte[] bytes = foundation().getContentAsByteArray();
            Resource broken = resource("001-task-run-foundation.sql", bytes,
                    "\ncreate table interruption_probe(id text);\nthis is invalid sql;\n");
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(dataSource, broken));
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertFalse(tableExists(jdbc, "interruption_probe"));
            assertFalse(tableExists(jdbc, "schema_migrations"));

            run(dataSource, foundation());
            assertTrue(tableExists(jdbc, "task_run_writer_claims"));
            assertEquals(1, jdbc.queryForObject("select count(*) from schema_migrations", Integer.class));
        }
    }

    @Test
    void twoIndependentConcurrentStartersSerialize(@TempDir Path tempDir) throws Exception {
        Path database = tempDir.resolve("concurrent.sqlite");
        DataSource first = fileDataSource(database);
        DataSource second = fileDataSource(database);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = Executors.newFixedThreadPool(2)) {
            for (DataSource dataSource : List.of(first, second)) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        run(dataSource, foundation());
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(35, TimeUnit.SECONDS));
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(1, new JdbcTemplate(first).queryForObject(
                "select count(*) from schema_migrations", Integer.class));
    }

    @Test
    void freshSchemaAndMigratedDatabaseHaveEquivalentOwnedMetadata() throws Exception {
        try (SingleConnectionDataSource migrated = memoryDataSource();
             SingleConnectionDataSource fresh = memoryDataSource()) {
            new JdbcTemplate(migrated).execute("create table representative_legacy_marker(id text)");
            run(migrated, foundation());
            try (Connection connection = fresh.getConnection()) {
                ScriptUtils.executeSqlScript(connection,
                        new EncodedResource(new ClassPathResource("schema.sql"), StandardCharsets.UTF_8));
            }
            run(fresh, foundation());
            assertEquals(ownedMetadata(new JdbcTemplate(migrated)),
                    ownedMetadata(new JdbcTemplate(fresh)));
        }
    }

    @Test
    void persistedLocationsRejectChatAbsoluteOrNonCanonicalPathsAndOperationKeyDigestConflicts() throws Exception {
        try (SingleConnectionDataSource dataSource = memoryDataSource()) {
            run(dataSource, foundation());
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            String key = "v1-task-" + "a".repeat(64);
            String insert = """
                    insert into task_run_locations(
                        task_run_id, source_kind, source_id, workspace_type, workspace_key,
                        workspace_identity, task_path_key, root_relative_path, created_at, updated_at)
                    values (?, 'task', 'definition-1', ?, 'agent-1', ?, ?, ?, 'now', 'now')
                    """;
            jdbc.update(insert, "run-1", "agent", "agent/agent-1", key,
                    "workspace/agent/agent-1/tasks/" + key + "/run-1");
            assertThrows(Exception.class, () -> jdbc.update(insert, "run-chat", "chat",
                    "chat/chat-1", key, "workspace/chat/chat-1/tasks/" + key + "/run-chat"));
            assertThrows(Exception.class, () -> jdbc.update(insert, "run-abs", "agent",
                    "agent/agent-1", key, "/tmp/run-abs"));
            jdbc.update("""
                    insert into task_run_idempotent_ops(
                        operation_key, request_digest, task_run_id, created_at, updated_at)
                    values ('op-1', ?, 'run-1', 'now', 'now')
                    """, "b".repeat(64));
            assertThrows(Exception.class, () -> jdbc.update("""
                    insert into task_run_idempotent_ops(
                        operation_key, request_digest, task_run_id, created_at, updated_at)
                    values ('op-1', ?, 'run-1', 'now', 'now')
                    """, "c".repeat(64)));
        }
    }

    @Test
    void bootstrapSchemaRejectsMalformedBookkeepingAndNoResourcesStillBootstraps() throws Exception {
        try (SingleConnectionDataSource malformed = memoryDataSource()) {
            new JdbcTemplate(malformed).execute("create table schema_migrations(version integer primary key)");
            assertThrows(SchemaMigrationRunner.MigrationException.class,
                    () -> run(malformed, foundation()));
        }
        try (SingleConnectionDataSource empty = memoryDataSource()) {
            run(empty);
            assertTrue(tableExists(new JdbcTemplate(empty), "schema_migrations"));
        }
    }

    private static Map<String, String> ownedMetadata(JdbcTemplate jdbc) {
        return jdbc.query("""
                select type || ':' || name as object_name, lower(replace(replace(sql, char(10), ' '), char(13), ' ')) as ddl
                from sqlite_master
                where (type = 'table' and name like 'task_run_%')
                   or (type = 'index' and name like 'idx_task_run_%')
                order by object_name
                """, rs -> {
            Map<String, String> result = new java.util.LinkedHashMap<>();
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2).replaceAll("\\s+", " "));
            }
            return result;
        });
    }

    private static void createBookkeeping(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table schema_migrations(
                    version integer primary key,
                    resource_name text not null unique,
                    checksum text not null check(length(checksum) = 64),
                    applied_at text not null)
                """);
    }

    private static boolean tableExists(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from sqlite_master where type='table' and name=?",
                Integer.class, table) == 1;
    }

    private static SchemaMigrationRunner run(DataSource dataSource, Resource... resources) {
        return new SchemaMigrationRunner(dataSource, resolver(resources));
    }

    private static Resource foundation() {
        return new ClassPathResource("db/migrations/001-task-run-foundation.sql");
    }

    private static Resource resource(String name, String sql) {
        return new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8), name) {
            @Override public String getFilename() { return name; }
        };
    }

    private static Resource resource(String name, byte[] prefix, String suffix) {
        byte[] tail = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = java.util.Arrays.copyOf(prefix, prefix.length + tail.length);
        System.arraycopy(tail, 0, bytes, prefix.length, tail.length);
        return new ByteArrayResource(bytes, name) {
            @Override public String getFilename() { return name; }
        };
    }

    private static ResourcePatternResolver resolver(Resource... resources) {
        return new ResourcePatternResolver() {
            @Override public Resource[] getResources(String pattern) { return resources; }
            @Override public Resource getResource(String location) {
                return "classpath:schema.sql".equals(location)
                        ? new ClassPathResource("schema.sql")
                        : new ClassPathResource(location.replace("classpath:", ""));
            }
            @Override public ClassLoader getClassLoader() { return getClass().getClassLoader(); }
        };
    }

    private static SingleConnectionDataSource memoryDataSource() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
                "jdbc:sqlite::memory:?foreign_keys=true", true);
        dataSource.setSuppressClose(true);
        return dataSource;
    }

    private static DataSource fileDataSource(Path path) {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + path + "?foreign_keys=true");
        return dataSource;
    }
}
