package io.mindspice.magenta2.ai.chat.tool.savedplan;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SavedPlanToolConfiguration {
    @Bean
    public ToolCallbackProvider savedPlanToolCallbackProvider(SavedPlanTools savedPlanTools) {
        return MethodToolCallbackProvider.builder()
            .toolObjects(savedPlanTools)
            .build();
    }
}
