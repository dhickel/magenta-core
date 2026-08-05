package io.mindspice.magenta2.ai.chat.config;

import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.repository.RepositoryBackedChatMemory;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.chat.service.AuditService;
import io.mindspice.magenta2.ai.chat.service.ContextManagementAdvisor;
import io.mindspice.magenta2.ai.chat.service.ContextUsageTracker;
import io.mindspice.magenta2.ai.chat.service.turn.PromptContextAssembler;
import io.mindspice.magenta2.ai.chat.service.turn.TerminalTurnRepair;
import io.mindspice.magenta2.ai.chat.service.turn.ToolAccessPolicy;
import io.mindspice.magenta2.ai.chat.service.turn.TurnAuditWriter;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AiConfig;
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
    PromptContextAssembler promptContextAssembler(AiConfig aiConfig) {
        return new PromptContextAssembler(aiConfig);
    }

    @Bean
    ToolAccessPolicy toolAccessPolicy(ChatToolRegistry chatToolRegistry) {
        return new ToolAccessPolicy(chatToolRegistry);
    }

    @Bean
    TerminalTurnRepair terminalTurnRepair() {
        return new TerminalTurnRepair();
    }

    @Bean
    TurnAuditWriter turnAuditWriter(
        AuditService auditService,
        @Autowired(required = false) AuditRepository auditRepository
    ) {
        return new TurnAuditWriter(auditService, auditRepository);
    }

    @Bean
    ContextManagementAdvisor contextManagementAdvisor(
        ChatMemoryRepository chatMemoryRepository,
        AiConfig aiConfig,
        ChatModelRouter chatModelRouter,
        TokenCountEstimator tokenCountEstimator,
        ContextUsageTracker usageTracker,
        ToolTranscriptService toolTranscriptService,
        @Autowired(required = false) AuditRepository auditRepository
    ) {
        return new ContextManagementAdvisor(
            chatMemoryRepository,
            aiConfig,
            chatModelRouter,
            tokenCountEstimator,
            usageTracker,
            toolTranscriptService,
            auditRepository
        );
    }
}
