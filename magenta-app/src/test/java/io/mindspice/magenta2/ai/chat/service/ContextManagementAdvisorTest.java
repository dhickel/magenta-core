package io.mindspice.magenta2.ai.chat.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.tool.ToolTranscriptService;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContextManagementAdvisorTest {

    @Test
    void omitStoredMessagesBuildsCleanPromptWithoutDeletingStoredTranscript() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            new SummaryRouter(new SummaryChatModel(), aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("older user request"),
            new AssistantMessage("older assistant response")
        ));

        ContextManagementAdvisor.PreparedPrompt prompt = advisor.preparePrompt(
            "conversation-1",
            List.of(new SystemMessage("system"), new UserMessage("execute approved plan")),
            "qwen3",
            true
        );

        assertThat(prompt.messages()).extracting(Message::getText)
            .containsExactly("system", "execute approved plan");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .containsExactly("older user request", "older assistant response", "execute approved plan");
    }

    @Test
    void omitStoredMessagesToolLoopKeepsCurrentExecutionInstruction() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            new SummaryRouter(new SummaryChatModel(), aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("older user request"),
            new AssistantMessage("older assistant response"),
            new UserMessage("execute approved plan")
        ));

        ContextManagementAdvisor.ToolLoopPrompt prompt = advisor.prepareToolLoopPrompt(
            "conversation-1",
            List.of(new AssistantMessage("tool call"), new SystemMessage("tool result")),
            List.of(new SystemMessage("system"), new UserMessage("execute approved plan")),
            "qwen3",
            true
        );

        assertThat(prompt.messages()).extracting(Message::getText)
            .containsExactly("system", "execute approved plan", "tool call", "tool result");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .containsExactly("older user request", "older assistant response", "execute approved plan");
    }

    @Test
    void compactionCarriesPreviousHiddenSummaryForward() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        SummaryRouter summaryRouter = new SummaryRouter(summaryModel, aiConfig());
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            summaryRouter,
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(
            new SystemMessage(ContextManagementAdvisor.SUMMARY_PREFIX + "prior summary to keep"),
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("tail one " + "x".repeat(60)),
            new AssistantMessage("tail two " + "x".repeat(60)),
            new UserMessage("tail three " + "x".repeat(60)),
            new AssistantMessage("tail four " + "x".repeat(60)),
            new UserMessage("tail five " + "x".repeat(60)),
            new AssistantMessage("tail six " + "x".repeat(60))
        ));

        advisor.preparePrompt("conversation-1", List.of(new SystemMessage("system"), new UserMessage("current")), "qwen3");

        assertThat(summaryModel.lastPromptText).contains("prior summary to keep");
        assertThat(summaryRouter.lastModelRef).isEqualTo("summary");
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .filteredOn(advisor::isCompactionNotice)
            .hasSize(1);
    }

    @Test
    void compactionUsesConfiguredCompactionModelWhenPresent() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        AiConfig config = aiConfigWithCompactionModel();
        SummaryRouter summaryRouter = new SummaryRouter(summaryModel, config);
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            config,
            summaryRouter,
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("tail one " + "x".repeat(60)),
            new AssistantMessage("tail two " + "x".repeat(60)),
            new UserMessage("tail three " + "x".repeat(60)),
            new AssistantMessage("tail four " + "x".repeat(60)),
            new UserMessage("tail five " + "x".repeat(60)),
            new AssistantMessage("tail six " + "x".repeat(60))
        ));

        advisor.preparePrompt("conversation-1", List.of(new SystemMessage("system"), new UserMessage("current")), "qwen3");

        assertThat(summaryRouter.lastModelRef).isEqualTo("compact");
    }

    @Test
    void toolLoopCheckpointCompactsActiveToolMessagesBeforeContinuing() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            new SummaryRouter(summaryModel, aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(new UserMessage("current")));

        ContextManagementAdvisor.ToolLoopPrompt prompt = advisor.prepareToolLoopPrompt(
            "conversation-1",
            List.of(
                new SystemMessage("active old " + "x".repeat(80)),
                new SystemMessage("active old " + "y".repeat(300)),
                new SystemMessage("active old " + "z".repeat(300)),
                new SystemMessage("active old " + "a".repeat(300)),
                new SystemMessage("active tail zero"),
                new SystemMessage("active tail one"),
                new SystemMessage("active tail two"),
                new SystemMessage("active tail three"),
                new SystemMessage("active tail one"),
                new SystemMessage("active tail two")
            ),
            List.of(new SystemMessage("system")),
            "qwen3"
        );

        assertThat(prompt.compacted()).isTrue();
        assertThat(prompt.activeMessages().getFirst().getText()).contains("Compacted active tool-use summary");
        assertThat(summaryModel.lastPromptText).contains("active old");
    }

    @Test
    void maintainStoredContextCompactsBeforeReturningUsage() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            new SummaryRouter(summaryModel, aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("tail one " + "x".repeat(60)),
            new AssistantMessage("tail two " + "x".repeat(60)),
            new UserMessage("tail three " + "x".repeat(60)),
            new AssistantMessage("tail four " + "x".repeat(60)),
            new UserMessage("tail five " + "x".repeat(60)),
            new AssistantMessage("tail six " + "x".repeat(60))
        ));

        ContextManagementAdvisor.StoredContextMaintenance maintenance = advisor.maintainStoredContext("conversation-1", "qwen3");

        assertThat(maintenance.compacted()).isTrue();
        assertThat(maintenance.usage().usedTokens()).isLessThanOrEqualTo(maintenance.usage().triggerTokens());
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .filteredOn(advisor::isHiddenSummary)
            .hasSize(1);
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .filteredOn(advisor::isCompactionNotice)
            .hasSize(1);
    }

    @Test
    void maintainStoredContextDegradesWhenCompactionModelFails() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig(),
            new SummaryRouter(new FailingSummaryChatModel(), aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );
        List<Message> originalMessages = List.of(
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("older message " + "x".repeat(250)),
            new AssistantMessage("older answer " + "x".repeat(250)),
            new UserMessage("tail one " + "x".repeat(60)),
            new AssistantMessage("tail two " + "x".repeat(60)),
            new UserMessage("tail three " + "x".repeat(60)),
            new AssistantMessage("tail four " + "x".repeat(60)),
            new UserMessage("tail five " + "x".repeat(60)),
            new AssistantMessage("tail six " + "x".repeat(60))
        );
        memoryRepository.saveAll("conversation-1", originalMessages);

        ContextManagementAdvisor.StoredContextMaintenance maintenance = advisor.maintainStoredContext("conversation-1", "qwen3");

        assertThat(maintenance.degraded()).isTrue();
        assertThat(maintenance.compacted()).isFalse();
        assertThat(maintenance.degradationReason()).contains("Context compaction failed");
        assertThat(maintenance.usage().usedTokens()).isGreaterThan(maintenance.usage().triggerTokens());
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .extracting(Message::getText)
            .containsExactlyElementsOf(originalMessages.stream().map(Message::getText).toList());
        assertThat(memoryRepository.findByConversationId("conversation-1"))
            .filteredOn(advisor::isHiddenSummary)
            .isEmpty();
    }

    @Test
    void runtimeSettingsControlContextBufferPercent() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        AiConfig aiConfig = aiConfig(); // contextBufferPercent=10 → trigger=1080 for qwen3

        // Runtime overrides buffer to 50% → trigger=600 for qwen3 (contextLength=1200)
        RuntimeSettingsService runtimeSettings = new FakeRuntimeSettingsService("summary-model", 50);
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig,
            new SummaryRouter(summaryModel, aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null,
            runtimeSettings
        );

        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("x".repeat(120)),
            new AssistantMessage("x".repeat(120)),
            new UserMessage("x".repeat(120)),
            new AssistantMessage("x".repeat(120)),
            new UserMessage("x".repeat(120))
        ));

        // ~780-820 estimated tokens (between 600 runtime trigger and 1080 default trigger)
        // With runtime's 50% buffer, trigger=600 < usedTokens → triggers compaction
        // Without runtime, default 10% buffer gives trigger=1080 > usedTokens → no compaction
        ContextUsage usage = advisor.estimateStoredUsage("conversation-1", "qwen3");

        assertThat(usage.triggerTokens()).as("trigger uses runtime 50%% buffer: 1200-600=600")
            .isEqualTo(600);
        assertThat(usage.usedTokens()).as("used tokens exceed tighter runtime trigger")
            .isGreaterThan(usage.triggerTokens());
    }

    @Test
    void defaultContextBufferAndCompactionModelApplyWhenNoRuntimeSettings() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, new ObjectMapper());
        SummaryChatModel summaryModel = new SummaryChatModel();
        AiConfig aiConfig = aiConfig(); // contextBufferPercent=10, compaction model key="summary"

        // Construct with 7-arg constructor → runtimeSettingsService=null → uses aiConfig defaults
        ContextManagementAdvisor advisor = new ContextManagementAdvisor(
            memoryRepository,
            aiConfig,
            new SummaryRouter(summaryModel, aiConfig()),
            new CharacterTokenEstimator(),
            new ContextUsageTracker(),
            new ToolTranscriptService(new ObjectMapper()),
            null
        );

        memoryRepository.saveAll("conversation-1", List.of(
            new UserMessage("x".repeat(100)),
            new AssistantMessage("x".repeat(100)),
            new UserMessage("x".repeat(100)),
            new AssistantMessage("x".repeat(100)),
            new UserMessage("x".repeat(100))
        ));

        // With default 10% buffer: trigger = 1200 - 120 = 1080
        // ~650-750 tokens < 1080 → no compaction triggered
        ContextUsage usage = advisor.estimateStoredUsage("conversation-1", "qwen3");

        assertThat(usage.triggerTokens()).as("default 10%% buffer: 1200-120=1080")
            .isEqualTo(1080);
        assertThat(usage.usedTokens()).as("used tokens below default trigger")
            .isLessThanOrEqualTo(usage.triggerTokens());
    }

    private AiConfig aiConfig() {
        return new AiConfig(
            "magenta",
            "summary",
            10,
            null,
            Map.of(
                "main", new ModelConfig("qwen3", "http://localhost:11434", EndpointType.OLLAMA, 1200, null, null),
                "summary", new ModelConfig("summary-model", "http://localhost:11434", EndpointType.OLLAMA, 512, null, null)
            ),
            Map.of("magenta", new AgentConfig("main", "You are Magenta.", List.of()))
        );
    }

    private AiConfig aiConfigWithCompactionModel() {
        return new AiConfig(
            "magenta",
            null,
            "summary",
            null,
            null,
            "compact",
            10,
            null,
            null,
            Map.of(
                "main", new ModelConfig("qwen3", "http://localhost:11434", EndpointType.OLLAMA, 1200, null, null),
                "summary", new ModelConfig("summary-model", "http://localhost:11434", EndpointType.OLLAMA, 512, null, null),
                "compact", new ModelConfig("compaction-model", "http://localhost:11434", EndpointType.OLLAMA, 512, null, null)
            ),
            Map.of("magenta", new AgentConfig("main", "You are Magenta.", List.of())),
            false
        );
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
                    total += estimate(content.getText());
                }
            }
            return total;
        }
    }

    private static final class SummaryRouter extends ChatModelRouter {
        private final ChatClient chatClient;
        private final AiConfig config;
        private String lastModelRef;

        SummaryRouter(ChatModel chatModel, AiConfig config) {
            super(null, null, null);
            this.config = config;
            this.chatClient = ChatClient.builder(chatModel).build();
        }

        @Override
        public ChatClient chatClient(String model) {
            lastModelRef = model;
            return chatClient;
        }

        @Override
        public ModelConfig modelConfig(String model) {
            ModelConfig byKey = config.models().get(model);
            if (byKey != null) {
                return byKey;
            }
            return config.models().values().stream()
                .filter(value -> model.equals(value.remoteModelName()))
                .findFirst()
                .orElseThrow();
        }

        @Override
        public OllamaChatOptions ollamaOptions(String model) {
            return OllamaChatOptions.builder().model(model).build();
        }

        @Override
        public OllamaChatOptions.Builder ollamaOptionsBuilder(String model) {
            return OllamaChatOptions.builder().model(model);
        }

        @Override
        public ToolCallingChatOptions chatOptions(String model) {
            return ollamaOptions(model);
        }

        @Override
        public ToolCallingChatOptions toolCallingOptions(String model) {
            return ollamaOptions(model);
        }
    }

    private static final class SummaryChatModel implements ChatModel {
        private String lastPromptText = "";

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPromptText = prompt.getInstructions().stream()
                .map(Message::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("short summary"))));
        }
    }

    private static final class FailingSummaryChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new RuntimeException("compaction endpoint unavailable");
        }
    }

    private static final class FakeRuntimeSettingsService extends RuntimeSettingsService {
        private final String compactionModelRemoteName;
        private final int contextBufferPercent;

        FakeRuntimeSettingsService(String compactionModelRemoteName, int contextBufferPercent) {
            super(null, null, null);
            this.compactionModelRemoteName = compactionModelRemoteName;
            this.contextBufferPercent = contextBufferPercent;
        }

        @Override
        public String compactionModel() {
            return compactionModelRemoteName;
        }

        @Override
        public int contextBufferPercent() {
            return contextBufferPercent;
        }

        @Override
        public String defaultModel() {
            return "qwen3";
        }

        @Override
        public String defaultSystemPrompt() {
            return null;
        }
    }
}
