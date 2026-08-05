package io.mindspice.magenta2.ai.chat.config;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat bean configuration for Magenta.
 *
 * <p>After Phase 09 Subphase D migration, the turn-loop framework (mindspice-core)
 * owns all turn-execution, tool-registry, persistence, and engine types. This class
 * provides only Magenta-specific wiring that has no framework equivalent yet.</p>
 */
@Configuration
public class ChatBeanConfig {

    @Bean
    TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }
}
