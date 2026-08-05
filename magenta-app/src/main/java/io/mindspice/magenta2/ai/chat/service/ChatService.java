package io.mindspice.magenta2.ai.chat.service;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import java.io.IOException;

import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.model.ChatToolActivity;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.turn.PromptContextAssembler;
import io.mindspice.magenta2.ai.chat.service.turn.TerminalTurnRepair;
import io.mindspice.magenta2.ai.chat.service.turn.ToolAccessPolicy;
import io.mindspice.magenta2.ai.chat.service.turn.TurnAuditWriter;
import io.mindspice.magenta2.ai.chat.service.turn.TurnDiagnostic;
import io.mindspice.magenta2.ai.chat.service.turn.TurnPhase;

import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService.ToolTranscriptEntry;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

@Service
public class ChatService {
    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    static final String THINKING_METADATA_KEY = "thinking";
    static final String MESSAGE_THINKING_METADATA_KEY = "magenta.thinking";

    private static final Pattern THINK_TAG_PATTERN = Pattern.compile("(?is)<think>(.*?)</think>");
    private static final int EMPTY_FINAL_RESPONSE_RETRY_LIMIT = 2;
    private static final int REPAIR_RETRY_LIMIT = 2;
    private static final String OLLAMA_TOOLS_UNSUPPORTED_MESSAGE = "does not support tools";

    private final ChatMemory chatMemory;
    private final ChatMemoryRepository chatMemoryRepository;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatMarkdownRenderer chatMarkdownRenderer;
    private final AiConfig aiConfig;
    private final ContextManagementAdvisor contextManagementAdvisor;
    private final ContextUsageTracker contextUsageTracker;
    private final ChatModelRouter chatModelRouter;
    private final ToolCallingManager toolCallingManager;
    private final ChatToolRegistry chatToolRegistry;
    private final ToolTranscriptService toolTranscriptService;
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final Set<String> toolUnsupportedModels = ConcurrentHashMap.newKeySet();

    // Extracted turn components
    private final PromptContextAssembler promptAssembler;
    private final ToolAccessPolicy toolAccessPolicy;
    private final TerminalTurnRepair turnRepair;
    private final TurnAuditWriter turnAuditWriter;

    public ChatService(
        ChatMemory chatMemory,
        ChatMemoryRepository chatMemoryRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMarkdownRenderer chatMarkdownRenderer,
        AiConfig aiConfig,
        ContextManagementAdvisor contextManagementAdvisor,
        ContextUsageTracker contextUsageTracker,
        ChatModelRouter chatModelRouter,
        ToolCallingManager toolCallingManager,
        ChatToolRegistry chatToolRegistry,
        ToolTranscriptService toolTranscriptService,
        AuditRepository auditRepository,
        ObjectMapper objectMapper,
        PromptContextAssembler promptAssembler,
        ToolAccessPolicy toolAccessPolicy,
        TerminalTurnRepair turnRepair,
        TurnAuditWriter turnAuditWriter
    ) {
        this.chatMemory = chatMemory;
        this.chatMemoryRepository = chatMemoryRepository;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.chatMarkdownRenderer = chatMarkdownRenderer;
        this.aiConfig = aiConfig;
        this.contextManagementAdvisor = contextManagementAdvisor;
        this.contextUsageTracker = contextUsageTracker;
        this.chatModelRouter = chatModelRouter;
        this.toolCallingManager = toolCallingManager;
        this.chatToolRegistry = chatToolRegistry;
        this.toolTranscriptService = toolTranscriptService;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.promptAssembler = promptAssembler;
        this.toolAccessPolicy = toolAccessPolicy;
        this.turnRepair = turnRepair;
        this.turnAuditWriter = turnAuditWriter;
    }

    // ── Public chat API ──

    public ChatResponse chat(ChatRequest request) {
        if (!(request instanceof ChatRequest.MsgRequest msgRequest)) {
            throw new IllegalArgumentException("message request is required");
        }
        rejectSystemChatConversation(msgRequest.conversationId());
        ResolvedChatRequest resolvedRequest = resolveFromMsgRequest(msgRequest);
        return chat(resolvedRequest);
    }

    public ChatResponse.MsgResponse chat(String conversationId, String message, String model) {
        rejectSystemChatConversation(conversationId);
        ResolvedChatRequest resolvedRequest = resolveFromParams(conversationId, message, model);
        return chat(resolvedRequest);
    }

    public ResolvedChatRequest resolve(ChatRequest request) {
        if (request instanceof ChatRequest.MsgRequest messageRequest) {
            rejectSystemChatConversation(messageRequest.conversationId());
            return resolveFromMsgRequest(messageRequest);
        }
        throw new IllegalArgumentException("message request is required");
    }

    private ResolvedChatRequest resolveFromMsgRequest(ChatRequest.MsgRequest msgRequest) {
        return resolveFromParams(msgRequest.conversationId(), msgRequest.message(), msgRequest.model());
    }

    private ResolvedChatRequest resolveFromParams(String conversationId, String message, String model) {
        String convId = StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
        String resolvedModel = StringUtils.hasText(model) ? model : defaultModel();
        return new ResolvedChatRequest(convId, message, resolvedModel);
    }

    private ChatResponse.MsgResponse chat(ResolvedChatRequest resolvedRequest) {
        rejectSystemChatConversation(resolvedRequest.conversationId());
        return chatNow(resolvedRequest);
    }

    private ChatResponse.MsgResponse chatNow(ResolvedChatRequest resolvedRequest) {
        List<ToolCallback> approvedTools = approvedTools(resolvedRequest);
        if (!approvedTools.isEmpty() && supportsTools(resolvedRequest.model())) {
            try {
                return toolChatWithRetry(resolvedRequest, approvedTools);
            } catch (NonTransientAiException exception) {
                if (!isToolUnsupported(exception)) {
                    throw exception;
                }
                rememberToolUnsupportedModel(resolvedRequest.model());
            }
        }
        return plainChat(resolvedRequest);
    }

    // ── Public stream API ──

    public Flux<ChatMessage> stream(ResolvedChatRequest request) {
        rejectSystemChatConversation(request.conversationId());
        return streamNow(request);
    }

    private Flux<ChatMessage> streamNow(ResolvedChatRequest request) {
        List<ToolCallback> approvedTools = approvedTools(request);
        if (!approvedTools.isEmpty() && supportsTools(request.model())) {
            return Flux.<ChatMessage>create(sink -> {
                try {
                    ChatMessage finalMessage = toolChatMessageWithRetry(request, approvedTools, sink::next);
                    sink.next(finalMessage);
                    sink.complete();
                } catch (RuntimeException exception) {
                    sink.error(exception);
                }
            })
                .onErrorResume(NonTransientAiException.class, exception -> {
                    if (!isToolUnsupported(exception)) {
                        return Flux.error(exception);
                    }
                    rememberToolUnsupportedModel(request.model());
                    return plainStream(request);
                });
        }
        return plainStream(request);
    }

    // ── Plain chat / stream ──

    private ChatResponse.MsgResponse plainChat(ResolvedChatRequest resolvedRequest) {
        turnAuditWriter.recordTurnStart(resolvedRequest);
        ChatClient.ChatClientRequestSpec prompt = prompt(resolvedRequest);

        ChatClientResponse chatClientResponse = prompt.call().chatClientResponse();
        String response = chatClientResponse.chatResponse().getResult().getOutput().getText();
        chatSessionMetadataRepository.saveModel(resolvedRequest.conversationId(), resolvedRequest.model());
        turnAuditWriter.enqueueTitleJob(resolvedRequest);

        AssistantMessage assistantMsg = chatClientResponse.chatResponse().getResult().getOutput();
        if (assistantMsg != null) {
            turnAuditWriter.recordAssistantMessage(assistantMsg, resolvedRequest);
        }

        StoredContextUsage maintenance = maintainContextUsage(resolvedRequest.conversationId(), resolvedRequest.model());
        turnAuditWriter.recordEndOfTurnContext(resolvedRequest, maintenance);
        return new ChatResponse.MsgResponse(
            resolvedRequest.conversationId(),
            resolvedRequest.model(),
            response,
            maintenance.usage(),
            ChatPlanState.normal()
        );
    }

    private Flux<ChatMessage> plainStream(ResolvedChatRequest request) {
        chatSessionMetadataRepository.saveModel(request.conversationId(), request.model());
        return Flux.defer(() -> {
            ChatClientResponse chatClientResponse = prompt(request).call().chatClientResponse();
            StoredContextUsage maintenance = maintainContextUsage(request.conversationId(), request.model());
            if (maintenance.compacted()) {
                return Flux.just(
                    systemMessage(ContextManagementAdvisor.COMPACTION_NOTICE),
                    renderAssistantMessage(chatClientResponse.chatResponse())
                );
            }
            return Flux.just(renderAssistantMessage(chatClientResponse.chatResponse()));
        }).doOnComplete(() -> {
            turnAuditWriter.enqueueTitleJob(request);
        });
    }

    // ── Conversation management ──

    public void clearConversation(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            chatMemory.clear(conversationId);
            chatSessionMetadataRepository.deleteByConversationId(conversationId);
            if (contextUsageTracker != null) {
                contextUsageTracker.clear(conversationId);
            }
        }
    }

    private void rejectSystemChatConversation(String conversationId) {
        if (conversationId == null || !StringUtils.hasText(conversationId)) {
            return;
        }
        var origin = chatSessionMetadataRepository.findOrigin(conversationId);
        if (origin.isPresent() && (origin.get() == ChatSessionOrigin.SYSTEM_CHAT
                || origin.get() == ChatSessionOrigin.PROJECT_CHAT)) {
            throw new IllegalArgumentException(
                    "Restricted chat conversations cannot be used through public chat endpoints");
        }
    }

    // ── Session / history ──

    public List<String> listConversationIds() {
        return listSessions().stream()
            .map(ChatSession::conversationId)
            .toList();
    }

    private List<String> rawConversationIds() {
        return new ArrayList<>(chatMemoryRepository.findConversationIds());
    }

    public List<ChatSession> listSessions() {
        if (chatSessionMetadataRepository == null) {
            return List.of();
        }
        return chatSessionMetadataRepository.findBrowserConversationIds().stream()
            .map(this::session)
            .filter(session -> session.origin() != ChatSessionOrigin.AGENT_CHAT)
            .filter(session -> session.origin() != ChatSessionOrigin.SYSTEM_CHAT)
            .filter(session -> session.origin() != ChatSessionOrigin.PROJECT_CHAT)
            .filter(session -> !session.archived())
            .sorted(java.util.Comparator
                .comparing(ChatSession::favorite).reversed()
                .thenComparing(
                    session -> session.updatedAt() == null ? "" : session.updatedAt(),
                    java.util.Comparator.reverseOrder()
                )
                .thenComparing(ChatSession::conversationId))
            .toList();
    }

    public boolean conversationExists(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return false;
        }
        return rawConversationIds().contains(conversationId);
    }

    public List<ChatMessage> history(String conversationId) {
        List<Message> messages = chatMemoryRepository.findByConversationId(conversationId);
        return toHistory(messages);
    }

    public void discardLastUserMessage(String conversationId, String messageText) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        List<Message> messages = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        if (messages.isEmpty()) {
            return;
        }
        Message lastMessage = messages.get(messages.size() - 1);
        if (lastMessage instanceof UserMessage && java.util.Objects.equals(lastMessage.getText(), messageText)) {
            messages.remove(messages.size() - 1);
            chatMemoryRepository.saveAll(conversationId, messages);
        }
    }

    private ChatSession session(String conversationId) {
        return new ChatSession(
            conversationId,
            conversationTitle(conversationId),
            conversationTitleJobStatus(conversationId),
            chatSessionMetadataRepository.isFavorite(conversationId),
            chatSessionMetadataRepository.isArchived(conversationId),
            chatSessionMetadataRepository.findUpdatedAt(conversationId).orElse(null),
            chatSessionMetadataRepository.findOrigin(conversationId).orElse(null),
            chatSessionMetadataRepository.findAgentId(conversationId).orElse(null),
            0
        );
    }

    public String conversationTitleJobStatus(String conversationId) {
        return null;
    }

    public String newConversationId() {
        return UUID.randomUUID().toString();
    }

    public String conversationTitle(String conversationId) {
        return chatSessionMetadataRepository.findTitle(conversationId).orElse(null);
    }

    public ChatSession renameConversation(String conversationId, String title) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("title is required");
        }
        String normalizedTitle = title.trim().replaceAll("\\s+", " ");
        if (normalizedTitle.length() > 120) {
            normalizedTitle = normalizedTitle.substring(0, 120).replaceAll("\\s+\\S*$", "").trim();
        }
        chatSessionMetadataRepository.updateTitle(conversationId, normalizedTitle);
        return session(conversationId);
    }

    public ChatSession setConversationFavorite(String conversationId, boolean favorite) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        chatSessionMetadataRepository.setFavorite(conversationId, favorite);
        return session(conversationId);
    }

    public ChatSession setConversationArchived(String conversationId, boolean archived) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId is required");
        }
        chatSessionMetadataRepository.setArchived(conversationId, archived);
        return session(conversationId);
    }

    // ── Model resolution ──

    public String defaultModel() {
        if (aiConfig != null && StringUtils.hasText(aiConfig.resolvedDefaultModelKey())) {
            return aiConfig.resolvedDefaultModelKey();
        }
        String defaultAgentName = aiConfig.defaultAgent();
        return aiConfig.agents().get(defaultAgentName).model();
    }

    public List<String> availableModels() {
        return aiConfig.models().values().stream()
            .map(config -> config.remoteModelName())
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    public record ModelOption(String key, String label) {}

    public List<ModelOption> availableModelOptions() {
        return aiConfig.models().entrySet().stream()
            .filter(entry -> StringUtils.hasText(entry.getValue().remoteModelName()))
            .map(entry -> new ModelOption(
                entry.getKey(),
                entry.getKey() + " (" + entry.getValue().remoteModelName() + ")"))
            .sorted(java.util.Comparator.comparing(ModelOption::label))
            .toList();
    }

    public String modelSelectionKey(String model) {
        if (!StringUtils.hasText(model) || aiConfig == null || aiConfig.models() == null) {
            return model;
        }
        if (aiConfig.models().containsKey(model)) {
            return model;
        }
        String configuredDefault = aiConfig.resolvedDefaultModelKey();
        if (remoteModelNameMatches(configuredDefault, model)) {
            return configuredDefault;
        }
        return aiConfig.models().entrySet().stream()
            .filter(entry -> model.equals(entry.getValue().remoteModelName()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(model);
    }

    private boolean remoteModelNameMatches(String modelKey, String remoteModelName) {
        if (!StringUtils.hasText(modelKey) || !StringUtils.hasText(remoteModelName)) {
            return false;
        }
        ModelConfig config = aiConfig.models().get(modelKey);
        return config != null && remoteModelName.equals(config.remoteModelName());
    }

    public String storedConversationModel(String conversationId) {
        return chatSessionMetadataRepository.findModel(conversationId).orElse(null);
    }

    // ── Context usage ──

    public ContextUsage contextUsage(String conversationId, String model) {
        if (contextUsageTracker != null) {
            ContextUsage trackedUsage = contextUsageTracker.find(conversationId);
            if (trackedUsage != null) {
                if (trackedUsage.usedTokens() <= trackedUsage.triggerTokens() || contextManagementAdvisor == null) {
                    return trackedUsage;
                }
                return maintainContextUsage(conversationId, model).usage();
            }
        }
        if (contextManagementAdvisor == null || chatMemoryRepository == null) {
            return null;
        }
        String resolvedModel = StringUtils.hasText(model) ? model : storedConversationModel(conversationId);
        return maintainContextUsage(conversationId, resolvedModel).usage();
    }

    public StoredContextUsage maintainContextUsage(String conversationId, String model) {
        if (contextManagementAdvisor == null || chatMemoryRepository == null) {
            ContextUsage trackedUsage = contextUsageTracker == null ? null : contextUsageTracker.find(conversationId);
            return new StoredContextUsage(trackedUsage, false);
        }
        String resolvedModel = StringUtils.hasText(model) ? model : storedConversationModel(conversationId);
        try {
            ContextManagementAdvisor.StoredContextMaintenance maintenance =
                contextManagementAdvisor.maintainStoredContext(conversationId, resolvedModel);
            return new StoredContextUsage(
                maintenance.usage(),
                maintenance.compacted(),
                maintenance.degraded(),
                maintenance.degradationReason()
            );
        } catch (RuntimeException exception) {
            ContextUsage fallbackUsage = null;
            try {
                fallbackUsage = contextManagementAdvisor.estimateStoredUsage(conversationId, resolvedModel);
                if (contextUsageTracker != null && fallbackUsage != null) {
                    contextUsageTracker.record(conversationId, fallbackUsage);
                }
            } catch (RuntimeException estimateException) {
                logger.warn(
                    "Context maintenance and fallback usage estimation failed conv={} model={} maintenanceError={} estimateError={}",
                    conversationId, resolvedModel, exception.toString(), estimateException.toString()
                );
                return new StoredContextUsage(
                    contextUsageTracker == null ? null : contextUsageTracker.find(conversationId),
                    false,
                    true,
                    "Context maintenance failed: " + rootCauseMessage(exception)
                );
            }
            logger.warn(
                "Context maintenance degraded conv={} model={} tokens={}/{} error={}",
                conversationId,
                resolvedModel,
                fallbackUsage.usedTokens(),
                fallbackUsage.triggerTokens(),
                exception.toString()
            );
            return new StoredContextUsage(
                fallbackUsage,
                false,
                true,
                "Context maintenance failed: " + rootCauseMessage(exception)
            );
        }
    }

    public StoredContextUsage snapshotContextUsage(String conversationId, String model) {
        if (contextManagementAdvisor == null || chatMemoryRepository == null) {
            ContextUsage trackedUsage = contextUsageTracker == null ? null : contextUsageTracker.find(conversationId);
            return new StoredContextUsage(trackedUsage, false);
        }
        String resolvedModel = StringUtils.hasText(model) ? model : storedConversationModel(conversationId);
        try {
            ContextUsage usage = contextManagementAdvisor.estimateStoredUsage(conversationId, resolvedModel);
            if (contextUsageTracker != null && usage != null) {
                contextUsageTracker.record(conversationId, usage);
            }
            return new StoredContextUsage(usage, false);
        } catch (RuntimeException exception) {
            logger.warn(
                "Context usage snapshot degraded conv={} model={} error={}",
                conversationId, resolvedModel, exception.toString()
            );
            return new StoredContextUsage(
                contextUsageTracker == null ? null : contextUsageTracker.find(conversationId),
                false,
                true,
                "Context usage snapshot failed: " + rootCauseMessage(exception)
            );
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? null : root.getMessage();
        if (!StringUtils.hasText(message) && throwable != null) {
            message = throwable.getMessage();
        }
        return StringUtils.hasText(message) ? message : "unknown execution error";
    }

    // ── Tool chat state machine ──

    private ChatResponse.MsgResponse toolChat(ResolvedChatRequest request, List<ToolCallback> approvedTools) {
        return toolChat(request, approvedTools, null).response();
    }

    private ToolChatResult toolChat(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer
    ) {
        if (chatModelRouter == null || toolCallingManager == null || contextManagementAdvisor == null || toolTranscriptService == null) {
            throw new IllegalStateException("Tool execution requires model routing, ToolCallingManager, context management, and tool transcripts");
        }
        chatSessionMetadataRepository.saveModel(request.conversationId(), request.model());

        List<Message> currentInstructions = currentInstructions(request);
        List<Message> currentSystemInstructions = currentInstructions.stream()
            .filter(SystemMessage.class::isInstance)
            .toList();
        List<Message> toolLoopInstructions = request.omitStoredMessages()
            ? currentInstructions
            : currentSystemInstructions;
        ToolCallingChatOptions options = toolOptions(request.model(), approvedTools);

        ContextManagementAdvisor.PreparedPrompt preparedPrompt = contextManagementAdvisor.preparePrompt(
            request.conversationId(), currentInstructions, request.model(), request.omitStoredMessages());
        Prompt prompt = new Prompt(preparedPrompt.messages(), options);

        if (logger.isDebugEnabled()) {
            logger.debug("{}", TurnDiagnostic.start(request.conversationId(), TurnPhase.PREPARE,
                Map.of("model", request.model(), "toolCount", approvedTools.size())));
        }
        logger.debug("Starting tool chat turn conv={} model={}",
            request.conversationId(), request.model());

        turnAuditWriter.recordTurnStart(request);

        var s = new ToolLoopState(request, approvedTools, toolMessageConsumer, options, toolLoopInstructions);
        s.prompt = prompt;
        s.phase = TurnPhase.INVOKE_MODEL;

        try {
            while (s.phase != TurnPhase.DONE) {
                switch (s.phase) {
                    case INVOKE_MODEL  -> handleInvokeModel(s);
                    case EVALUATE      -> handleEvaluate(s);
                    case EXECUTE_TOOLS -> handleExecuteTools(s);
                    case REPAIR        -> handleRepair(s);
                    case FINALIZE      -> handleFinalize(s);
                }
            }
            return new ToolChatResult(s.chatResponse, s.finalMessage);
        } catch (RuntimeException e) {
            logger.error("Tool chat turn failed conv={}: {}",
                request.conversationId(), e.getMessage(), e);
            throw e;
        }
    }

    private void handleInvokeModel(ToolLoopState s) {
        s.response = chatModelRouter.chatModel(s.request.model()).call(s.prompt);
        s.phase = TurnPhase.EVALUATE;
    }

    private void handleEvaluate(ToolLoopState s) {
        if (s.planCompletionDetected) {
            s.phase = TurnPhase.FINALIZE;
            return;
        }
        if (s.response != null && s.response.hasToolCalls()) {
            s.phase = TurnPhase.EXECUTE_TOOLS;
            return;
        }
        if (s.toolUseAbort != null) {
            s.phase = TurnPhase.REPAIR;
            return;
        }
        if (isEmptyFinalResponse(s.response)
            && s.emptyFinalResponseRetries < EMPTY_FINAL_RESPONSE_RETRY_LIMIT) {
            s.phase = TurnPhase.REPAIR;
            return;
        }
        s.phase = TurnPhase.FINALIZE;
    }

    private void handleExecuteTools(ToolLoopState s) {
        collectThinking(s.response, s.thinkingParts);

        try {
            s.toolLoopGuard.recordToolCalls(s.response.getResult().getOutput().getToolCalls());
        } catch (ToolUseAbort abort) {
            logger.warn("Tool use aborted conv={} (identical calls): {}",
                s.request.conversationId(), abort.getMessage());
            s.toolUseAbort = abort;
            s.conversationHistory = new ArrayList<>(s.prompt.getInstructions());
            s.phase = TurnPhase.EVALUATE;
            return;
        }

        List<MalformedToolCall> malformedToolCalls = malformedToolCalls(s.response);
        if (!malformedToolCalls.isEmpty()) {
            handleMalformedToolCallArguments(s, malformedToolCalls);
            return;
        }

        int promptMessageCount = s.prompt.getInstructions().size();
        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = toolCallingManager.executeToolCalls(s.prompt, s.response);
        } catch (ToolExecutionException exception) {
            handleToolExecutionFailure(s, exception);
            return;
        }
        List<ToolTranscriptEntry> toolTranscriptEntries = toolTranscriptEntries(s.response, toolExecutionResult);
        List<ChatMessage> pendingToolMessages = new ArrayList<>();

        for (ToolTranscriptEntry entry : toolTranscriptEntries) {
            Message transcriptMessage = toolTranscriptService.message(entry);
            s.messagesToPersist.add(transcriptMessage);
            ChatMessage toolMessage = toolMessage(transcriptMessage);
            if (toolMessage.toolActivity() != null) {
                s.toolActivities.add(toolMessage.toolActivity());
            }
            pendingToolMessages.add(toolMessage);
            turnAuditWriter.recordToolExec(entry, s.request.conversationId(), s.request.model());
        }

        s.conversationHistory = new ArrayList<>(toolExecutionResult.conversationHistory());
        if (s.conversationHistory.size() > promptMessageCount) {
            s.activeToolMessages.addAll(
                s.conversationHistory.subList(promptMessageCount, s.conversationHistory.size()));
        }
        s.currentSystemInstructions = currentSystemInstructions(s.request);

        try {
            s.toolLoopGuard.recordToolResponses(toolExecutionResult);
        } catch (ToolUseAbort abort) {
            logger.warn("Tool use aborted conv={} (error rate): {}",
                s.request.conversationId(), abort.getMessage());
            s.toolUseAbort = abort;
            s.phase = TurnPhase.EVALUATE;
            return;
        }

        ContextManagementAdvisor.ToolLoopPrompt checkpoint = contextManagementAdvisor.prepareToolLoopPrompt(
            s.request.conversationId(), s.activeToolMessages,
            s.currentSystemInstructions, s.request.model(), s.request.omitStoredMessages());
        s.activeToolMessages = new ArrayList<>(checkpoint.activeMessages());
        s.conversationHistory = new ArrayList<>(checkpoint.messages());
        s.prompt = new Prompt(s.conversationHistory, s.toolOptions);

        if (checkpoint.compacted() && !s.compactionNoticeEmitted) {
            s.compactionNoticeEmitted = true;
            if (!hasCompactionNotice(s.request.conversationId())) {
                s.messagesToPersist.add(compactionNoticeMessage());
            }
            if (s.toolMessageConsumer != null) {
                s.toolMessageConsumer.accept(systemMessage(ContextManagementAdvisor.COMPACTION_NOTICE));
            }
        }

        if (!checkpoint.toolUseAllowed()) {
            s.toolUseAbort = new ToolUseAbort(
                "Context is too large to safely continue tool use after compaction.");
            s.phase = TurnPhase.EVALUATE;
            return;
        }

        turnAuditWriter.recordContextUsage(s.request.conversationId(), checkpoint.usage(), s.request.model());

        if (s.toolMessageConsumer != null) {
            pendingToolMessages.forEach(s.toolMessageConsumer);
        }

        s.phase = TurnPhase.INVOKE_MODEL;
    }

    private void handleToolExecutionFailure(ToolLoopState s, ToolExecutionException exception) {
        logger.warn("Tool execution failed conv={} during tool argument conversion/execution: {}",
            s.request.conversationId(), exception.getMessage());
        List<MalformedToolCall> failedCalls = failedToolCalls(s.response, exception);
        List<ChatMessage> pendingToolMessages = new ArrayList<>();
        for (MalformedToolCall failedCall : failedCalls) {
            ToolTranscriptEntry entry = toolTranscriptService.malformedArgumentsEntry(
                failedCall.id(),
                failedCall.name(),
                failedCall.arguments(),
                failedCall.parserMessage()
            );
            Message transcriptMessage = toolTranscriptService.message(entry);
            s.messagesToPersist.add(transcriptMessage);
            s.activeToolMessages.add(transcriptMessage);
            ChatMessage toolMessage = toolMessage(transcriptMessage);
            if (toolMessage.toolActivity() != null) {
                s.toolActivities.add(toolMessage.toolActivity());
            }
            pendingToolMessages.add(toolMessage);
            turnAuditWriter.recordToolExec(entry, s.request.conversationId(), s.request.model());
        }

        SystemMessage controlMessage = new SystemMessage(
            "One or more tool calls failed before Magenta could apply them: "
                + compactParserMessage(exception.getMessage()) + ". "
                + "Retry the failed tool call with the exact schema shown for that tool, or continue with another action."
        );
        s.messagesToPersist.add(controlMessage);
        s.activeToolMessages.add(controlMessage);

        ContextManagementAdvisor.ToolLoopPrompt checkpoint = contextManagementAdvisor.prepareToolLoopPrompt(
            s.request.conversationId(), s.activeToolMessages,
            s.currentSystemInstructions, s.request.model(), s.request.omitStoredMessages());
        s.activeToolMessages = new ArrayList<>(checkpoint.activeMessages());
        s.conversationHistory = new ArrayList<>(checkpoint.messages());
        s.prompt = new Prompt(s.conversationHistory, s.toolOptions);

        turnAuditWriter.recordContextUsage(s.request.conversationId(), checkpoint.usage(), s.request.model());
        if (s.toolMessageConsumer != null) {
            pendingToolMessages.forEach(s.toolMessageConsumer);
        }
        if (!checkpoint.toolUseAllowed()) {
            s.toolUseAbort = new ToolUseAbort(
                "Context is too large to safely continue tool use after a tool execution failure.");
            s.phase = TurnPhase.EVALUATE;
            return;
        }
        s.phase = TurnPhase.INVOKE_MODEL;
    }

    private List<MalformedToolCall> failedToolCalls(org.springframework.ai.chat.model.ChatResponse response, ToolExecutionException exception) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of(new MalformedToolCall(null, "unknown", "", exception.getMessage()));
        }
        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of(new MalformedToolCall(null, "unknown", "", exception.getMessage()));
        }
        return toolCalls.stream()
            .map(call -> new MalformedToolCall(call.id(), call.name(), call.arguments(), exception.getMessage()))
            .toList();
    }

    private void handleMalformedToolCallArguments(ToolLoopState s, List<MalformedToolCall> malformedToolCalls) {
        logger.warn(
            "Rejected malformed tool-call arguments conv={} malformedCount={}",
            s.request.conversationId(),
            malformedToolCalls.size()
        );

        List<ChatMessage> pendingToolMessages = new ArrayList<>();
        for (MalformedToolCall malformedToolCall : malformedToolCalls) {
            ToolTranscriptEntry entry = toolTranscriptService.malformedArgumentsEntry(
                malformedToolCall.id(),
                malformedToolCall.name(),
                malformedToolCall.arguments(),
                malformedToolCall.parserMessage()
            );
            Message transcriptMessage = toolTranscriptService.message(entry);
            s.messagesToPersist.add(transcriptMessage);
            s.activeToolMessages.add(transcriptMessage);
            ChatMessage toolMessage = toolMessage(transcriptMessage);
            if (toolMessage.toolActivity() != null) {
                s.toolActivities.add(toolMessage.toolActivity());
            }
            pendingToolMessages.add(toolMessage);
            turnAuditWriter.recordToolExec(entry, s.request.conversationId(), s.request.model());
        }

        SystemMessage controlMessage = malformedToolCallControlMessage(malformedToolCalls);
        s.messagesToPersist.add(controlMessage);
        s.activeToolMessages.add(controlMessage);

        ContextManagementAdvisor.ToolLoopPrompt checkpoint = contextManagementAdvisor.prepareToolLoopPrompt(
            s.request.conversationId(), s.activeToolMessages,
            s.currentSystemInstructions, s.request.model(), s.request.omitStoredMessages());
        s.activeToolMessages = new ArrayList<>(checkpoint.activeMessages());
        s.conversationHistory = new ArrayList<>(checkpoint.messages());
        s.prompt = new Prompt(s.conversationHistory, s.toolOptions);

        if (checkpoint.compacted() && !s.compactionNoticeEmitted) {
            s.compactionNoticeEmitted = true;
            if (!hasCompactionNotice(s.request.conversationId())) {
                s.messagesToPersist.add(compactionNoticeMessage());
            }
            if (s.toolMessageConsumer != null) {
                s.toolMessageConsumer.accept(systemMessage(ContextManagementAdvisor.COMPACTION_NOTICE));
            }
        }

        if (!checkpoint.toolUseAllowed()) {
            s.toolUseAbort = new ToolUseAbort(
                "Context is too large to safely continue tool use after compaction.");
            s.phase = TurnPhase.EVALUATE;
            return;
        }

        turnAuditWriter.recordContextUsage(s.request.conversationId(), checkpoint.usage(), s.request.model());

        if (s.toolMessageConsumer != null) {
            pendingToolMessages.forEach(s.toolMessageConsumer);
        }

        s.phase = TurnPhase.INVOKE_MODEL;
    }

    private void handleRepair(ToolLoopState s) {
        if (s.toolUseAbort != null) {
            Message controlMessage = toolUseAbortControlMessage(s.toolUseAbort);
            s.messagesToPersist.add(controlMessage);
            s.activeToolMessages.add(controlMessage);
            ContextManagementAdvisor.ToolLoopPrompt checkpoint = contextManagementAdvisor.prepareToolLoopPrompt(
                s.request.conversationId(), s.activeToolMessages,
                s.currentSystemInstructions, s.request.model(), s.request.omitStoredMessages());
            turnAuditWriter.recordContextUsage(s.request.conversationId(), checkpoint.usage(), s.request.model());
            if (!checkpoint.toolUseAllowed()) {
                throw new IllegalStateException(
                    "Context is too large to send safely after tool compaction: "
                        + checkpoint.usage().usedTokens() + " estimated tokens exceeds trigger budget "
                        + checkpoint.usage().triggerTokens());
            }
            s.prompt = new Prompt(checkpoint.messages(), toolFinalOptions(s.request.model()));
            s.toolUseAbort = null;
            s.phase = TurnPhase.INVOKE_MODEL;
            return;
        }

        if (!s.planCompletionDetected && isEmptyFinalResponse(s.response)
            && s.emptyFinalResponseRetries < EMPTY_FINAL_RESPONSE_RETRY_LIMIT) {
            collectThinking(s.response, s.thinkingParts);
            s.emptyFinalResponseRetries++;
            Message controlMessage = emptyFinalResponseControlMessage();
            List<Message> retryMessages = new ArrayList<>(s.prompt.getInstructions());
            retryMessages.add(controlMessage);
            s.prompt = new Prompt(retryMessages, s.prompt.getOptions());
            s.phase = TurnPhase.INVOKE_MODEL;
            return;
        }

        s.phase = TurnPhase.FINALIZE;
    }

    private void handleFinalize(ToolLoopState s) {
        collectThinking(s.response, s.thinkingParts);

        AssistantMessage finalAssistantMessage;
        if (s.planCompletionDetected) {
            finalAssistantMessage = StringUtils.hasText(s.validatedFinalMessage)
                ? new AssistantMessage(s.validatedFinalMessage)
                : new AssistantMessage("");
        } else if (s.response == null || s.response.getResult() == null) {
            finalAssistantMessage = new AssistantMessage("");
        } else {
            finalAssistantMessage = assistantMessageWithThinking(
                s.response.getResult(), combinedThinking(s.thinkingParts));
        }

        if (finalAssistantMessage != null) {
            s.messagesToPersist.add(finalAssistantMessage);
            turnAuditWriter.recordAssistantMessage(finalAssistantMessage, s.request);
        }
        contextManagementAdvisor.saveAssistantMessages(s.request.conversationId(), s.messagesToPersist);

        StoredContextUsage maintenance = s.planCompletionDetected
            ? snapshotContextUsage(s.request.conversationId(), s.request.model())
            : maintainContextUsage(s.request.conversationId(), s.request.model());
        turnAuditWriter.recordEndOfTurnContext(s.request, maintenance);
        if (maintenance.compacted() && s.toolMessageConsumer != null) {
            s.toolMessageConsumer.accept(systemMessage(ContextManagementAdvisor.COMPACTION_NOTICE));
        }

        ChatResponse.MsgResponse chatResponse = new ChatResponse.MsgResponse(
            s.request.conversationId(), s.request.model(),
            finalAssistantMessage == null ? "" : finalAssistantMessage.getText(),
            maintenance.usage(),
            ChatPlanState.normal(),
            List.copyOf(s.toolActivities));

        ChatMessage finalMessage = renderAssistantMessage(assistantMessageParts(
            finalAssistantMessage,
            finalAssistantMessage == null ? "" : finalAssistantMessage.getText()));

        turnAuditWriter.enqueueTitleJob(s.request);
        logger.debug("Tool chat turn completed conv={} tokens={}",
            s.request.conversationId(),
            maintenance.usage() != null ? maintenance.usage().usedTokens() : "?");

        s.chatResponse = chatResponse;
        s.finalMessage = finalMessage;
        s.phase = TurnPhase.DONE;
    }

    // ── Tool chat retry wrappers ──

    private ChatMessage toolChatMessage(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer
    ) {
        ToolChatResult result = toolChat(request, approvedTools, toolMessageConsumer);
        List<ChatMessage> history = history(request.conversationId());
        if (!history.isEmpty()) {
            ChatMessage lastMessage = history.get(history.size() - 1);
            if (isAssistantRole(lastMessage.role())) {
                return lastMessage;
            }
        }
        return result.finalMessage();
    }

    private ChatMessage toolChatMessageWithRetry(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer
    ) {
        List<Message> snapshot = snapshotConversation(request.conversationId());
        try {
            return toolChatMessage(request, approvedTools, toolMessageConsumer);
        } catch (RuntimeException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            logger.warn("Retrying turn after transient failure conv={}: {}", request.conversationId(), e.getMessage());
            restoreConversation(request.conversationId(), snapshot);
            try {
                return toolChatMessage(request, approvedTools, toolMessageConsumer);
            } catch (RuntimeException retryException) {
                restoreConversation(request.conversationId(), snapshot);
                throw retryException;
            }
        }
    }

    private ChatResponse.MsgResponse toolChatWithRetry(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools
    ) {
        List<Message> snapshot = snapshotConversation(request.conversationId());
        try {
            return toolChat(request, approvedTools, null).response();
        } catch (RuntimeException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            logger.warn("Retrying turn after transient failure conv={}: {}", request.conversationId(), e.getMessage());
            restoreConversation(request.conversationId(), snapshot);
            try {
                return toolChat(request, approvedTools, null).response();
            } catch (RuntimeException retryException) {
                restoreConversation(request.conversationId(), snapshot);
                throw retryException;
            }
        }
    }

    // ── Prompt / instructions ──

    private List<Message> currentInstructions(ResolvedChatRequest request) {
        String systemPrompt = effectiveSystemPrompt(request);
        return promptAssembler.assembleTurnInstructions(request, systemPrompt);
    }

    private List<Message> currentSystemInstructions(ResolvedChatRequest request) {
        return currentInstructions(request).stream()
            .filter(SystemMessage.class::isInstance)
            .toList();
    }

    private ChatClient.ChatClientRequestSpec prompt(ResolvedChatRequest request) {
        ChatClient chatClient = chatModelRouter == null
            ? null
            : ChatClient.builder(chatModelRouter.chatModel(request.model()))
                .defaultAdvisors(contextManagementAdvisor)
                .build();
        if (chatClient == null) {
            throw new IllegalStateException("Chat execution requires model routing");
        }
        ChatClient.ChatClientRequestSpec prompt = chatClient.prompt()
            .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, request.conversationId()));
        if (request.omitStoredMessages()) {
            prompt = prompt.advisors(advisorSpec -> advisorSpec.param(
                ContextManagementAdvisor.OMIT_STORED_MESSAGES_KEY,
                Boolean.TRUE
            ));
        }

        String systemPrompt = effectiveSystemPrompt(request);
        if (StringUtils.hasText(systemPrompt)) {
            prompt = prompt.system(systemPrompt);
        }

        prompt = prompt.user(request.message());

        if (StringUtils.hasText(request.model())) {
            prompt = prompt.options(chatModelRouter.chatOptions(request.model()).mutate());
        }
        return prompt;
    }

    String defaultSystemPrompt() {
        return promptAssembler.defaultSystemPrompt();
    }

    String effectiveSystemPrompt(ResolvedChatRequest request) {
        return promptAssembler.assembleTurnInstructions(request, defaultSystemPrompt())
            .stream()
            .filter(SystemMessage.class::isInstance)
            .map(Message::getText)
            .collect(Collectors.joining("\n"));
    }

    // ── Tool resolution ──

    private List<ToolCallback> approvedTools(ResolvedChatRequest request) {
        return approvedTools(request, null);
    }

    private List<ToolCallback> approvedTools(ResolvedChatRequest request, List<String> approvedToolNames) {
        if (chatToolRegistry == null) {
            return List.of();
        }
        if (approvedToolNames != null) {
            return filterApprovedTools(approvedToolNames, request);
        }
        if (aiConfig == null || !StringUtils.hasText(aiConfig.defaultAgent())) {
            return List.of();
        }
        AgentConfig defaultAgent = aiConfig.agents().get(aiConfig.defaultAgent());
        if (defaultAgent == null) {
            return List.of();
        }
        return filterApprovedTools(defaultAgent.approvedTools(), request);
    }

    private List<ToolCallback> filterApprovedTools(List<String> approvedToolNames, ResolvedChatRequest request) {
        return toolAccessPolicy.filterToolsByMode(
            approvedToolNames,
            toolAccessPolicy.interactionMode(request.conversationId()),
            request.conversationId());
    }

    // ── Tool support detection ──

    private boolean supportsTools(String model) {
        return !StringUtils.hasText(model) || !toolUnsupportedModels.contains(model);
    }

    private void rememberToolUnsupportedModel(String model) {
        if (StringUtils.hasText(model)) {
            toolUnsupportedModels.add(model);
        }
    }

    static boolean isToolUnsupported(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(OLLAMA_TOOLS_UNSUPPORTED_MESSAGE)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ── Tool options ──

    private ToolCallingChatOptions toolOptions(String model, List<ToolCallback> approvedTools) {
        ToolCallingChatOptions options = StringUtils.hasText(model)
            ? chatModelRouter.toolCallingOptions(model)
            : DefaultToolCallingChatOptions.builder().build();
        return options.mutate().toolCallbacks(approvedTools).build();
    }

    private ToolCallingChatOptions toolFinalOptions(String model) {
        ToolCallingChatOptions options = StringUtils.hasText(model)
            ? chatModelRouter.toolCallingOptions(model)
            : DefaultToolCallingChatOptions.builder().build();
        return options.mutate().toolCallbacks(List.of()).build();
    }

    // ── Repair helpers (mode-unaware) ──

    private SystemMessage toolUseAbortControlMessage(ToolUseAbort abort) {
        return turnRepair.toolUseAbortControlMessage(abort);
    }

    private boolean isEmptyFinalResponse(org.springframework.ai.chat.model.ChatResponse response) {
        return turnRepair.hasNoContentOrToolCalls(response);
    }

    private SystemMessage emptyFinalResponseControlMessage() {
        return new SystemMessage("""
            Your previous response had thinking but no user-visible message and no tool calls.
            Continue the turn now with a concise user-visible answer or an appropriate tool call.
            Do not return an empty assistant message.
            """.trim());
    }

    // ── Tool transcript / malformed calls ──

    private List<MalformedToolCall> malformedToolCalls(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return List.of();
        }
        List<MalformedToolCall> malformed = new ArrayList<>();
        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        for (AssistantMessage.ToolCall toolCall : toolCalls == null ? List.<AssistantMessage.ToolCall>of() : toolCalls) {
            String arguments = toolCall.arguments();
            if (!StringUtils.hasText(arguments)) {
                malformed.add(new MalformedToolCall(
                    toolCall.id(),
                    toolCall.name(),
                    arguments,
                    "No argument JSON was provided."
                ));
                continue;
            }
            try {
                objectMapper.readTree(arguments);
            } catch (JsonProcessingException exception) {
                malformed.add(new MalformedToolCall(
                    toolCall.id(),
                    toolCall.name(),
                    arguments,
                    exception.getOriginalMessage()
                ));
            }
        }
        return malformed;
    }

    private SystemMessage malformedToolCallControlMessage(List<MalformedToolCall> malformedToolCalls) {
        String details = malformedToolCalls.stream()
            .map(call -> "tool=" + valueOrUnknown(call.name())
                + ", callId=" + valueOrUnknown(call.id())
                + ", parser=" + compactParserMessage(call.parserMessage()))
            .collect(Collectors.joining("; "));
        return new SystemMessage(
            "One or more tool calls had malformed JSON arguments, so no tools in that batch were executed. "
                + "Bad call details: " + details + ". "
                + "Retry with valid JSON object arguments or choose the next action."
        );
    }

    private String valueOrUnknown(String value) {
        return StringUtils.hasText(value) ? value.trim() : "unknown";
    }

    private String compactParserMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "invalid JSON";
        }
        String compact = message.replaceAll("\\s+", " ").trim();
        return compact.length() > 240 ? compact.substring(0, 240) + " [truncated]" : compact;
    }

    private List<ToolTranscriptEntry> toolTranscriptEntries(
        org.springframework.ai.chat.model.ChatResponse response,
        ToolExecutionResult toolExecutionResult
    ) {
        if (toolExecutionResult == null || toolExecutionResult.conversationHistory() == null) {
            return List.of();
        }
        List<AssistantMessage.ToolCall> toolCalls = response.getResult().getOutput().getToolCalls();
        Map<String, AssistantMessage.ToolCall> callsById = toolCalls.stream()
            .collect(Collectors.toMap(
                AssistantMessage.ToolCall::id,
                java.util.function.Function.identity(),
                (left, right) -> left
            ));

        ToolResponseMessage latestToolResponseMessage = null;
        for (Message message : toolExecutionResult.conversationHistory()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                latestToolResponseMessage = toolResponseMessage;
            }
        }
        if (latestToolResponseMessage == null) {
            return List.of();
        }

        return latestToolResponseMessage.getResponses().stream()
            .map(toolResponse -> {
                AssistantMessage.ToolCall toolCall = callsById.get(toolResponse.id());
                String arguments = toolCall == null ? "" : toolCall.arguments();
                return toolTranscriptService.fullResultEntry(
                    toolResponse.id(),
                    toolResponse.name(),
                    arguments,
                    toolResponse.responseData()
                );
            })
            .toList();
    }

    // ── Message rendering ──

    public ChatMessage renderAssistantMessage(String text) {
        MessageParts messageParts = splitThinkingFallback(text == null ? "" : text);
        return renderAssistantMessage(messageParts);
    }

    public ChatMessage renderAssistantMessage(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null || response.getResult() == null) {
            return renderAssistantMessage("");
        }
        return renderAssistantMessage(response.getResult());
    }

    private ChatMessage renderAssistantMessage(org.springframework.ai.chat.model.Generation generation) {
        if (generation == null) {
            return renderAssistantMessage("");
        }
        String text = generation.getOutput() == null || generation.getOutput().getText() == null
            ? ""
            : generation.getOutput().getText();
        String thinking = thinkingText(generation);
        MessageParts messageParts = StringUtils.hasText(thinking)
            ? new MessageParts(text, thinking)
            : splitThinkingFallback(text);
        return renderAssistantMessage(messageParts);
    }

    private ChatMessage renderAssistantMessage(MessageParts messageParts) {
        String visibleText = messageParts.visibleText();
        String thinkingText = messageParts.thinkingText();
        return new ChatMessage(
            "assistant",
            visibleText,
            chatMarkdownRenderer.render(visibleText),
            StringUtils.hasText(thinkingText) ? chatMarkdownRenderer.render(thinkingText) : null
        );
    }

    private ChatMessage userMessage(String text) {
        return new ChatMessage("user", text, chatMarkdownRenderer.render(text), null);
    }

    private ChatMessage systemMessage(String text) {
        return new ChatMessage("system", text, chatMarkdownRenderer.render(text), null);
    }

    public ChatMessage systemNotice(String text) {
        return systemMessage(text);
    }

    private ChatMessage toolMessage(Message message) {
        String text = toolTranscriptService.renderForHistory(message);
        return new ChatMessage(
            "tool",
            text,
            chatMarkdownRenderer.render(text),
            null,
            toolTranscriptService.activityFor(message)
        );
    }

    // ── History rendering ──

    private List<ChatMessage> toHistory(List<Message> messages) {
        return messages.stream()
            .filter(message -> contextManagementAdvisor == null || !contextManagementAdvisor.isHiddenSummary(message))
            .map(message -> {
                if (toolTranscriptService != null && toolTranscriptService.isToolTranscript(message)) {
                    return toolMessage(message);
                }
                String role = message.getMessageType().getValue();
                String sourceText = contextManagementAdvisor != null && contextManagementAdvisor.isCompactionNotice(message)
                    ? contextManagementAdvisor.visibleNoticeText(message)
                    : (message.getText() == null ? "" : message.getText());
                MessageParts messageParts = isAssistantRole(role)
                    ? assistantMessageParts(message, sourceText)
                    : new MessageParts(sourceText, "");
                String visibleText = messageParts.visibleText();
                String thinkingText = messageParts.thinkingText();

                return new ChatMessage(
                    role,
                    visibleText,
                    chatMarkdownRenderer.render(visibleText),
                    StringUtils.hasText(thinkingText) ? chatMarkdownRenderer.render(thinkingText) : null
                );
            })
            .toList();
    }

    private boolean isAssistantRole(String role) {
        return "assistant".equalsIgnoreCase(role);
    }

    // ── Thinking extraction ──

    private MessageParts assistantMessageParts(Message message, String sourceText) {
        String thinkingText = message == null || message.getMetadata() == null
            ? null
            : stringValue(message.getMetadata().get(MESSAGE_THINKING_METADATA_KEY));
        return StringUtils.hasText(thinkingText)
            ? new MessageParts(sourceText, thinkingText)
            : splitThinkingFallback(sourceText);
    }

    AssistantMessage assistantMessageWithThinking(org.springframework.ai.chat.model.Generation generation) {
        return assistantMessageWithThinking(generation, thinkingText(generation));
    }

    AssistantMessage assistantMessageWithThinking(
        org.springframework.ai.chat.model.Generation generation,
        String thinking
    ) {
        AssistantMessage output = generation == null ? null : generation.getOutput();
        if (output == null) {
            return new AssistantMessage("");
        }
        if (!StringUtils.hasText(thinking)) {
            return output;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(output.getMetadata());
        metadata.put(MESSAGE_THINKING_METADATA_KEY, thinking);
        return AssistantMessage.builder()
            .content(output.getText())
            .properties(metadata)
            .toolCalls(output.getToolCalls())
            .media(output.getMedia())
            .build();
    }

    private void collectThinking(org.springframework.ai.chat.model.ChatResponse response, List<String> thinkingParts) {
        if (response == null || response.getResult() == null) {
            return;
        }
        String thinking = thinkingText(response.getResult());
        if (StringUtils.hasText(thinking)) {
            thinkingParts.add(thinking.trim());
        }
    }

    private String combinedThinking(List<String> thinkingParts) {
        return thinkingParts.stream()
            .filter(StringUtils::hasText)
            .collect(Collectors.joining("\n\n"));
    }

    static String thinkingText(org.springframework.ai.chat.model.Generation generation) {
        if (generation.getMetadata() != null) {
            String thinking = stringValue(generation.getMetadata().get(THINKING_METADATA_KEY));
            if (thinking != null) return thinking;
        }
        org.springframework.ai.chat.messages.AssistantMessage output = generation.getOutput();
        if (output instanceof DeepSeekAssistantMessage deepSeekMessage) {
            String reasoning = deepSeekMessage.getReasoningContent();
            if (StringUtils.hasText(reasoning)) {
                return reasoning;
            }
        }
        if (output != null && output.getMetadata() != null) {
            String reasoning = stringValue(output.getMetadata().get("reasoningContent"));
            return reasoning;
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private MessageParts splitThinkingFallback(String text) {
        Matcher matcher = THINK_TAG_PATTERN.matcher(text);
        StringBuilder visible = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        int lastEnd = 0;
        boolean foundAny = false;

        while (matcher.find()) {
            foundAny = true;
            visible.append(text, lastEnd, matcher.start());
            String innerThinking = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (!innerThinking.isEmpty()) {
                if (!thinking.isEmpty()) {
                    thinking.append("\n\n");
                }
                thinking.append(innerThinking);
            }
            lastEnd = matcher.end();
        }

        if (!foundAny) {
            return new MessageParts(text, "");
        }

        visible.append(text.substring(lastEnd));
        return new MessageParts(visible.toString().trim(), thinking.toString().trim());
    }

    // ── Compaction helpers ──

    private SystemMessage compactionNoticeMessage() {
        return new SystemMessage(ContextManagementAdvisor.NOTICE_PREFIX + ContextManagementAdvisor.COMPACTION_NOTICE);
    }

    private boolean hasCompactionNotice(String conversationId) {
        if (chatMemoryRepository == null || contextManagementAdvisor == null) {
            return false;
        }
        return chatMemoryRepository.findByConversationId(conversationId).stream()
            .anyMatch(contextManagementAdvisor::isCompactionNotice);
    }

    // ── Conversation snapshot / restore ──

    private List<Message> snapshotConversation(String conversationId) {
        if (!StringUtils.hasText(conversationId) || chatMemoryRepository == null) {
            return List.of();
        }
        return new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
    }

    private void restoreConversation(String conversationId, List<Message> snapshot) {
        if (!StringUtils.hasText(conversationId) || chatMemoryRepository == null) {
            return;
        }
        chatMemoryRepository.saveAll(conversationId, snapshot);
    }

    private boolean isRetryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ResourceAccessException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ── Data carriers ──

    private record MessageParts(String visibleText, String thinkingText) {
    }

    private record ToolChatResult(ChatResponse.MsgResponse response, ChatMessage finalMessage) {
    }

    private record MalformedToolCall(String id, String name, String arguments, String parserMessage) {
    }

    // ── Tool loop state ──

    private static final class ToolLoopState {
        // ── Immutable request context ──
        final ResolvedChatRequest request;
        final List<ToolCallback> approvedTools;
        final Consumer<ChatMessage> toolMessageConsumer;
        final ToolCallingChatOptions toolOptions;
        List<Message> currentSystemInstructions;

        // ── Current phase ──
        TurnPhase phase = TurnPhase.PREPARE;

        // ── Mutable prompt/response ──
        Prompt prompt;
        org.springframework.ai.chat.model.ChatResponse response;

        // ── Accumulators ──
        final List<Message> messagesToPersist = new ArrayList<>();
        final List<ChatToolActivity> toolActivities = new ArrayList<>();
        final List<String> thinkingParts = new ArrayList<>();

        // ── Tool execution state ──
        final ToolLoopGuard toolLoopGuard = new ToolLoopGuard();
        List<Message> activeToolMessages = new ArrayList<>();
        List<Message> conversationHistory = null;
        ToolUseAbort toolUseAbort = null;

        // ── Flags and counters ──
        boolean compactionNoticeEmitted = false;
        boolean planCompletionDetected = false;
        String validatedFinalMessage = null;
        int emptyFinalResponseRetries = 0;

        // ── Output (set by FINALIZE, returned by dispatcher) ──
        ChatResponse.MsgResponse chatResponse;
        ChatMessage finalMessage;

        ToolLoopState(
            ResolvedChatRequest request,
            List<ToolCallback> approvedTools,
            Consumer<ChatMessage> toolMessageConsumer,
            ToolCallingChatOptions toolOptions,
            List<Message> currentSystemInstructions
        ) {
            this.request = request;
            this.approvedTools = approvedTools;
            this.toolMessageConsumer = toolMessageConsumer;
            this.toolOptions = toolOptions;
            this.currentSystemInstructions = currentSystemInstructions;
        }
    }
}
