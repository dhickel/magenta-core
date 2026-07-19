package io.mindspice.magenta2.avatar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Configuration
public class AvatarDataConfiguration {

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "dataSource")
    DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    @Primary
    @ConditionalOnMissingBean(name = "jdbcTemplate")
    JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "avatarDataSource")
    DataSource avatarDataSource(MagentaRootProperties rootProperties) {
        Path database = rootProperties.path().resolve("avatar.sqlite").toAbsolutePath().normalize();
        createParent(database);
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + database + "?foreign_keys=true");
        return dataSource;
    }

    @Bean(name = "avatarJdbcTemplate")
    JdbcTemplate avatarJdbcTemplate(@Qualifier("avatarDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    private void createParent(Path database) {
        try {
            Files.createDirectories(database.getParent());
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create Avatar SQLite parent directory: " + database.getParent(), exception);
        }
    }
}
