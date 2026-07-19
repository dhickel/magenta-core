package io.mindspice.magenta2.ai.chat.service;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import org.springframework.web.client.ResourceAccessException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.agent.job.AgentJobService;
import io.mindspice.magenta2.ai.agent.job.AgentJobStatus;
import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin;
import io.mindspice.magenta2.ai.chat.model.ChatToolActivity;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.model.PlanMode;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanToolContext;
import io.mindspice.magenta2.ai.chat.plan.PlanToolExecutionContext;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.turn.PromptContextAssembler;
import io.mindspice.magenta2.ai.chat.service.turn.TerminalTurnRepair;
import io.mindspice.magenta2.ai.chat.service.turn.ToolAccessPolicy;
import io.mindspice.magenta2.ai.chat.service.turn.TurnAuditWriter;
import io.mindspice.magenta2.ai.chat.service.turn.TurnDiagnostic;
import io.mindspice.magenta2.ai.chat.service.turn.TurnPhase;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService.ToolTranscriptEntry;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.execution.ActiveTurnPhase;
import io.mindspice.magenta2.ai.execution.ActiveTurnRegistry.ActiveTurn;
import io.mindspice.magenta2.ai.execution.ConversationTurnCoordinator;
import io.mindspice.magenta2.ai.execution.MagentaWorkRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolver;
import io.mindspice.magenta2.ai.skills.AgentSkillActivationService;
import io.mindspice.magenta2.ai.skills.AgentSkillPromptCatalogAssembler;
import io.mindspice.magenta2.ai.skills.AgentSkillRuntimeCatalogService;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final int PLAN_TURN_REPAIR_RETRY_LIMIT = 2;
    private static final int EXECUTION_COMPLETION_REPAIR_RETRY_LIMIT = 2;
    private static final String OLLAMA_TOOLS_UNSUPPORTED_MESSAGE = "does not support tools";
    // Backward-compatible references to ToolAccessPolicy constants
    public static final List<String> PLAN_MODE_TOOLS = ToolAccessPolicy.PLAN_MODE_TOOLS;
    public static final List<String> TASK_MODE_TOOLS = ToolAccessPolicy.TASK_MODE_TOOLS;
    private static final String EXECUTE_PLAN_MESSAGE = "Execute the saved plan now. Work through the plan directly and report the completed result.";
    private static final String EXECUTE_PLAN_CLEAN_MESSAGE = "Execute the approved anonymous chat plan now using only the approved plan instructions and the persistent chat file directory context. Do not rely on earlier chat transcript details unless they are present in the approved plan.";
    private static final String PLAN_NEEDS_REVIEW_MESSAGE = "Plan execution needs review. Magenta could not verify completion through plan_complete after retrying. Review the saved execution evidence and validation feedback before trusting the result.";
    private static final String PLAN_ANSWER_MODEL_FAILURE_MESSAGE = "I saved your planning answer, but Magenta could not continue the planning turn because the configured planning model request failed. Check the selected planning model and API credentials, then send another planning message to continue.";
    private static final String PLAN_ANSWER_CONTINUATION_FAILURE_MESSAGE = "I saved your planning answer, but Magenta could not continue the planning turn because a planning tool or model call failed. Check the unavailable service or model configuration, then send another planning message to continue.";
    private static final String EXECUTE_TASK_MESSAGE = """
        Execute the reusable task now using the provided runtime inputs.
        Work through the declared task steps directly. You must call task_complete with outputValues keyed exactly by declared output name before any final completion answer.
        If you cannot complete the task, call task_report with the evidence gathered and explain the missing output, then continue until task_complete succeeds or Magenta marks the run for review.
        """.trim();
    private static final String BEGIN_PLAN_MESSAGE = "The user is ready to plan. The backend has collected the opening answers; continue planning from that seed context.";
    private static final String CONTINUE_PLAN_MESSAGE = "The user opened an existing plan in planning chat. Review the loaded structured state and ask the next focused question needed to improve or complete the plan.";

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
    private final PlanService planService;
    private final TaskService taskService;
    private final AgentJobService agentJobService;
    private final ConversationTurnCoordinator turnCoordinator;
    private final AuditRepository auditRepository;
    private final ObjectMapper objectMapper;
    private final RuntimeSettingsService runtimeSettingsService;
    private final AuditService auditService;
    private final RequestResolver requestResolver;
    private final ChatFileService chatFileService;
    private final ChatPendingMessageService chatPendingMessageService;
    private final AgentSkillActivationService agentSkillActivationService;
    private final Set<String> toolUnsupportedModels = ConcurrentHashMap.newKeySet();
    private final Map<String, Semaphore> streamLocks = new ConcurrentHashMap<>();

    // Extracted turn components (Plan 01 — Chat Turn Orchestration)
    private final PromptContextAssembler promptAssembler;
    private final ToolAccessPolicy toolAccessPolicy;
    private final TerminalTurnRepair turnRepair;
    private final TurnAuditWriter turnAuditWriter;

    public ChatService(
        ChatMemory chatMemory,
        ChatMemoryRepository chatMemoryRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatMarkdownRenderer chatMarkdownRenderer,
        AiConfig aiConfig
    ) {
        this(
            chatMemory,
            chatMemoryRepository,
            chatSessionMetadataRepository,
            chatMarkdownRenderer,
            aiConfig,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

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
        PlanService planService,
        TaskService taskService,
        AgentJobService agentJobService,
        ConversationTurnCoordinator turnCoordinator,
        AuditRepository auditRepository,
        ObjectMapper objectMapper,
        RuntimeSettingsService runtimeSettingsService,
        AuditService auditService,
        RequestResolver requestResolver,
        ChatFileService chatFileService,
        ChatPendingMessageService chatPendingMessageService,
        io.mindspice.magenta2.ai.chat.plan.WorkTypeProfileService workTypeProfileService,
        AgentsMdResolver agentsMdResolver
    ) {
        this(
            chatMemory,
            chatMemoryRepository,
            chatSessionMetadataRepository,
            chatMarkdownRenderer,
            aiConfig,
            contextManagementAdvisor,
            contextUsageTracker,
            chatModelRouter,
            toolCallingManager,
            chatToolRegistry,
            toolTranscriptService,
            planService,
            taskService,
            agentJobService,
            turnCoordinator,
            auditRepository,
            objectMapper,
            runtimeSettingsService,
            auditService,
            requestResolver,
            chatFileService,
            chatPendingMessageService,
            workTypeProfileService,
            agentsMdResolver,
            null,
            null,
            null
        );
    }

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
        PlanService planService
    ) {
        this(
            chatMemory,
            chatMemoryRepository,
            chatSessionMetadataRepository,
            chatMarkdownRenderer,
            aiConfig,
            contextManagementAdvisor,
            contextUsageTracker,
            chatModelRouter,
            toolCallingManager,
            chatToolRegistry,
            toolTranscriptService,
            planService,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            aiConfig != null && chatSessionMetadataRepository != null
                ? new RequestResolver(aiConfig, chatSessionMetadataRepository, chatMemoryRepository, planService, null, null)
                : null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Autowired
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
        @Autowired(required = false) PlanService planService,
        @Autowired(required = false) TaskService taskService,
        @Autowired(required = false) AgentJobService agentJobService,
        @Autowired(required = false) ConversationTurnCoordinator turnCoordinator,
        @Autowired(required = false) AuditRepository auditRepository,
        @Autowired(required = false) ObjectMapper objectMapper,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService,
        @Autowired(required = false) AuditService auditService,
        @Autowired(required = false) RequestResolver requestResolver,
        @Autowired(required = false) ChatFileService chatFileService,
        @Autowired(required = false) ChatPendingMessageService chatPendingMessageService,
        @Autowired(required = false) io.mindspice.magenta2.ai.chat.plan.WorkTypeProfileService workTypeProfileService,
        @Autowired(required = false) AgentsMdResolver agentsMdResolver,
        @Autowired(required = false) AgentSkillRuntimeCatalogService skillRuntimeCatalogService,
        @Autowired(required = false) AgentSkillPromptCatalogAssembler skillPromptCatalogAssembler,
        @Autowired(required = false) AgentSkillActivationService agentSkillActivationService
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
        this.planService = planService;
        this.taskService = taskService;
        this.agentJobService = agentJobService;
        this.turnCoordinator = turnCoordinator;
        this.auditRepository = auditRepository;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.runtimeSettingsService = runtimeSettingsService;
        this.auditService = auditService;
        this.requestResolver = requestResolver;
        this.chatFileService = chatFileService;
        this.chatPendingMessageService = chatPendingMessageService;
        this.agentSkillActivationService = agentSkillActivationService;

        // Initialize extracted turn components
        this.promptAssembler = new PromptContextAssembler(
            aiConfig,
            runtimeSettingsService,
            planService,
            taskService,
            workTypeProfileService,
            agentsMdResolver,
            skillPromptCatalogAssembler
        );
        this.toolAccessPolicy = new ToolAccessPolicy(chatToolRegistry, planService, taskService, skillRuntimeCatalogService);
        this.turnRepair = new TerminalTurnRepair(planService, taskService);
        this.turnAuditWriter = new TurnAuditWriter(auditService, auditRepository);
    }

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
        PlanService planService,
        TaskService taskService,
        AgentJobService agentJobService,
        ConversationTurnCoordinator turnCoordinator,
        AuditRepository auditRepository,
        ObjectMapper objectMapper,
        RuntimeSettingsService runtimeSettingsService,
        AuditService auditService,
        RequestResolver requestResolver,
        ChatFileService chatFileService,
        ChatPendingMessageService chatPendingMessageService,
        io.mindspice.magenta2.ai.chat.plan.WorkTypeProfileService workTypeProfileService
    ) {
        this(
            chatMemory,
            chatMemoryRepository,
            chatSessionMetadataRepository,
            chatMarkdownRenderer,
            aiConfig,
            contextManagementAdvisor,
            contextUsageTracker,
            chatModelRouter,
            toolCallingManager,
            chatToolRegistry,
            toolTranscriptService,
            planService,
            taskService,
            agentJobService,
            turnCoordinator,
            auditRepository,
            objectMapper,
            runtimeSettingsService,
            auditService,
            requestResolver,
            chatFileService,
            chatPendingMessageService,
            workTypeProfileService,
            null,
            null,
            null,
            null
        );
    }

    public ChatResponse chat(ChatRequest request) {
        if (!(request instanceof ChatRequest.MsgRequest msgRequest)) {
            throw new IllegalArgumentException("message request is required");
        }
        ResolvedChatRequest resolvedRequest = requestResolver.resolve(msgRequest);
        return chat(resolvedRequest);
    }

    public ChatResponse.MsgResponse chat(String conversationId, String message, String model) {
        ResolvedChatRequest resolvedRequest = requestResolver.resolve(conversationId, message, model, null);
        return chat(resolvedRequest);
    }

    private ChatResponse.MsgResponse chat(ResolvedChatRequest resolvedRequest) {
        return chat(resolvedRequest, null, null);
    }

    private ChatResponse.MsgResponse chat(
        ResolvedChatRequest resolvedRequest,
        List<String> approvedToolNames,
        OrchestrationTaskContext turnContext
    ) {
        if (turnCoordinator != null) {
            return await(turnCoordinator.submit(
                resolvedRequest.conversationId(),
                MagentaWorkRequest.CHAT_PRIORITY,
                "chat turn " + resolvedRequest.conversationId(),
                () -> chatNow(resolvedRequest, null, approvedToolNames, turnContext)
            ));
        }
        return chatNow(resolvedRequest, null, approvedToolNames, turnContext);
    }

    private ChatResponse.MsgResponse chatNow(ResolvedChatRequest resolvedRequest, ActiveTurn activeTurn) {
        return chatNow(resolvedRequest, activeTurn, null, null);
    }

    private ChatResponse.MsgResponse chatNow(
        ResolvedChatRequest resolvedRequest,
        ActiveTurn activeTurn,
        List<String> approvedToolNames,
        OrchestrationTaskContext turnContext
    ) {
        OrchestrationTaskContext previousContext = OrchestrationTaskContextHolder.current();
        boolean installedTurnContext = false;
        if (turnContext != null) {
            OrchestrationTaskContextHolder.set(turnContext);
            installedTurnContext = true;
        }
        boolean installedChatContext = turnContext == null
            && installChatFileContextIfNeeded(resolvedRequest.conversationId(), previousContext);
        try {
            List<ToolCallback> approvedTools = approvedTools(resolvedRequest, approvedToolNames);
            if (!approvedTools.isEmpty() && supportsTools(resolvedRequest.model())) {
                try {
                    return toolChatWithRetry(resolvedRequest, approvedTools, activeTurn);
                } catch (NonTransientAiException exception) {
                    if (!isToolUnsupported(exception)) {
                        throw exception;
                    }
                    rememberToolUnsupportedModel(resolvedRequest.model());
                }
            }
            return plainChat(resolvedRequest);
        } finally {
            restoreContext(previousContext, installedTurnContext || installedChatContext);
        }
    }

    public ChatResponse chatAsAgent(AgentProfile agent, ChatRequest.MsgRequest request) {
        if (agent == null) {
            throw new IllegalArgumentException("agent is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("message request is required");
        }
        String model = StringUtils.hasText(request.model()) ? request.model() : agent.defaultModel();
        if (!StringUtils.hasText(model)) {
            model = defaultModel();
        }
        ResolvedChatRequest resolvedRequest = requestResolver.resolve(new ChatRequest.MsgRequest(
            request.conversationId(),
            request.message(),
            model,
            request.planningModel()
        ));
        markAgentConversation(resolvedRequest.conversationId(), agent.id());
        OrchestrationTaskContext turnContext = new OrchestrationTaskContext(
            agent.id(), agent.name(), null, null, null, null,
            null, null, null, "AGENT_CHAT", null, null, null
        );
        List<String> agentApprovedTools = agent.approvedTools() == null ? List.of() : agent.approvedTools();
        return chat(resolvedRequest, agentApprovedTools, turnContext);
    }

    private boolean installChatFileContextIfNeeded(String conversationId, OrchestrationTaskContext previousContext) {
        if (previousContext != null || planService == null || !StringUtils.hasText(conversationId)) {
            return false;
        }
        try {
            Path dir = planService.chatFileDirectory(conversationId).toRealPath();
            OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
                null, null, null, null, null, null, null, null, null, "CHAT", null, dir.toString(), null
            ));
            return true;
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private void restoreContext(OrchestrationTaskContext previousContext, boolean installedChatContext) {
        if (!installedChatContext) {
            return;
        }
        if (previousContext == null) {
            OrchestrationTaskContextHolder.clear();
        } else {
            OrchestrationTaskContextHolder.set(previousContext);
        }
    }

    private String approvedPlanExecutionMessage(String conversationId, boolean cleanContext) {
        String markdown = planService.approvalMarkdown(conversationId);
        String files = "";
        try {
            files = "\n\nPersistent chat file directory: " + planService.chatFileDirectory(conversationId).toRealPath();
        } catch (Exception e) {
            logger.warn("Failed to resolve chat file directory for conversation: {}", conversationId, e);
        }
        return (cleanContext ? EXECUTE_PLAN_CLEAN_MESSAGE : EXECUTE_PLAN_MESSAGE)
            + "\n\nApproved anonymous plan:\n\n" + markdown + files;
    }

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
            planState(resolvedRequest.conversationId())
        );
    }

    public ResolvedChatRequest resolve(ChatRequest request) {
        return requestResolver.resolve(request);
    }

    public Flux<ChatMessage> stream(ResolvedChatRequest request) {
        return stream(request, null);
    }

    public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
        if (turnCoordinator != null) {
            return Flux.defer(() -> {
                Semaphore lock = streamLocks.computeIfAbsent(
                    request.conversationId(), k -> new Semaphore(1));
                if (!lock.tryAcquire()) {
                    return Flux.error(new IllegalStateException(
                        "Another stream is already active for conversation " + request.conversationId()));
                }
                return streamNow(request, activeTurn)
                    .doFinally(signal -> {
                        lock.release();
                        streamLocks.compute(request.conversationId(), (k, v) ->
                            v != null && v.availablePermits() > 0 ? null : v);
                    });
            });
        }
        return streamNow(request, activeTurn);
    }

    private Flux<ChatMessage> streamNow(ResolvedChatRequest request, ActiveTurn activeTurn) {
        return Flux.defer(() -> {
            OrchestrationTaskContext previousContext = OrchestrationTaskContextHolder.current();
            boolean installedChatContext = installChatFileContextIfNeeded(request.conversationId(), previousContext);
            return streamNowWithContext(request, activeTurn)
                .doFinally(signal -> restoreContext(previousContext, installedChatContext));
        });
    }

    private Flux<ChatMessage> streamNowWithContext(ResolvedChatRequest request, ActiveTurn activeTurn) {
        List<ToolCallback> approvedTools = approvedTools(request);
        if (!approvedTools.isEmpty() && supportsTools(request.model())) {
            // Try the tool-capable path first. Models that reject tools are remembered and use plain chat later.
            return Flux.<ChatMessage>create(sink -> {
                try {
                    ChatMessage finalMessage = toolChatMessageWithRetry(request, approvedTools, sink::next, activeTurn);
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

    public void clearConversation(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            chatMemory.clear(conversationId);
            chatSessionMetadataRepository.deleteByConversationId(conversationId);
            if (planService != null) {
                planService.exitPlan(conversationId);
            }
            if (contextUsageTracker != null) {
                contextUsageTracker.clear(conversationId);
            }
            if (chatPendingMessageService != null) {
                chatPendingMessageService.deleteByConversationId(conversationId);
            }
            if (agentSkillActivationService != null) {
                agentSkillActivationService.clearConversationActivations(conversationId);
            }
        }
    }

    public ChatResponse.MsgResponse beginPlan(String conversationId) {
        return beginPlan(conversationId, null, null);
    }

    public ChatResponse.MsgResponse beginPlan(String conversationId, String selectedModel, String planningModel) {
        return beginPlan(conversationId, selectedModel, planningModel, null);
    }

    public ChatResponse.MsgResponse beginPlan(
        String conversationId,
        String selectedModel,
        String planningModel,
        String userInstruction
    ) {
        requirePlanService();
        String prePlanningModel = StringUtils.hasText(selectedModel)
            ? selectedModel
            : storedConversationModel(conversationId);
        if (!StringUtils.hasText(prePlanningModel)) {
            prePlanningModel = defaultModel();
        }
        String executionModel = StringUtils.hasText(planningModel)
            ? planningModel
            : resolvedPlanningModel(conversationId);
        planService.beginPlan(conversationId, prePlanningModel, executionModel);
        if (StringUtils.hasText(userInstruction)) {
            planService.recordPromptAnswer(conversationId, userInstruction, null);
        }
        return new ChatResponse.MsgResponse(
            conversationId,
            executionModel,
            "",
            maintainContextUsage(conversationId, executionModel).usage(),
            planState(conversationId)
        );
    }

    public ChatResponse.MsgResponse beginPlanFromDefinition(
        String conversationId,
        String planId,
        String selectedModel,
        String planningModel
    ) {
        return beginPlanFromDefinition(conversationId, planId, selectedModel, planningModel, null);
    }

    public ChatResponse.MsgResponse beginPlanFromDefinition(
        String conversationId,
        String planId,
        String selectedModel,
        String planningModel,
        String userInstruction
    ) {
        requirePlanService();
        if (!StringUtils.hasText(planId)) {
            throw new IllegalArgumentException("planId is required");
        }
        String prePlanningModel = StringUtils.hasText(selectedModel)
            ? selectedModel
            : storedConversationModel(conversationId);
        if (!StringUtils.hasText(prePlanningModel)) {
            prePlanningModel = defaultModel();
        }
        String executionModel = StringUtils.hasText(planningModel)
            ? planningModel
            : resolvedPlanningModel(conversationId);
        PlanDefinition source = planService.getTask(planId);
        planService.beginPlanFromDefinition(conversationId, source, prePlanningModel, executionModel);
        String continuationMessage = CONTINUE_PLAN_MESSAGE;
        if (StringUtils.hasText(userInstruction)) {
            continuationMessage += " The user also asked: " + userInstruction.trim();
        }
        return chat(requestResolver.resolve(conversationId, continuationMessage, executionModel, null).withoutTitleJob());
    }

    public void exitPlan(String conversationId) {
        requirePlanService();
        String prePlanningModel = planService.prePlanningModel(conversationId);
        planService.exitPlan(conversationId);
        if (StringUtils.hasText(prePlanningModel)) {
            chatSessionMetadataRepository.saveModel(conversationId, prePlanningModel);
        }
        if (contextUsageTracker != null) {
            contextUsageTracker.clear(conversationId);
        }
    }

    public ChatResponse.MsgResponse submitPlanAnswer(String conversationId, String answer, String notes) {
        return submitPlanAnswer(conversationId, answer, notes, null);
    }

    public ChatResponse.MsgResponse submitPlanAnswer(String conversationId, String answer, String notes, Integer questionIndex) {
        requirePlanService();
        PlanDefinition plan;
        try {
            plan = planService.recordPromptAnswer(conversationId, answer, notes, questionIndex);
        } catch (IllegalStateException exception) {
            if (!isRecoverablePlanningAnswerConflict(exception) || planService.mode(conversationId) != PlanMode.PLAN) {
                throw exception;
            }
            String model = resolvedPlanningModel(conversationId);
            return savedAnswerFailureResponse(
                conversationId,
                model,
                "That planning answer was for an older prompt. I refreshed the current planning prompt so you can continue."
            );
        }
        if (plan.hasPendingQuestion()) {
            String model = resolvedPlanningModel(conversationId);
            return new ChatResponse.MsgResponse(
                conversationId,
                model,
                "",
                maintainContextUsage(conversationId, model).usage(),
                planState(conversationId)
            );
        }
        String continueModel = resolvedPlanningModel(conversationId);
        try {
            return chat(requestResolver.resolve(
                conversationId,
                "Continue planning from the three opening answers stored in the structured planning state. Treat those answers as authoritative seed context and ask only the next useful clarification if the plan is not ready.",
                continueModel,
                null
            ).withoutTitleJob());
        } catch (NonTransientAiException exception) {
            logger.warn("Planning answer continuation failed conv={} model={}: {}",
                conversationId, continueModel, exception.getMessage());
            if (auditService != null) {
                auditService.recordError(conversationId, "planning_model_failure",
                    safeAiFailureMessage(exception), stackTraceString(exception), continueModel);
            }
            return savedAnswerFailureResponse(conversationId, continueModel, PLAN_ANSWER_MODEL_FAILURE_MESSAGE);
        } catch (RuntimeException exception) {
            logger.warn("Planning answer continuation failed conv={} model={}: {}",
                conversationId, continueModel, exception.getMessage(), exception);
            if (auditService != null) {
                auditService.recordError(conversationId, "planning_continuation_failure",
                    safeAiFailureMessage(exception), stackTraceString(exception), continueModel);
            }
            return savedAnswerFailureResponse(conversationId, continueModel, PLAN_ANSWER_CONTINUATION_FAILURE_MESSAGE);
        }
    }

    private boolean isRecoverablePlanningAnswerConflict(IllegalStateException exception) {
        if (exception == null || !StringUtils.hasText(exception.getMessage())) {
            return false;
        }
        String message = exception.getMessage();
        return message.contains("No active planning question exists")
            || message.contains("Stale planning answer.");
    }

    private ChatResponse.MsgResponse savedAnswerFailureResponse(String conversationId, String model, String message) {
        ensureRecoverablePlanningPrompt(conversationId);
        chatMemory.add(conversationId, List.of(new AssistantMessage(message)));
        return new ChatResponse.MsgResponse(
            conversationId,
            model,
            message,
            maintainContextUsage(conversationId, model).usage(),
            planState(conversationId)
        );
    }

    private void ensureRecoverablePlanningPrompt(String conversationId) {
        if (planService == null || planService.mode(conversationId) != PlanMode.PLAN) {
            return;
        }
        ChatPlanState state = planService.view(conversationId);
        if (!"DRAFT".equals(state.status()) || StringUtils.hasText(state.promptQuestion())) {
            return;
        }
        planService.askQuestions(conversationId, List.of(
            "What should we clarify, change, or add before continuing this plan?"
        ));
    }

    public ChatPlanState approvePlan(String conversationId) {
        requirePlanService();
        planService.approvePlan(conversationId);
        return planState(conversationId);
    }

    public ChatPlanState continuePlanning(String conversationId) {
        requirePlanService();
        planService.askQuestions(
            conversationId,
            List.of("What should we clarify, change, or add before approving this plan?")
        );
        return planState(conversationId);
    }

    public ChatResponse.MsgResponse executeSavedPlan(String conversationId, boolean clearContext) {
        return executeSavedPlan(conversationId, clearContext, true);
    }

    public ChatResponse.MsgResponse executeSavedPlan(String conversationId) {
        return executeSavedPlan(conversationId, false, true);
    }

    private ChatResponse.MsgResponse executeSavedPlan(String conversationId, boolean clearContext, boolean persistFinalMessage) {
        ResolvedChatRequest request = resolveSavedPlanExecution(conversationId, clearContext);
        try {
            ChatResponse.MsgResponse response = chat(request);
            planService.recordFallbackExecutionEvidence(conversationId);
            if (planService.mode(conversationId) == PlanMode.EXECUTE_PLAN) {
                response = markPlanNeedsReviewResponse(response);
            }
            if (persistFinalMessage && isValidatedPlanCompletion(conversationId)) {
                planService.persistChatFinalMessage(conversationId, response.response());
            }
            return new ChatResponse.MsgResponse(
                response.conversationId(),
                response.model(),
                response.response(),
                response.contextUsage(),
                planState(conversationId),
                response.toolActivities()
            );
        } catch (RuntimeException exception) {
            recordExecutionFailure(conversationId, exception);
            throw new IllegalStateException("Plan execution failed: " + rootCauseMessage(exception), exception);
        }
    }

    public ResolvedChatRequest resolveSavedPlanExecution(String conversationId) {
        return resolveSavedPlanExecution(conversationId, false);
    }

    public ResolvedChatRequest resolveSavedPlanExecution(String conversationId, boolean clearContext) {
        requirePlanService();
        String model = planService.executionModel(conversationId);
        if (!StringUtils.hasText(model)) {
            model = planService.prePlanningModel(conversationId);
        }
        if (!StringUtils.hasText(model)) {
            model = storedConversationModel(conversationId);
        }
        if (!StringUtils.hasText(model)) {
            model = defaultModel();
        }
        if (contextUsageTracker != null) {
            contextUsageTracker.clear(conversationId);
        }
        planService.markExecuting(conversationId);
        ResolvedChatRequest request = requestResolver.resolve(
            conversationId,
            approvedPlanExecutionMessage(conversationId, clearContext),
            model,
            null
        ).withoutTitleJob();
        return clearContext ? request.omittingStoredMessages() : request;
    }

    public void handlePlanExecutionStreamFinished(String conversationId) {
        handlePlanExecutionStreamFinished(conversationId, null);
    }

    public void handlePlanExecutionStreamFinished(String conversationId, String finalMessage) {
        requirePlanService();
        planService.recordFallbackExecutionEvidence(conversationId);
        if (planService.mode(conversationId) == PlanMode.EXECUTE_PLAN) {
            planService.markNeedsReview(conversationId);
            persistControlledReviewMessage(conversationId, finalMessage);
        } else if (isValidatedPlanCompletion(conversationId)) {
            planService.persistChatFinalMessage(conversationId, planService.finalMessage(conversationId));
        }
    }

    public void recordExecutionFailure(String conversationId, RuntimeException exception) {
        requirePlanService();
        if (auditService != null) {
            auditService.recordError(
                conversationId, "plan_execution",
                rootCauseMessage(exception),
                stackTraceString(exception),
                null
            );
        }
        try {
            planService.recordExecutionReport(
                conversationId,
                "Execution failed before completion.",
                List.of(),
                List.of(rootCauseMessage(exception)),
                List.of("Saved plan execution did not complete."),
                List.of()
            );
            planService.markNeedsReview(conversationId);
        } catch (RuntimeException reportException) {
            exception.addSuppressed(reportException);
        }
    }

    public TaskExecutionResult executeTaskBlocking(String taskId, Map<String, Object> inputValues, String conversationId, String modelOverride) {
        requireTaskService();
        ResolvedTaskExecution execution = resolveTaskExecution(taskId, inputValues, conversationId, modelOverride);
        TaskRun started = taskService.getRun(execution.runId());
        if (started.status() != TaskRunStatus.RUNNING) {
            return new TaskExecutionResult(execution.conversationId(), started, null);
        }
        try {
            ChatResponse.MsgResponse response = chat(execution.request());
            TaskRun run = taskService.getRun(execution.runId());
            if (run.status() == TaskRunStatus.RUNNING) {
                run = taskService.markActiveRunNeedsReview(
                    execution.conversationId(),
                    "Task execution returned without calling task_complete."
                );
            }
            return new TaskExecutionResult(execution.conversationId(), run, response);
        } catch (RuntimeException exception) {
            try {
                TaskRun current = taskService.getRun(execution.runId());
                if (current.status() != TaskRunStatus.RUNNING) {
                    return new TaskExecutionResult(execution.conversationId(), current, null);
                }
                TaskRun failed = taskService.failActiveRun(execution.conversationId(), rootCauseMessage(exception));
                return new TaskExecutionResult(execution.conversationId(), failed, null);
            } catch (RuntimeException failException) {
                exception.addSuppressed(failException);
                throw exception;
            }
        }
    }

    public Flux<TaskExecutionEvent> streamTaskExecution(String taskId, Map<String, Object> inputValues, String conversationId, String modelOverride) {
        requireTaskService();
        ResolvedTaskExecution execution = resolveTaskExecution(taskId, inputValues, conversationId, modelOverride);
        return Flux.create(sink -> {
            sink.next(TaskExecutionEvent.started(execution.conversationId(), execution.runId()));
            TaskRun started = taskService.getRun(execution.runId());
            if (started.status() != TaskRunStatus.RUNNING) {
                sink.next(TaskExecutionEvent.finished(execution.conversationId(), started));
                sink.complete();
                return;
            }
            try {
                ChatMessage finalMessage = toolChatMessageWithRetry(
                    execution.request(),
                    approvedTools(execution.request()),
                    message -> sink.next(TaskExecutionEvent.message(execution.conversationId(), execution.runId(), message)),
                    null
                );
                if (finalMessage != null) {
                    sink.next(TaskExecutionEvent.message(execution.conversationId(), execution.runId(), finalMessage));
                }
                TaskRun run = taskService.getRun(execution.runId());
                if (run.status() == TaskRunStatus.RUNNING) {
                    run = taskService.markActiveRunNeedsReview(
                        execution.conversationId(),
                        "Task execution returned without calling task_complete."
                    );
                }
                sink.next(TaskExecutionEvent.finished(execution.conversationId(), run));
                sink.complete();
            } catch (RuntimeException exception) {
                TaskRun current = taskService.getRun(execution.runId());
                if (current.status() == TaskRunStatus.RUNNING) {
                    current = taskService.failActiveRun(execution.conversationId(), rootCauseMessage(exception));
                }
                sink.next(TaskExecutionEvent.finished(execution.conversationId(), current));
                sink.complete();
            }
        });
    }

    private ResolvedTaskExecution resolveTaskExecution(
        String taskId,
        Map<String, Object> inputValues,
        String conversationId,
        String modelOverride
    ) {
        String resolvedConversationId = StringUtils.hasText(conversationId) ? conversationId : UUID.randomUUID().toString();
        String storedModel = storedConversationModel(resolvedConversationId);
        String model = StringUtils.hasText(modelOverride)
            ? modelOverride
            : (StringUtils.hasText(storedModel) ? storedModel : defaultModel());
        if (contextUsageTracker != null) {
            contextUsageTracker.clear(resolvedConversationId);
        }
        OrchestrationTaskContext orchestrationContext = OrchestrationTaskContextHolder.current();
        TaskRun run = taskService.startChatExecution(
            resolvedConversationId,
            taskId,
            inputValues == null ? Map.of() : inputValues,
            orchestrationContext
        );
        ResolvedChatRequest request = requestResolver.resolve(resolvedConversationId, EXECUTE_TASK_MESSAGE, model, null).withoutTitleJob();
        return new ResolvedTaskExecution(resolvedConversationId, run.id(), request);
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

    private static String stackTraceString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    public ChatPlanState planState(String conversationId) {
        return planService == null ? ChatPlanState.normal() : planService.view(conversationId);
    }

    public List<String> listConversationIds() {
        return listSessions().stream()
            .map(ChatSession::conversationId)
            .toList();
    }

    private List<String> rawConversationIds() {
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

    public List<ChatSession> listSessions() {
        if (chatSessionMetadataRepository == null) {
            return List.of();
        }
        return chatSessionMetadataRepository.findBrowserConversationIds().stream()
            .map(this::session)
            .filter(session -> session.origin() != ChatSessionOrigin.AGENT_CHAT)
            .filter(session -> !session.archived())
            .filter(session -> interactionMode(session.conversationId()) == PlanMode.NORMAL)
            .sorted(java.util.Comparator
                .comparing(ChatSession::favorite).reversed()
                .thenComparing(
                    session -> session.updatedAt() == null ? "" : session.updatedAt(),
                    java.util.Comparator.reverseOrder()
                )
                .thenComparing(ChatSession::conversationId))
            .toList();
    }

    public List<ChatSession> listAgentSessions(String agentId) {
        if (chatSessionMetadataRepository == null) {
            return List.of();
        }
        return chatSessionMetadataRepository.findAgentConversationIds(agentId).stream()
            .map(this::session)
            .filter(session -> !session.archived())
            .toList();
    }

    public void markAgentConversation(String conversationId, String agentId) {
        if (chatSessionMetadataRepository == null) {
            return;
        }
        chatSessionMetadataRepository.saveAgentOrigin(conversationId, agentId);
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

    public String storedConversationModel(String conversationId) {
        return chatSessionMetadataRepository.findModel(conversationId).orElse(null);
    }

    private String storedConversationPlanningModel(String conversationId) {
        return chatSessionMetadataRepository.findPlanningModel(conversationId).orElse(null);
    }

    public String conversationTitle(String conversationId) {
        String storedTitle = chatSessionMetadataRepository.findTitle(conversationId).orElse(null);
        if (StringUtils.hasText(storedTitle)) {
            return storedTitle;
        }
        if (planService == null) {
            return null;
        }
        return planService.activePlan(conversationId)
            .map(plan -> {
                if (StringUtils.hasText(plan.goal())) {
                    return "Plan for " + compactTitle(plan.goal());
                }
                return StringUtils.hasText(plan.title()) ? plan.title() : null;
            })
            .filter(StringUtils::hasText)
            .orElse(null);
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

    private String compactTitle(String value) {
        String title = value.trim().replaceAll("\\s+", " ");
        String prefix = "Plan for ";
        int maxGoalLength = Math.max(16, 80 - prefix.length());
        if (title.length() <= maxGoalLength) {
            return title;
        }
        return title.substring(0, maxGoalLength).replaceAll("\\s+\\S*$", "").trim();
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
            chatFileService == null ? 0 : chatFileService.countFiles(conversationId)
        );
    }

    public String conversationTitleJobStatus(String conversationId) {
        if (agentJobService == null) {
            return null;
        }
        return agentJobService.latestConversationTitleStatus(conversationId)
            .map(AgentJobStatus::name)
            .orElse(null);
    }

    public String newConversationId() {
        return UUID.randomUUID().toString();
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

    private String resolvedPlanningModel(String conversationId) {
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

    /**
     * Returns model options as (key, label) pairs where key is the configured
     * model alias and label is the remote model name for display.
     * The key is validated by {@code RuntimeSettingsService} as the canonical model reference.
     */
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
        if (runtimeSettingsService != null) {
            String runtimeDefault = runtimeSettingsService.get().defaultModel();
            if (remoteModelNameMatches(runtimeDefault, model)) {
                return runtimeDefault;
            }
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

    private ChatResponse.MsgResponse toolChat(ResolvedChatRequest request, List<ToolCallback> approvedTools) {
        return toolChat(request, approvedTools, null, null).response();
    }

    // ── Refactored toolChat: state machine dispatcher ──

    private ToolChatResult toolChat(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer,
        ActiveTurn activeTurn
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
        PlanMode mode = interactionMode(request.conversationId());

        ContextManagementAdvisor.PreparedPrompt preparedPrompt = contextManagementAdvisor.preparePrompt(
            request.conversationId(), currentInstructions, request.model(), request.omitStoredMessages());
        Prompt prompt = new Prompt(preparedPrompt.messages(), options);

        if (logger.isDebugEnabled()) {
            logger.debug("{}", TurnDiagnostic.start(request.conversationId(), TurnPhase.PREPARE,
                Map.of("mode", mode, "model", request.model(), "toolCount", approvedTools.size())));
        }
        logger.debug("Starting tool chat turn conv={} mode={} model={}",
            request.conversationId(), mode, request.model());

        PlanToolExecutionContext.set(new PlanToolContext(
            request.conversationId(), mode,
            mode == PlanMode.EXECUTE_TASK && taskService != null
                ? taskService.runIdForConversation(request.conversationId()) : null));
        turnAuditWriter.recordTurnStart(request);

        var s = new ToolLoopState(request, approvedTools, toolMessageConsumer,
            activeTurn, mode, options, toolLoopInstructions);
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
            logger.error("Tool chat turn failed conv={} mode={}: {}",
                request.conversationId(), mode, e.getMessage(), e);
            if (auditService != null) {
                auditService.recordError(request.conversationId(), "tool_execution",
                    e.getMessage(), stackTraceString(e), request.model());
            }
            throw e;
        } finally {
            PlanToolExecutionContext.clear();
        }
    }

    private void handleInvokeModel(ToolLoopState s) {
        phase(s.activeTurn, ActiveTurnPhase.MODEL_CALL);
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
        if (requiresPlanTurnRepair(s.request.conversationId(), s.mode)
            && s.planTurnRepairRetries < PLAN_TURN_REPAIR_RETRY_LIMIT) {
            s.phase = TurnPhase.REPAIR;
            return;
        }
        if (requiresExecutionCompletionRepair(s.request.conversationId(), s.mode)
            && s.executionCompletionRepairRetries < EXECUTION_COMPLETION_REPAIR_RETRY_LIMIT) {
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

        phase(s.activeTurn, ActiveTurnPhase.TOOL_CALL);
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

        phase(s.activeTurn, ActiveTurnPhase.TOOL_CHECKPOINT);
        if (s.activeTurn != null) {
            java.util.Optional<String> nextInterrupt = s.activeTurn.pollInterrupt();
            while (nextInterrupt.isPresent()) {
                String interrupt = nextInterrupt.get();
                UserMessage interruptMessage = new UserMessage(interrupt);
                s.conversationHistory.add(interruptMessage);
                s.activeToolMessages.add(interruptMessage);
                s.messagesToPersist.add(interruptMessage);
                if (s.toolMessageConsumer != null) {
                    s.toolMessageConsumer.accept(userMessage(interrupt));
                }
                nextInterrupt = s.activeTurn.pollInterrupt();
            }
        }

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

        if (s.mode == PlanMode.EXECUTE_PLAN && planService != null
            && planService.mode(s.request.conversationId()) != PlanMode.EXECUTE_PLAN) {
            s.validatedFinalMessage = planService.finalMessage(s.request.conversationId());
            s.planCompletionDetected = true;
            s.phase = TurnPhase.EVALUATE;
            return;
        }
        if (s.mode == PlanMode.EXECUTE_TASK && taskService != null
            && taskService.mode(s.request.conversationId()) != PlanMode.EXECUTE_TASK) {
            String taskRunId = PlanToolExecutionContext.current() == null
                ? null : PlanToolExecutionContext.current().runId();
            s.validatedFinalMessage = taskRunId == null ? null : taskService.finalMessage(taskRunId);
            s.planCompletionDetected = true;
            s.phase = TurnPhase.EVALUATE;
            return;
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
                + "Retry the failed tool call with the exact schema shown for that tool, or end the planning turn with ask_user_questions or plan_ready_for_approval."
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
            Message controlMessage = emptyFinalResponseControlMessage(s.mode);
            List<Message> retryMessages = new ArrayList<>(s.prompt.getInstructions());
            retryMessages.add(controlMessage);
            s.prompt = new Prompt(retryMessages, s.prompt.getOptions());
            s.phase = TurnPhase.INVOKE_MODEL;
            return;
        }

        if (requiresPlanTurnRepair(s.request.conversationId(), s.mode)
            && s.planTurnRepairRetries < PLAN_TURN_REPAIR_RETRY_LIMIT) {
            collectThinking(s.response, s.thinkingParts);
            s.planTurnRepairRetries++;
            Message controlMessage = invalidPlanTurnControlMessage();
            List<Message> retryMessages = new ArrayList<>(s.prompt.getInstructions());
            retryMessages.add(controlMessage);
            s.prompt = new Prompt(retryMessages, s.prompt.getOptions());
            s.phase = TurnPhase.INVOKE_MODEL;
            return;
        }

        if (requiresExecutionCompletionRepair(s.request.conversationId(), s.mode)
            && s.executionCompletionRepairRetries < EXECUTION_COMPLETION_REPAIR_RETRY_LIMIT) {
            collectThinking(s.response, s.thinkingParts);
            s.executionCompletionRepairRetries++;
            Message controlMessage = invalidExecutionCompletionControlMessage(s.mode);
            List<Message> retryMessages = new ArrayList<>(s.prompt.getInstructions());
            retryMessages.add(controlMessage);
            s.prompt = new Prompt(retryMessages, s.prompt.getOptions());
            s.phase = TurnPhase.INVOKE_MODEL;
            return;
        }

        s.phase = TurnPhase.FINALIZE;
    }

    private void handleFinalize(ToolLoopState s) {
        String forcedPlanningQuestion = null;
        if (requiresPlanTurnRepair(s.request.conversationId(), s.mode)) {
            forcedPlanningQuestion = "What should we clarify, change, or add before continuing this plan?";
            planService.askQuestions(s.request.conversationId(), List.of(forcedPlanningQuestion));
        }

        phase(s.activeTurn, ActiveTurnPhase.COMPLETING);
        collectThinking(s.response, s.thinkingParts);

        AssistantMessage finalAssistantMessage;
        if (s.planCompletionDetected) {
            finalAssistantMessage = StringUtils.hasText(s.validatedFinalMessage)
                ? new AssistantMessage(s.validatedFinalMessage)
                : new AssistantMessage("");
        } else if (StringUtils.hasText(forcedPlanningQuestion)) {
            finalAssistantMessage = new AssistantMessage(forcedPlanningQuestion);
        } else if (s.response == null || s.response.getResult() == null) {
            finalAssistantMessage = new AssistantMessage("");
        } else {
            finalAssistantMessage = assistantMessageWithThinking(
                s.response.getResult(), combinedThinking(s.thinkingParts));
        }

        if (!s.planCompletionDetected && s.mode == PlanMode.EXECUTE_PLAN && planService != null
            && planService.mode(s.request.conversationId()) == PlanMode.EXECUTE_PLAN) {
            planService.recordFallbackExecutionEvidence(s.request.conversationId());
            planService.markNeedsReview(s.request.conversationId());
            finalAssistantMessage = new AssistantMessage(PLAN_NEEDS_REVIEW_MESSAGE);
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
            planState(s.request.conversationId()),
            List.copyOf(s.toolActivities));

        ChatMessage finalMessage = renderAssistantMessage(assistantMessageParts(
            finalAssistantMessage,
            finalAssistantMessage == null ? "" : finalAssistantMessage.getText()));

        turnAuditWriter.enqueueTitleJob(s.request);
        logger.debug("Tool chat turn completed conv={} mode={} tokens={}",
            s.request.conversationId(), s.mode,
            maintenance.usage() != null ? maintenance.usage().usedTokens() : "?");

        s.chatResponse = chatResponse;
        s.finalMessage = finalMessage;
        s.phase = TurnPhase.DONE;
    }

    private ChatResponse.MsgResponse markPlanNeedsReviewResponse(ChatResponse.MsgResponse response) {
        planService.markNeedsReview(response.conversationId());
        persistControlledReviewMessage(response.conversationId(), response.response());
        return new ChatResponse.MsgResponse(
            response.conversationId(),
            response.model(),
            PLAN_NEEDS_REVIEW_MESSAGE,
            response.contextUsage(),
            planState(response.conversationId()),
            response.toolActivities()
        );
    }

    private boolean isValidatedPlanCompletion(String conversationId) {
        return planService.activePlan(conversationId)
            .map(plan -> plan.status() == PlanStatus.COMPLETED)
            .orElse(false);
    }

    private void persistControlledReviewMessage(String conversationId, String ordinaryResponse) {
        List<Message> messages = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        if (!messages.isEmpty()) {
            Message last = messages.get(messages.size() - 1);
            if (last instanceof AssistantMessage && java.util.Objects.equals(last.getText(), ordinaryResponse)) {
                messages.remove(messages.size() - 1);
            }
        }
        messages.add(new AssistantMessage(PLAN_NEEDS_REVIEW_MESSAGE));
        chatMemoryRepository.saveAll(conversationId, messages);
    }

    // ── End refactored toolChat ──

    private List<Message> currentInstructions(ResolvedChatRequest request) {
        String systemPrompt = effectiveSystemPrompt(request);
        return promptAssembler.assembleTurnInstructions(request, systemPrompt);
    }

    private List<Message> currentSystemInstructions(ResolvedChatRequest request) {
        return currentInstructions(request).stream()
            .filter(SystemMessage.class::isInstance)
            .toList();
    }

    private ChatMessage toolChatMessage(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer,
        ActiveTurn activeTurn
    ) {
        ToolChatResult result = toolChat(request, approvedTools, toolMessageConsumer, activeTurn);
        List<ChatMessage> history = history(request.conversationId());
        if (!history.isEmpty()) {
            ChatMessage lastMessage = history.get(history.size() - 1);
            if (isAssistantRole(lastMessage.role())) {
                return lastMessage;
            }
        }
        return result.finalMessage();
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

    private void phase(ActiveTurn activeTurn, ActiveTurnPhase phase) {
        if (activeTurn != null) {
            activeTurn.phase(phase);
        }
    }

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

    private SystemMessage toolUseAbortControlMessage(ToolUseAbort abort) {
        return turnRepair.toolUseAbortControlMessage(abort);
    }

    private boolean isEmptyFinalResponse(org.springframework.ai.chat.model.ChatResponse response) {
        return turnRepair.hasNoContentOrToolCalls(response);
    }

    private SystemMessage emptyFinalResponseControlMessage(PlanMode mode) {
        return turnRepair.emptyFinalResponseControlMessage(mode);
    }

    private boolean requiresPlanTurnRepair(String conversationId, PlanMode mode) {
        return turnRepair.needsPlanTurnRepair(conversationId, mode);
    }

    private boolean requiresExecutionCompletionRepair(String conversationId, PlanMode mode) {
        return turnRepair.needsExecutionCompletionRepair(conversationId, mode);
    }

    private SystemMessage invalidPlanTurnControlMessage() {
        return turnRepair.invalidPlanTurnControlMessage();
    }

    private SystemMessage invalidExecutionCompletionControlMessage(PlanMode mode) {
        return turnRepair.invalidExecutionCompletionControlMessage(mode);
    }

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
            .collect(java.util.stream.Collectors.joining("; "));
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
            .collect(java.util.stream.Collectors.toMap(
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
        if (runtimeSettingsService != null) {
            return filterApprovedTools(runtimeSettingsService.approvedTools(), request);
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
        PlanMode mode = interactionMode(request.conversationId());
        return toolAccessPolicy.filterToolsByMode(approvedToolNames, mode, request.conversationId());
    }

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

    private static String safeAiFailureMessage(Throwable exception) {
        String message = exception == null ? null : exception.getMessage();
        if (!StringUtils.hasText(message)) {
            return "AI model request failed";
        }
        if (message.contains("401") || message.toLowerCase().contains("authentication")) {
            return "AI model request failed authentication";
        }
        return "AI model request failed";
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
        PlanMode mode = interactionMode(request.conversationId());
        return promptAssembler.mergeModePrompt(mode, request.conversationId());
    }

    private PlanMode interactionMode(String conversationId) {
        return toolAccessPolicy.interactionMode(conversationId);
    }

    private void requirePlanService() {
        if (planService == null) {
            throw new IllegalStateException("Plan service is not available");
        }
    }

    private void requireTaskService() {
        if (taskService == null) {
            throw new IllegalStateException("Task service is not available");
        }
    }

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
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(output.getMetadata());
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
            .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    static String thinkingText(org.springframework.ai.chat.model.Generation generation) {
        // Ollama: ChatGenerationMetadata under "thinking"
        if (generation.getMetadata() != null) {
            String thinking = stringValue(generation.getMetadata().get(THINKING_METADATA_KEY));
            if (thinking != null) return thinking;
        }
        // OpenAI/DeepSeek: AssistantMessage properties under "reasoningContent"
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

    private record MessageParts(String visibleText, String thinkingText) {
    }

    private record ToolChatResult(ChatResponse.MsgResponse response, ChatMessage finalMessage) {
    }

    private record MalformedToolCall(String id, String name, String arguments, String parserMessage) {
    }

    private static final class ToolLoopState {
        // ── Immutable request context ──
        final ResolvedChatRequest request;
        final List<ToolCallback> approvedTools;
        final Consumer<ChatMessage> toolMessageConsumer;
        final ActiveTurn activeTurn;
        final PlanMode mode;
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
        int planTurnRepairRetries = 0;
        int executionCompletionRepairRetries = 0;

        // ── Output (set by FINALIZE, returned by dispatcher) ──
        ChatResponse.MsgResponse chatResponse;
        ChatMessage finalMessage;

        ToolLoopState(
            ResolvedChatRequest request,
            List<ToolCallback> approvedTools,
            Consumer<ChatMessage> toolMessageConsumer,
            ActiveTurn activeTurn,
            PlanMode mode,
            ToolCallingChatOptions toolOptions,
            List<Message> currentSystemInstructions
        ) {
            this.request = request;
            this.approvedTools = approvedTools;
            this.toolMessageConsumer = toolMessageConsumer;
            this.activeTurn = activeTurn;
            this.mode = mode;
            this.toolOptions = toolOptions;
            this.currentSystemInstructions = currentSystemInstructions;
        }
    }

    private <T> T await(java.util.concurrent.CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Chat turn was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = unwrap(exception);
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(cause);
        }
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof ExecutionException || current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    // ── Conversation snapshot / restore for transient-failure recovery ──

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
        Throwable current = unwrap(error);
        while (current != null) {
            if (current instanceof ResourceAccessException || current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private ChatMessage toolChatMessageWithRetry(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        Consumer<ChatMessage> toolMessageConsumer,
        ActiveTurn activeTurn
    ) {
        List<Message> snapshot = snapshotConversation(request.conversationId());
        try {
            return toolChatMessage(request, approvedTools, toolMessageConsumer, activeTurn);
        } catch (RuntimeException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            logger.warn("Retrying turn after transient failure conv={}: {}", request.conversationId(), e.getMessage());
            restoreConversation(request.conversationId(), snapshot);
            try {
                return toolChatMessage(request, approvedTools, toolMessageConsumer, activeTurn);
            } catch (RuntimeException retryException) {
                restoreConversation(request.conversationId(), snapshot);
                throw retryException;
            }
        }
    }

    private ChatResponse.MsgResponse toolChatWithRetry(
        ResolvedChatRequest request,
        List<ToolCallback> approvedTools,
        ActiveTurn activeTurn
    ) {
        List<Message> snapshot = snapshotConversation(request.conversationId());
        try {
            return toolChat(request, approvedTools, null, activeTurn).response();
        } catch (RuntimeException e) {
            if (!isRetryable(e)) {
                throw e;
            }
            logger.warn("Retrying turn after transient failure conv={}: {}", request.conversationId(), e.getMessage());
            restoreConversation(request.conversationId(), snapshot);
            try {
                return toolChat(request, approvedTools, null, activeTurn).response();
            } catch (RuntimeException retryException) {
                restoreConversation(request.conversationId(), snapshot);
                throw retryException;
            }
        }
    }

    private record ResolvedTaskExecution(String conversationId, String runId, ResolvedChatRequest request) {
    }
}
