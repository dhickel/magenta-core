package io.mindspice.magenta2.ai.chat.tool.web;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.WebSearchConfig;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentWebToolConfiguration {

    @Bean
    public ToolCallbackProvider agentWebToolCallbackProvider(AgentWebTools agentWebTools, AiConfig aiConfig) {
        WebSearchConfig webSearch = aiConfig == null ? null : aiConfig.webSearch();
        if (webSearch == null || !webSearch.isEnabled()) {
            return ToolCallbackProvider.from();
        }
        return MethodToolCallbackProvider.builder()
            .toolObjects(agentWebTools)
            .build();
    }
}
