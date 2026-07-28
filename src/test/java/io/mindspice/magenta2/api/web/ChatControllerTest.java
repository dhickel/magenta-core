package io.mindspice.magenta2.api.web;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.model.ChatMessage;
import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.model.ChatSession;
import io.mindspice.magenta2.ai.chat.model.ChatSessionSurface;
import io.mindspice.magenta2.ai.chat.model.ContextUsage;
import io.mindspice.magenta2.ai.chat.model.PendingMessageAckRequest;
import io.mindspice.magenta2.ai.chat.model.PendingMessageRequest;
import io.mindspice.magenta2.ai.chat.repository.ChatPendingMessageRepository;
import io.mindspice.magenta2.ai.chat.service.ChatPendingMessageService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.chat.service.ResolvedChatRequest;
import io.mindspice.magenta2.ai.chat.service.StoredContextUsage;
import io.mindspice.magenta2.ai.execution.ActiveTurnRegistry;
import io.mindspice.magenta2.ai.execution.ActiveTurnRegistry.ActiveTurn;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatControllerTest {

    private static final String CONVERSATION_ID = "00000000-0000-0000-0000-000000000001";

    private final StubChatService chatService = new StubChatService(
        List.of(CONVERSATION_ID),
        Map.of(CONVERSATION_ID, "qwen3")
    );
    private final ChatController chatController = new ChatController(chatService);

    @Test
    void nonPlanningSlashCommandsAreNotSupported() {
        assertThatThrownBy(() -> chatController.command(new ChatRequest.CmdRequest(null, "/switch " + CONVERSATION_ID)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).isEqualTo("unknown command: switch");
            });
    }

    @Test
    void newCommandStartsUnsavedChatWithoutCreatingConversationId() {
        ChatResponse.CmdResponse response = chatController.command(
            new ChatRequest.CmdRequest(CONVERSATION_ID, "/new")
        );

        assertThat(response.conversationId()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.message()).isEqualTo("New chat");
        assertThat(response.conversationIds()).containsExactly(CONVERSATION_ID);
        assertThat(response.history()).isEmpty();
        assertThat(response.contextUsage()).isNull();
        assertThat(response.planState().mode()).isEqualTo("NORMAL");
        assertThat(chatService.newConversationIdCalls).isZero();
    }

    @Test
    void sessionsPayloadIncludesNullableTitles() {
        chatService.title = "Reminder Followup";
        chatService.titleJobStatus = "SUCCEEDED";

        var response = chatController.sessions();

        assertThat(response.conversationIds()).containsExactly(CONVERSATION_ID);
        assertThat(response.sessions()).containsExactly(new ChatSession(CONVERSATION_ID, "Reminder Followup", "SUCCEEDED"));
    }

    @Test
    void historyPayloadIncludesConversationTitle() {
        chatService.title = "Reminder Followup";

        var response = chatController.history(CONVERSATION_ID);

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.title()).isEqualTo("Reminder Followup");
    }

    @Test
    void historyPayloadReturnsCompletedStateWhenContextMaintenanceDegrades() {
        chatService.planState = new ChatPlanState(
            "NORMAL",
            "COMPLETED",
            "Saved Plan",
            null,
            null,
            null,
            List.of("Step"),
            List.of("Do the work"),
            List.of("Evidence: validated")
        );
        chatService.historyMessages = List.of(new ChatMessage(
            "assistant",
            "Validated final message",
            "<p>Validated final message</p>",
            null
        ));
        chatService.contextUsage = new StoredContextUsage(
            new ContextUsage(1200, 1200, 1080, 100.0),
            false,
            true,
            "Context maintenance failed"
        );

        var response = chatController.history(CONVERSATION_ID);

        assertThat(response.messages()).extracting(ChatMessage::text)
            .containsExactly("Validated final message");
        assertThat(response.planState().mode()).isEqualTo("NORMAL");
        assertThat(response.planState().status()).isEqualTo("COMPLETED");
        assertThat(response.contextUsage()).isEqualTo(new ContextUsage(1200, 1200, 1080, 100.0));
    }

    @Test
    void renameUpdatesConversationTitle() {
        ChatSession response = chatController.rename(
            CONVERSATION_ID,
            new ChatRequest.SetTitle("  Updated   Session  ")
        );

        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        assertThat(response.title()).isEqualTo("Updated Session");
        assertThat(chatService.title).isEqualTo("Updated Session");
    }

    @Test
    void renameRejectsBlankTitle() {
        assertThatThrownBy(() -> chatController.rename(CONVERSATION_ID, new ChatRequest.SetTitle(" ")))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).isEqualTo("title is required");
            });
    }

    @Test
    void favoriteUpdatesConversationFavoriteState() {
        ChatSession response = chatController.favorite(CONVERSATION_ID, new ChatRequest.Favorite(true));

        assertThat(response.favorite()).isTrue();
        assertThat(chatService.favorite).isTrue();
    }

    @Test
    void favoriteRequestBindsBrowserPayload() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ChatRequest.Favorite request = objectMapper.readValue("{\"favorite\":true}", ChatRequest.Favorite.class);
        ChatRequest.Favorite legacyRequest = objectMapper.readValue("{\"isFavorite\":true}", ChatRequest.Favorite.class);

        assertThat(request.favorite()).isTrue();
        assertThat(legacyRequest.favorite()).isTrue();
    }

    @Test
    void messageRequestBindsSurfaceCaseInsensitively() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        ChatRequest.MsgRequest browser = objectMapper.readValue(
            "{\"message\":\"ping\",\"surface\":\"browser\"}",
            ChatRequest.MsgRequest.class
        );
        assertThat(browser.surface()).isEqualTo(ChatSessionSurface.BROWSER);
        assertThatThrownBy(() -> objectMapper.readValue(
            "{\"message\":\"ping\",\"surface\":\"AvAtAr\"}",
            ChatRequest.MsgRequest.class
        )).hasMessageContaining("Unknown chat session surface");
        assertThatThrownBy(() -> objectMapper.readValue(
            "{\"message\":\"ping\",\"surface\":\"external\"}",
            ChatRequest.MsgRequest.class
        )).hasMessageContaining("Unknown chat session surface");
        assertThatThrownBy(() -> objectMapper.readValue(
            "{\"message\":\"ping\",\"surface\":\"   \"}",
            ChatRequest.MsgRequest.class
        )).hasMessageContaining("Unknown chat session surface");
    }

    @Test
    void archiveUpdatesConversationArchiveState() {
        ChatSession response = chatController.archive(CONVERSATION_ID, new ChatRequest.Archive(true));

        assertThat(response.archived()).isTrue();
        assertThat(chatService.archived).isTrue();
    }

    @Test
    void planCommandStartsPlanningTurn() {
        ChatResponse.CmdResponse response = chatController.command(
            new ChatRequest.CmdRequest(CONVERSATION_ID, "/plan")
        );

        assertThat(response.message()).isEqualTo("What goal should we plan?");
        assertThat(response.planState().mode()).isEqualTo("PLAN");
        assertThat(response.planState().goal()).isNull();
    }

    @Test
    void planCommandUsesArgumentsAsInitialGoalAnswer() {
        ChatResponse.CmdResponse response = chatController.command(
            new ChatRequest.CmdRequest(CONVERSATION_ID, "/plan add reminder support")
        );

        assertThat(response.message()).isEqualTo("What assumptions should guide the plan?");
        assertThat(response.planState().mode()).isEqualTo("PLAN");
        assertThat(chatService.initialPlanningInstruction).isEqualTo("add reminder support");
    }

    @Test
    void cancelPlanEndpointDropsPlanState() {
        chatController.command(new ChatRequest.CmdRequest(CONVERSATION_ID, "/plan"));

        ChatPlanState response = chatController.cancelPlanAction(CONVERSATION_ID);

        assertThat(response.mode()).isEqualTo("NORMAL");
    }

    @Test
    void execPlanCommandIsNotSupported() {
        assertThatThrownBy(() -> chatController.command(new ChatRequest.CmdRequest(CONVERSATION_ID, "/exec-plan")))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).isEqualTo("unknown command: exec-plan");
            });
    }

    @Test
    void outputsChangedSideChannelIsNotPlanExecutionFinalMessage() throws Exception {
        OutputsChangedStreamChatService stubService = new OutputsChangedStreamChatService(
            List.of(CONVERSATION_ID),
            Map.of(CONVERSATION_ID, "qwen3")
        );
        ActiveTurnRegistry turnRegistry = new ActiveTurnRegistry();
        ChatController controller = new ChatController(stubService, turnRegistry);

        SseEmitter emitter = controller.streamPlanExecution(CONVERSATION_ID, null);

        assertThat(emitter).isNotNull();
        assertThat(stubService.streamCompleted.await(5, TimeUnit.SECONDS)).isTrue();
        // Plan finalization should use the assistant message, not outputs_changed
        assertThat(stubService.savedFinalMessage).isNotNull();
        assertThat(stubService.savedFinalMessage)
            .as("plan execution final message should be assistant text, not outputs_changed")
            .isNotEqualTo("conv-1");
        assertThat(stubService.streamFinishedCalled).isTrue();
    }

    @Test
    void streamReturnsBeforeDelayedChatStreamCompletes() throws Exception {
        BlockingStreamChatService blockingChatService = new BlockingStreamChatService(
            List.of(CONVERSATION_ID),
            Map.of(CONVERSATION_ID, "qwen3")
        );
        ChatController controller = new ChatController(blockingChatService);

        CompletableFuture<SseEmitter> response = CompletableFuture.supplyAsync(() ->
            controller.stream(new ChatRequest.MsgRequest(CONVERSATION_ID, "use tools", "qwen3", null))
        );

        assertThat(blockingChatService.subscribed.await(1, TimeUnit.SECONDS)).isTrue();
        SseEmitter emitter = response.get(200, TimeUnit.MILLISECONDS);
        assertThat(emitter).isNotNull();

        blockingChatService.release.countDown();
        response.get(1, TimeUnit.SECONDS);
    }

    @Test
    void renameRejectsInvalidUuid() {
        assertThatThrownBy(() -> chatController.rename("not-a-uuid", new ChatRequest.SetTitle("title")))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
    }

    @Test
    void approvePlanRejectsInvalidUuid() {
        assertThatThrownBy(() -> chatController.approvePlan("not-a-uuid"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
    }

    @Test
    void streamPlanExecutionRejectsInvalidUuid() {
        assertThatThrownBy(() -> chatController.streamPlanExecution("not-a-uuid", null))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
    }

    @Test
    void continuePlanningRejectsInvalidUuid() {
        assertThatThrownBy(() -> chatController.continuePlanning("not-a-uuid"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
    }

    @Test
    void executePlanRunsApprovedAnonymousPlan() {
        chatService.savedPlan = true;

        ChatResponse.CmdResponse response = chatController.executePlan(CONVERSATION_ID);

        assertThat(response.message()).isEqualTo("executed plan");
        assertThat(chatService.executed).isTrue();
    }

    @Test
    void executePlanRejectsOverlappingExecutionForSameConversation() {
        chatService.savedPlan = true;
        ActiveTurnRegistry turnRegistry = new ActiveTurnRegistry();
        ActiveTurn activeTurn = turnRegistry.registerPlanExecution(CONVERSATION_ID);
        ChatController controller = new ChatController(chatService, turnRegistry);

        assertThatThrownBy(() -> controller.executePlan(CONVERSATION_ID))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(exception.getReason()).contains("Plan execution already active");
            });
        assertThat(chatService.executed).isFalse();

        turnRegistry.complete(activeTurn.turnId());
    }

    @Test
    void streamPlanExecutionBindsApprovedAnonymousPlan() {
        SseEmitter emitter = chatController.streamPlanExecution(CONVERSATION_ID, null);

        assertThat(emitter).isNotNull();
    }

    @Test
    void streamPlanExecutionRejectsOverlappingExecutionForSameConversationBeforeResolving() {
        ActiveTurnRegistry turnRegistry = new ActiveTurnRegistry();
        ActiveTurn activeTurn = turnRegistry.registerPlanExecution(CONVERSATION_ID);
        ChatController controller = new ChatController(chatService, turnRegistry);

        assertThatThrownBy(() -> controller.streamPlanExecution(CONVERSATION_ID, null))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(exception.getReason()).contains("Plan execution already active");
            });

        turnRegistry.complete(activeTurn.turnId());
    }

    @Test
    void streamPlanExecutionForwardsCleanContextRequest() {
        SseEmitter emitter = chatController.streamPlanExecution(
            CONVERSATION_ID,
            new ChatController.PlanExecuteRequest(true)
        );

        assertThat(emitter).isNotNull();
        assertThat(chatService.cleanStreamExecution).isTrue();
    }

    @Test
    void answerPlanPromptRejectsInvalidUuid() {
        assertThatThrownBy(() -> chatController.answerPlanPrompt(
            "not-a-uuid", new ChatRequest.PlanAnswer("yes", null, 0)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
    }

    @Test
    void streamEmitterUsesNoTimeoutByDefault() {
        BlockingStreamChatService blockingChatService = new BlockingStreamChatService(
            List.of(CONVERSATION_ID),
            Map.of(CONVERSATION_ID, "qwen3")
        );
        ChatController controller = new ChatController(blockingChatService);

        SseEmitter emitter = controller.stream(
            new ChatRequest.MsgRequest(CONVERSATION_ID, "hello", "qwen3", null)
        );

        assertThat(emitter.getTimeout()).isZero();
        blockingChatService.release.countDown();
    }

    @Test
    void streamSubscriptionIsRegisteredWithGuard() throws Exception {
        CompletableStreamChatService completableChatService = new CompletableStreamChatService(
            List.of(CONVERSATION_ID),
            Map.of(CONVERSATION_ID, "qwen3")
        );
        io.mindspice.magenta2.ai.execution.ActiveTurnRegistry turnRegistry =
            new io.mindspice.magenta2.ai.execution.ActiveTurnRegistry();
        ChatController controller = new ChatController(completableChatService, turnRegistry);

        controller.stream(new ChatRequest.MsgRequest(CONVERSATION_ID, "hello", "qwen3", null));

        assertThat(completableChatService.subscribed.await(1, TimeUnit.SECONDS)).isTrue();
        completableChatService.release.countDown();
        // Emitter completes, guard disposes subscription, turn is cleaned up.
        // The stream completes normally so ActiveTurn is removed.
        assertThat(completableChatService.completed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void pendingMessageEndpointsEnqueueListClaimAckAndRelease() {
        ChatController controller = new ChatController(
            chatService,
            pendingMessageService(),
            new ActiveTurnRegistry(),
            null,
            0
        );

        var enqueued = controller.enqueuePendingMessage(
            CONVERSATION_ID,
            new PendingMessageRequest(" queued text ", "main", "planner", ChatSessionSurface.BROWSER)
        );

        assertThat(enqueued.messages()).hasSize(1);
        assertThat(enqueued.messages().getFirst().messageText()).isEqualTo("queued text");

        var listed = controller.pendingMessages(CONVERSATION_ID);
        assertThat(listed.messages()).extracting(message -> message.messageText())
            .containsExactly("queued text");

        var claimed = controller.claimPendingMessage(CONVERSATION_ID);
        assertThat(claimed.message().status()).isEqualTo("CLAIMED");

        controller.releasePendingMessage(
            CONVERSATION_ID,
            claimed.message().id(),
            new PendingMessageAckRequest(claimed.claimToken())
        );
        var claimedAgain = controller.claimPendingMessage(CONVERSATION_ID);
        controller.ackPendingMessage(
            CONVERSATION_ID,
            claimedAgain.message().id(),
            new PendingMessageAckRequest(claimedAgain.claimToken())
        );

        assertThat(controller.pendingMessages(CONVERSATION_ID).messages()).isEmpty();
    }

    @Test
    void pendingMessageEndpointsValidateUuidBlankMessageAndClaimToken() {
        ChatController controller = new ChatController(
            chatService,
            pendingMessageService(),
            new ActiveTurnRegistry(),
            null,
            0
        );

        assertThatThrownBy(() -> controller.pendingMessages("not-a-uuid"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid UUID");
            });
        assertThatThrownBy(() -> controller.enqueuePendingMessage(CONVERSATION_ID, new PendingMessageRequest(" ", null, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).isEqualTo("message is required");
            });
        assertThatThrownBy(() -> controller.ackPendingMessage(CONVERSATION_ID, "missing", new PendingMessageAckRequest(" ")))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).isEqualTo("claimToken is required");
            });
    }

    private ChatPendingMessageService pendingMessageService() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new ChatPendingMessageService(new ChatPendingMessageRepository(new JdbcTemplate(dataSource)));
    }

    private static class StubChatService extends ChatService {
        private final List<String> conversationIds;
        private final Map<String, String> modelsByConversationId;
        private ChatPlanState planState = ChatPlanState.normal();
        private boolean savedPlan;
        private boolean executed;
        private boolean cleanStreamExecution;
        private boolean exited;
        private int newConversationIdCalls;
        private String title;
        private String titleJobStatus;
        private String initialPlanningInstruction;
        private boolean favorite;
        private boolean archived;
        private List<ChatMessage> historyMessages = List.of();
        private StoredContextUsage contextUsage;

        StubChatService(List<String> conversationIds, Map<String, String> modelsByConversationId) {
            super(null, null, null, null, null);
            this.conversationIds = conversationIds;
            this.modelsByConversationId = modelsByConversationId;
        }

        @Override
        public List<String> listConversationIds() {
            return conversationIds;
        }

        @Override
        public List<ChatSession> listSessions() {
            return conversationIds.stream()
                .map(conversationId -> new ChatSession(conversationId, title, titleJobStatus, favorite, archived, null))
                .toList();
        }

        @Override
        public boolean conversationExists(String conversationId) {
            return conversationIds.contains(conversationId);
        }

        @Override
        public String newConversationId() {
            newConversationIdCalls++;
            return "00000000-0000-0000-0000-000000000002";
        }

        @Override
        public List<ChatMessage> history(String conversationId) {
            return historyMessages;
        }

        @Override
        public StoredContextUsage maintainContextUsage(String conversationId, String model) {
            return contextUsage == null ? new StoredContextUsage(null, false) : contextUsage;
        }

        @Override
        public StoredContextUsage snapshotContextUsage(String conversationId, String model) {
            return contextUsage == null ? new StoredContextUsage(null, false) : contextUsage;
        }

        @Override
        public String storedConversationModel(String conversationId) {
            return modelsByConversationId.get(conversationId);
        }

        @Override
        public String conversationTitle(String conversationId) {
            return title;
        }

        @Override
        public String conversationTitleJobStatus(String conversationId) {
            return titleJobStatus;
        }

        @Override
        public ChatSession renameConversation(String conversationId, String title) {
            this.title = title.trim().replaceAll("\\s+", " ");
            return new ChatSession(conversationId, this.title, titleJobStatus, favorite, archived, null);
        }

        @Override
        public ChatSession setConversationFavorite(String conversationId, boolean favorite) {
            this.favorite = favorite;
            return new ChatSession(conversationId, title, titleJobStatus, favorite, archived, null);
        }

        @Override
        public ChatSession setConversationArchived(String conversationId, boolean archived) {
            this.archived = archived;
            return new ChatSession(conversationId, title, titleJobStatus, favorite, archived, null);
        }

        @Override
        public ChatResponse.MsgResponse beginPlan(String conversationId) {
            planState = new ChatPlanState("PLAN", "DRAFT", null, null, null, null, List.of(), List.of(), List.of());
            return new ChatResponse.MsgResponse(conversationId, "qwen3", "What goal should we plan?", null, planState);
        }

        @Override
        public ChatResponse.MsgResponse beginPlan(String conversationId, String selectedModel, String planningModel) {
            return beginPlan(conversationId);
        }

        @Override
        public ChatResponse.MsgResponse beginPlan(
            String conversationId,
            String selectedModel,
            String planningModel,
            String userInstruction
        ) {
            if (userInstruction == null) {
                return beginPlan(conversationId);
            }
            initialPlanningInstruction = userInstruction;
            planState = new ChatPlanState(
                "PLAN",
                "DRAFT",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "questions",
                "What assumptions should guide the plan?",
                List.of(),
                2,
                3,
                null,
                null,
                null,
                null
            );
            return new ChatResponse.MsgResponse(
                conversationId,
                "qwen3",
                "What assumptions should guide the plan?",
                null,
                planState
            );
        }

        @Override
        public void exitPlan(String conversationId) {
            planState = ChatPlanState.normal();
            exited = true;
        }

        @Override
        public ChatResponse.MsgResponse executeSavedPlan(String conversationId) {
            if (!savedPlan) {
                throw new IllegalStateException("No saved plan exists for this conversation");
            }
            executed = true;
            planState = new ChatPlanState(
                "NORMAL",
                "NEEDS_REVIEW",
                "Saved Plan",
                null,
                null,
                null,
                List.of("Step"),
                List.of("Do the work"),
                List.of("Evidence: ran")
            );
            return new ChatResponse.MsgResponse(conversationId, "qwen3", "executed plan", null, planState);
        }

        @Override
        public ChatResponse.MsgResponse executeSavedPlan(String conversationId, boolean clearContext) {
            return executeSavedPlan(conversationId);
        }

        @Override
        public ChatPlanState planState(String conversationId) {
            return planState;
        }

        @Override
        public ResolvedChatRequest resolveSavedPlanExecution(String conversationId) {
            return new ResolvedChatRequest(conversationId, "Execute the saved plan.", "qwen3");
        }

        @Override
        public ResolvedChatRequest resolveSavedPlanExecution(String conversationId, boolean clearContext) {
            cleanStreamExecution = clearContext;
            return clearContext
                ? new ResolvedChatRequest(conversationId, "Execute the saved plan.", "qwen3").omittingStoredMessages()
                : resolveSavedPlanExecution(conversationId);
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
            return Flux.just(new ChatMessage("assistant", "done", "<p>done</p>", null));
        }

        @Override
        public void handlePlanExecutionStreamFinished(String conversationId) {
            handlePlanExecutionStreamFinished(conversationId, "done");
        }

        @Override
        public void handlePlanExecutionStreamFinished(String conversationId, String finalMessage) {
            planState = new ChatPlanState(
                "NORMAL",
                "COMPLETED",
                "Saved Plan",
                null,
                null,
                null,
                List.of("Step"),
                List.of("Do the work"),
                List.of("Evidence: stream finished")
            );
        }
    }

    private static class BlockingStreamChatService extends StubChatService {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingStreamChatService(List<String> conversationIds, Map<String, String> modelsByConversationId) {
            super(conversationIds, modelsByConversationId);
        }

        @Override
        public ResolvedChatRequest resolve(ChatRequest request) {
            return new ResolvedChatRequest(CONVERSATION_ID, "use tools", "qwen3");
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request) {
            return stream(request, null);
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
            return Flux.create(sink -> {
                subscribed.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                    sink.next(new ChatMessage("assistant", "done", "<p>done</p>", null));
                    sink.complete();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    sink.error(exception);
                }
            });
        }

        @Override
        public List<ChatMessage> history(String conversationId) {
            return List.of(new ChatMessage("assistant", "done", "<p>done</p>", null));
        }
    }

    private static class CompletableStreamChatService extends StubChatService {
        private final CountDownLatch subscribed = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        CompletableStreamChatService(List<String> conversationIds, Map<String, String> modelsByConversationId) {
            super(conversationIds, modelsByConversationId);
        }

        @Override
        public ResolvedChatRequest resolve(ChatRequest request) {
            return new ResolvedChatRequest(CONVERSATION_ID, "hello", "qwen3");
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request) {
            return stream(request, null);
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
            return Flux.create(sink -> {
                subscribed.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                    sink.next(new ChatMessage("assistant", "result", "<p>result</p>", null));
                    sink.complete();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    sink.error(exception);
                } finally {
                    completed.countDown();
                }
            });
        }

        @Override
        public List<ChatMessage> history(String conversationId) {
            return List.of(new ChatMessage("assistant", "result", "<p>result</p>", null));
        }
    }

    private static class OutputsChangedStreamChatService extends StubChatService {
        private final CountDownLatch streamCompleted = new CountDownLatch(1);
        private String savedFinalMessage;
        private boolean streamFinishedCalled;

        OutputsChangedStreamChatService(List<String> conversationIds, Map<String, String> modelsByConversationId) {
            super(conversationIds, modelsByConversationId);
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
            return Flux.just(
                new ChatMessage("outputs_changed", request.conversationId(), null, null),
                new ChatMessage("assistant", "final answer", "<p>final answer</p>", null)
            ).doOnComplete(() -> streamCompleted.countDown());
        }

        @Override
        public void handlePlanExecutionStreamFinished(String conversationId) {
            handlePlanExecutionStreamFinished(conversationId, "final answer");
        }

        @Override
        public void handlePlanExecutionStreamFinished(String conversationId, String finalMessage) {
            savedFinalMessage = finalMessage;
            streamFinishedCalled = true;
            super.handlePlanExecutionStreamFinished(conversationId, finalMessage);
        }

        @Override
        public ResolvedChatRequest resolveSavedPlanExecution(String conversationId) {
            return new ResolvedChatRequest(conversationId, "Execute the saved plan.", "qwen3");
        }

        @Override
        public ResolvedChatRequest resolveSavedPlanExecution(String conversationId, boolean clearContext) {
            return resolveSavedPlanExecution(conversationId);
        }
    }

    private static class ErrorPlanExecutionChatService extends StubChatService {
        private final CountDownLatch failureRecorded = new CountDownLatch(1);
        private String executionFailureMessage;

        ErrorPlanExecutionChatService(List<String> conversationIds, Map<String, String> modelsByConversationId) {
            super(conversationIds, modelsByConversationId);
        }

        @Override
        public Flux<ChatMessage> stream(ResolvedChatRequest request, ActiveTurn activeTurn) {
            return Flux.error(new IllegalStateException("model timed out"));
        }

        @Override
        public void recordExecutionFailure(String conversationId, RuntimeException exception) {
            executionFailureMessage = exception.getMessage();
            failureRecorded.countDown();
        }
    }
}
