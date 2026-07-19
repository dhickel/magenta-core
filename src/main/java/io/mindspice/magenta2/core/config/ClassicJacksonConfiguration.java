package io.mindspice.magenta2.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.DeserializationFeature;

/** Provides Jackson 2 for Magenta's existing persistence and configuration services. */
@Configuration
public class ClassicJacksonConfiguration {

    @Bean
    ObjectMapper classicObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    JsonMapperBuilderCustomizer webJsonCompatibility() {
        return builder -> builder.disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    }
}
