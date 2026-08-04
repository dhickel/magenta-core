package io.mindspice.magenta2.ai.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillRuntimeCatalogService {
    private final AgentSkillCatalogService catalogService;
    private final AgentSkillAssignmentService assignmentService;
    private final AgentSkillAgentContextResolver agentContextResolver;

    public AgentSkillRuntimeCatalogService(
        AgentSkillCatalogService catalogService,
        AgentSkillAssignmentService assignmentService,
        AgentSkillAgentContextResolver agentContextResolver
    ) {
        this.catalogService = catalogService;
        this.assignmentService = assignmentService;
        this.agentContextResolver = agentContextResolver;
    }

    public List<AgentSkillCatalogEntry> catalogForConversation(String conversationId) {
        String agentId = agentContextResolver.resolveAgentId(conversationId).orElse(null);
        if (!StringUtils.hasText(agentId)) {
            return List.of();
        }

        Map<String, AgentSkill> loadableByName = new LinkedHashMap<>();
        for (AgentSkill skill : catalogService.listAll()) {
            if (skill.status().loadable() && StringUtils.hasText(skill.name()) && StringUtils.hasText(skill.description())) {
                loadableByName.putIfAbsent(skill.name(), skill);
            }
        }
        if (loadableByName.isEmpty()) {
            return List.of();
        }

        return assignmentService.listEnabledAgentSkillNames(agentId).stream()
            .map(loadableByName::get)
            .filter(skill -> skill != null)
            .map(skill -> new AgentSkillCatalogEntry(skill.name(), skill.description()))
            .toList();
    }

    public boolean hasAvailableSkillsForConversation(String conversationId) {
        return !catalogForConversation(conversationId).isEmpty();
    }
}
