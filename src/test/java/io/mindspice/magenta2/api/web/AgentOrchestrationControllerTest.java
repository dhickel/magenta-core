package io.mindspice.magenta2.api.web;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Set;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.mindspice.magenta2.ai.chat.model.ChatPlanState;
import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.service.AgentChatPromptService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentEventReaction;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentSchedule;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentOrchestrationControllerTest {

    private static AgentOrchestrationController newController(
        InboxService inboxService,
        AssignmentService assignmentService,
        ScheduleService scheduleService,
        EventReactionService reactionService,
        AgentProfileService agentProfileService,
        ChatService chatService
    ) {
        return new AgentOrchestrationController(
            inboxService, assignmentService, scheduleService, reactionService,
            agentProfileService, chatService, new AgentChatPromptService()
        );
    }

    @Test
    void agentChatStreamReturnsBeforeChatServiceCompletes() throws Exception {
        BlockingChatService chatService = new BlockingChatService();
        StubAgentProfileService profileService = new StubAgentProfileService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, chatService
        );

        CompletableFuture<SseEmitter> response = CompletableFuture.supplyAsync(() ->
            controller.chat("agent-1",
                new AgentOrchestrationController.AgentChatRequest(null, "Inspect this task", null, "task editor"))
        );

        assertThat(chatService.started.await(1, TimeUnit.SECONDS)).isTrue();
        SseEmitter emitter = response.get(200, TimeUnit.MILLISECONDS);
        assertThat(emitter.getTimeout()).isZero();
        chatService.release.countDown();
    }

    @Test
    void agentChatStreamFlushesStartBeforeChatServiceCompletes() throws Exception {
        BlockingChatService chatService = new BlockingChatService();
        StubAgentProfileService profileService = new StubAgentProfileService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, chatService
        );

        SseEmitter emitter = controller.chat("agent-1",
            new AgentOrchestrationController.AgentChatRequest(null, "Inspect this task", null, "task editor"));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.awaitEventContaining("event=start", 1, TimeUnit.SECONDS)).isTrue();
        assertThat(chatService.started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(chatService.release.getCount()).isEqualTo(1);

        chatService.release.countDown();
        assertThat(captured.awaitEventContaining("event=done", 2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.completed.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void agentChatStreamEmitsStartAndDone() throws Exception {
        StubChatService chatService = new StubChatService();
        StubAgentProfileService profileService = new StubAgentProfileService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, chatService
        );

        SseEmitter emitter = controller.chat("agent-1",
            new AgentOrchestrationController.AgentChatRequest(null, "Inspect this task", null, "task editor"));

        assertThat(emitter.getTimeout()).isZero();
        assertThat(chatService.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(profileService.requestedId).isEqualTo("agent-1");
        assertThat(chatService.agent.id()).isEqualTo("agent-1");
        assertThat(chatService.request.message()).contains("Agent page context: task editor");
        assertThat(chatService.request.model()).isEqualTo("qwen3");
    }

    @Test
    void agentChatStreamEmitsErrorForBlankMessage() throws Exception {
        StubAgentProfileService profileService = new StubAgentProfileService();
        StubChatService chatService = new StubChatService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, chatService
        );

        SseEmitter emitter = controller.chat("agent-1",
            new AgentOrchestrationController.AgentChatRequest(null, " ", null, "agent detail"));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(emitter.getTimeout()).isZero();
        assertThat(captured.awaitEventContaining("event=error", 1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anySatisfy(event -> assertThat(event).contains("message is required"));
        assertThat(chatService.request).isNull();
    }

    @Test
    void agentChatStreamEmitsErrorForUnsupportedChatResponse() throws Exception {
        StubAgentProfileService profileService = new StubAgentProfileService();
        NullResponseChatService nullChatService = new NullResponseChatService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, nullChatService
        );

        SseEmitter emitter = controller.chat("agent-1",
            new AgentOrchestrationController.AgentChatRequest(null, "hello", "qwen3", "agent detail"));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.awaitEventContaining("event=error", 1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anySatisfy(event ->
            assertThat(event).contains("agent chat returned an unsupported response")
        );
    }

    @Test
    void assignRejectsNullAssignmentType() {
        StubAgentProfileService profileService = new StubAgentProfileService();
        StubChatService chatService = new StubChatService();
        AgentOrchestrationController controller = newController(
            null, new StubAssignmentService(), null, null, profileService, chatService
        );

        assertThatThrownBy(() -> controller.assign("agent-1", new AgentOrchestrationController.AgentAssignmentCreateRequest(
            null, null, null, 0, null, null, java.util.Map.of()
        ))).isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
            assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        });
    }

    @Test
    void assignSucceedsWithValidAssignment() {
        StubAgentProfileService profileService = new StubAgentProfileService();
        StubChatService chatService = new StubChatService();
        AgentOrchestrationController controller = newController(
            null, new StubAssignmentService(), null, null, profileService, chatService
        );

        WorkAssignment result = controller.assign("agent-1", new AgentOrchestrationController.AgentAssignmentCreateRequest(
            null, null, AssignmentType.TASK_RUN, 0, null, null, java.util.Map.of()
        ));

        assertThat(result).isNotNull();
        assertThat(result.agentId()).isEqualTo("agent-1");
    }

    @Test
    void assignUsesPathAgentIdWithoutBodyAgentId() {
        StubAgentProfileService profileService = new StubAgentProfileService();
        StubChatService chatService = new StubChatService();
        var assignmentService = new AgentIdCapturingAssignmentService();
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, profileService, chatService
        );

        controller.assign("agent-from-path", new AgentOrchestrationController.AgentAssignmentCreateRequest(
            null, null, AssignmentType.REPORT, 0, null, null, java.util.Map.of()
        ));

        assertThat(assignmentService.receivedAgentId).isEqualTo("agent-from-path");
    }

    @Test
    void assignIgnoresUnknownJsonFieldAgentId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
            {
              "assignmentType": "REPORT",
              "priority": 0,
              "modelOverride": null,
              "input": {"message": "hello"},
              "agentId": "should-be-ignored"
            }
            """;
        AgentOrchestrationController.AgentAssignmentCreateRequest request =
            mapper.readValue(json, AgentOrchestrationController.AgentAssignmentCreateRequest.class);

        assertThat(request.assignmentType()).isEqualTo(AssignmentType.REPORT);
        assertThat(request.input()).containsEntry("message", "hello");
    }

    @Test
    void deleteAssignmentReturnsConflictForRunningAssignment() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        assignmentService.status = OrchestrationStatus.RUNNING;
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        assertThatThrownBy(() -> controller.deleteAssignment("agent-1", "assign-1"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void deleteAssignmentReturnsConflictForTerminalAssignment() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        assignmentService.status = OrchestrationStatus.COMPLETED;
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        assertThatThrownBy(() -> controller.deleteAssignment("agent-1", "assign-1"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void purgeAssignmentHistoryReturnsPurgedCount() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        assignmentService.purged = 3;
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        Map<String, Object> response = controller.purgeAssignmentHistory("agent-1", 30);

        assertThat(response).containsEntry("purged", 3);
        assertThat(assignmentService.purgedOlderThanDays).isEqualTo(30);
    }

    @Test
    void deleteAssignmentCallsServiceForEligibleAssignment() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        controller.deleteAssignment("agent-1", "assign-1");

        assertThat(assignmentService.deletedAssignmentId).isEqualTo("assign-1");
    }

    @Test
    void lifecycleControlsUsePathAgentId() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        controller.cancel("agent-1", "assign-cancel");
        controller.pause("agent-1", "assign-pause");
        controller.resume("agent-1", "assign-resume");

        assertThat(assignmentService.lifecycleCalls).containsExactly(
            "cancel:agent-1:assign-cancel",
            "pause:agent-1:assign-pause",
            "resume:agent-1:assign-resume"
        );
    }

    @Test
    void lifecycleCrossAgentRejectionReturnsNotFound() {
        StubAssignmentService assignmentService = new StubAssignmentService();
        assignmentService.rejectLifecycle = true;
        AgentOrchestrationController controller = newController(
            null, assignmentService, null, null, new StubAgentProfileService(), new StubChatService()
        );

        assertThatThrownBy(() -> controller.cancel("agent-1", "assign-2"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.pause("agent-1", "assign-2"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> controller.resume("agent-1", "assign-2"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void agentChatStreamEmitterHasNoTimeout() {
        StubAgentProfileService profileService = new StubAgentProfileService();
        StubChatService chatService = new StubChatService();
        AgentOrchestrationController controller = newController(
            null, null, null, null, profileService, chatService
        );

        SseEmitter emitter = controller.chat(
            "agent-1",
            new AgentOrchestrationController.AgentChatRequest(null, "hello", "qwen3", "agent detail")
        );

        assertThat(emitter.getTimeout()).isZero();
    }

    private static class StubAgentProfileService extends AgentProfileService {
        private String requestedId;

        StubAgentProfileService() {
            super(null, null, null);
        }

        @Override
        public AgentProfile get(String id) {
            requestedId = id;
            return new AgentProfile(
                id,
                "Magenta",
                AgentProfileStatus.ACTIVE,
                "qwen3",
                "system",
                List.of(),
                List.of(),
                true,
                Instant.EPOCH,
                Instant.EPOCH
            );
        }
    }

    private CapturedSse initializeEmitter(SseEmitter emitter) throws Exception {
        CapturedSse captured = new CapturedSse();
        Class<?> handlerType = Class.forName(
            "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler"
        );
        Object handler = Proxy.newProxyInstance(
            handlerType.getClassLoader(),
            new Class<?>[] { handlerType },
            (proxy, method, args) -> {
                if ("send".equals(method.getName()) && args[0] instanceof Set<?> set) {
                    for (Object item : set) {
                        captured.add(String.valueOf(item.getClass().getMethod("getData").invoke(item)));
                    }
                } else if ("send".equals(method.getName())) {
                    captured.add(String.valueOf(args[0]));
                } else if ("complete".equals(method.getName())) {
                    captured.completed.countDown();
                } else if ("completeWithError".equals(method.getName())) {
                    captured.completed.countDown();
                }
                return null;
            }
        );
        var initialize = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
            .getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
        return captured;
    }

    private static final class CapturedSse {
        private final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch completed = new CountDownLatch(1);

        private void add(String event) {
            events.add(event);
            synchronized (events) {
                events.notifyAll();
            }
        }

        private boolean awaitEventContaining(String value, long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            synchronized (events) {
                while (events.stream().noneMatch(event -> event.contains(value))) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return false;
                    }
                    TimeUnit.NANOSECONDS.timedWait(events, remaining);
                }
                return true;
            }
        }
    }

    private static class StubAssignmentService extends AssignmentService {
        private OrchestrationStatus status = OrchestrationStatus.QUEUED;
        private String deletedAssignmentId;
        private boolean rejectLifecycle;
        private final List<String> lifecycleCalls = new ArrayList<>();

        StubAssignmentService() {
            super(null, null, null, null);
        }

        @Override
        public WorkAssignment create(AssignmentRequest request) {
            if (request.agentId() == null || request.agentId().isBlank()) {
                throw new IllegalArgumentException("agentId is required");
            }
            if (request.assignmentType() == null) {
                throw new IllegalArgumentException("assignmentType is required");
            }
            return new WorkAssignment("assign-1", request.agentId(), null, null, request.assignmentType(),
                0, OrchestrationStatus.QUEUED, null, null, 0,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, null, null, null, null, null, null);
        }

        @Override
        public void delete(String agentId, String assignmentId) {
            if (status == OrchestrationStatus.RUNNING || status == OrchestrationStatus.CANCEL_REQUESTED) {
                throw new IllegalStateException("Running assignments cannot be deleted");
            }
            if (status.isTerminal()) {
                throw new IllegalStateException("Terminal assignments are retained in History");
            }
            deletedAssignmentId = assignmentId;
        }

        @Override
        public WorkAssignment cancel(String agentId, String assignmentId) {
            lifecycleCalls.add("cancel:" + agentId + ":" + assignmentId);
            if (rejectLifecycle) {
                throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
            }
            return assignment(assignmentId, agentId, OrchestrationStatus.CANCELLED);
        }

        @Override
        public WorkAssignment pause(String agentId, String assignmentId) {
            lifecycleCalls.add("pause:" + agentId + ":" + assignmentId);
            if (rejectLifecycle) {
                throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
            }
            return assignment(assignmentId, agentId, OrchestrationStatus.PAUSED);
        }

        @Override
        public WorkAssignment resume(String agentId, String assignmentId) {
            lifecycleCalls.add("resume:" + agentId + ":" + assignmentId);
            if (rejectLifecycle) {
                throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
            }
            return assignment(assignmentId, agentId, OrchestrationStatus.QUEUED);
        }

        private WorkAssignment assignment(String assignmentId, String agentId, OrchestrationStatus status) {
            return new WorkAssignment(assignmentId, agentId, null, null, AssignmentType.REPORT,
                0, status, null, null, 0,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, null, null, null, null, null, null);
        }

        private int purged;
        private int purgedOlderThanDays;

        @Override
        public int purgeHistory(String agentId, int olderThanDays) {
            purgedOlderThanDays = olderThanDays;
            return purged;
        }
    }

    private static class AgentIdCapturingAssignmentService extends AssignmentService {
        private String receivedAgentId;

        AgentIdCapturingAssignmentService() {
            super(null, null, null, null);
        }

        @Override
        public WorkAssignment create(AssignmentRequest request) {
            receivedAgentId = request.agentId();
            return new WorkAssignment("assign-1", request.agentId(), null, null, request.assignmentType(),
                0, OrchestrationStatus.QUEUED, null, null, 0,
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                null, null, null, null, null, null, null);
        }
    }

    private static class NullResponseChatService extends ChatService {
        NullResponseChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public ChatResponse chatAsAgent(AgentProfile agent, ChatRequest.MsgRequest request) {
            // Return a response that isn't MsgResponse, triggering error path
            return null;
        }
    }

    private static class StubChatService extends ChatService {
        private AgentProfile agent;
        private ChatRequest.MsgRequest request;
        private final CountDownLatch completed = new CountDownLatch(1);

        StubChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public ChatResponse chatAsAgent(AgentProfile agent, ChatRequest.MsgRequest request) {
            this.agent = agent;
            this.request = request;
            var response = new ChatResponse.MsgResponse("conversation-1", this.request.model(), "ok", null, ChatPlanState.normal());
            completed.countDown();
            return response;
        }
    }

    private static class BlockingChatService extends ChatService {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public ChatResponse chatAsAgent(AgentProfile agent, ChatRequest.MsgRequest request) {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return new ChatResponse.MsgResponse("conv-1", request.model(), "done", null, ChatPlanState.normal());
        }
    }

    @Test
    void schedulesEndpointReturns404WhenDisabled() {
        AgentOrchestrationController controller = newController(
            null, null, null, null, null, null
        );

        assertThatThrownBy(() -> controller.schedules("agent-1"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("Schedules are not available");
            });
    }

    @Test
    void scheduleCreateEndpointReturns404WhenDisabled() {
        AgentOrchestrationController controller = newController(
            null, null, null, null, null, null
        );

        assertThatThrownBy(() -> controller.schedule("agent-1",
            new AgentSchedule("sched-1", "agent-1", "job-1", Map.of(),
                "0 * * * *", "UTC", true, null, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("Schedules are not available");
            });
    }

    @Test
    void reactionsEndpointReturns404WhenDisabled() {
        AgentOrchestrationController controller = newController(
            null, null, null, null, null, null
        );

        assertThatThrownBy(() -> controller.reactions("agent-1"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("Event reactions are not available");
            });
    }

    @Test
    void reactionCreateEndpointReturns404WhenDisabled() {
        AgentOrchestrationController controller = newController(
            null, null, null, null, null, null
        );

        assertThatThrownBy(() -> controller.reaction("agent-1",
            new AgentEventReaction("reac-1", "agent-1", EventType.WORKFLOW_STATUS_CHANGED,
                Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT, Map.of(),
                true, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("Event reactions are not available");
            });
    }

    @Test
    void schedulesLifecycleSupportsUpdateAndDeleteWhenEnabled() {
        StubScheduleService scheduleService = new StubScheduleService();
        AgentOrchestrationController controller = newController(
            null, null, scheduleService, null, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "schedulesEnabled", true);

        AgentSchedule created = controller.schedule("agent-1",
            new AgentSchedule(null, "agent-1", "job-1", Map.of("assignmentType", "JOB_RUN"),
                "0 * * * * *", "UTC", true, null, null, null));

        AgentSchedule updated = controller.updateSchedule("agent-1", created.id(),
            new AgentSchedule(created.id(), "agent-1", "job-2",
                Map.of("assignmentType", "TASK_RUN", "input", Map.of("taskId", "task-1")),
                "0 */5 * * * *", "UTC", false, null, null, null));

        assertThat(updated.jobId()).isEqualTo("job-2");
        assertThat(updated.cronExpression()).isEqualTo("0 */5 * * * *");
        assertThat(updated.enabled()).isFalse();

        controller.deleteSchedule("agent-1", created.id());
        assertThat(scheduleService.schedules("agent-1")).isEmpty();
    }

    @Test
    void scheduleCreateReturnsBadRequestForInvalidAssignmentTemplate() {
        StubScheduleService scheduleService = new StubScheduleService();
        AgentOrchestrationController controller = newController(
            null, null, scheduleService, null, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "schedulesEnabled", true);

        assertThatThrownBy(() -> controller.schedule("agent-1",
            new AgentSchedule(null, "agent-1", null, Map.of("assignmentType", "NOT_A_TYPE"),
                "0 * * * * *", "UTC", true, null, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid assignmentType");
            });
        assertThatThrownBy(() -> controller.schedule("agent-1",
            new AgentSchedule(null, "agent-1", null, Map.of(),
                "0 * * * * *", "UTC", true, null, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("JOB_RUN assignments require jobId");
            });
    }

    @Test
    void schedulesDeleteIsScopedByAgentWhenEnabled() {
        StubScheduleService scheduleService = new StubScheduleService();
        AgentOrchestrationController controller = newController(
            null, null, scheduleService, null, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "schedulesEnabled", true);

        AgentSchedule created = controller.schedule("agent-1",
            new AgentSchedule(null, "agent-1", "job-1", Map.of("assignmentType", "JOB_RUN"),
                "0 * * * * *", "UTC", true, null, null, null));

        assertThatThrownBy(() -> controller.deleteSchedule("agent-2", created.id()))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("schedule not found");
            });
        assertThat(scheduleService.schedules("agent-1")).hasSize(1);
    }

    @Test
    void reactionsLifecycleSupportsUpdateAndDeleteWhenEnabled() {
        StubReactionService reactionService = new StubReactionService();
        AgentOrchestrationController controller = newController(
            null, null, null, reactionService, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "reactionsEnabled", true);

        AgentEventReaction created = controller.reaction("agent-1",
            new AgentEventReaction(null, "agent-1", EventType.JOB_STATUS_CHANGED,
                Map.of("status", "QUEUED"), ReactionActionType.ENQUEUE_ASSIGNMENT,
                Map.of("assignmentType", "REPORT"), true, null, null));

        AgentEventReaction updated = controller.updateReaction("agent-1", created.id(),
            new AgentEventReaction(created.id(), "agent-1", EventType.MANUAL_USER_EVENT,
                Map.of("source", "ops"), ReactionActionType.ENQUEUE_ASSIGNMENT,
                Map.of("assignmentType", "TASK_RUN", "input", Map.of("taskId", "task-1")), false, null, null));

        assertThat(updated.eventType()).isEqualTo(EventType.MANUAL_USER_EVENT);
        assertThat(updated.enabled()).isFalse();

        controller.deleteReaction("agent-1", created.id());
        assertThat(reactionService.reactions("agent-1")).isEmpty();
    }

    @Test
    void reactionCreateReturnsBadRequestForInvalidAssignmentTemplate() {
        StubReactionService reactionService = new StubReactionService();
        AgentOrchestrationController controller = newController(
            null, null, null, reactionService, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "reactionsEnabled", true);

        assertThatThrownBy(() -> controller.reaction("agent-1",
            new AgentEventReaction(null, "agent-1", EventType.JOB_STATUS_CHANGED,
                Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
                Map.of("assignmentType", "NOT_A_TYPE"), true, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("invalid assignmentType");
            });
        assertThatThrownBy(() -> controller.reaction("agent-1",
            new AgentEventReaction(null, "agent-1", EventType.JOB_STATUS_CHANGED,
                Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
                Map.of("assignmentType", "WORKFLOW_RUN"), true, null, null)))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(exception.getReason()).contains("WORKFLOW_RUN assignments require input.workflowId");
            });
    }

    @Test
    void reactionsDeleteIsScopedByAgentWhenEnabled() {
        StubReactionService reactionService = new StubReactionService();
        AgentOrchestrationController controller = newController(
            null, null, null, reactionService, new StubAgentProfileService(), null
        );
        setFeatureFlag(controller, "reactionsEnabled", true);

        AgentEventReaction created = controller.reaction("agent-1",
            new AgentEventReaction(null, "agent-1", EventType.JOB_STATUS_CHANGED,
                Map.of("status", "QUEUED"), ReactionActionType.ENQUEUE_ASSIGNMENT,
                Map.of("assignmentType", "REPORT"), true, null, null));

        assertThatThrownBy(() -> controller.deleteReaction("agent-2", created.id()))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(exception.getReason()).contains("reaction not found");
            });
        assertThat(reactionService.reactions("agent-1")).hasSize(1);
    }

    private static void setFeatureFlag(AgentOrchestrationController controller, String fieldName, boolean value) {
        try {
            java.lang.reflect.Field field = AgentOrchestrationController.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(controller, value);
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to set feature flag for test", exception);
        }
    }

    private static class StubScheduleService extends ScheduleService {
        private final Map<String, List<AgentSchedule>> byAgent = new java.util.HashMap<>();

        StubScheduleService() {
            super(null, null, null, null, true);
        }

        @Override
        public List<AgentSchedule> schedules(String agentId) {
            return byAgent.getOrDefault(agentId, List.of());
        }

        @Override
        public AgentSchedule schedule(String agentId, String scheduleId) {
            return schedules(agentId).stream()
                .filter(schedule -> scheduleId.equals(schedule.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("schedule not found"));
        }

        @Override
        public AgentSchedule save(String agentId, AgentSchedule schedule) {
            if (!org.springframework.scheduling.support.CronExpression.isValidExpression(schedule.cronExpression())) {
                throw new IllegalArgumentException("invalid cronExpression");
            }
            validateAssignmentTemplate(schedule.assignmentTemplate(), AssignmentType.JOB_RUN, schedule.jobId());
            List<AgentSchedule> current = new ArrayList<>(schedules(agentId));
            AgentSchedule saved = new AgentSchedule(
                schedule.id() == null || schedule.id().isBlank() ? "sched-" + (current.size() + 1) : schedule.id(),
                agentId,
                schedule.jobId(),
                schedule.assignmentTemplate(),
                schedule.cronExpression(),
                schedule.timezone(),
                schedule.enabled(),
                Instant.now().plusSeconds(60),
                schedule.createdAt() == null ? Instant.now() : schedule.createdAt(),
                Instant.now()
            );
            current.removeIf(existing -> existing.id().equals(saved.id()));
            current.add(saved);
            byAgent.put(agentId, current);
            return saved;
        }

        @Override
        public void delete(String agentId, String scheduleId) {
            List<AgentSchedule> current = new ArrayList<>(schedules(agentId));
            boolean removed = current.removeIf(schedule -> schedule.id().equals(scheduleId));
            if (!removed) {
                throw new IllegalStateException("schedule not found");
            }
            byAgent.put(agentId, current);
        }
    }

    private static class StubReactionService extends EventReactionService {
        private final Map<String, List<AgentEventReaction>> byAgent = new java.util.HashMap<>();

        StubReactionService() {
            super(null, null);
        }

        @Override
        public List<AgentEventReaction> reactions(String agentId) {
            return byAgent.getOrDefault(agentId, List.of());
        }

        @Override
        public AgentEventReaction reaction(String agentId, String reactionId) {
            return reactions(agentId).stream()
                .filter(reaction -> reactionId.equals(reaction.id()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("reaction not found"));
        }

        @Override
        public AgentEventReaction save(String agentId, AgentEventReaction reaction) {
            validateAssignmentTemplate(reaction.assignmentTemplate(), AssignmentType.REPORT, null);
            List<AgentEventReaction> current = new ArrayList<>(reactions(agentId));
            AgentEventReaction saved = new AgentEventReaction(
                reaction.id() == null || reaction.id().isBlank() ? "reaction-" + (current.size() + 1) : reaction.id(),
                agentId,
                reaction.eventType(),
                reaction.filter(),
                ReactionActionType.ENQUEUE_ASSIGNMENT,
                reaction.assignmentTemplate(),
                reaction.enabled(),
                reaction.createdAt() == null ? Instant.now() : reaction.createdAt(),
                Instant.now()
            );
            current.removeIf(existing -> existing.id().equals(saved.id()));
            current.add(saved);
            byAgent.put(agentId, current);
            return saved;
        }

        @Override
        public void delete(String agentId, String reactionId) {
            List<AgentEventReaction> current = new ArrayList<>(reactions(agentId));
            boolean removed = current.removeIf(reaction -> reaction.id().equals(reactionId));
            if (!removed) {
                throw new IllegalStateException("reaction not found");
            }
            byAgent.put(agentId, current);
        }
    }

    private static void validateAssignmentTemplate(
        Map<String, Object> template,
        AssignmentType defaultType,
        String fallbackJobId
    ) {
        Map<String, Object> values = template == null ? Map.of() : template;
        AssignmentType type;
        try {
            Object rawType = values.get("assignmentType");
            type = AssignmentType.valueOf(rawType == null || rawType.toString().isBlank()
                ? defaultType.name()
                : rawType.toString().trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid assignmentType");
        }
        Object rawInput = values.get("input");
        Map<?, ?> input = rawInput instanceof Map<?, ?> map ? map : Map.of();
        if (type == AssignmentType.TASK_RUN && !hasText(input.get("taskId"))) {
            throw new IllegalArgumentException("TASK_RUN assignments require input.taskId");
        }
        if (type == AssignmentType.WORKFLOW_RUN && !hasText(input.get("workflowId"))) {
            throw new IllegalArgumentException("WORKFLOW_RUN assignments require input.workflowId");
        }
        if (type == AssignmentType.JOB_RUN
            && !hasText(values.get("jobId"))
            && !hasText(fallbackJobId)
            && !hasText(input.get("jobId"))) {
            throw new IllegalArgumentException("JOB_RUN assignments require jobId");
        }
    }

    private static boolean hasText(Object value) {
        return value != null && !value.toString().isBlank();
    }
}
