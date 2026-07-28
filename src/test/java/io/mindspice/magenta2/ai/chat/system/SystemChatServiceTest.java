package io.mindspice.magenta2.ai.chat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.Test;

class SystemChatServiceTest {

    @Test
    void resolvesThePathAgentRatherThanTheRuntimeDefaultAgent() {
        RuntimeSettingsService settings = mock(RuntimeSettingsService.class);
        AgentProfileService agents = mock(AgentProfileService.class);
        AgentContextService contexts = mock(AgentContextService.class);
        WorkspaceDirectoryService directories = mock(WorkspaceDirectoryService.class);
        ChatSessionMetadataRepository sessions = mock(ChatSessionMetadataRepository.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(settings, agents, contexts, directories, sessions, router, tools);
        String agentId = "agent-from-route";
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, agentId,
                Path.of("/tmp/workspace/agent", agentId));
        ContextBinding binding = ContextBinding.forInsert(agentId, "system-chat:" + agentId,
                SelectionKind.PERSONAL, null, null, PersonalSelection.HOME, java.time.Instant.EPOCH);

        when(settings.systemChatEnabled()).thenReturn(true);
        when(settings.systemChatModel()).thenReturn("restricted-model");
        when(agents.get(agentId)).thenReturn(new AgentProfile(agentId, "Route Agent", AgentProfileStatus.ACTIVE,
                "restricted-model", "", java.util.List.of(), java.util.List.of(), false, null, null));
        when(directories.resolveForRead(WorkspaceKind.AGENT, agentId)).thenReturn(descriptor);
        when(contexts.getBinding(binding.bindingKey())).thenReturn(new AgentContextService.ContextLookup<>(
                AgentContextService.ContextOutcome.UNAVAILABLE, null));
        when(contexts.resolveBinding(descriptor, binding.bindingKey()))
                .thenReturn(AgentContextService.BindingResolution.resolved(binding, null));
        when(sessions.findOrigin("system-chat:" + agentId)).thenReturn(Optional.of(ChatSessionOrigin.SYSTEM_CHAT));
        when(sessions.findAgentId("system-chat:" + agentId)).thenReturn(Optional.of(agentId));
        when(contexts.getOwnerLink("CONVERSATION", "system-chat:" + agentId))
                .thenReturn(new AgentContextService.ContextLookup<>(AgentContextService.ContextOutcome.AVAILABLE,
                        new io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.BindingOwnerLink(
                                "CONVERSATION", "system-chat:" + agentId, binding.bindingKey(), java.time.Instant.EPOCH)));

        var result = service.resolveProfile(agentId);

        assertThat(result.available()).isTrue();
        assertThat(result.profile().agentId()).isEqualTo(agentId);
        assertThat(result.conversationId()).isEqualTo("system-chat:" + agentId);
        verify(agents).get(agentId);
    }
}
