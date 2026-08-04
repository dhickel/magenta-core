package io.mindspice.magenta2.ai.chat.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.mindspice.magenta2.ai.chat.repository.ChatSessionMetadataRepository;
import io.mindspice.magenta2.ai.chat.service.ChatModelRouter;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@EnableTransactionManagement
class SystemChatTransactionIntegrationTest {
    private static Path ROOT;
    private static Path DB;
    private static Path CONFIG;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        ROOT = Path.of(System.getProperty("java.io.tmpdir"),
                "magenta-sc-tx-" + UUID.randomUUID());
        DB = ROOT.resolve("magenta.sqlite");
        Files.createDirectories(ROOT.resolve("data"));
        Files.createDirectories(ROOT.resolve("prompts"));
        Files.writeString(ROOT.resolve("prompts/system.md"), "System-chat transaction integration test prompt.");
        CONFIG = ROOT.resolve("ai-config.json");
        Files.writeString(CONFIG, """
                {
                  "defaultAgent": "magenta",
                  "defaultModel": "local",
                  "summaryModel": "local",
                  "planningModel": "local",
                  "compactionModel": "local",
                  "contextBufferPercent": 33,
                  "dataRoot": "%s",
                  "models": {
                    "local": {
                      "remoteModelName": "test-model",
                      "remoteEndpoint": "http://127.0.0.1:11434",
                      "endpointType": "OLLAMA",
                      "contextLength": 32000,
                      "thinkLevel": 0
                    }
                  },
                  "agents": {
                    "magenta": {
                      "model": "local",
                      "systemPrompt": "prompts/system.md",
                      "approvedTools": [],
                      "allowedShellCommands": []
                    }
                  }
                }
                """.formatted(ROOT.resolve("data").toString().replace("\\", "\\\\")));

        registry.add("magenta.root.path", () -> ROOT.toString());
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DB + "?foreign_keys=true&busy_timeout=5000");
        registry.add("app.ai.config-path", () -> CONFIG.toString());
        registry.add("magenta.orchestration.runner-delay-ms", () -> "600000");
    }

    static final AtomicBoolean failPostWriteVerification = new AtomicBoolean(false);
    static final AtomicBoolean originWritten = new AtomicBoolean(false);

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        ChatModelRouter fakeRouter() {
            return mock(ChatModelRouter.class);
        }

        @Bean
        @Primary
        ChatSessionMetadataRepository instrumentedRepo(JdbcTemplate jdbc) {
            return new ChatSessionMetadataRepository(jdbc) {
                @Override
                public void saveOriginIfAbsent(String conversationId,
                                               io.mindspice.magenta2.ai.chat.model.ChatSessionOrigin origin,
                                               String ownerAgentId) {
                    super.saveOriginIfAbsent(conversationId, origin, ownerAgentId);
                    originWritten.set(true);
                }

                @Override
                public Optional<String> findAgentId(String conversationId) {
                    if (originWritten.get() && failPostWriteVerification.compareAndSet(true, false)) {
                        throw new RuntimeException("test-induced post-write failure");
                    }
                    return super.findAgentId(conversationId);
                }
            };
        }
    }

    @Autowired SystemChatService systemChatService;
    @Autowired AgentProfileService agents;
    @Autowired RuntimeSettingsService runtimeSettings;
    @Autowired ChatModelRouter chatModelRouter;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;

    private TransactionTemplate ambientTransaction;
    private String agentId;

    private final AtomicBoolean modelCalled = new AtomicBoolean(false);
    private final AtomicReference<Boolean> transactionActiveInModel = new AtomicReference<>(true);
    private final AtomicReference<Boolean> preparationFactsVisibleInModel = new AtomicReference<>(false);

    @BeforeEach
    void setUp() {
        ambientTransaction = new TransactionTemplate(transactionManager);

        agentId = "sc-tx-agent-" + UUID.randomUUID().toString().substring(0, 12);
        agents.create(new AgentProfile(agentId, "TX Test Agent " + agentId, AgentProfileStatus.ACTIVE,
                "local", "prompt", List.of(), List.of(), true, null, null));

        runtimeSettings.save(new RuntimeSettings(null, null, "local", "local", "local", "local",
                33, "local", null, null, null, true, -1, false));

        modelCalled.set(false);
        transactionActiveInModel.set(true);
        preparationFactsVisibleInModel.set(false);
        failPostWriteVerification.set(false);
        originWritten.set(false);

        ChatModel fakeModel = mock(ChatModel.class, org.mockito.Answers.RETURNS_MOCKS);
        when(fakeModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenAnswer(inv -> {
            modelCalled.set(true);
            transactionActiveInModel.set(TransactionSynchronizationManager.isActualTransactionActive());
            preparationFactsVisibleInModel.set(preparationFactsVisible(agentId));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("response from test model"))));
        });

        ChatClient fakeClient = ChatClient.builder(fakeModel).build();
        when(chatModelRouter.chatClient(anyString())).thenReturn(fakeClient);
    }

    @AfterEach
    void clearHolder() {
        SystemChatProfileHolder.clear();
    }

    @Test
    void modelCallbackHasNoActiveTransactionAndSeesCommittedPreparationFacts() throws Exception {
        SystemChatService.InvocationResult result = systemChatService.invoke(agentId, "hello");

        assertThat(result.available()).isTrue();
        assertThat(result.conversationId()).isEqualTo("system-chat:" + agentId);
        assertThat(modelCalled.get()).as("model callback was invoked").isTrue();
        assertThat(transactionActiveInModel.get())
                .as("no transaction active during model callback").isFalse();
        assertThat(preparationFactsVisibleInModel.get())
                .as("independent model-callback connection sees committed preparation facts").isTrue();
    }

    @Test
    void invokeCalledFromAmbientTransactionStillProducesTransactionFreeModelCallback() {
        SystemChatService.InvocationResult result = ambientTransaction.execute(status ->
                systemChatService.invoke(agentId, "hello from ambient tx"));

        assertThat(result).isNotNull();
        assertThat(result.available()).isTrue();
        assertThat(modelCalled.get()).isTrue();
        assertThat(transactionActiveInModel.get())
                .as("model callback must be transaction-free even from ambient transaction")
                .isFalse();
        assertThat(preparationFactsVisibleInModel.get())
                .as("ambient transaction does not hide committed preparation facts from callback")
                .isTrue();
    }

    @Test
    void preparationRollbackDiscardsAllFactsAndSuppressesModelCallback() throws Exception {
        failPostWriteVerification.set(true);

        SystemChatService.InvocationResult result = systemChatService.invoke(agentId, "should rollback");

        assertThat(result.available())
                .as("invocation unavailable after preparation rollback").isFalse();
        assertThat(modelCalled.get())
                .as("model callback never reached after rollback").isFalse();

        try (Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + DB + "?foreign_keys=true")) {
            var checkJdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));

            String origin = checkJdbc.query(
                    "select origin from ai_chat_session_metadata where conversation_id = ?",
                    rs -> rs.next() ? rs.getString("origin") : null,
                    "system-chat:" + agentId);
            assertThat(origin)
                    .as("origin not committed after rollback").isNull();

            String linkedBinding = checkJdbc.query(
                    "select binding_key from binding_owner_links where owner_type = 'CONVERSATION' and owner_id = ?",
                    rs -> rs.next() ? rs.getString("binding_key") : null,
                    "system-chat:" + agentId);
            assertThat(linkedBinding)
                    .as("owner link not committed after rollback").isNull();
        }

        try (Connection conn = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + DB + "?foreign_keys=true")) {
            var checkJdbc = new JdbcTemplate(new SingleConnectionDataSource(conn, true));
            String bindingKey = "system-chat:" + agentId;
            Integer bindingCount = checkJdbc.queryForObject(
                    "select count(*) from context_bindings where binding_key = ?",
                    Integer.class, bindingKey);
            assertThat(bindingCount).isZero();
        }
    }

    private boolean preparationFactsVisible(String expectedAgentId) {
        try (Connection connection = java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + DB + "?foreign_keys=true")) {
            var checkJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String bindingKey = "system-chat:" + expectedAgentId;
            Integer bindingCount = checkJdbc.queryForObject(
                    "select count(*) from context_bindings where binding_key = ?",
                    Integer.class, bindingKey);
            String origin = checkJdbc.queryForObject(
                    "select origin from ai_chat_session_metadata where conversation_id = ?",
                    String.class, "system-chat:" + expectedAgentId);
            String storedAgentId = checkJdbc.queryForObject(
                    "select agent_id from ai_chat_session_metadata where conversation_id = ?",
                    String.class, "system-chat:" + expectedAgentId);
            String linkedBinding = checkJdbc.queryForObject(
                    "select binding_key from binding_owner_links where owner_type = 'CONVERSATION' and owner_id = ?",
                    String.class, "system-chat:" + expectedAgentId);
            return bindingCount == 1
                    && "SYSTEM_CHAT".equals(origin)
                    && expectedAgentId.equals(storedAgentId)
                    && bindingKey.equals(linkedBinding);
        } catch (Exception exception) {
            return false;
        }
    }
}
