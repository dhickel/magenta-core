package io.mindspice.magenta2.ai.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.BindingOwnerLink;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextBinding;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.PersonalSelection;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;

class RequestResolverAgentBindingTest {

    @Test
    void capturesNewAgentChatAgainstTheServerResolvedRouteAgent() {
        ChatMemoryRepository memory = mock(ChatMemoryRepository.class);
        ChatSessionMetadataRepository sessions = mock(ChatSessionMetadataRepository.class);
        AgentContextService contexts = mock(AgentContextService.class);
        when(memory.findConversationIds()).thenReturn(List.of());
        RequestResolver resolver = new RequestResolver(config(), sessions, memory, null, null, null, contexts);

        ResolvedChatRequest resolved = resolver.resolveForAgent(
                new ChatRequest.MsgRequest("conversation-1", "hello", "model", null), "agent-route");

        assertThat(resolved.contextBindingKey()).isEqualTo("conversation:conversation-1");
        verify(contexts).captureDefaultOrPersonalHome("agent-route", "conversation:conversation-1",
                "conversation-capture:conversation-1");
        verify(contexts).linkOwner("CONVERSATION", "conversation-1", "conversation:conversation-1",
                "conversation-owner-link:conversation-1");
    }

    @Test
    void rejectsAnExistingAgentChatBoundToAnotherAgent() {
        ChatMemoryRepository memory = mock(ChatMemoryRepository.class);
        ChatSessionMetadataRepository sessions = mock(ChatSessionMetadataRepository.class);
        AgentContextService contexts = mock(AgentContextService.class);
        when(memory.findConversationIds()).thenReturn(List.of("conversation-1"));
        when(contexts.getOwnerLink("CONVERSATION", "conversation-1")).thenReturn(
                new AgentContextService.ContextLookup<>(AgentContextService.ContextOutcome.AVAILABLE,
                        new BindingOwnerLink("CONVERSATION", "conversation-1", "conversation:conversation-1",
                                Instant.EPOCH)));
        when(contexts.getBinding("conversation:conversation-1")).thenReturn(
                new AgentContextService.ContextLookup<>(AgentContextService.ContextOutcome.AVAILABLE,
                        ContextBinding.forInsert("agent-default", "conversation:conversation-1",
                                SelectionKind.PERSONAL, null, null, PersonalSelection.HOME, Instant.EPOCH)));
        RequestResolver resolver = new RequestResolver(config(), sessions, memory, null, null, null, contexts);

        assertThatThrownBy(() -> resolver.resolveForAgent(
                new ChatRequest.MsgRequest("conversation-1", "hello", "model", null), "agent-route"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Existing conversation binding does not belong to the route agent");
    }

    private static AiConfig config() {
        return new AiConfig("agent-default", "model", null, null, null, null, null,
                Path.of("."), null, Map.of(), Map.of(), false);
    }
}
