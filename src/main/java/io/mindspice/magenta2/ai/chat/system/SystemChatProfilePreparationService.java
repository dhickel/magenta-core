package io.mindspice.magenta2.ai.chat.system;

import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.ChatInvocationProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.BindingResolution;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns the transactional preparation of a system-chat profile.
 *
 * <p>Preflight validation (no writes) occurs first: feature enabled, nonblank
 * agent and model, existing agent, trusted AGENT descriptor, compatible
 * existing origin, and compatible existing owner link. Only after preflight
 * are binding, origin, and owner-link facts written. A post-write re-read
 * rejects mismatched facts by throwing through the transactional proxy,
 * rolling back all preparation writes together.</p>
 */
@Service
class SystemChatProfilePreparationService {

    private final RuntimeSettingsService runtimeSettingsService;
    private final AgentProfileService agentProfileService;
    private final AgentContextService agentContextService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final SystemChatToolRegistry toolRegistry;

    SystemChatProfilePreparationService(RuntimeSettingsService runtimeSettingsService,
                                        AgentProfileService agentProfileService,
                                        AgentContextService agentContextService,
                                        WorkspaceDirectoryService workspaceDirectoryService,
                                        ChatSessionMetadataRepository chatSessionMetadataRepository,
                                        SystemChatToolRegistry toolRegistry) {
        this.runtimeSettingsService = runtimeSettingsService;
        this.agentProfileService = agentProfileService;
        this.agentContextService = agentContextService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.toolRegistry = toolRegistry;
    }

    /**
     * Prepares a system-chat profile in a single transaction.
     *
     * <p>Disabled, blank, missing, or incompatible facts return unavailable
     * without writing. When writes occur, post-write verification failures
     * throw through the proxy so Spring rolls back binding, origin, and
     * owner-link writes together.</p>
     */
    @Transactional
    public SystemChatService.SystemChatResult prepare(String agentId) {
        // ── Preflight: validate all readable conflict facts without writing ──

        if (!runtimeSettingsService.systemChatEnabled()) {
            return SystemChatService.SystemChatResult.unavailable();
        }
        String model = runtimeSettingsService.systemChatModel();
        if (!StringUtils.hasText(agentId) || !StringUtils.hasText(model)) {
            return SystemChatService.SystemChatResult.unavailable();
        }
        if (agentProfileService.get(agentId) == null) {
            return SystemChatService.SystemChatResult.unavailable();
        }

        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(WorkspaceKind.AGENT, agentId);
        String bindingKey = "system-chat:" + agentId;
        String conversationId = "system-chat:" + agentId;

        // Check existing origin/agent facts before any write. A partially populated
        // metadata row is not a system conversation that this flow may claim.
        if (chatSessionMetadataRepository.existsByConversationId(conversationId)
                && (chatSessionMetadataRepository.findOrigin(conversationId).orElse(null) != ChatSessionOrigin.SYSTEM_CHAT
                || !agentId.equals(chatSessionMetadataRepository.findAgentId(conversationId).orElse(null)))) {
            return SystemChatService.SystemChatResult.unavailable();
        }

        // Check existing owner and binding facts before any write. Neither can be
        // retargeted or inferred when it belongs to a different agent.
        var existingOwner = agentContextService.getOwnerLink("CONVERSATION", conversationId);
        if (existingOwner.outcome() == ContextOutcome.AVAILABLE
                && !bindingKey.equals(existingOwner.value().bindingKey())) {
            return SystemChatService.SystemChatResult.unavailable();
        }
        var existingBinding = agentContextService.getBinding(bindingKey);
        if (existingBinding.outcome() == ContextOutcome.AVAILABLE
                && !agentId.equals(existingBinding.value().agentId())) {
            return SystemChatService.SystemChatResult.unavailable();
        }
        if (existingBinding.outcome() == ContextOutcome.AVAILABLE
                && agentContextService.resolveBinding(descriptor, existingBinding.value()).outcome()
                != ContextOutcome.AVAILABLE) {
            return SystemChatService.SystemChatResult.unavailable();
        }

        // ── Writes: capture binding, save origin, link owner ──

        if (existingBinding.outcome() != ContextOutcome.AVAILABLE) {
            agentContextService.captureDefaultOrPersonalHome(agentId, bindingKey, bindingKey);
        }
        BindingResolution binding = agentContextService.resolveBinding(descriptor, bindingKey);
        if (binding.outcome() != ContextOutcome.AVAILABLE
                || binding.binding() == null
                || !agentId.equals(binding.binding().agentId())) {
            throw new PreparationRollbackException("Post-write binding verification failed");
        }

        chatSessionMetadataRepository.saveOriginIfAbsent(conversationId, ChatSessionOrigin.SYSTEM_CHAT, agentId);

        if (existingOwner.outcome() != ContextOutcome.AVAILABLE) {
            agentContextService.linkOwner("CONVERSATION", conversationId, bindingKey,
                    "system-chat-owner-link:" + agentId);
        }

        // ── Post-write verification: all durable facts must match ──

        var verifiedOwner = agentContextService.getOwnerLink("CONVERSATION", conversationId);
        if (chatSessionMetadataRepository.findOrigin(conversationId).orElse(null) != ChatSessionOrigin.SYSTEM_CHAT
                || !agentId.equals(chatSessionMetadataRepository.findAgentId(conversationId).orElse(null))
                || verifiedOwner.outcome() != ContextOutcome.AVAILABLE
                || !bindingKey.equals(verifiedOwner.value().bindingKey())) {
            throw new PreparationRollbackException("Post-write preparation verification failed");
        }

        ChatInvocationProfile.SystemChatProfile profile = new ChatInvocationProfile.SystemChatProfile(
                model, SystemChatService.FIXED_PROMPT, SystemChatToolRegistry.FIXED_CALLBACK_NAMES, bindingKey,
                agentId, SystemChatService.FIXED_MAX_READ_CONTEXT, descriptor);
        toolRegistry.requireFixedProfile(profile);
        return SystemChatService.SystemChatResult.available(profile, conversationId);
    }

    /**
     * Internal rollback signal that escapes the transactional proxy uncaught
     * so Spring rolls back binding, origin, and owner-link writes together.
     */
    static final class PreparationRollbackException extends RuntimeException {
        PreparationRollbackException(String message) {
            super(message);
        }
    }
}
