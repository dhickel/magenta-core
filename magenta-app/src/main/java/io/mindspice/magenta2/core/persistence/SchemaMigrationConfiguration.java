package io.mindspice.magenta2.core.persistence;

import java.util.Arrays;
import java.util.LinkedHashSet;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Ensures JDBC repositories cannot initialize before ordered migrations finish. */
@Configuration(proxyBeanMethods = false)
class SchemaMigrationConfiguration {
    @Bean
    static BeanFactoryPostProcessor repositoryMigrationDependency() {
        return beanFactory -> {
            for (String beanName : beanFactory.getBeanDefinitionNames()) {
                if ("schemaMigrationRunner".equals(beanName)) {
                    continue;
                }
                BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
                String className = definition.getBeanClassName();
                if (className == null || !className.startsWith("io.mindspice.magenta2.")
                        || !className.endsWith("Repository")) {
                    continue;
                }
                LinkedHashSet<String> dependencies = new LinkedHashSet<>();
                if (definition.getDependsOn() != null) {
                    dependencies.addAll(Arrays.asList(definition.getDependsOn()));
                }
                dependencies.add("schemaMigrationRunner");
                definition.setDependsOn(dependencies.toArray(String[]::new));
            }
        };
    }
}
