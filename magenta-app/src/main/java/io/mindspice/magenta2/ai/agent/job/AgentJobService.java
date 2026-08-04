package io.mindspice.magenta2.ai.agent.job;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.agent.job.AgentJobRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.execution.MagentaWorkExecutor;
import io.mindspice.magenta2.ai.execution.MagentaWorkRequest;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentJobService {

    static final int MAX_TITLE_LENGTH = 60;

    static final String TITLE_SYSTEM_PROMPT = """
        You write concise conversation titles for Magenta chat sessions.
        Return only a short title that identifies the topic or focus of the conversation.
        Maximum length: %d characters. Do not use quotes, markdown, commentary, or punctuation-heavy labels.
        """.formatted(MAX_TITLE_LENGTH);

    private final AgentJobRepository agentJobRepository;
    private final ChatSessionMetadataRepository chatSessionMetadataRepository;
    private final ChatModelRouter chatModelRouter;
    private final AiConfig aiConfig;
    private final ObjectMapper objectMapper;
    private final MagentaWorkExecutor magentaWorkExecutor;
    private final RuntimeSettingsService runtimeSettingsService;

    @Autowired
    public AgentJobService(
        AgentJobRepository agentJobRepository,
        ChatSessionMetadataRepository chatSessionMetadataRepository,
        ChatModelRouter chatModelRouter,
        AiConfig aiConfig,
        @Autowired(required = false) RuntimeSettingsService runtimeSettingsService,
        ObjectMapper objectMapper,
        MagentaWorkExecutor magentaWorkExecutor
    ) {
        this.agentJobRepository = agentJobRepository;
        this.chatSessionMetadataRepository = chatSessionMetadataRepository;
        this.chatModelRouter = chatModelRouter;
        this.aiConfig = aiConfig;
        this.runtimeSettingsService = runtimeSettingsService;
        this.objectMapper = objectMapper;
        this.magentaWorkExecutor = magentaWorkExecutor;
    }

    public Optional<AgentJob> submitConversationTitle(String conversationId, String selectedModel, String firstUserMessage) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(firstUserMessage)) {
            return Optional.empty();
        }
        String titleModel = titleModel();
        if (!StringUtils.hasText(titleModel)) {
            return Optional.empty();
        }
        if (StringUtils.hasText(chatSessionMetadataRepository.findTitle(conversationId).orElse(null))) {
            return Optional.empty();
        }
        if (agentJobRepository.latestStatus(AgentJobType.CONVERSATION_TITLE, conversationId).isPresent()) {
            return Optional.empty();
        }
        Optional<AgentJob> enqueued = agentJobRepository.enqueue(
            UUID.randomUUID().toString(),
            AgentJobType.CONVERSATION_TITLE,
            conversationId,
            titleModel,
            json(Map.of("firstUserMessage", firstUserMessage))
        );
        enqueued.ifPresent(job -> {
            magentaWorkExecutor.submitBackground(
                job.conversationId(),
                MagentaWorkRequest.BACKGROUND_PRIORITY,
                "conversation title " + job.conversationId(),
                () -> {
                    runConversationTitleJob(job.id());
                    return null;
                }
            );
        });
        return enqueued;
    }

    public Optional<AgentJobStatus> latestConversationTitleStatus(String conversationId) {
        return agentJobRepository.latestStatus(AgentJobType.CONVERSATION_TITLE, conversationId);
    }

    void runConversationTitleJob(String jobId) {
        AgentJob job = agentJobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown agent job: " + jobId));
        agentJobRepository.markRunning(job.id());
        try {
            String title = generateTitle(job.selectedModel(), firstUserMessage(job.inputJson()));
            if (StringUtils.hasText(title)) {
                chatSessionMetadataRepository.saveTitleIfAbsent(job.conversationId(), title);
            }
            agentJobRepository.markSucceeded(job.id(), json(Map.of("title", title == null ? "" : title)));
        } catch (RuntimeException exception) {
            agentJobRepository.markFailed(job.id(), exception.getMessage());
            throw exception;
        }
    }

    String generateTitle(String selectedModel, String firstUserMessage) {
        ChatClientResponse response = chatModelRouter.chatClient(selectedModel)
            .prompt(new Prompt(
                java.util.List.of(
                    new org.springframework.ai.chat.messages.SystemMessage(TITLE_SYSTEM_PROMPT),
                    new org.springframework.ai.chat.messages.UserMessage("Title this conversation:\n\n" + firstUserMessage)
                ),
                chatModelRouter.basicChatOptions(selectedModel)
            ))
            .call()
            .chatClientResponse();
        String rawTitle = response.chatResponse().getResult().getOutput().getText();
        return cleanTitle(rawTitle);
    }

    private String titleModel() {
        if (runtimeSettingsService != null) {
            return runtimeSettingsService.summaryModel();
        }
        if (aiConfig == null || aiConfig.models() == null) {
            return null;
        }
        ModelConfig model = aiConfig.models().get(aiConfig.resolvedSummaryModelKey());
        return model == null ? null : model.remoteModelName();
    }

    String cleanTitle(String rawTitle) {
        if (!StringUtils.hasText(rawTitle)) {
            return null;
        }
        String title = rawTitle
            .replaceAll("(?is)<think>.*?</think>", "")
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replaceAll("\\s+", " ")
            .trim();
        title = title.replaceAll("^[\\s\"'`]+|[\\s\"'`]+$", "");
        if (!StringUtils.hasText(title)) {
            return null;
        }
        if (title.length() <= MAX_TITLE_LENGTH) {
            return title;
        }
        return title.substring(0, MAX_TITLE_LENGTH).replaceAll("\\s+\\S*$", "").trim();
    }

    private String firstUserMessage(String inputJson) {
        try {
            Map<String, String> input = objectMapper.readValue(inputJson, new TypeReference<>() { });
            return input.getOrDefault("firstUserMessage", "");
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse agent job input", exception);
        }
    }

    private String json(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize agent job payload", exception);
        }
    }
}
