package io.mindspice.magenta2.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.util.Arrays;

import javax.sql.DataSource;

import io.mindspice.magenta2.Magenta2Application;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class MagentaRootConfigurationTest {

    @Test
    void createsParentDirectoryForFileBackedSqliteBeforeConnection(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("missing-root").resolve("magenta.sqlite");
        DataSourceProperties properties = sqliteProperties("jdbc:sqlite:" + db + "?foreign_keys=true");

        assertThat(db.getParent()).doesNotExist();
        MagentaRootConfiguration.createSqliteParentDirectory(properties.getUrl());
        DataSource dataSource = properties.initializeDataSourceBuilder().build();

        assertThat(db.getParent()).isDirectory();
        try (Connection ignored = dataSource.getConnection()) {
            assertThat(db).exists();
        } finally {
            close(dataSource);
        }
    }

    @Test
    void resolvesDefaultPlaceholderUrlBeforeCreatingSqliteParent(@TempDir Path tempDir) {
        Path magentaRoot = tempDir.resolve("placeholder-root");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("magenta.root.path", magentaRoot.toString())
            .withProperty(
                "spring.datasource.url",
                "jdbc:sqlite:${magenta.root.path}/magenta.sqlite?foreign_keys=true"
            );
        MagentaRootConfiguration.SqliteDataSourceParentDirectoryInitializer initializer =
            new MagentaRootConfiguration.SqliteDataSourceParentDirectoryInitializer();
        initializer.setEnvironment(environment);

        initializer.postProcessBeanFactory(null);

        assertThat(magentaRoot).isDirectory();
    }

    @Test
    void ignoresInMemorySqliteUrls(@TempDir Path tempDir) throws Exception {
        Path untouchedRoot = tempDir.resolve("memory-root");
        MockEnvironment environment = new MockEnvironment()
            .withProperty("magenta.root.path", untouchedRoot.toString())
            .withProperty("spring.datasource.url", "jdbc:sqlite::memory:?foreign_keys=true");
        MagentaRootConfiguration.SqliteDataSourceParentDirectoryInitializer initializer =
            new MagentaRootConfiguration.SqliteDataSourceParentDirectoryInitializer();
        initializer.setEnvironment(environment);

        assertThat(MagentaRootConfiguration.sqliteFilePath("jdbc:sqlite::memory:?foreign_keys=true")).isEmpty();
        initializer.postProcessBeanFactory(null);
        DataSourceProperties properties = sqliteProperties("jdbc:sqlite::memory:?foreign_keys=true");
        MagentaRootConfiguration.createSqliteParentDirectory(properties.getUrl());
        DataSource dataSource = properties.initializeDataSourceBuilder().build();

        assertThat(untouchedRoot).doesNotExist();
        close(dataSource);
    }

    @Test
    void ignoresUriMemorySqliteUrls() {
        assertThat(MagentaRootConfiguration.sqliteFilePath("jdbc:sqlite:file:memdb?mode=memory&cache=shared"))
            .isEmpty();
        assertThat(MagentaRootConfiguration.sqliteFilePath("jdbc:sqlite:file::memory:?cache=shared"))
            .isEmpty();
    }

    @Test
    void createsParentDirectoryForFileBackedSqliteUriForms(@TempDir Path tempDir) throws Exception {
        Path absoluteDb = tempDir.resolve("uri-absolute").resolve("magenta.sqlite");
        Path localDb = tempDir.resolve("uri-localhost").resolve("magenta.sqlite");

        MagentaRootConfiguration.createSqliteParentDirectory(
            "jdbc:sqlite:file:" + absoluteDb + "?mode=rwc&foreign_keys=true");
        MagentaRootConfiguration.createSqliteParentDirectory(
            "jdbc:sqlite:file://localhost" + localDb + "?mode=rwc&foreign_keys=true");

        assertThat(absoluteDb.getParent()).isDirectory();
        assertThat(localDb.getParent()).isDirectory();
    }

    @Test
    void preservesOperatorFileBackedDatasourceOverride(@TempDir Path tempDir) throws Exception {
        Path db = tempDir.resolve("operator").resolve("override.sqlite");
        String url = "jdbc:sqlite:" + db + "?foreign_keys=true";
        DataSourceProperties properties = sqliteProperties(url);
        MagentaRootConfiguration.createSqliteParentDirectory(properties.getUrl());
        DataSource dataSource = properties.initializeDataSourceBuilder().build();

        assertThat(db.getParent()).isDirectory();
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(url);
        } finally {
            close(dataSource);
        }
    }

    @Test
    void freshInstallContextUsesRootOwnedDefaultsAndInitializesSchema(@TempDir Path tempDir) throws Exception {
        Path magentaRoot = tempDir.resolve("fresh-root");
        Path aiConfig = writeAiConfig(tempDir.resolve("config"));

        assertThat(magentaRoot).doesNotExist();
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Magenta2Application.class)
            .web(WebApplicationType.NONE)
            .run(
                "--server.port=0",
                "--magenta.root.path=" + magentaRoot,
                "--app.ai.config-path=" + aiConfig,
                "--spring.datasource.url=jdbc:sqlite:" + magentaRoot.resolve("magenta.sqlite") + "?foreign_keys=true",
                "--spring.datasource.driver-class-name=org.sqlite.JDBC",
                "--magenta.features.schedules-enabled=false",
                "--magenta.features.reactions-enabled=false",
                "--magenta.plan.execution-stream-timeout-seconds=0"
            )) {
            Path dataRoot = magentaRoot.resolve("root").normalize();

            assertThat(magentaRoot).isDirectory();
            assertThat(dataRoot).isDirectory();
            assertThat(magentaRoot.resolve("magenta.sqlite")).exists();
            assertThat(magentaRoot.resolve("avatar.sqlite")).doesNotExist();
            assertNoAvatarBeans(context);
            assertThat(context.getBean(AiConfig.class).dataRoot()).isEqualTo(dataRoot);
            assertThat(context.getBean(WorkspaceDirectoryService.class).dataRoot()).isEqualTo(dataRoot.toRealPath());

            Integer tableCount = context.getBean(JdbcTemplate.class).queryForObject(
                "select count(*) from sqlite_master where type = 'table' and name = 'ai_chat_memory'",
                Integer.class
            );
            assertThat(tableCount).isEqualTo(1);
        }
    }

    @Test
    void startupLeavesPreExistingAvatarSentinelUntouched(@TempDir Path tempDir) throws Exception {
        Path magentaRoot = Files.createDirectories(tempDir.resolve("sentinel-root"));
        Path sentinel = magentaRoot.resolve("avatar.sqlite");
        byte[] original = "not a sqlite database; retained user data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(sentinel, original);
        FileTime modified = Files.getLastModifiedTime(sentinel);
        Path aiConfig = writeAiConfig(tempDir.resolve("sentinel-config"));

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Magenta2Application.class)
            .web(WebApplicationType.NONE)
            .run(
                "--server.port=0",
                "--magenta.root.path=" + magentaRoot,
                "--app.ai.config-path=" + aiConfig,
                "--spring.datasource.url=jdbc:sqlite:" + magentaRoot.resolve("magenta.sqlite") + "?foreign_keys=true",
                "--spring.datasource.driver-class-name=org.sqlite.JDBC",
                "--magenta.features.schedules-enabled=false",
                "--magenta.features.reactions-enabled=false",
                "--magenta.plan.execution-stream-timeout-seconds=0"
            )) {
            assertThat(context.getBean(JdbcTemplate.class).queryForObject("select 1", Integer.class)).isEqualTo(1);
            assertNoAvatarBeans(context);
        }

        assertThat(Files.readAllBytes(sentinel)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(sentinel)).isEqualTo(modified);
    }

    private static void assertNoAvatarBeans(ConfigurableApplicationContext context) {
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
            .filter(name -> name.toLowerCase(java.util.Locale.ROOT).contains("avatar"))
            .toList()).isEmpty();
        assertThat(Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType)
            .filter(java.util.Objects::nonNull)
            .map(Class::getName)
            .filter(name -> name.startsWith("io.mindspice.magenta2.avatar.")
                || name.contains("AvatarDashboard") || name.contains("AvatarAssistantTool"))
            .toList()).isEmpty();
    }

    private static DataSourceProperties sqliteProperties(String url) {
        DataSourceProperties properties = new DataSourceProperties();
        properties.setUrl(url);
        properties.setDriverClassName("org.sqlite.JDBC");
        return properties;
    }

    private static Path writeAiConfig(Path configDir) throws IOException {
        Files.createDirectories(configDir.resolve("prompts"));
        Files.writeString(configDir.resolve("prompts/system.md"), "Fresh install test agent.");
        Path config = configDir.resolve("ai-config.json");
        Files.writeString(config, """
            {
              "defaultAgent": "magenta",
              "defaultModel": "local-qwen",
              "summaryModel": "local-qwen",
              "planningModel": "local-qwen",
              "compactionModel": "local-qwen",
              "contextBufferPercent": 33,
              "unsafeAllowWildcardShellCommands": false,
              "models": {
                "local-qwen": {
                  "remoteModelName": "qwen3",
                  "remoteEndpoint": "http://localhost:11434",
                  "endpointType": "OLLAMA",
                  "contextLength": 8192
                }
              },
              "agents": {
                "magenta": {
                  "model": "local-qwen",
                  "systemPrompt": "prompts/system.md",
                  "approvedTools": [],
                  "allowedShellCommands": []
                }
              }
            }
            """);
        return config;
    }

    private static void close(DataSource dataSource) throws Exception {
        if (dataSource instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
