package io.mindspice.magenta2.ai.chat.tool.file;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentFileToolConfiguration {

    @Bean
    public ToolCallbackProvider agentFileToolCallbackProvider(AgentFileTools agentFileTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(agentFileTools)
            .build();
    }
}
