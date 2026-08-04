package io.mindspice.magenta2.ai.chat.tool.skills;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentSkillToolConfiguration {
    @Bean
    public ToolCallbackProvider agentSkillToolCallbackProvider(AgentSkillActivationTools tools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
    }
}
