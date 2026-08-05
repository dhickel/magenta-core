package io.mindspice.magenta2.ai.chat.service;

import java.io.IOException;
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

import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.repository.RepositoryBackedChatMemory;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.chat.service.turn.PromptContextAssembler;
import io.mindspice.magenta2.ai.chat.service.turn.TerminalTurnRepair;
import io.mindspice.magenta2.ai.chat.service.turn.ToolAccessPolicy;
import io.mindspice.magenta2.ai.chat.service.turn.TurnAuditWriter;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.api.OllamaChatOptions;
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
        ChatService chatService = new ChatService(null, null, null, null, aiConfig, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThat(chatService.defaultModel()).isEqualTo("deepseek-v4-max");
        assertThat(chatService.modelSelectionKey("deepseek-v4-pro")).isEqualTo("deepseek-v4-max");
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
            null,
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
    void listSessionsOnlyIncludesBrowserSurfaceNormalChats() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, objectMapper);
        ChatSessionMetadataRepository metadataRepository = new ChatSessionMetadataRepository(jdbcTemplate);
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
            null
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
        metadataRepository.saveOriginIfAbsent(planningConversationId, io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin.SYSTEM_CHAT, null);

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
        PromptContextAssembler promptAssembler = new PromptContextAssembler(config, null, null, null, null, null, null);
        ToolAccessPolicy toolAccessPolicy = new ToolAccessPolicy(toolRegistry, null, null);
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
            null,
            null,
            promptAssembler,
            toolAccessPolicy,
            new TerminalTurnRepair(null, null),
            new TurnAuditWriter(null, null)
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
        PromptContextAssembler promptAssembler = new PromptContextAssembler(config, null, null, null, null, null, null);
        ToolAccessPolicy toolAccessPolicy = new ToolAccessPolicy(toolRegistry, null, null);
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
            null,
            null,
            promptAssembler,
            toolAccessPolicy,
            new TerminalTurnRepair(null, null),
            new TurnAuditWriter(null, null)
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
        PromptContextAssembler promptAssembler = new PromptContextAssembler(config, null, null, null, null, null, null);
        ToolAccessPolicy toolAccessPolicy = new ToolAccessPolicy(toolRegistry, null, null);
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
            null,
            null,
            promptAssembler,
            toolAccessPolicy,
            new TerminalTurnRepair(null, null),
            new TurnAuditWriter(null, null)
        );

        var response = chatService.chat("conversation-1", "recover from closed stream", "main");

        assertThat(response.response()).isEqualTo("Recovered after transient provider close.");
        assertThat(chatModel.calls.get()).isEqualTo(2);
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .containsExactly("recover from closed stream", "Recovered after transient provider close.");
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
