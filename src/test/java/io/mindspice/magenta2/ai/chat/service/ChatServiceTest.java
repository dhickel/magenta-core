package io.mindspice.magenta2.ai.chat.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.repository.ChatPendingMessageRepository;
import io.mindspice.magenta2.ai.chat.plan.PlanRepository;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.repository.RepositoryBackedChatMemory;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileToolService;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentsMdResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class ChatServiceTest {

    @Test
    void extractsReasoningFromDedicatedDeepSeekMessage() {
        var message = org.springframework.ai.deepseek.DeepSeekAssistantMessage.builder()
            .content("final answer")
            .reasoningContent("reasoning trace")
            .build();
        var generation = new org.springframework.ai.chat.model.Generation(message);

        assertThat(ChatService.thinkingText(generation)).isEqualTo("reasoning trace");
    }

    @TempDir
    Path tempDir;

    @Test
    void resolvingSavedPlanExecutionPreservesExistingTranscriptRows() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
            null,
            null,
            null,
            null,
            null,
            null,
            planService
        );

        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("User planning request"),
            AssistantMessage.builder().content("Assistant planning response").build()
        ));
        planService.beginPlan("conversation-1");
        planService.saveDraftPlan(
            "conversation-1",
            "Preserve chat history",
            "Transcript Plan",
            "Keep existing chat transcript rows.",
            null,
            List.of("Resolve saved plan execution"),
            List.of("Existing transcript rows are present"),
            List.of("History readback still contains the original user and assistant messages")
        );
        planService.approvePlan("conversation-1");

        ResolvedChatRequest request = chatService.resolveSavedPlanExecution("conversation-1");

        assertThat(request.conversationId()).isEqualTo("conversation-1");
        assertThat(planService.activePlan("conversation-1").orElseThrow().status())
            .isEqualTo(PlanStatus.EXECUTING);
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(message -> message.getText())
            .containsExactly("User planning request", "Assistant planning response");
    }

    @Test
    void pendingMessagesAreOutsideHistoryUntilSentAndClearDeletesQueue() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ChatPendingMessageService pendingMessageService = new ChatPendingMessageService(
            new ChatPendingMessageRepository(jdbcTemplate)
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
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
            pendingMessageService,
            null
        );

        pendingMessageService.enqueue("conversation-1", "queued only", "main", null, ChatSessionSurface.BROWSER);

        assertThat(chatService.history("conversation-1")).isEmpty();
        assertThat(pendingMessageService.list("conversation-1")).hasSize(1);

        chatService.clearConversation("conversation-1");

        assertThat(pendingMessageService.list("conversation-1")).isEmpty();
    }

    @Test
    void modelSelectionKeyPrefersConfiguredDefaultAliasForAmbiguousRemoteNames() {
        Map<String, ModelConfig> models = new LinkedHashMap<>();
        models.put("deepseek-v4", new ModelConfig(
            "deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, "sk-test"
        ));
        models.put("deepseek-v4-max", new ModelConfig(
            "deepseek-v4-pro", "https://api.deepseek.com", EndpointType.DEEPSEEK, 128000, 4, "sk-test"
        ));
        AiConfig aiConfig = new AiConfig(
            "default-agent",
            "deepseek-v4-max",
            "deepseek-v4",
            "deepseek-v4",
            null,
            10,
            Path.of("."),
            null,
            models,
            Map.of("default-agent", new AgentConfig("deepseek-v4", "You are Magenta.", List.of()))
        );
        ChatService chatService = new ChatService(null, null, null, null, aiConfig);

        assertThat(chatService.defaultModel()).isEqualTo("deepseek-v4-max");
        assertThat(chatService.modelSelectionKey("deepseek-v4-pro")).isEqualTo("deepseek-v4-max");
    }


    @Test
    void resolvingCleanSavedPlanExecutionFlagsPromptContextWithoutClearingTranscriptRows() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
            null,
            null,
            null,
            null,
            null,
            null,
            planService
        );

        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("User planning request"),
            AssistantMessage.builder().content("Assistant planning response").build()
        ));
        planService.beginPlan("conversation-1");
        planService.saveDraftPlan(
            "conversation-1",
            "Preserve chat history",
            "Transcript Plan",
            "Keep existing chat transcript rows.",
            null,
            List.of("Resolve saved plan execution"),
            List.of("Existing transcript rows are present"),
            List.of("History readback still contains the original user and assistant messages")
        );
        planService.approvePlan("conversation-1");

        ResolvedChatRequest request = chatService.resolveSavedPlanExecution("conversation-1", true);

        assertThat(request.omitStoredMessages()).isTrue();
        assertThat(request.message()).contains("Approved anonymous plan");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(message -> message.getText())
            .containsExactly("User planning request", "Assistant planning response");
    }

    @Test
    void streamExecutionFinalizationMarksStillExecutingPlanNeedsReviewInsteadOfCompleted() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
            null,
            null,
            null,
            null,
            null,
            null,
            planService
        );

        memoryRepository.saveAll("conversation-1", List.of(new UserMessage("execute this")));
        planService.beginPlan("conversation-1");
        planService.saveDraftPlan(
            "conversation-1",
            "Gate completion",
            "Completion Gate",
            "Do not trust ordinary assistant text.",
            null,
            List.of("Validated result"),
            List.of("Run work"),
            List.of("plan_complete validates completion")
        );
        planService.markExecuting("conversation-1");
        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("execute this"),
            AssistantMessage.builder().content("Ordinary model completion").build()
        ));

        chatService.handlePlanExecutionStreamFinished("conversation-1", "Ordinary model completion");

        assertThat(planService.activePlan("conversation-1").orElseThrow().status())
            .isEqualTo(PlanStatus.NEEDS_REVIEW);
        assertThat(planService.activePlan("conversation-1").orElseThrow().finalMessage()).isNull();
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(message -> message.getText())
            .containsExactly(
                "execute this",
                "Plan execution needs review. Magenta could not verify completion through plan_complete after retrying. Review the saved execution evidence and validation feedback before trusting the result."
            );
    }

    @Test
    void streamExecutionFinalizationPersistsFinalArtifactOnlyAfterValidatedCompletion() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        Path dataRoot = Files.createDirectories(tempDir.resolve("validated-stream-data"));
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        PlanService planService = new PlanService(
            new PlanRepository(jdbcTemplate, objectMapper),
            memoryRepository,
            null,
            new ChatMarkdownRenderer(),
            directoryService,
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(dataRoot),
            null,
            null,
            null,
            null,
            null,
            null,
            planService
        );

        planService.beginPlan("conversation-validated");
        planService.saveDraftPlan(
            "conversation-validated",
            "Gate completion",
            "Validated Completion",
            "Persist only validator-approved final output.",
            null,
            List.of("Validated result"),
            List.of("Run work"),
            List.of("plan_complete validates completion")
        );
        planService.markExecuting("conversation-validated");
        planService.markCompleted("conversation-validated", "Validated final message");

        chatService.handlePlanExecutionStreamFinished("conversation-validated", "Validated final message");

        Path finalMessage = dataRoot.resolve("workspace/chat/conversation-validated/final-message.md");
        assertThat(finalMessage).exists();
        assertThat(Files.readString(finalMessage)).isEqualTo("Validated final message\n");
    }

    @Test
    void maintainContextUsageReturnsDegradedUsageWhenMaintenanceThrows() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
            new ThrowingMaintenanceAdvisor(),
            usageTracker,
            null,
            null,
            null,
            null,
            null
        );

        StoredContextUsage usage = chatService.maintainContextUsage("conversation-1", "qwen3");

        assertThat(usage.degraded()).isTrue();
        assertThat(usage.compacted()).isFalse();
        assertThat(usage.degradationReason()).contains("Context maintenance failed");
        assertThat(usage.usage()).isEqualTo(new ContextUsage(1200, 1200, 1080, 100.0));
        assertThat(usageTracker.find("conversation-1")).isEqualTo(usage.usage());
    }

    @Test
    void listSessionsIncludesChatFileOutputCount() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        Path dataRoot = Files.createDirectories(tempDir.resolve("new-magenta-root/root"));
        ChatFileService chatFileService = new ChatFileService(
            WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot)
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(dataRoot),
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
            objectMapper,
            null,
            null,
            null,
            chatFileService,
            null,
            null
        );
        String conversationId = "00000000-0000-0000-0000-000000000001";
        memoryRepository.saveAll(conversationId, List.of(new UserMessage("hello")));
        metadataRepository.saveSurfaceIfAbsent(conversationId, ChatSessionSurface.BROWSER);
        // Phase 02: chat files are now under workspace/chat/{conversationId}
        Path files = Files.createDirectories(dataRoot.resolve("workspace/chat/" + conversationId + "/nested"));
        Files.writeString(files.resolve("seeded.md"), "seeded");

        assertThat(chatService.listSessions())
            .singleElement()
            .extracting(ChatSession::outputCount)
            .isEqualTo(1);
    }

    @Test
    void listSessionsOnlyIncludesBrowserSurfaceNormalChats() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            null,
            aiConfig(),
            null,
            null,
            null,
            null,
            null,
            null,
            planService
        );

        String browserConversationId = "00000000-0000-0000-0000-000000000010";
        String internalConversationId = "00000000-0000-0000-0000-000000000011";
        String agentConversationId = "00000000-0000-0000-0000-000000000012";
        String planningConversationId = "00000000-0000-0000-0000-000000000013";

        memoryRepository.saveAll(browserConversationId, List.of(new UserMessage("browser")));
        metadataRepository.saveSurfaceIfAbsent(browserConversationId, ChatSessionSurface.BROWSER);

        memoryRepository.saveAll(internalConversationId, List.of(new UserMessage("internal")));
        metadataRepository.saveSurfaceIfAbsent(internalConversationId, ChatSessionSurface.INTERNAL);

        memoryRepository.saveAll(agentConversationId, List.of(new UserMessage("agent")));
        metadataRepository.saveOriginIfAbsent(agentConversationId, io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin.AGENT_CHAT, "agent-1");

        memoryRepository.saveAll(planningConversationId, List.of(new UserMessage("plan")));
        planService.beginPlan(planningConversationId);

        assertThat(chatService.listSessions())
            .extracting(ChatSession::conversationId)
            .containsExactly(browserConversationId);
    }

    @Test
    void malformedToolCallArgumentsAreReportedWithoutExecutingTools() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ToolTranscriptService transcriptService = new ToolTranscriptService(objectMapper);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        MalformedThenFinalChatModel chatModel = new MalformedThenFinalChatModel();
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ChatToolRegistry toolRegistry = new ChatToolRegistry(List.of(new SampleToolCallback()), List.of());
        ChatModelRouter router = new TestChatModelRouter(chatModel);
        AiConfig config = aiConfig(Path.of("."), List.of("sample_tool"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            transcriptService,
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            toolCallingManager,
            toolRegistry,
            transcriptService,
            null
        );

        var response = chatService.chat("conversation-1", "use the tool", "main");

        assertThat(response.response()).isEqualTo("Recovered after diagnostic.");
        assertThat(chatModel.prompts()).hasSize(2);
        assertThat(chatModel.prompts().get(1)).contains("malformed JSON arguments");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .anySatisfy(text -> assertThat(text).contains("Malformed tool-call arguments"))
            .anySatisfy(text -> assertThat(text).contains("no tools in that batch were executed"))
            .contains("Recovered after diagnostic.");
        assertThat(response.toolActivities())
            .singleElement()
            .satisfies(activity -> {
                assertThat(activity.toolName()).isEqualTo("sample_tool");
                assertThat(activity.status()).isEqualTo("error");
                assertThat(activity.summary()).contains("Malformed tool-call arguments");
            });
        verifyNoInteractions(toolCallingManager);
    }

    @Test
    void toolExecutionArgumentFailuresAreReportedAndRetried() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ToolTranscriptService transcriptService = new ToolTranscriptService(objectMapper);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ToolExecutionFailureThenFinalChatModel chatModel = new ToolExecutionFailureThenFinalChatModel();
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolDefinition definition = new DefaultToolDefinition("sample_tool", "Sample test tool", "{\"type\":\"object\"}");
        when(toolCallingManager.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new ToolExecutionException(definition, new IllegalArgumentException(
                "Conversion from JSON to java.util.List<String> failed"
            )));
        ChatToolRegistry toolRegistry = new ChatToolRegistry(List.of(new SampleToolCallback()), List.of());
        ChatModelRouter router = new TestChatModelRouter(chatModel);
        AiConfig config = aiConfig(Path.of("."), List.of("sample_tool"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            transcriptService,
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            toolCallingManager,
            toolRegistry,
            transcriptService,
            null
        );

        var response = chatService.chat("conversation-1", "ask a question", "main");

        assertThat(response.toolActivities())
            .singleElement()
            .satisfies(activity -> {
                assertThat(activity.toolName()).isEqualTo("sample_tool");
                assertThat(activity.status()).isEqualTo("error");
                assertThat(activity.summary()).contains("Malformed tool-call arguments");
            });
        assertThat(chatModel.prompts()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(chatModel.prompts().get(1)).contains("failed before Magenta could apply them");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .anySatisfy(text -> assertThat(text).contains("Malformed tool-call arguments"))
            .anySatisfy(text -> assertThat(text).contains("failed before Magenta could apply them"));
    }

    @Test
    void toolLoopRefreshesAgentsMdContextFromActualFileToolTargets() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ToolTranscriptService transcriptService = new ToolTranscriptService(objectMapper);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        Path dataRoot = Files.createDirectories(tempDir.resolve("agents-md-tool-loop"));
        Path workspaceRoot = Files.createDirectories(dataRoot.resolve("workspace/agent/agent-1"));
        Path outputRoot = Files.createDirectories(workspaceRoot.resolve("runs/run-1/outputs"));
        Files.createDirectories(workspaceRoot.resolve("a"));
        Files.createDirectories(workspaceRoot.resolve("b"));
        Files.writeString(workspaceRoot.resolve("AGENTS.md"), "workspace-root-guidance");
        Files.writeString(workspaceRoot.resolve("a/AGENTS.md"), "nested-a-guidance");
        Files.writeString(workspaceRoot.resolve("b/AGENTS.md"), "nested-b-guidance");
        Files.writeString(workspaceRoot.resolve("a/file.txt"), "a\n");
        Files.writeString(workspaceRoot.resolve("b/file.txt"), "b\n");

        AiConfig config = aiConfig(dataRoot, List.of("file_read"));
        AgentFileToolService fileTool = new AgentFileToolService(config);
        FileToolPathSwitchChatModel chatModel = new FileToolPathSwitchChatModel();
        ChatModelRouter router = new TestChatModelRouter(chatModel);
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            transcriptService,
            null
        );
        ChatToolRegistry toolRegistry = new ChatToolRegistry(List.of(new NamedToolCallback("file_read")), List.of());
        RequestResolver requestResolver = new RequestResolver(
            config,
            metadataRepository,
            memoryRepository,
            null,
            null,
            null,
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            new RuntimeFileToolCallingManager(fileTool, objectMapper),
            toolRegistry,
            transcriptService,
            null,
            null,
            null,
            null,
            null,
            objectMapper,
            null,
            null,
            requestResolver,
            null,
            null,
            null,
            new AgentsMdResolver()
        );
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            null,
            "TASK_RUN",
            workspaceRoot.toString(),
            outputRoot.toString(),
            null,
            workspaceRoot.toString(),
            null, null, null, null, null));

        try {
            ChatResponse.MsgResponse response = chatService.chat("conversation-1", "read both files", "main");

            assertThat(response.response()).isEqualTo("done");
            assertThat(chatModel.prompts()).hasSize(3);
            assertThat(chatModel.prompts().get(1)).contains("nested-a-guidance");
            assertThat(chatModel.prompts().get(1)).doesNotContain("nested-b-guidance");
            assertThat(chatModel.prompts().get(2)).contains("nested-b-guidance");
            assertThat(chatModel.prompts().get(2)).doesNotContain("nested-a-guidance");
            assertThat(OrchestrationTaskContextHolder.current().activeRuntimePath())
                .isEqualTo("b/file.txt");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void wrappedIoFailuresAreRetriedAsTransientModelFailures() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ToolTranscriptService transcriptService = new ToolTranscriptService(objectMapper);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        WrappedIoFailureThenFinalChatModel chatModel = new WrappedIoFailureThenFinalChatModel();
        ChatToolRegistry toolRegistry = new ChatToolRegistry(List.of(new SampleToolCallback()), List.of());
        ChatModelRouter router = new TestChatModelRouter(chatModel);
        AiConfig config = aiConfig(Path.of("."), List.of("sample_tool"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            transcriptService,
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            mock(ToolCallingManager.class),
            toolRegistry,
            transcriptService,
            null
        );

        var response = chatService.chat("conversation-1", "recover from closed stream", "main");

        assertThat(response.response()).isEqualTo("Recovered after transient provider close.");
        assertThat(chatModel.calls.get()).isEqualTo(2);
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .containsExactly("recover from closed stream", "Recovered after transient provider close.");
    }

    @Test
    void planningAnswerModelFailureReturnsControlledResponseAfterSavingAnswer() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ChatModelRouter router = new TestChatModelRouter(new AuthenticationFailureChatModel());
        AiConfig config = aiConfig();
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            new ToolTranscriptService(objectMapper),
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            null,
            null,
            null,
            planService
        );

        planService.beginPlan("conversation-1");
        planService.askQuestions("conversation-1", List.of("What should Magenta build?"));

        var response = chatService.submitPlanAnswer("conversation-1", "A reliable planning flow.", null, 1);

        assertThat(response.response()).contains("I saved your planning answer");
        assertThat(response.planState().promptQuestion())
            .isEqualTo("What should we clarify, change, or add before continuing this plan?");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .contains(
                "Planning answer\n\nQuestion: What should Magenta build?\n\nAnswer: A reliable planning flow.",
                "I saved your planning answer, but Magenta could not continue the planning turn because the configured planning model request failed. Check the selected planning model and API credentials, then send another planning message to continue."
            );
    }

    @Test
    void planningAnswerToolFailureReturnsControlledResponseAfterSavingAnswer() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ChatModelRouter router = new TestChatModelRouter(new ToolCallingChatModel());
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        when(toolCallingManager.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new RuntimeException("web_search connection refused"));
        AiConfig config = aiConfig(Path.of("."), List.of("ask_user_questions"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            new ToolTranscriptService(objectMapper),
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            toolCallingManager,
            new ChatToolRegistry(List.of(new NamedToolCallback("ask_user_questions")), List.of()),
            new ToolTranscriptService(objectMapper),
            planService
        );

        planService.beginPlan("conversation-1");
        planService.askQuestions("conversation-1", List.of("What should Magenta build?"));

        var response = chatService.submitPlanAnswer("conversation-1", "A reliable planning flow.", null, 1);

        assertThat(response.response()).contains("I saved your planning answer");
        assertThat(response.response()).contains("planning tool or model call failed");
        assertThat(response.planState().promptQuestion())
            .isEqualTo("What should we clarify, change, or add before continuing this plan?");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .contains(
                "Planning answer\n\nQuestion: What should Magenta build?\n\nAnswer: A reliable planning flow.",
                "I saved your planning answer, but Magenta could not continue the planning turn because a planning tool or model call failed. Check the unavailable service or model configuration, then send another planning message to continue."
            );
    }

    @Test
    void missingPlanningQuestionReturnsRecoverablePromptInsteadOfThrowing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ChatModelRouter router = new TestChatModelRouter(new FixedChatModel("unused"));
        AiConfig config = aiConfig();
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            new ToolTranscriptService(objectMapper),
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            null,
            null,
            null,
            planService
        );

        planService.beginPlan("conversation-1");
        planService.recordPromptAnswer("conversation-1", "Goal", null, 1);
        planService.recordPromptAnswer("conversation-1", "Assumptions", null, 2);
        planService.recordPromptAnswer("conversation-1", "Deliverables", null, 3);

        var response = chatService.submitPlanAnswer("conversation-1", "stale duplicate", null, 3);

        assertThat(response.response()).contains("older prompt");
        assertThat(response.planState().promptQuestion())
            .isEqualTo("What should we clarify, change, or add before continuing this plan?");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .contains("That planning answer was for an older prompt. I refreshed the current planning prompt so you can continue.");
    }

    @Test
    void stalePlanningAnswerIndexReturnsCurrentPromptInsteadOfThrowing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper), memoryRepository);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        ChatModelRouter router = new TestChatModelRouter(new FixedChatModel("unused"));
        AiConfig config = aiConfig();
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            new ToolTranscriptService(objectMapper),
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            null,
            null,
            null,
            planService
        );

        planService.beginPlan("conversation-1");
        planService.recordPromptAnswer("conversation-1", "Goal", null, 1);

        var response = chatService.submitPlanAnswer("conversation-1", "stale duplicate", null, 1);

        assertThat(response.response()).contains("older prompt");
        assertThat(response.planState().promptQuestion())
            .isEqualTo("What assumptions, details, expectations, constraints, or preferred approach should guide the plan?");
        assertThat(response.planState().promptQuestionIndex()).isEqualTo(2);
    }

    @Test
    void chatAsAgentUsesAgentModelAndMarksAgentSession() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ContextUsageTracker usageTracker = new ContextUsageTracker();
        AiConfig config = aiConfig();
        ChatModelRouter router = new TestChatModelRouter(new FixedChatModel("Agent answer"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            new ToolTranscriptService(objectMapper),
            null
        );
        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            null,
            null,
            null,
            null
        );
        AgentProfile agent = new AgentProfile(
            "agent-1",
            "Agent One",
            AgentProfileStatus.ACTIVE,
            "main",
            "system",
            List.of(),
            List.of(),
            true,
            null,
            null
        );

        ChatResponse.MsgResponse response = (ChatResponse.MsgResponse) chatService.chatAsAgent(
            agent,
            new io.mindspice.magenta2.ai.chat.model.ChatRequest.MsgRequest(
                null, "hello from side panel", null, null
            )
        );

        assertThat(response.model()).isEqualTo("main");
        assertThat(response.response()).isEqualTo("Agent answer");
        assertThat(metadataRepository.findAgentConversationIds("agent-1"))
            .containsExactly(response.conversationId());
        assertThat(memoryRepository.findByConversationId(response.conversationId()))
            .extracting(Message::getText)
            .containsExactly("hello from side panel", "Agent answer");
    }

    @Test
    void fileMutatingToolNamesContainsExpectedTools() {
        assertThat(ChatService.FILE_MUTATING_TOOL_NAMES)
            .containsExactlyInAnyOrder("file_write", "file_append", "file_replace", "file_delete");
    }

    @Test
    void fileWriteToolProducesOutputsChangedStreamSignal() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
        ToolTranscriptService transcriptService = new ToolTranscriptService(objectMapper);
        ContextUsageTracker usageTracker = new ContextUsageTracker();

        FileWriteThenFinalChatModel chatModel = new FileWriteThenFinalChatModel();
        ChatModelRouter router = new TestChatModelRouter(chatModel);
        AiConfig config = aiConfig(Path.of("."), List.of("file_write"));
        ContextManagementAdvisor contextAdvisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            router,
            new CharacterTokenEstimator(),
            usageTracker,
            transcriptService,
            null
        );

        ToolCallingManager toolCallingManager = new SuccessToolCallingManager();
        ChatToolRegistry toolRegistry = new ChatToolRegistry(
            List.of(new NamedToolCallback("file_write")), List.of()
        );

        ChatService chatService = new ChatService(
            new RepositoryBackedChatMemory(memoryRepository),
            memoryRepository,
            metadataRepository,
            new ChatMarkdownRenderer(),
            config,
            contextAdvisor,
            usageTracker,
            router,
            toolCallingManager,
            toolRegistry,
            transcriptService,
            null
        );

        var messages = chatService.stream(
            new ResolvedChatRequest("conv-1", "write a file", "main")
        ).collectList().block();

        assertThat(messages).isNotNull();
        assertThat(messages).anySatisfy(msg -> {
            assertThat(msg.role()).isEqualTo("outputs_changed");
            assertThat(msg.text()).isEqualTo("conv-1");
        });
        // Read-only tools do not produce outputs_changed signals
        long outputsChangedCount = messages.stream()
            .filter(msg -> "outputs_changed".equals(msg.role()))
            .count();
        assertThat(outputsChangedCount).isEqualTo(1);
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }

    private AiConfig aiConfig() {
        return aiConfig(Path.of("."));
    }

    private AiConfig aiConfig(Path dataRoot) {
        return aiConfig(dataRoot, List.of());
    }

    private AiConfig aiConfig(Path dataRoot, List<String> approvedTools) {
        return new AiConfig(
            "default-agent",
            "main",
            "main",
            "main",
            null,
            10,
            dataRoot,
            null,
            Map.of("main", new ModelConfig("qwen3", "http://localhost:11434", EndpointType.OLLAMA, 8192, null, null)),
            Map.of("default-agent", new AgentConfig("main", "You are Magenta.", approvedTools))
        );
    }

    private static final class MalformedThenFinalChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new java.util.ArrayList<>();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n")));
            if (calls.getAndIncrement() == 0) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "sample_tool",
                        "{\"path\":"
                    )))
                    .build();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("Recovered after diagnostic."))
            ));
        }

        List<String> prompts() {
            return prompts;
        }
    }

    private static final class ToolExecutionFailureThenFinalChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new java.util.ArrayList<>();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n")));
            if (calls.getAndIncrement() == 0) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "sample_tool",
                        "{\"questions\":[{\"question\":\"What should happen next?\"}]}"
                    )))
                    .build();
                return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(toolCallMessage)));
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("Recovered after tool execution diagnostic."))
            ));
        }

        List<String> prompts() {
            return prompts;
        }
    }

    private static final class FileToolPathSwitchChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new java.util.ArrayList<>();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getInstructions().stream()
                .map(Message::getText)
                .collect(Collectors.joining("\n")));
            int call = calls.getAndIncrement();
            if (call == 0) {
                return toolCallResponse("call-a", "{\"path\":\"a/file.txt\",\"startLine\":1,\"maxLines\":10}");
            }
            if (call == 1) {
                return toolCallResponse("call-b", "{\"path\":\"b/file.txt\",\"startLine\":1,\"maxLines\":10}");
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("done"))
            ));
        }

        List<String> prompts() {
            return prompts;
        }

        private org.springframework.ai.chat.model.ChatResponse toolCallResponse(String id, String arguments) {
            AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", "file_read", arguments)))
                .build();
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(toolCallMessage)));
        }
    }

    private static final class RuntimeFileToolCallingManager implements ToolCallingManager {
        private final AgentFileToolService fileTool;
        private final ObjectMapper objectMapper;

        private RuntimeFileToolCallingManager(AgentFileToolService fileTool, ObjectMapper objectMapper) {
            this.fileTool = fileTool;
            this.objectMapper = objectMapper;
        }

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of(new DefaultToolDefinition("file_read", "Read a file", "{\"type\":\"object\"}"));
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, org.springframework.ai.chat.model.ChatResponse response) {
            AssistantMessage assistantMessage = response.getResult().getOutput();
            AssistantMessage.ToolCall toolCall = assistantMessage.getToolCalls().getFirst();
            try {
                String path = objectMapper.readTree(toolCall.arguments()).path("path").asText();
                AgentFileToolService.FileReadResult result = fileTool.read(path, 1, 10);
                ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                        toolCall.id(),
                        toolCall.name(),
                        objectMapper.writeValueAsString(result)
                    )))
                    .build();
                List<Message> history = new java.util.ArrayList<>(prompt.getInstructions());
                history.add(assistantMessage);
                history.add(toolResponse);
                return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .build();
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        }
    }

    private static final class WrappedIoFailureThenFinalChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            if (calls.getAndIncrement() == 0) {
                throw new RestClientException(
                    "Error while extracting response for type [ChatCompletion]",
                    new IOException("closed")
                );
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("Recovered after transient provider close."))
            ));
        }
    }

    private static final class TestChatModelRouter extends ChatModelRouter {
        private final ChatModel chatModel;

        TestChatModelRouter(ChatModel chatModel) {
            super(null, null, null);
            this.chatModel = chatModel;
        }

        @Override
        public ChatModel chatModel(String model) {
            return chatModel;
        }

        @Override
        public ToolCallingChatOptions toolCallingOptions(String model) {
            return OllamaChatOptions.builder().model("qwen3").build();
        }

        @Override
        public ToolCallingChatOptions chatOptions(String model) {
            return toolCallingOptions(model);
        }
    }

    private static final class AuthenticationFailureChatModel implements ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            throw new NonTransientAiException(
                "401 - {\"error\":{\"message\":\"Authentication Fails, Your api key is invalid\"}}"
            );
        }
    }

    private static final class FixedChatModel implements ChatModel {
        private final String response;

        private FixedChatModel(String response) {
            this.response = response;
        }

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage(response))
            ));
        }
    }

    private static final class ToolCallingChatModel implements ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                    "call-1",
                    "function",
                    "ask_user_questions",
                    "{\"questions\":[\"What should happen next?\"]}"
                )))
                .build();
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new Generation(toolCallMessage)));
        }
    }

    private static final class ThrowingMaintenanceAdvisor extends ContextManagementAdvisor {
        private ThrowingMaintenanceAdvisor() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public StoredContextMaintenance maintainStoredContext(String conversationId, String remoteModelName) {
            throw new RuntimeException("compaction endpoint unavailable");
        }

        @Override
        public ContextUsage estimateStoredUsage(String conversationId, String remoteModelName) {
            return new ContextUsage(1200, 1200, 1080, 100.0);
        }
    }

    private static final class SampleToolCallback implements ToolCallback {
        private final ToolDefinition definition = new DefaultToolDefinition(
            "sample_tool",
            "Sample test tool",
            "{\"type\":\"object\"}"
        );

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "{\"ok\":true}";
        }
    }

    private static final class NamedToolCallback implements ToolCallback {
        private final ToolDefinition definition;

        private NamedToolCallback(String name) {
            this.definition = new DefaultToolDefinition(name, "Named test tool", "{\"type\":\"object\"}");
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String toolInput) {
            return "{\"ok\":true}";
        }
    }

    private static final class FileWriteThenFinalChatModel implements ChatModel {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            if (calls.getAndIncrement() == 0) {
                AssistantMessage toolCallMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "file_write",
                        "{\"path\":\"output.md\",\"content\":\"hello\"}"
                    )))
                    .build();
                return new org.springframework.ai.chat.model.ChatResponse(
                    List.of(new Generation(toolCallMessage))
                );
            }
            return new org.springframework.ai.chat.model.ChatResponse(List.of(
                new Generation(new AssistantMessage("done"))
            ));
        }
    }

    private static final class SuccessToolCallingManager implements ToolCallingManager {
        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions options) {
            return List.of(new DefaultToolDefinition(
                "file_write", "Write a file", "{\"type\":\"object\"}"
            ));
        }

        @Override
        public ToolExecutionResult executeToolCalls(
            Prompt prompt,
            org.springframework.ai.chat.model.ChatResponse response
        ) {
            AssistantMessage assistantMessage = response.getResult().getOutput();
            AssistantMessage.ToolCall toolCall = assistantMessage.getToolCalls().getFirst();
            ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                    toolCall.id(),
                    toolCall.name(),
                    "{\"ok\":true,\"path\":\"output.md\"}"
                )))
                .build();
            List<Message> history = new java.util.ArrayList<>(prompt.getInstructions());
            history.add(assistantMessage);
            history.add(toolResponse);
            return ToolExecutionResult.builder()
                .conversationHistory(history)
                .build();
        }
    }

    private static final class CharacterTokenEstimator implements TokenCountEstimator {
        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimate(MediaContent content) {
            return content == null ? 0 : estimate(content.getText());
        }

        @Override
        public int estimate(Iterable<MediaContent> contents) {
            int total = 0;
            if (contents != null) {
                for (MediaContent content : contents) {
                    total += estimate(content);
                }
            }
            return total;
        }
    }
}
