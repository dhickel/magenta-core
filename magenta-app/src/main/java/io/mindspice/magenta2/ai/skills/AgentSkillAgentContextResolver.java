package io.mindspice.magenta2.ai.skills;

import java.util.Optional;

import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillAgentContextResolver {
    private final ChatSessionMetadataRepository sessionMetadataRepository;
    private final RuntimeSettingsService runtimeSettingsService;

    public AgentSkillAgentContextResolver(
        ChatSessionMetadataRepository sessionMetadataRepository,
        RuntimeSettingsService runtimeSettingsService
    ) {
        this.sessionMetadataRepository = sessionMetadataRepository;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    public Optional<String> resolveAgentId(String conversationId) {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context != null && context.hasAgentContext()) {
            return Optional.of(context.agentId());
        }
        if (StringUtils.hasText(conversationId)) {
            Optional<String> sessionAgent = sessionMetadataRepository.findAgentId(conversationId.trim());
            if (sessionAgent.isPresent()) {
                return sessionAgent;
            }
        }
        try {
            return Optional.ofNullable(runtimeSettingsService.defaultAgentProfile())
                .map(profile -> profile.id())
                .filter(StringUtils::hasText);
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public Optional<String> resolveConversationId(String conversationIdHint) {
        if (StringUtils.hasText(conversationIdHint)) {
            return Optional.of(conversationIdHint.trim());
        }
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context == null || !StringUtils.hasText(context.workspaceIdentity()) || !StringUtils.hasText(context.runType())) {
            return Optional.empty();
        }
        if ("CHAT".equalsIgnoreCase(context.runType()) || "AGENT_CHAT".equalsIgnoreCase(context.runType())) {
            return Optional.of(context.workspaceIdentity());
        }
        return Optional.empty();
    }
}
