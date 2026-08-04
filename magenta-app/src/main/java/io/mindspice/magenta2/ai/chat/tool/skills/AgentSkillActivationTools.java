package io.mindspice.magenta2.ai.chat.tool.skills;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class AgentSkillActivationTools {
    private final AgentSkillActivationService activationService;
    private final ObjectMapper objectMapper;

    public AgentSkillActivationTools(AgentSkillActivationService activationService, ObjectMapper objectMapper) {
        this.activationService = activationService;
        this.objectMapper = objectMapper;
    }

    @Tool(
        name = "activate_skill",
        description = "Load full instructions for an assigned Agent Skill by skill name. Returns the skill body plus a resource listing without reading resource files."
    )
    public String activateSkill(
        @ToolParam(description = "Assigned skill name to activate exactly as listed in available_skills.")
        String skillName
    ) {
        return json(activationService.activateSkill(null, skillName));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize skill activation result", exception);
        }
    }
}
