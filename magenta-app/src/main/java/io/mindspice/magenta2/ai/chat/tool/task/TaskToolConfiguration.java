package io.mindspice.magenta2.ai.chat.tool.task;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskToolConfiguration {
    @Bean
    public ToolCallbackProvider taskToolCallbackProvider(TaskTools taskTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(taskTools)
            .build();
    }
}
