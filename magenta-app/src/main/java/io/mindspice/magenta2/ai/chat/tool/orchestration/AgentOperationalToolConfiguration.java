package io.mindspice.magenta2.ai.chat.tool.orchestration;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentOperationalToolConfiguration {

    @Bean
    public ToolCallbackProvider agentOperationalToolCallbackProvider(AgentOperationalTools agentOperationalTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(agentOperationalTools)
            .build();
    }
}
