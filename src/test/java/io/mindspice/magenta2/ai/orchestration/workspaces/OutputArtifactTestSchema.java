package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.sql.Connection;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Initializes migration-owned schema for repository unit tests without production DDL. */
public final class OutputArtifactTestSchema {
    private OutputArtifactTestSchema() { }

    public static void initialize(JdbcTemplate jdbcTemplate) {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("schema.sql"));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize test schema", exception);
        }
    }
}
