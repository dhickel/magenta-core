package io.mindspice.magenta2.ai.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns request and model resolution for chat interactions.
 *
 * <p>Resolves conversation IDs, selects models based on stored preferences,
 * runtime settings, planning/task mode overrides, and available model configs.
 * Also provides conversation existence checks and interaction-mode queries.
 */
@Service
public class RequestResolver {
    private static final Logger logger = LoggerFactory.getLogger(RequestResolver.class);

    private final AiConfig aiConfig;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatMemoryRepository chatMemoryRepository;
    private final PlanService planService;
    private final TaskService taskService;
    private final RuntimeSettingsService runtimeSettingsService;
    private final AgentContextService agentContextService;

    @Autowired
    public RequestResolver(
        AiConfig aiConfig,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMemoryRepository chatMemoryRepository,
        @Autowired(required = false) PlanService planService,
        @Autowired(required = false) TaskService taskService,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService,
        @Autowired(required = false) AgentContextService agentContextService
    ) {
        this.aiConfig = aiConfig;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.chatMemoryRepository = chatMemoryRepository;
        this.planService = planService;
        this.taskService = taskService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.agentContextService = agentContextService;
    }

    /** Package-private minimal constructor for tests where only config and metadata are needed. */
    RequestResolver(AiConfig aiConfig, ChatSessionMetadataRepository chatSessionMetadataRepository) {
        this(aiConfig, chatSessionMetadataRepository, null, null, null, null, null);
    }

    @Transactional
    public ResolvedChatRequest resolve(ChatRequest request) {
        if (request instanceof ChatRequest.MsgRequest msgRequest) {
            return resolve(
                msgRequest.conversationId(),
                msgRequest.message(),
                msgRequest.model(),
                msgRequest.planningModel(),
                msgRequest.surface(),
                msgRequest.contextCapture(),
                null
            );
        }
        throw new IllegalArgumentException("message request is required");
    }

    /**
     * Resolves an agent-scoped chat turn using the server-resolved route agent
     * for immutable context capture. Existing conversations must already be
     * bound to that same agent; a side-panel route can never retarget them.
     */
    @Transactional
    ResolvedChatRequest resolveForAgent(ChatRequest.MsgRequest request, String agentId) {
        if (request == null || !StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("Agent-scoped chat request requires an agent");
        }
        return resolve(request.conversationId(), request.message(), request.model(), request.planningModel(),
                request.surface(), request.contextCapture(), agentId.trim());
    }

    @Transactional
    public ResolvedChatRequest resolve(String conversationId, String message, String model, String planningModel) {
        return resolve(conversationId, message, model, planningModel, null, null, null);
    }

    @Transactional
    public ResolvedChatRequest resolve(
        String conversationId,
        String message,
        String model,
        String planningModel,
        ChatSessionSurface surface
    ) {
        return resolve(conversationId, message, model, planningModel, surface, null, null);
    }

    @Transactional
    public ResolvedChatRequest resolve(
        String conversationId,
        String message,
        String model,
        String planningModel,
        ChatSessionSurface surface,
        String contextCapture
    ) {
        return resolve(conversationId, message, model, planningModel, surface, contextCapture, null);
    }

    private ResolvedChatRequest resolve(
        String conversationId,
        String message,
        String model,
        String planningModel,
        ChatSessionSurface surface,
        String contextCapture,
        String boundAgentId
    ) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
        boolean newConversation = !conversationExists(resolvedConversationId);
        String bindingKey = resolveContextBinding(resolvedConversationId, newConversation, contextCapture, boundAgentId);
        if (newConversation) {
            chatSessionMetadataRepository.saveOriginIfAbsent(resolvedConversationId, ChatSessionOrigin.CHAT, null);
            if (surface != null) {
                chatSessionMetadataRepository.saveSurfaceIfAbsent(resolvedConversationId, surface);
            }
        } else if (surface != null) {
            chatSessionMetadataRepository.saveSurfaceIfAbsent(resolvedConversationId, surface);
        }
        if (StringUtils.hasText(planningModel)) {
            chatSessionMetadataRepository.savePlanningModel(resolvedConversationId, planningModel);
        }
        String storedModel = storedConversationModel(resolvedConversationId);
        String selectedModel = StringUtils.hasText(model)
                ? model
                : (StringUtils.hasText(storedModel) ? storedModel : defaultModel());
        if (interactionMode(resolvedConversationId) == PlanMode.PLAN
            || interactionMode(resolvedConversationId) == PlanMode.TASK) {
            selectedModel = resolvedPlanningModel(resolvedConversationId);
        }
        return new ResolvedChatRequest(resolvedConversationId, message, selectedModel, newConversation, true, false, bindingKey);
    }

    /**
     * Resolves the immutable context binding through the authoritative owner-link
     * model. Chat-session metadata is not a second binding store.
     */
    private String resolveContextBinding(String conversationId, boolean newConversation,
                                         String contextCapture, String boundAgentId) {
        if (StringUtils.hasText(contextCapture)
                && !"default".equalsIgnoreCase(contextCapture.trim())) {
            throw new IllegalArgumentException("Unsupported context capture selector");
        }
        if (agentContextService == null) {
            // Lightweight compatibility fixtures do not wire context persistence.
            // A real application always has the service and therefore captures a binding.
            return null;
        }
        if (!newConversation) {
            var link = agentContextService.getOwnerLink("CONVERSATION", conversationId);
            if (link.outcome() != AgentContextService.ContextOutcome.AVAILABLE || link.value() == null) {
                throw new IllegalStateException("Existing conversation has no immutable binding owner link");
            }
            if (StringUtils.hasText(boundAgentId)) {
                var binding = agentContextService.getBinding(link.value().bindingKey());
                if (binding.outcome() != AgentContextService.ContextOutcome.AVAILABLE || binding.value() == null
                        || !boundAgentId.equals(binding.value().agentId())) {
                    throw new IllegalStateException("Existing conversation binding does not belong to the route agent");
                }
            }
            return link.value().bindingKey();
        }
        String captureAgentId = StringUtils.hasText(boundAgentId)
                ? boundAgentId : aiConfig == null ? null : aiConfig.defaultAgent();
        if (!StringUtils.hasText(captureAgentId)) {
            throw new IllegalStateException("A server-resolved default agent is required for context capture");
        }
        String bindingKey = "conversation:" + conversationId;
        agentContextService.captureDefaultOrPersonalHome(captureAgentId, bindingKey,
            "conversation-capture:" + conversationId);
        agentContextService.linkOwner("CONVERSATION", conversationId, bindingKey,
            "conversation-owner-link:" + conversationId);
        return bindingKey;
    }

    public String contextBindingKey(String conversationId) {
        if (agentContextService == null || !StringUtils.hasText(conversationId)) {
            return null;
        }
        var link = agentContextService.getOwnerLink("CONVERSATION", conversationId);
        return link.outcome() == AgentContextService.ContextOutcome.AVAILABLE && link.value() != null
            ? link.value().bindingKey() : null;
    }

    public String storedConversationModel(String conversationId) {
        return chatSessionMetadataRepository.findModel(conversationId).orElse(null);
    }

    private String storedConversationPlanningModel(String conversationId) {
        return chatSessionMetadataRepository.findPlanningModel(conversationId).orElse(null);
    }

    public String defaultModel() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.defaultModel();
        }
        if (aiConfig != null && StringUtils.hasText(aiConfig.resolvedDefaultModelKey())) {
            return aiConfig.resolvedDefaultModelKey();
        }
        String defaultAgentName = aiConfig.defaultAgent();
        return aiConfig.agents().get(defaultAgentName).model();
    }

    public String planningModel() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.planningModel();
        }
        if (aiConfig == null || aiConfig.models() == null) {
            return defaultModel();
        }
        String modelKey = aiConfig.resolvedPlanningModelKey();
        ModelConfig model = aiConfig.models().get(modelKey);
        return model == null ? defaultModel() : model.remoteModelName();
    }

    public String resolvedPlanningModel(String conversationId) {
        String stored = storedConversationPlanningModel(conversationId);
        return StringUtils.hasText(stored) ? stored : planningModel();
    }

    public List<String> availableModels() {
        return aiConfig.models().values().stream()
            .map(config -> config.remoteModelName())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    public PlanMode interactionMode(String conversationId) {
        PlanMode planMode = planService == null ? PlanMode.NORMAL : planService.mode(conversationId);
        if (planMode != PlanMode.NORMAL) {
            return planMode;
        }
        return taskService == null ? PlanMode.NORMAL : taskService.mode(conversationId);
    }

    public boolean conversationExists(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        return rawConversationIds().contains(conversationId);
    }

    public List<String> rawConversationIds() {
        List<String> conversationIds = new ArrayList<>(chatMemoryRepository.findConversationIds());
        if (planService != null) {
            for (String planConversationId : planService.listConversationIds()) {
                if (!conversationIds.contains(planConversationId)) {
                    conversationIds.add(planConversationId);
                }
            }
        }
        return conversationIds;
    }

    public String newConversationId() {
        return UUID.randomUUID().toString();
    }
}
