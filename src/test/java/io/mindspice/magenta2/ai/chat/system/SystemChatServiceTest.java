package io.mindspice.magenta2.ai.chat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Answers.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class SystemChatServiceTest {

    @AfterEach
    void clearHolder() {
        SystemChatProfileHolder.clear();
    }

    @Test
    void delegatesProfileResolutionToCollaborator() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);
        String agentId = "agent-from-route";
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, agentId,
                Path.of("/tmp/workspace/agent", agentId));
        ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                "restricted-model", SystemChatService.FIXED_PROMPT,
                SystemChatToolRegistry.FIXED_CALLBACK_NAMES, "system-chat:" + agentId,
                agentId, SystemChatService.FIXED_MAX_READ_CONTEXT, descriptor);

        when(preparation.prepare(agentId))
                .thenReturn(SystemChatService.SystemChatResult.available(profile, "system-chat:" + agentId));

        var result = service.resolveProfile(agentId);

        assertThat(result.available()).isTrue();
        assertThat(result.profile().agentId()).isEqualTo(agentId);
        assertThat(result.profile().bindingKey()).isEqualTo("system-chat:" + agentId);
        assertThat(result.profile().fixedPrompt()).isEqualTo(SystemChatService.FIXED_PROMPT);
        assertThat(result.profile().fixedCallbackNames()).isEqualTo(SystemChatToolRegistry.FIXED_CALLBACK_NAMES);
        assertThat(result.profile().maxReadTokens()).isEqualTo(SystemChatService.FIXED_MAX_READ_CONTEXT);
        assertThat(result.profile().agentDescriptor().key()).isEqualTo(agentId);
        assertThat(result.conversationId()).isEqualTo("system-chat:" + agentId);
        verify(preparation).prepare(agentId);
    }

    @Test
    void unavailablePreparationYieldsUnavailableProfile() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);

        when(preparation.prepare("missing-agent"))
                .thenReturn(SystemChatService.SystemChatResult.unavailable());

        var result = service.resolveProfile("missing-agent");

        assertThat(result.available()).isFalse();
        verify(preparation).prepare("missing-agent");
    }

    @Test
    void collaboratorExceptionConvertedToUnavailable() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);

        when(preparation.prepare("agent-1"))
                .thenThrow(new SystemChatProfilePreparationService.PreparationRollbackException("test failure"));

        var result = service.resolveProfile("agent-1");

        assertThat(result.available()).isFalse();
    }

    @Test
    void invokeReturnsUnavailableWhenPreparationFails() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);

        when(preparation.prepare("agent-1"))
                .thenReturn(SystemChatService.SystemChatResult.unavailable());

        var result = service.invoke("agent-1", "hello");

        assertThat(result.available()).isFalse();
        verify(router, never()).chatClient(anyString());
        assertThat(SystemChatProfileHolder.current()).isNull();
    }

    @Test
    void invokeReturnsUnavailableWhenClientNotFound() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);
        String agentId = "agent-1";
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, agentId,
                Path.of("/tmp/workspace/agent", agentId));
        ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                "restricted-model", SystemChatService.FIXED_PROMPT,
                SystemChatToolRegistry.FIXED_CALLBACK_NAMES, "system-chat:" + agentId,
                agentId, SystemChatService.FIXED_MAX_READ_CONTEXT, descriptor);

        when(preparation.prepare(agentId))
                .thenReturn(SystemChatService.SystemChatResult.available(profile, "system-chat:" + agentId));
        when(router.chatClient("restricted-model")).thenReturn(null);

        var result = service.invoke(agentId, "hello");

        assertThat(result.available()).isFalse();
        assertThat(SystemChatProfileHolder.current()).isNull();
    }

    @Test
    void invokeReturnsUnavailableAndClearsHolderOnModelFailure() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);
        String agentId = "agent-1";
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, agentId,
                Path.of("/tmp/workspace/agent", agentId));
        ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                "restricted-model", SystemChatService.FIXED_PROMPT,
                SystemChatToolRegistry.FIXED_CALLBACK_NAMES, "system-chat:" + agentId,
                agentId, SystemChatService.FIXED_MAX_READ_CONTEXT, descriptor);

        when(preparation.prepare(agentId))
                .thenReturn(SystemChatService.SystemChatResult.available(profile, "system-chat:" + agentId));
        when(tools.callbacksFor(profile)).thenReturn(List.of());

        ChatModel mockModel = mock(ChatModel.class, RETURNS_MOCKS);
        when(mockModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenThrow(new RuntimeException("model failure"));
        ChatClient client = ChatClient.builder(mockModel).build();
        when(router.chatClient("restricted-model")).thenReturn(client);

        var result = service.invoke(agentId, "hello");

        assertThat(result.available()).isFalse();
        assertThat(SystemChatProfileHolder.current()).isNull();
    }

    @Test
    void invokeReturnsAvailableAndClearsHolderOnSuccess() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);
        String agentId = "agent-1";
        WorkspaceDescriptor descriptor = new WorkspaceDescriptor(WorkspaceKind.AGENT, agentId,
                Path.of("/tmp/workspace/agent", agentId));
        ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                "restricted-model", SystemChatService.FIXED_PROMPT,
                SystemChatToolRegistry.FIXED_CALLBACK_NAMES, "system-chat:" + agentId,
                agentId, SystemChatService.FIXED_MAX_READ_CONTEXT, descriptor);

        when(preparation.prepare(agentId))
                .thenReturn(SystemChatService.SystemChatResult.available(profile, "system-chat:" + agentId));
        when(tools.callbacksFor(profile)).thenReturn(List.of());

        ChatModel mockModel = mock(ChatModel.class, RETURNS_MOCKS);
        when(mockModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("response from model")))));
        ChatClient client = ChatClient.builder(mockModel).build();
        when(router.chatClient("restricted-model")).thenReturn(client);

        var result = service.invoke(agentId, "hello");

        assertThat(result.available()).isTrue();
        assertThat(result.conversationId()).isEqualTo("system-chat:" + agentId);
        assertThat(result.response()).isEqualTo("response from model");
        assertThat(SystemChatProfileHolder.current()).isNull();
    }

    @Test
    void invokeReturnsUnavailableForBlankAgentId() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);

        var result = service.invoke("", "hello");

        assertThat(result.available()).isFalse();
        verify(preparation, never()).prepare(any());
    }

    @Test
    void invokeReturnsUnavailableForBlankMessage() {
        SystemChatProfilePreparationService preparation = mock(SystemChatProfilePreparationService.class);
        ChatModelRouter router = mock(ChatModelRouter.class);
        SystemChatToolRegistry tools = mock(SystemChatToolRegistry.class);
        SystemChatService service = new SystemChatService(preparation, router, tools);

        var result = service.invoke("agent-1", "");

        assertThat(result.available()).isFalse();
        verify(preparation, never()).prepare(any());
    }
}
