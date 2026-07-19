package io.mindspice.magenta2.api.web.selector;

import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityLookupServiceTest {

    @Test
    void searchFiltersAgentsByQueryAndAvailability() {
        AgentProfileService agents = new StubAgentProfileService(List.of(
            new AgentProfile("agent-1", "Magenta", AgentProfileStatus.ACTIVE, "qwen", "", List.of(), List.of(), true, null, null),
            new AgentProfile("agent-2", "Disabled", AgentProfileStatus.DISABLED, "qwen", "", List.of(), List.of(), true, null, null)
        ));
        EntityLookupService service = service(agents, new StubChatService(List.of()));

        List<EntityOption> options = service.search(EntityKind.AGENT,
            new SelectorQuery("mag", 20, null, false, Map.of()));

        assertThat(options).extracting(EntityOption::id).containsExactly("agent-1");
    }

    @Test
    void validateKnownAndMissingModelValues() {
        ChatService chat = new StubChatService(List.of("qwen3", "gpt-5.5"));
        EntityLookupService service = service(new StubAgentProfileService(List.of()), chat);

        assertThat(service.validate(EntityKind.MODEL, "gpt-5.5", false).exists()).isTrue();
        EntityValidation missing = service.validate(EntityKind.MODEL, "unknown", true);
        assertThat(missing.exists()).isFalse();
        assertThat(missing.message()).isEqualTo("Not found");
    }

    @Test
    void blankRequiredValueIsNotValid() {
        EntityLookupService service = service(new StubAgentProfileService(List.of()), new StubChatService(List.of()));

        EntityValidation validation = service.validate(EntityKind.JOB, "", true);

        assertThat(validation.exists()).isFalse();
        assertThat(validation.message()).isEqualTo("Required");
    }

    private EntityLookupService service(AgentProfileService agents, ChatService chat) {
        return new EntityLookupService(
            agents, null, null, null, null, chat
        );
    }

    private static class StubAgentProfileService extends AgentProfileService {
        private final List<AgentProfile> agents;

        StubAgentProfileService(List<AgentProfile> agents) {
            super(null, null, null);
            this.agents = agents;
        }

        @Override
        public List<AgentProfile> list() {
            return agents;
        }

        @Override
        public AgentProfile get(String id) {
            return agents.stream()
                .filter(agent -> agent.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Agent profile not found: " + id));
        }
    }

    private static class StubChatService extends ChatService {
        private final List<String> models;

        StubChatService(List<String> models) {
            super(null, null, null, null, null);
            this.models = models;
        }

        @Override
        public List<String> availableModels() {
            return models;
        }

        @Override
        public List<ModelOption> availableModelOptions() {
            return models.stream()
                .map(model -> new ModelOption(model, model))
                .toList();
        }
    }
}
