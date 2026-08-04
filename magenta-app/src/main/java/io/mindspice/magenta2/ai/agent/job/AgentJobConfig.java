package io.mindspice.magenta2.ai.agent.job;

import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentJobConfig {
    // Background jobs now run exclusively through MagentaWorkExecutor.
    // The agentJobTaskExecutor bean was removed — see AgentJobService.
}
