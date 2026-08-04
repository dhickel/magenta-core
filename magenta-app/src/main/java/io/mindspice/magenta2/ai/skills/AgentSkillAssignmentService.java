package io.mindspice.magenta2.ai.skills;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillAssignmentService {
    private final AgentSkillAssignmentRepository assignmentRepository;
    private final AgentSkillCatalogService catalogService;
    private final AgentProfileService agentProfileService;

    public AgentSkillAssignmentService(
        AgentSkillAssignmentRepository assignmentRepository,
        AgentSkillCatalogService catalogService,
        AgentProfileService agentProfileService
    ) {
        this.assignmentRepository = assignmentRepository;
        this.catalogService = catalogService;
        this.agentProfileService = agentProfileService;
    }

    public AgentSkillAssignment assignToAgent(String agentId, String skillName, boolean enabled) {
        String normalizedAgentId = requireAgentId(agentId);
        String normalizedSkillName = requireSkillName(skillName);
        agentProfileService.get(normalizedAgentId);

        Long skillId = catalogService.listAll().stream()
            .filter(skill -> normalizedSkillName.equals(skill.name()))
            .map(AgentSkill::id)
            .findFirst()
            .orElse(null);
        return assignmentRepository.save(skillId, normalizedSkillName, AgentSkillTargetType.AGENT, normalizedAgentId, enabled);
    }

    public void unassignFromAgent(String agentId, String skillName) {
        assignmentRepository.delete(requireSkillName(skillName), AgentSkillTargetType.AGENT, requireAgentId(agentId));
    }

    public List<AgentSkillAssignment> listAgentAssignments(String agentId) {
        return assignmentRepository.findByTarget(AgentSkillTargetType.AGENT, requireAgentId(agentId));
    }

    public List<AgentSkillAssignment> listAssignmentsForSkill(String skillName) {
        return assignmentRepository.findBySkillName(requireSkillName(skillName));
    }

    public List<String> listEnabledAgentSkillNames(String agentId) {
        return assignmentRepository.findEnabledSkillNames(AgentSkillTargetType.AGENT, requireAgentId(agentId));
    }

    public boolean isAssignedAndEnabled(String agentId, String skillName) {
        String normalizedAgentId = requireAgentId(agentId);
        String normalizedSkillName = requireSkillName(skillName);
        return assignmentRepository.find(normalizedSkillName, AgentSkillTargetType.AGENT, normalizedAgentId)
            .map(AgentSkillAssignment::enabled)
            .orElse(false);
    }

    public Map<String, AgentSkillAssignment> enabledAssignmentsBySkillName(String agentId) {
        Map<String, AgentSkillAssignment> assignments = new LinkedHashMap<>();
        for (AgentSkillAssignment assignment : listAgentAssignments(agentId)) {
            if (assignment.enabled() && StringUtils.hasText(assignment.skillName())) {
                assignments.putIfAbsent(assignment.skillName(), assignment);
            }
        }
        return Map.copyOf(assignments);
    }

    private String requireAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }
        return agentId.trim();
    }

    private String requireSkillName(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            throw new IllegalArgumentException("skillName is required");
        }
        return skillName.trim();
    }
}
