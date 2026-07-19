package io.mindspice.magenta2.ai.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

public class ContextManagementAdvisor implements CallAdvisor, StreamAdvisor {
    private static final Logger log = LoggerFactory.getLogger(ContextManagementAdvisor.class);

    public static final String CONTEXT_USAGE_KEY = "magenta.contextUsage";
    public static final String OMIT_STORED_MESSAGES_KEY = "magenta.omitStoredMessages";
    public static final String SUMMARY_PREFIX = "[[MAGENTA_CONTEXT_SUMMARY]]\n";
    public static final String NOTICE_PREFIX = "[[MAGENTA_CONTEXT_COMPACTED_NOTICE]] ";
    public static final String COMPACTION_NOTICE = "Context compacted to keep the conversation within the model window.";
    static final String SUMMARY_SYSTEM_PROMPT = """
        Summarize the previous Magenta conversation for future context.
        Preserve user goals, decisions, constraints, active tasks, important facts, tool results, and unresolved questions.
        Be concise, factual, and do not add new instructions.
        """;

    private static final int MIN_TAIL_MESSAGES = 6;

    private final ChatMemoryRepository chatMemoryRepository;
    private final AiConfig aiConfig;
    private final ChatModelRouter chatModelRouter;
    private final TokenCountEstimator tokenCountEstimator;
    private final ContextUsageTracker usageTracker;
    private final ToolTranscriptService toolTranscriptService;
    private final AuditRepository auditRepository;
    private final RuntimeSettingsService runtimeSettingsService;

    public ContextManagementAdvisor(
        ChatMemoryRepository chatMemoryRepository,
        AiConfig aiConfig,
        ChatModelRouter chatModelRouter,
        TokenCountEstimator tokenCountEstimator,
        ContextUsageTracker usageTracker,
        ToolTranscriptService toolTranscriptService,
        AuditRepository auditRepository
    ) {
        this(
            chatMemoryRepository,
            aiConfig,
            chatModelRouter,
            tokenCountEstimator,
            usageTracker,
            toolTranscriptService,
            auditRepository,
            null
        );
    }

    @Autowired
    public ContextManagementAdvisor(
        ChatMemoryRepository chatMemoryRepository,
        AiConfig aiConfig,
        ChatModelRouter chatModelRouter,
        TokenCountEstimator tokenCountEstimator,
        ContextUsageTracker usageTracker,
        ToolTranscriptService toolTranscriptService,
        AuditRepository auditRepository,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService
    ) {
        this.chatMemoryRepository = chatMemoryRepository;
        this.aiConfig = aiConfig;
        this.chatModelRouter = chatModelRouter;
        this.tokenCountEstimator = tokenCountEstimator;
        this.usageTracker = usageTracker;
        this.toolTranscriptService = toolTranscriptService;
        this.auditRepository = auditRepository;
        this.runtimeSettingsService = runtimeSettingsService;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PreparedRequest prepared = prepare(request);
        ChatClientResponse response = chain.nextCall(prepared.request());
        saveAssistantMessages(prepared.conversationId(), assistantMessages(response));
        ContextUsage usage = estimateStoredUsage(prepared.conversationId(), prepared.model());
        usageTracker.record(prepared.conversationId(), usage);
        return response.mutate()
            .context(CONTEXT_USAGE_KEY, usage)
            .build();
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PreparedRequest prepared = prepare(request);
        return chain.nextStream(prepared.request())
            .doOnComplete(() -> usageTracker.record(
                prepared.conversationId(),
                estimateStoredUsage(prepared.conversationId(), prepared.model())
            ));
    }

    @Override
    public String getName() {
        return "MagentaContextManagementAdvisor";
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    public ContextUsage estimateStoredUsage(String conversationId, String remoteModelName) {
        List<Message> messages = new ArrayList<>();
        String systemPrompt = defaultSystemPrompt();
        if (StringUtils.hasText(systemPrompt)) {
            messages.add(new SystemMessage(systemPrompt));
        }
        messages.addAll(toModelMemory(chatMemoryRepository.findByConversationId(conversationId)));
        return estimateUsage(messages, remoteModelName);
    }

    public StoredContextMaintenance maintainStoredContext(String conversationId, String remoteModelName) {
        List<Message> storedMessages = chatMemoryRepository.findByConversationId(conversationId);
        boolean compacted = false;
        List<Message> truncatedMessages = truncateExpiredToolResults(storedMessages);
        if (truncatedMessages != storedMessages) {
            storedMessages = truncatedMessages;
            chatMemoryRepository.saveAll(conversationId, storedMessages);
        }

        ContextUsage usage = estimateStoredUsage(conversationId, remoteModelName);
        log.debug("maintainStoredContext conv={} tokens={}/{} ({:.0f}%) trigger={}", conversationId,
            usage.usedTokens(), usage.maxTokens(), usage.percentUsed(), usage.triggerTokens());
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.info("Compacting stored context: conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            try {
                storedMessages = compact(conversationId, storedMessages, storedMaintenanceInstructions(), remoteModelName);
            } catch (ContextCompactionSummaryException exception) {
                log.warn(
                    "Stored context maintenance degraded during compaction: conv={} model={} tokens={} trigger={} error={}",
                    conversationId, remoteModelName, usage.usedTokens(), usage.triggerTokens(), exception.toString()
                );
                usageTracker.record(conversationId, usage);
                return new StoredContextMaintenance(
                    usage,
                    false,
                    true,
                    "Context compaction failed during maintenance: " + rootCauseMessage(exception)
                );
            }
            usage = estimateStoredUsage(conversationId, remoteModelName);
            log.info("After compact: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
            compacted = true;
        }
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.info("Trimming stored context: conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            storedMessages = trimToBudget(conversationId, storedMessages, storedMaintenanceInstructions(), remoteModelName);
            usage = estimateStoredUsage(conversationId, remoteModelName);
            log.info("After trim: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
        }
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.error("Stored context too large after all strategies: conv={} tokens={} trigger={}",
                conversationId, usage.usedTokens(), usage.triggerTokens());
            usageTracker.record(conversationId, usage);
            return new StoredContextMaintenance(
                usage,
                compacted,
                true,
                "Context remains above the maintenance trigger after compaction and trimming."
            );
        }

        usageTracker.record(conversationId, usage);
        return new StoredContextMaintenance(usage, compacted, false, null);
    }

    public ContextUsage estimateUsage(List<Message> messages, String remoteModelName) {
        int maxTokens = maxTokens(remoteModelName);
        int triggerTokens = triggerTokens(maxTokens);
        int usedTokens = estimateTokens(messages);
        double percent = maxTokens == 0 ? 0.0 : (usedTokens * 100.0) / maxTokens;
        return new ContextUsage(usedTokens, maxTokens, triggerTokens, percent);
    }

    public boolean isHiddenSummary(Message message) {
        return message instanceof SystemMessage && message.getText() != null && message.getText().startsWith(SUMMARY_PREFIX);
    }

    public boolean isCompactionNotice(Message message) {
        return message instanceof SystemMessage && message.getText() != null && message.getText().startsWith(NOTICE_PREFIX);
    }

    public String visibleNoticeText(Message message) {
        if (!isCompactionNotice(message)) {
            return message.getText();
        }
        return message.getText().substring(NOTICE_PREFIX.length());
    }

    public PreparedPrompt preparePrompt(String conversationId, List<Message> currentInstructions, String model) {
        return preparePrompt(conversationId, currentInstructions, model, false);
    }

    public PreparedPrompt preparePrompt(
        String conversationId,
        List<Message> currentInstructions,
        String model,
        boolean omitStoredMessages
    ) {
        List<Message> storedMessages = chatMemoryRepository.findByConversationId(conversationId);
        List<Message> promptStoredMessages = storedMessages;
        if (!omitStoredMessages) {
            List<Message> truncatedMessages = truncateExpiredToolResults(storedMessages);
            if (truncatedMessages != storedMessages) {
                storedMessages = truncatedMessages;
                promptStoredMessages = truncatedMessages;
                chatMemoryRepository.saveAll(conversationId, storedMessages);
            }
        } else {
            promptStoredMessages = List.of();
        }
        List<Message> promptMessages = buildPromptMessages(promptStoredMessages, currentInstructions);
        ContextUsage usage = estimateUsage(promptMessages, model);
        log.debug("preparePrompt conv={} tokens={}/{} ({:.0f}%) trigger={}", conversationId,
            usage.usedTokens(), usage.maxTokens(), usage.percentUsed(), usage.triggerTokens());

        if (!omitStoredMessages && usage.usedTokens() > usage.triggerTokens()) {
            log.info("Compacting context: conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            storedMessages = compact(conversationId, storedMessages, currentInstructions, model);
            promptStoredMessages = storedMessages;
            promptMessages = buildPromptMessages(promptStoredMessages, currentInstructions);
            usage = estimateUsage(promptMessages, model);
            log.info("After compact: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
        }
        if (!omitStoredMessages && usage.usedTokens() > usage.triggerTokens()) {
            log.info("Trimming context: conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            storedMessages = trimToBudget(conversationId, storedMessages, currentInstructions, model);
            promptStoredMessages = storedMessages;
            promptMessages = buildPromptMessages(promptStoredMessages, currentInstructions);
            usage = estimateUsage(promptMessages, model);
            log.info("After trim: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
        }
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.error("Context too large after all strategies: conv={} tokens={} trigger={}",
                conversationId, usage.usedTokens(), usage.triggerTokens());
            throw new IllegalStateException(
                "Context is too large to send safely after compaction: "
                    + usage.usedTokens() + " estimated tokens exceeds trigger budget " + usage.triggerTokens()
            );
        }

        Message currentMessage = new Prompt(currentInstructions).getLastUserOrToolResponseMessage();
        if (currentMessage != null) {
            List<Message> updatedMemory = new ArrayList<>(storedMessages);
            updatedMemory.add(currentMessage);
            chatMemoryRepository.saveAll(conversationId, updatedMemory);
        }

        usageTracker.record(conversationId, usage);
        return new PreparedPrompt(promptMessages, usage);
    }

    public ToolLoopPrompt prepareToolLoopPrompt(
        String conversationId,
        List<Message> activeMessages,
        List<Message> currentSystemInstructions,
        String model
    ) {
        return prepareToolLoopPrompt(conversationId, activeMessages, currentSystemInstructions, model, false);
    }

    public ToolLoopPrompt prepareToolLoopPrompt(
        String conversationId,
        List<Message> activeMessages,
        List<Message> currentSystemInstructions,
        String model,
        boolean omitStoredMessages
    ) {
        List<Message> storedMessages = chatMemoryRepository.findByConversationId(conversationId);
        List<Message> promptStoredMessages = storedMessages;
        if (!omitStoredMessages) {
            List<Message> truncatedMessages = truncateExpiredToolResults(storedMessages);
            if (truncatedMessages != storedMessages) {
                storedMessages = truncatedMessages;
                promptStoredMessages = truncatedMessages;
                chatMemoryRepository.saveAll(conversationId, storedMessages);
            }
        } else {
            promptStoredMessages = List.of();
        }

        List<Message> active = new ArrayList<>(activeMessages == null ? List.of() : activeMessages);
        List<Message> promptMessages = buildToolLoopPromptMessages(promptStoredMessages, currentSystemInstructions, active);
        ContextUsage usage = estimateUsage(promptMessages, model);
        log.debug("prepareToolLoopPrompt conv={} tokens={}/{} ({:.0f}%) trigger={}", conversationId,
            usage.usedTokens(), usage.maxTokens(), usage.percentUsed(), usage.triggerTokens());
        boolean compacted = false;

        if (!omitStoredMessages && usage.usedTokens() > usage.triggerTokens()) {
            log.info("Compacting context (tool loop): conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            storedMessages = compact(conversationId, storedMessages, activeSystemInstructions(currentSystemInstructions, active), model);
            promptStoredMessages = storedMessages;
            promptMessages = buildToolLoopPromptMessages(promptStoredMessages, currentSystemInstructions, active);
            usage = estimateUsage(promptMessages, model);
            log.info("After compact: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
            compacted = true;
        }
        if (!omitStoredMessages && usage.usedTokens() > usage.triggerTokens()) {
            log.info("Trimming context (tool loop): conv={} tokens={} exceeds trigger={}", conversationId,
                usage.usedTokens(), usage.triggerTokens());
            storedMessages = trimToBudget(conversationId, storedMessages, activeSystemInstructions(currentSystemInstructions, active), model);
            promptStoredMessages = storedMessages;
            promptMessages = buildToolLoopPromptMessages(promptStoredMessages, currentSystemInstructions, active);
            usage = estimateUsage(promptMessages, model);
            log.info("After trim: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
            compacted = true;
        }
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.info("Compacting active tool messages: conv={} tokens={}", conversationId, usage.usedTokens());
            active = compactActiveToolMessages(active);
            promptMessages = buildToolLoopPromptMessages(promptStoredMessages, currentSystemInstructions, active);
            usage = estimateUsage(promptMessages, model);
            log.info("After active compact: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
            compacted = true;
        }
        if (usage.usedTokens() > usage.triggerTokens()) {
            log.info("Trimming active tool messages: conv={} tokens={}", conversationId, usage.usedTokens());
            active = trimActiveToolMessages(active, promptStoredMessages, currentSystemInstructions, model);
            promptMessages = buildToolLoopPromptMessages(promptStoredMessages, currentSystemInstructions, active);
            usage = estimateUsage(promptMessages, model);
            log.info("After active trim: conv={} tokens={}/{} ({:.0f}%)", conversationId,
                usage.usedTokens(), usage.maxTokens(), usage.percentUsed());
            compacted = true;
        }

        usageTracker.record(conversationId, usage);
        return new ToolLoopPrompt(promptMessages, active, usage, usage.usedTokens() <= usage.triggerTokens(), compacted);
    }

    public void saveAssistantMessages(String conversationId, List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        List<Message> storedMessages = new ArrayList<>(chatMemoryRepository.findByConversationId(conversationId));
        storedMessages.addAll(messages);
        chatMemoryRepository.saveAll(conversationId, storedMessages);
    }

    private PreparedRequest prepare(ChatClientRequest request) {
        String conversationId = conversationId(request.context());
        String model = modelName(request.prompt());
        Map<String, Object> context = request.context() == null ? Map.of() : request.context();
        boolean omitStoredMessages = Boolean.TRUE.equals(context.get(OMIT_STORED_MESSAGES_KEY));
        PreparedPrompt preparedPrompt = preparePrompt(
            conversationId,
            request.prompt().getInstructions(),
            model,
            omitStoredMessages
        );
        Prompt prompt = request.prompt().mutate()
            .messages(preparedPrompt.messages())
            .build();
        return new PreparedRequest(
            request.mutate()
                .prompt(prompt)
                .context(CONTEXT_USAGE_KEY, preparedPrompt.usage())
                .build(),
            conversationId,
            model
        );
    }

    private List<Message> compact(
        String conversationId,
        List<Message> storedMessages,
        List<Message> currentInstructions,
        String model
    ) {
        List<Message> previousSummaries = storedMessages.stream()
            .filter(this::isHiddenSummary)
            .map(message -> (Message) new SystemMessage(
                "Previous compacted conversation summary:\n" + message.getText().substring(SUMMARY_PREFIX.length())
            ))
            .toList();
        List<Message> compactable = storedMessages.stream()
            .filter(message -> !isHiddenSummary(message))
            .filter(message -> !isCompactionNotice(message))
            .toList();
        if (compactable.size() <= MIN_TAIL_MESSAGES) {
            return storedMessages;
        }

        List<Message> tail = retainedTail(compactable, model);
        List<Message> older = compactable.subList(0, Math.max(0, compactable.size() - tail.size()));
        if (older.isEmpty()) {
            return storedMessages;
        }

        List<Message> summaryInput = new ArrayList<>(previousSummaries);
        summaryInput.addAll(older);
        String summary = summarize(summaryInput);
        List<Message> compacted = new ArrayList<>();
        compacted.add(new SystemMessage(SUMMARY_PREFIX + summary));
        compacted.add(new SystemMessage(NOTICE_PREFIX + COMPACTION_NOTICE));
        compacted.addAll(tail);
        chatMemoryRepository.saveAll(conversationId, compacted);

        if (auditRepository != null) {
            ContextUsage postUsage = estimateStoredUsage(conversationId, model);
            int msgCount = chatMemoryRepository.findByConversationId(conversationId).size();
            auditRepository.recordCompaction(conversationId, postUsage, msgCount, "summarize", summary, model);
        }
        return compacted;
    }

    private List<Message> trimToBudget(
        String conversationId,
        List<Message> storedMessages,
        List<Message> currentInstructions,
        String model
    ) {
        List<Message> trimmed = new ArrayList<>(storedMessages);
        while (!trimmed.isEmpty() && estimateUsage(buildPromptMessages(trimmed, currentInstructions), model).usedTokens()
            > triggerTokens(maxTokens(model))) {
            int removeIndex = firstRemovableMessageIndex(trimmed);
            if (removeIndex < 0) {
                break;
            }
            trimmed.remove(removeIndex);
        }
        chatMemoryRepository.saveAll(conversationId, trimmed);

        if (auditRepository != null) {
            ContextUsage postUsage = estimateStoredUsage(conversationId, model);
            int msgCount = chatMemoryRepository.findByConversationId(conversationId).size();
            auditRepository.recordCompaction(conversationId, postUsage, msgCount, "trim", null, model);
        }
        return trimmed;
    }

    private int firstRemovableMessageIndex(List<Message> messages) {
        for (int i = 0; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (!isHiddenSummary(message) && !isCompactionNotice(message)) {
                return i;
            }
        }
        return -1;
    }

    private List<Message> retainedTail(List<Message> messages, String model) {
        int maxTailTokens = Math.max(1_000, triggerTokens(maxTokens(model)) / 3);
        List<Message> tail = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            tail.add(0, messages.get(i));
            if (tail.size() >= MIN_TAIL_MESSAGES && estimateTokens(tail) >= maxTailTokens) {
                tail.remove(0);
                break;
            }
        }
        return tail.isEmpty() ? messages.subList(Math.max(0, messages.size() - MIN_TAIL_MESSAGES), messages.size()) : tail;
    }

    private String summarize(List<Message> olderMessages) {
        String compactionModelRef = resolveCompactionModelReference();
        ModelConfig compactionModel = chatModelRouter.modelConfig(compactionModelRef);
        String renderedConversation = renderConversation(olderMessages);
        String summary;
        try {
            summary = chatModelRouter.chatClient(compactionModelRef)
                .prompt()
                .system(SUMMARY_SYSTEM_PROMPT)
                .user(renderedConversation)
                .options(chatModelRouter.chatOptions(compactionModelRef).mutate())
                .call()
                .content();
        } catch (RuntimeException exception) {
            throw new ContextCompactionSummaryException(
                "Context compaction failed while calling model " + compactionModel.remoteModelName()
                    + ": " + rootCauseMessage(exception),
                exception
            );
        }
        if (!StringUtils.hasText(summary)) {
            throw new ContextCompactionSummaryException(
                "Context compaction failed: compaction model returned an empty summary"
            );
        }
        return summary.trim();
    }

    private List<Message> buildPromptMessages(List<Message> storedMessages, List<Message> currentInstructions) {
        List<Message> promptMessages = new ArrayList<>();
        boolean planning = containsPlanningState(currentInstructions);
        if (!planning) {
            currentInstructions.stream()
                .filter(SystemMessage.class::isInstance)
                .forEach(promptMessages::add);
        }
        promptMessages.addAll(toModelMemory(storedMessages));
        if (planning) {
            currentInstructions.stream()
                .filter(SystemMessage.class::isInstance)
                .forEach(promptMessages::add);
        }
        currentInstructions.stream()
            .filter(message -> !(message instanceof SystemMessage))
            .forEach(promptMessages::add);
        return promptMessages;
    }

    private List<Message> buildToolLoopPromptMessages(
        List<Message> storedMessages,
        List<Message> currentSystemInstructions,
        List<Message> activeMessages
    ) {
        List<Message> promptMessages = new ArrayList<>();
        boolean planning = containsPlanningState(currentSystemInstructions);
        if (currentSystemInstructions != null && !planning) {
            currentSystemInstructions.stream()
                .filter(SystemMessage.class::isInstance)
                .forEach(promptMessages::add);
        }
        promptMessages.addAll(toModelMemory(storedMessages));
        if (currentSystemInstructions != null && planning) {
            currentSystemInstructions.stream()
                .filter(SystemMessage.class::isInstance)
                .forEach(promptMessages::add);
        }
        if (currentSystemInstructions != null) {
            currentSystemInstructions.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .forEach(promptMessages::add);
        }
        if (activeMessages != null) {
            promptMessages.addAll(activeMessages);
        }
        return promptMessages;
    }

    private boolean containsPlanningState(List<Message> messages) {
        return messages != null && messages.stream()
            .filter(SystemMessage.class::isInstance)
            .map(Message::getText)
            .anyMatch(text -> text != null && text.contains("Runtime planning state:"));
    }

    private List<Message> activeSystemInstructions(List<Message> currentSystemInstructions, List<Message> activeMessages) {
        List<Message> messages = new ArrayList<>();
        if (currentSystemInstructions != null) {
            messages.addAll(currentSystemInstructions);
        }
        if (activeMessages != null) {
            messages.addAll(activeMessages);
        }
        return messages;
    }

    private List<Message> storedMaintenanceInstructions() {
        String systemPrompt = defaultSystemPrompt();
        return StringUtils.hasText(systemPrompt) ? List.of(new SystemMessage(systemPrompt)) : List.of();
    }

    private List<Message> compactActiveToolMessages(List<Message> activeMessages) {
        if (activeMessages.size() <= MIN_TAIL_MESSAGES) {
            return activeMessages;
        }
        List<Message> tail = activeMessages.subList(activeMessages.size() - MIN_TAIL_MESSAGES, activeMessages.size());
        List<Message> older = activeMessages.subList(0, activeMessages.size() - MIN_TAIL_MESSAGES);
        String summary = summarize(older);
        List<Message> compacted = new ArrayList<>();
        compacted.add(new SystemMessage("Compacted active tool-use summary:\n" + summary));
        compacted.addAll(tail);
        return compacted;
    }

    private List<Message> trimActiveToolMessages(
        List<Message> activeMessages,
        List<Message> storedMessages,
        List<Message> currentSystemInstructions,
        String model
    ) {
        List<Message> trimmed = new ArrayList<>(activeMessages);
        while (!trimmed.isEmpty()
            && estimateUsage(buildToolLoopPromptMessages(storedMessages, currentSystemInstructions, trimmed), model).usedTokens()
                > triggerTokens(maxTokens(model))
            && trimmed.size() > 2) {
            trimmed.remove(0);
        }
        return trimmed;
    }

    private List<Message> toModelMemory(List<Message> storedMessages) {
        return storedMessages.stream()
            .filter(message -> !isCompactionNotice(message))
            .map(message -> isHiddenSummary(message)
                ? new SystemMessage("Previous compacted conversation summary:\n" + message.getText().substring(SUMMARY_PREFIX.length()))
                : isToolTranscript(message)
                    ? new SystemMessage(toolTranscriptService.renderForModel(message))
                : message)
            .toList();
    }

    private List<Message> assistantMessages(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return List.of();
        }
        return response.chatResponse().getResults().stream()
            .map(this::assistantMessage)
            .filter(message -> message != null)
            .map(message -> (Message) message)
            .toList();
    }

    private AssistantMessage assistantMessage(Generation generation) {
        AssistantMessage output = generation == null ? null : generation.getOutput();
        if (output == null) {
            return null;
        }
        String thinking = ChatService.thinkingText(generation);
        if (!StringUtils.hasText(thinking)) {
            return output;
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(output.getMetadata());
        metadata.put(ChatService.MESSAGE_THINKING_METADATA_KEY, thinking);
        return AssistantMessage.builder()
            .content(output.getText())
            .properties(metadata)
            .toolCalls(output.getToolCalls())
            .media(output.getMedia())
            .build();
    }

    private String renderConversation(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            builder.append(message.getMessageType().getValue())
                .append(": ")
                .append(renderMessageText(message))
                .append("\n\n");
        }
        return builder.toString().trim();
    }

    private String renderMessageText(Message message) {
        if (isToolTranscript(message)) {
            return toolTranscriptService.renderForModel(message);
        }
        return message.getText() == null ? "" : message.getText();
    }

    private List<Message> truncateExpiredToolResults(List<Message> messages) {
        if (toolTranscriptService == null) {
            return messages;
        }
        return toolTranscriptService.truncateExpiredLargeResults(messages);
    }

    private boolean isToolTranscript(Message message) {
        return toolTranscriptService != null && toolTranscriptService.isToolTranscript(message);
    }

    private int estimateTokens(List<Message> messages) {
        return tokenCountEstimator.estimate(renderConversation(messages));
    }

    private int maxTokens(String remoteModelName) {
        return aiConfig.models().values().stream()
            .filter(model -> remoteModelName == null || remoteModelName.equals(model.remoteModelName()))
            .findFirst()
            .map(ModelConfig::contextLength)
            .filter(value -> value != null && value > 0)
            .orElseGet(() -> {
                if (runtimeSettingsService != null) {
                    String defaultRemoteModel = runtimeSettingsService.defaultModel();
                    return aiConfig.models().values().stream()
                        .filter(model -> defaultRemoteModel.equals(model.remoteModelName()))
                        .findFirst()
                        .map(ModelConfig::contextLength)
                        .orElseThrow();
                }
                return aiConfig.models().get(aiConfig.agents().get(aiConfig.defaultAgent()).model()).contextLength();
            });
    }

    private int triggerTokens(int maxTokens) {
        int bufferPercent = runtimeSettingsService == null
            ? aiConfig.resolvedContextBufferPercent()
            : runtimeSettingsService.contextBufferPercent();
        return Math.max(1, maxTokens - (int) Math.ceil(maxTokens * (bufferPercent / 100.0)));
    }

    private String modelName(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options != null && StringUtils.hasText(options.getModel())) {
            return options.getModel();
        }
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.defaultModel();
        }
        AgentConfig defaultAgent = aiConfig.agents().get(aiConfig.defaultAgent());
        return aiConfig.models().get(defaultAgent.model()).remoteModelName();
    }

    private String defaultSystemPrompt() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.defaultSystemPrompt();
        }
        if (aiConfig == null || !StringUtils.hasText(aiConfig.defaultAgent()) || aiConfig.agents() == null) {
            return null;
        }
        AgentConfig defaultAgent = aiConfig.agents().get(aiConfig.defaultAgent());
        return defaultAgent == null ? null : defaultAgent.systemPrompt();
    }

    private String keyForRemoteModel(String remoteModelName) {
        if (!StringUtils.hasText(remoteModelName)) {
            return null;
        }
        return aiConfig.models().entrySet().stream()
            .filter(entry -> remoteModelName.equals(entry.getValue().remoteModelName()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(remoteModelName);
    }

    private String resolveCompactionModelReference() {
        if (runtimeSettingsService == null) {
            return aiConfig.resolvedCompactionModelKey();
        }
        String runtimeCompaction = keyForRemoteModel(runtimeSettingsService.compactionModel());
        if (StringUtils.hasText(runtimeCompaction)) {
            return runtimeCompaction;
        }
        return aiConfig.resolvedCompactionModelKey();
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return StringUtils.hasText(message) ? message : throwable.getClass().getSimpleName();
    }

    private String conversationId(Map<String, Object> context) {
        Object conversationId = context.get(ChatMemory.CONVERSATION_ID);
        if (conversationId instanceof String value && StringUtils.hasText(value)) {
            return value;
        }
        return "default";
    }

    private record PreparedRequest(ChatClientRequest request, String conversationId, String model) {
    }

    public record PreparedPrompt(List<Message> messages, ContextUsage usage) {
    }

    public record ToolLoopPrompt(
        List<Message> messages,
        List<Message> activeMessages,
        ContextUsage usage,
        boolean toolUseAllowed,
        boolean compacted
    ) {
    }

    public record StoredContextMaintenance(
        ContextUsage usage,
        boolean compacted,
        boolean degraded,
        String degradationReason
    ) {
    }

    private static final class ContextCompactionSummaryException extends RuntimeException {
        private ContextCompactionSummaryException(String message) {
            super(message);
        }

        private ContextCompactionSummaryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
