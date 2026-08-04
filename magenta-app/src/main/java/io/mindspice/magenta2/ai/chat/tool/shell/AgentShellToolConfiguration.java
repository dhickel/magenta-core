package io.mindspice.magenta2.ai.chat.tool.shell;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentShellToolConfiguration {

    @Bean
    public ToolCallbackProvider agentShellToolCallbackProvider(AgentShellTools agentShellTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(agentShellTools)
            .build();
    }
}
