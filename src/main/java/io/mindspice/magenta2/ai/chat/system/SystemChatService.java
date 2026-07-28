package io.mindspice.magenta2.ai.chat.system;

import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.BindingResolution;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Sole entry point for the server-created, fixed system-chat profile. */
@Service
public class SystemChatService {
    private static final Logger log = LoggerFactory.getLogger(SystemChatService.class);

    static final String FIXED_PROMPT = """
            You are a restricted system chat assistant. Your only capabilities are:
            - Read bounded agent status, workspace file facts, and assignment lifecycle facts.
            - Create non-executing lifecycle action proposals (PAUSE, RESUME, RETRY, REPLAY, CANCEL).

            You cannot write files, execute shell commands or processes, approve or activate
            skills, access other agents, or execute any lifecycle action directly. Every
            response must be secret-free: no credentials, tokens, absolute paths, raw failure
            details, or internal claim/fence owner identifiers.

            When you recommend a lifecycle action, you must call the proposal tool. You cannot
            confirm or execute a proposal; only an explicit user confirmation does that.
            """.stripIndent();
    static final int FIXED_MAX_READ_CONTEXT = 16_000;

    private final RuntimeSettingsService runtimeSettingsService;
    private final AgentProfileService agentProfileService;
    private final AgentContextService agentContextService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatModelRouter chatModelRouter;
    private final SystemChatToolRegistry toolRegistry;

    public SystemChatService(RuntimeSettingsService runtimeSettingsService,
                             AgentProfileService agentProfileService,
                             AgentContextService agentContextService,
                             WorkspaceDirectoryService workspaceDirectoryService,
                             ChatSessionMetadataRepository chatSessionMetadataRepository,
                             ChatModelRouter chatModelRouter,
                             SystemChatToolRegistry toolRegistry) {
        this.runtimeSettingsService = runtimeSettingsService;
        this.agentProfileService = agentProfileService;
        this.agentContextService = agentContextService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.chatModelRouter = chatModelRouter;
        this.toolRegistry = toolRegistry;
    }

    /** Resolves a profile only from server-owned settings, binding, and agent facts. */
    @Transactional
    public SystemChatResult resolveProfile(String agentId) {
        if (!runtimeSettingsService.systemChatEnabled()) {
            return SystemChatResult.unavailable();
        }
        String model = runtimeSettingsService.systemChatModel();
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(model)) {
            return SystemChatResult.unavailable();
        }
        try {
            if (agentProfileService.get(agentId) == null) {
                return SystemChatResult.unavailable();
            }
            WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, agentId);
            String bindingKey = "system-chat:" + agentId;
            if (agentContextService.getBinding(bindingKey).outcome() != ContextOutcome.AVAILABLE) {
                agentContextService.captureDefaultOrPersonalHome(agentId, bindingKey, bindingKey);
            }
            BindingResolution binding = agentContextService.resolveBinding(descriptor, bindingKey);
            if (binding.outcome() != ContextOutcome.AVAILABLE
                    || binding.binding() == null
                    || !agentId.equals(binding.binding().agentId())) {
                return SystemChatResult.unavailable();
            }
            String conversationId = createOrReuseSystemConversation(agentId, bindingKey);
            if (conversationId == null) {
                return SystemChatResult.unavailable();
            }
            ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                    model, FIXED_PROMPT, SystemChatToolRegistry.FIXED_CALLBACK_NAMES, bindingKey,
                    agentId, FIXED_MAX_READ_CONTEXT, descriptor);
            toolRegistry.requireFixedProfile(profile);
            return SystemChatResult.available(profile, conversationId);
        } catch (RuntimeException exception) {
            log.debug("System-chat profile is unavailable", exception);
            return SystemChatResult.unavailable();
        }
    }

    /**
     * Runs a turn without normal chat memory, prompt, skills, or tool resolution.
     * This method deliberately owns the profile holder lifetime so direct callbacks
     * have no caller-supplied authority path.
     */
    @Transactional
    public InvocationResult invoke(String agentId, String message) {
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(message)) {
            return InvocationResult.unavailable();
        }
        SystemChatResult resolved = resolveProfile(agentId);
        if (!resolved.available()) {
            return InvocationResult.unavailable();
        }
        try {
            ChatClient client = chatModelRouter.chatClient(resolved.profile().model());
            if (client == null) {
                return InvocationResult.unavailable();
            }
            SystemChatProfileHolder.set(resolved.profile());
            String response = client.prompt()
                    .system(resolved.profile().fixedPrompt())
                    .user(message)
                    .tools(toolRegistry.callbacksFor(resolved.profile()))
                    .call()
                    .content();
            return InvocationResult.available(resolved.conversationId(), redactModelResponse(response));
        } catch (RuntimeException exception) {
            log.debug("System-chat invocation is unavailable", exception);
            return InvocationResult.unavailable();
        } finally {
            SystemChatProfileHolder.clear();
        }
    }

    private String createOrReuseSystemConversation(String agentId, String bindingKey) {
        String conversationId = "system-chat:" + agentId;
        var existingOrigin = chatSessionMetadataRepository.findOrigin(conversationId);
        if (existingOrigin.isPresent() && existingOrigin.get() != ChatSessionOrigin.SYSTEM_CHAT) {
            return null;
        }
        chatSessionMetadataRepository.saveOriginIfAbsent(conversationId, ChatSessionOrigin.SYSTEM_CHAT, agentId);
        if (chatSessionMetadataRepository.findOrigin(conversationId).orElse(null) != ChatSessionOrigin.SYSTEM_CHAT
                || !agentId.equals(chatSessionMetadataRepository.findAgentId(conversationId).orElse(null))) {
            return null;
        }
        var owner = agentContextService.getOwnerLink("CONVERSATION", conversationId);
        if (owner.outcome() == ContextOutcome.AVAILABLE) {
            return bindingKey.equals(owner.value().bindingKey()) ? conversationId : null;
        }
        agentContextService.linkOwner("CONVERSATION", conversationId, bindingKey,
                "system-chat-owner-link:" + agentId);
        return conversationId;
    }

    private static String redactModelResponse(String response) {
        if (response == null) {
            return "";
        }
        return response
                .replaceAll("(?i)(?:bearer\\s+|token[=:\\s]+|api[_-]?key[=:\\s]+|password[=:\\s]+)[^\\s,;]+", "[redacted]")
                .replaceAll("(?i)[a-z][a-z0-9+.-]*://[^\\s]+", "[url]")
                .replaceAll("/(?:[A-Za-z0-9_.-]+/){2,}[A-Za-z0-9_.-]*", "[path]");
    }

    public record SystemChatResult(ChatInvocationProfile.SystemChatProfile profile,
                                   String conversationId, boolean available) {
        static SystemChatResult unavailable() { return new SystemChatResult(null, null, false); }
        static SystemChatResult available(ChatInvocationProfile.SystemChatProfile profile, String conversationId) {
            return new SystemChatResult(profile, conversationId, true);
        }
    }

    public record InvocationResult(String conversationId, String response, boolean available) {
        static InvocationResult unavailable() { return new InvocationResult(null, null, false); }
        static InvocationResult available(String conversationId, String response) {
            return new InvocationResult(conversationId, response, true);
        }
    }
}
