package io.mindspice.magenta2.ai.skills;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillPromptCatalogAssembler {
    private final AgentSkillRuntimeCatalogService runtimeCatalogService;

    public AgentSkillPromptCatalogAssembler(AgentSkillRuntimeCatalogService runtimeCatalogService) {
        this.runtimeCatalogService = runtimeCatalogService;
    }

    public String promptAppend(String conversationId) {
        List<AgentSkillCatalogEntry> catalog = runtimeCatalogService.catalogForConversation(conversationId);
        if (catalog.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("## Available Agent Skills\n");
        builder.append("When a task matches a skill description, call `activate_skill` with that skill name.\n\n");
        builder.append("<available_skills>\n");
        for (AgentSkillCatalogEntry entry : catalog) {
            if (!StringUtils.hasText(entry.name()) || !StringUtils.hasText(entry.description())) {
                continue;
            }
            builder.append("  <skill>\n");
            builder.append("    <name>").append(entry.name().trim()).append("</name>\n");
            builder.append("    <description>").append(entry.description().trim()).append("</description>\n");
            builder.append("  </skill>\n");
        }
        builder.append("</available_skills>");
        return builder.toString();
    }
}
