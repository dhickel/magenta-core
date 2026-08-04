package io.mindspice.magenta2.ai.chat.config;

import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.RepositoryBackedChatMemory;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.chat.service.ContextManagementAdvisor;
import io.mindspice.magenta2.ai.chat.service.ContextUsageTracker;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatBeanConfig {

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return new RepositoryBackedChatMemory(chatMemoryRepository);
    }

    @Bean
    TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    ContextManagementAdvisor contextManagementAdvisor(
        ChatMemoryRepository chatMemoryRepository,
        AiConfig aiConfig,
        ChatModelRouter chatModelRouter,
        TokenCountEstimator tokenCountEstimator,
        ContextUsageTracker usageTracker,
        ToolTranscriptService toolTranscriptService,
        @Autowired(required = false) AuditRepository auditRepository,
        RuntimeSettingsService runtimeSettingsService
    ) {
        return new ContextManagementAdvisor(
            chatMemoryRepository,
            aiConfig,
            chatModelRouter,
            tokenCountEstimator,
            usageTracker,
            toolTranscriptService,
            auditRepository,
            runtimeSettingsService
        );
    }
}
