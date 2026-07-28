package io.mindspice.magenta2.core.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import java.time.Clock;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(MagentaRootProperties.class)
public class MagentaRootConfiguration {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    static SqliteDataSourceParentDirectoryInitializer sqliteDataSourceParentDirectoryInitializer() {
        return new SqliteDataSourceParentDirectoryInitializer();
    }

    static void createSqliteParentDirectory(String url) throws IOException {
        sqliteFilePath(url)
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .map(Path::getParent)
            .ifPresent(parent -> {
                try {
                    Files.createDirectories(parent);
                } catch (IOException exception) {
                    throw new IllegalStateException("Failed to create SQLite parent directory: " + parent, exception);
                }
            });
    }

    static Optional<Path> sqliteFilePath(String url) {
        if (!StringUtils.hasText(url) || !url.startsWith(SQLITE_PREFIX)) {
            return Optional.empty();
        }

        String location = url.substring(SQLITE_PREFIX.length());
        String query = null;
        int queryIndex = location.indexOf('?');
        if (queryIndex >= 0) {
            query = location.substring(queryIndex + 1);
            location = location.substring(0, queryIndex);
        }
        if (!StringUtils.hasText(location)
            || ":memory:".equals(location)
            || isSqliteMemoryUri(location, query)) {
            return Optional.empty();
        }
        if (location.startsWith("file:")) {
            return sqliteFileUriPath(location);
        }
        return Optional.of(Path.of(location));
    }

    private static boolean isSqliteMemoryUri(String location, String query) {
        return "file::memory:".equals(location)
            || (location.startsWith("file:") && query != null && query.matches("(^|.*&)mode=memory($|&.*)"));
    }

    private static Optional<Path> sqliteFileUriPath(String location) {
        String fileSpec = location.substring("file:".length());
        if (!StringUtils.hasText(fileSpec) || ":memory:".equals(fileSpec)) {
            return Optional.empty();
        }
        if (fileSpec.startsWith("//")) {
            try {
                URI uri = new URI(location);
                String host = uri.getHost();
                if (StringUtils.hasText(host) && !"localhost".equalsIgnoreCase(host)) {
                    return Optional.empty();
                }
                return StringUtils.hasText(uri.getPath())
                    ? Optional.of(Path.of(uri.getPath()))
                    : Optional.empty();
            } catch (URISyntaxException exception) {
                return Optional.empty();
            }
        }
        return Optional.of(Path.of(fileSpec));
    }

    static final class SqliteDataSourceParentDirectoryInitializer
        implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            String url = environment == null ? null : environment.getProperty("spring.datasource.url");
            try {
                createSqliteParentDirectory(url);
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to prepare SQLite datasource parent directory", exception);
            }
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }
}
