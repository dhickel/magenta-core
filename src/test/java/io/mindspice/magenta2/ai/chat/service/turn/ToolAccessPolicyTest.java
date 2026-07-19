package io.mindspice.magenta2.ai.chat.service.turn;

import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.skills.AgentSkillRuntimeCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolAccessPolicyTest {

    @Test
    void planModeKeepsOperationalToolsOutOfDraftingEvenWithWildcardApproval() {
        ToolAccessPolicy policy = new ToolAccessPolicy(
            new ChatToolRegistry(List.of(
                new NamedToolCallback("file_read"),
                new NamedToolCallback("agent_queue_list")
            ), List.of()),
            null,
            null
        );

        List<String> toolNames = policy.filterToolsByMode(List.of("*"), PlanMode.PLAN).stream()
            .map(callback -> callback.getToolDefinition().name())
            .toList();

        assertThat(toolNames).containsExactly("file_read");
    }

    @Test
    void normalModeAllowsExplicitOperationalToolsForAgentTurns() {
        ToolAccessPolicy policy = new ToolAccessPolicy(
            new ChatToolRegistry(List.of(
                new NamedToolCallback("agent_queue_list"),
                new NamedToolCallback("file_read")
            ), List.of()),
            null,
            null
        );

        List<String> toolNames = policy.filterToolsByMode(
            List.of("agent_queue_list", "file_read"), PlanMode.NORMAL
        ).stream()
            .map(callback -> callback.getToolDefinition().name())
            .toList();

        assertThat(toolNames).containsExactly("agent_queue_list", "file_read");
    }

    @Test
    void hidesActivateSkillWhenNoSkillsAreAvailable() {
        AgentSkillRuntimeCatalogService skillCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(skillCatalogService.hasAvailableSkillsForConversation("conversation-1")).thenReturn(false);

        ToolAccessPolicy policy = new ToolAccessPolicy(
            new ChatToolRegistry(List.of(
                new NamedToolCallback("file_read"),
                new NamedToolCallback("activate_skill")
            ), List.of()),
            null,
            null,
            skillCatalogService
        );

        List<String> toolNames = policy.filterToolsByMode(List.of("*"), PlanMode.NORMAL, "conversation-1").stream()
            .map(callback -> callback.getToolDefinition().name())
            .toList();

        assertThat(toolNames).containsExactly("file_read");
    }

    @Test
    void keepsActivateSkillWhenSkillsAreAvailable() {
        AgentSkillRuntimeCatalogService skillCatalogService = mock(AgentSkillRuntimeCatalogService.class);
        when(skillCatalogService.hasAvailableSkillsForConversation("conversation-1")).thenReturn(true);

        ToolAccessPolicy policy = new ToolAccessPolicy(
            new ChatToolRegistry(List.of(
                new NamedToolCallback("file_read"),
                new NamedToolCallback("activate_skill")
            ), List.of()),
            null,
            null,
            skillCatalogService
        );

        List<String> toolNames = policy.filterToolsByMode(List.of("*"), PlanMode.NORMAL, "conversation-1").stream()
            .map(callback -> callback.getToolDefinition().name())
            .toList();

        assertThat(toolNames).containsExactly("file_read", "activate_skill");
    }

    private static final class NamedToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private NamedToolCallback(String name) {
            this.definition = new DefaultToolDefinition(name, "Named test tool", "{\"type\":\"object\"}");
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "{\"ok\":true}";
        }
    }
}
