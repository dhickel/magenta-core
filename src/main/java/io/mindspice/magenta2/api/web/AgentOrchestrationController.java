package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mindspice.magenta2.ai.chat.model.ChatRequest;
import io.mindspice.magenta2.ai.chat.model.ChatResponse;
import io.mindspice.magenta2.ai.chat.service.AgentChatPromptService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentEventReaction;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentSchedule;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEventService;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleMode;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiring;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleFiringAttempt;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.ScheduleSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService.SchedulerClaim;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionDispatch;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService.ReactionSimulation;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/agents/{agentId}")
public class AgentOrchestrationController {
    private final InboxService inboxService;
    private final AssignmentService assignmentService;
    private final ScheduleService scheduleService;
    private final EventReactionService reactionService;
    private final OrchestrationEventService eventService;
    private final AgentProfileService agentProfileService;
    private final ChatService chatService;
    private final AgentChatPromptService agentChatPromptService;

    @Value("${magenta.features.schedules-enabled:false}")
    private boolean schedulesEnabled;

    @Value("${magenta.features.reactions-enabled:false}")
    private boolean reactionsEnabled;

    public AgentOrchestrationController(
        InboxService inboxService,
        AssignmentService assignmentService,
        ScheduleService scheduleService,
        EventReactionService reactionService,
        OrchestrationEventService eventService,
        AgentProfileService agentProfileService,
        ChatService chatService,
        AgentChatPromptService agentChatPromptService
    ) {
        this.inboxService = inboxService;
        this.assignmentService = assignmentService;
        this.scheduleService = scheduleService;
        this.reactionService = reactionService;
        this.eventService = eventService;
        this.agentProfileService = agentProfileService;
        this.chatService = chatService;
        this.agentChatPromptService = agentChatPromptService;
    }

    // ── Inbox ──

    @GetMapping("/inbox")
    public List<InboxMessage> inbox(@PathVariable String agentId) {
        return inboxService.messages(agentId);
    }

    @PostMapping("/inbox")
    public InboxMessage send(@PathVariable String agentId, @Valid @RequestBody InboxMessage message) {
        try {
            return inboxService.send(agentId, message);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/inbox/{messageId}/read")
    public InboxMessage read(@PathVariable String messageId) {
        return inboxService.markRead(messageId);
    }

    @PostMapping("/inbox/{messageId}/handled")
    public InboxMessage handled(@PathVariable String messageId) {
        return inboxService.markHandled(messageId);
    }

    // ── Assignments ──

    @GetMapping("/assignments")
    public List<WorkAssignment> assignments(@PathVariable String agentId) {
        return assignmentService.assignments(agentId);
    }

    @GetMapping("/assignment-history")
    public List<WorkAssignment> assignmentHistory(@PathVariable String agentId) {
        return assignmentService.historyAssignments(agentId);
    }

    @DeleteMapping("/assignment-history")
    public Map<String, Object> purgeAssignmentHistory(
        @PathVariable String agentId,
        @RequestParam("olderThanDays") int olderThanDays
    ) {
        try {
            int purged = assignmentService.purgeHistory(agentId, olderThanDays);
            return Map.of("purged", purged);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/assignments")
    public WorkAssignment assign(
        @PathVariable String agentId,
        @Valid @RequestBody AgentAssignmentCreateRequest request
    ) {
        try {
            return assignmentService.create(new AssignmentRequest(
                agentId,
                request.jobId(),
                request.jobItemId(),
                request.assignmentType(),
                null,
                request.priority(),
                request.modelOverride(),
                request.projectId(),
                request.workspaceType(),
                request.workspaceKey(),
                request.input(),
                request.operationKey()
            ));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/assignments/{assignmentId}/cancel")
    public WorkAssignment cancel(@PathVariable String agentId, @PathVariable String assignmentId) {
        try {
            return assignmentService.cancel(agentId, assignmentId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw assignmentLifecycleException(exception);
        }
    }

    @PostMapping("/assignments/{assignmentId}/pause")
    public WorkAssignment pause(@PathVariable String agentId, @PathVariable String assignmentId) {
        try {
            return assignmentService.pause(agentId, assignmentId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw assignmentLifecycleException(exception);
        }
    }

    @PostMapping("/assignments/{assignmentId}/resume")
    public WorkAssignment resume(@PathVariable String agentId, @PathVariable String assignmentId) {
        try {
            return assignmentService.resume(agentId, assignmentId);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw assignmentLifecycleException(exception);
        }
    }

    @PostMapping("/assignments/{assignmentId}/retry")
    public WorkAssignment retry(@PathVariable String agentId, @PathVariable String assignmentId,
                                @RequestBody(required = false) RunOperationRequest request) {
        try {
            return assignmentService.retry(agentId, assignmentId,
                request == null ? null : request.operationKey());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw assignmentLifecycleException(exception);
        }
    }

    @PostMapping("/assignments/{assignmentId}/replay")
    public WorkAssignment replay(@PathVariable String agentId, @PathVariable String assignmentId,
                                 @RequestBody(required = false) RunOperationRequest request) {
        try {
            return assignmentService.replay(agentId, assignmentId,
                request == null ? null : request.operationKey());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw assignmentLifecycleException(exception);
        }
    }

    @DeleteMapping("/assignments/{assignmentId}")
    public void deleteAssignment(@PathVariable String agentId, @PathVariable String assignmentId) {
        try {
            assignmentService.delete(agentId, assignmentId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (IllegalStateException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("not found")) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage());
        }
    }

    // ── Schedules (revisioned) ──

    @GetMapping("/schedules")
    public List<ScheduleDefinition> schedules(@PathVariable String agentId) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        return scheduleService.definitions(agentId);
    }

    @GetMapping("/schedules/{scheduleId}")
    public ScheduleDefinition schedule(@PathVariable String agentId, @PathVariable String scheduleId) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            return scheduleService.definition(agentId, scheduleId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/schedules")
    public ScheduleDefinition createSchedule(@PathVariable String agentId,
                                              @Valid @RequestBody ScheduleCreateRequest request) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            return scheduleService.save(agentId, new ScheduleDefinition(
                    null, agentId, 0,
                    ScheduleMode.CRON,
                    request.cronExpression(), request.timezone(),
                    null,
                    request.enabled() != null ? request.enabled() : true, false,
                    null,
                    request.jobId(),
                    request.assignmentTemplate() == null ? Map.of() : request.assignmentTemplate(),
                    null, null, null, null));
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/schedules/{scheduleId}")
    public ScheduleDefinition updateSchedule(
        @PathVariable String agentId, @PathVariable String scheduleId,
        @Valid @RequestBody ScheduleCreateRequest request
    ) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            ScheduleDefinition existing = scheduleService.definition(agentId, scheduleId);
            return scheduleService.update(agentId, scheduleId, new ScheduleDefinition(
                    scheduleId, agentId, existing.revision(),
                    existing.mode() != null ? existing.mode() : ScheduleMode.CRON,
                    request.cronExpression() != null ? request.cronExpression() : existing.cronExpression(),
                    request.timezone() != null ? request.timezone() : existing.timezone(),
                    existing.fireAt(),
                    request.enabled() != null ? request.enabled() : existing.enabled(),
                    false,
                    existing.projectId(),
                    request.jobId() != null ? request.jobId() : existing.jobId(),
                    request.assignmentTemplate() != null ? request.assignmentTemplate() : existing.assignmentTemplate(),
                    null, null, existing.createdAt(), existing.updatedAt()));
        } catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/schedules/{scheduleId}/enable")
    public ScheduleDefinition enableSchedule(@PathVariable String agentId,
                                              @PathVariable String scheduleId,
                                              @RequestBody Map<String, Boolean> body) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
            return scheduleService.enable(agentId, scheduleId, enabled);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @DeleteMapping("/schedules/{scheduleId}")
    public void deleteSchedule(@PathVariable String agentId, @PathVariable String scheduleId) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            scheduleService.delete(agentId, scheduleId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/schedules/{scheduleId}/firings")
    public List<ScheduleFiring> scheduleFirings(
            @PathVariable String agentId, @PathVariable String scheduleId,
            @RequestParam(defaultValue = "1") int revision,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            scheduleService.definition(agentId, scheduleId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return scheduleService.firings(scheduleId, revision, offset, limit);
    }

    @GetMapping("/schedules/{scheduleId}/firings/{firingId}/attempts")
    public List<ScheduleFiringAttempt> firingAttempts(
            @PathVariable String agentId, @PathVariable String scheduleId,
            @PathVariable String firingId) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        return scheduleService.firingAttempts(firingId);
    }

    @PostMapping("/schedules/simulate")
    public ScheduleSimulation simulateSchedule(@PathVariable String agentId,
                                                @Valid @RequestBody ScheduleSimRequest request) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            return scheduleService.simulate(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/schedules/{scheduleId}/cancel-linked")
    public Map<String, Object> cancelLinkedWork(@PathVariable String agentId,
                                                 @PathVariable String scheduleId,
                                                 @RequestParam(defaultValue = "1") int revision) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            scheduleService.cancelLinkedWork(agentId, scheduleId, revision);
            return Map.of("cancelled", true);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    // ── Event Reactions (revisioned) ──

    @GetMapping("/event-reactions")
    public List<ReactionDefinition> reactions(@PathVariable String agentId) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        return reactionService.definitions(agentId);
    }

    @GetMapping("/event-reactions/{reactionId}")
    public ReactionDefinition reaction(@PathVariable String agentId, @PathVariable String reactionId) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            return reactionService.definition(agentId, reactionId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @PostMapping("/event-reactions")
    public ReactionDefinition createReaction(@PathVariable String agentId,
                                              @Valid @RequestBody ReactionCreateRequest request) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            return reactionService.save(agentId, new ReactionDefinition(
                    null, agentId, 0,
                    request.eventType(),
                    request.filter() == null ? Map.of() : request.filter(),
                    request.actionType() != null ? request.actionType() : ReactionActionType.ENQUEUE_ASSIGNMENT,
                    request.assignmentTemplate() == null ? Map.of() : request.assignmentTemplate(),
                    request.enabled() != null ? request.enabled() : true, false,
                    null,
                    null, null));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PutMapping("/event-reactions/{reactionId}")
    public ReactionDefinition updateReaction(
        @PathVariable String agentId, @PathVariable String reactionId,
        @Valid @RequestBody ReactionCreateRequest request
    ) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            ReactionDefinition existing = reactionService.definition(agentId, reactionId);
            return reactionService.update(agentId, reactionId, new ReactionDefinition(
                    reactionId, agentId, existing.revision(),
                    request.eventType() != null ? request.eventType() : existing.eventType(),
                    request.filter() != null ? request.filter() : existing.filter(),
                    request.actionType() != null ? request.actionType() : existing.actionType(),
                    request.assignmentTemplate() != null ? request.assignmentTemplate() : existing.assignmentTemplate(),
                    request.enabled() != null ? request.enabled() : existing.enabled(),
                    false, existing.projectId(),
                    existing.createdAt(), existing.updatedAt()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @PostMapping("/event-reactions/{reactionId}/enable")
    public ReactionDefinition enableReaction(@PathVariable String agentId,
                                              @PathVariable String reactionId,
                                              @RequestBody Map<String, Boolean> body) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
            return reactionService.enable(agentId, reactionId, enabled);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @DeleteMapping("/event-reactions/{reactionId}")
    public void deleteReaction(@PathVariable String agentId, @PathVariable String reactionId) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            reactionService.delete(agentId, reactionId);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        }
    }

    @GetMapping("/event-reactions/{reactionId}/dispatches")
    public List<ReactionDispatch> reactionDispatches(
            @PathVariable String agentId, @PathVariable String reactionId,
            @RequestParam(defaultValue = "1") int revision,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            reactionService.definition(agentId, reactionId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        return reactionService.dispatches(reactionId, revision, offset, limit);
    }

    @GetMapping("/event-reactions/dispatches/by-root-event/{rootEventId}")
    public List<ReactionDispatch> dispatchesByRootEvent(
            @PathVariable String agentId, @PathVariable String rootEventId) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        return reactionService.dispatchesByRootEvent(rootEventId);
    }

    @PostMapping("/event-reactions/simulate")
    public ReactionSimulation simulateReaction(@PathVariable String agentId,
                                                @Valid @RequestBody ReactionSimRequest request) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            return reactionService.simulate(request);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    // ── Orchestration Events ──

    @GetMapping("/orchestration-events")
    public List<OrchestrationEvent> orchestrationEvents(
            @PathVariable String agentId,
            @RequestParam(defaultValue = "SCHEDULE") String sourceType,
            @RequestParam(required = false) String sourceId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit) {
        return eventService.findEventsForSourcePaginated(sourceType, sourceId, offset, limit);
    }

    // ── Chat ──

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String agentId, @Valid @RequestBody AgentChatRequest request) {
        SseEmitter emitter = SseStreamLifecycle.createEmitter();
        SseStreamLifecycle.SubscriptionGuard guard = SseStreamLifecycle.guardSubscription();
        SseStreamLifecycle.registerCallbacks(emitter, guard, null, null);

        Disposable subscription = Flux.defer(() -> {
                AgentProfile agent = agentProfileService.get(agentId);
                String message = request == null ? null : request.message();
                if (message == null || message.isBlank()) {
                    return Flux.just(new AgentChatEvent("error", Map.of(
                        "event", "error",
                        "error", "message is required"
                    )));
                }
                String prompt = agentChatPromptService.buildPrompt(request.pageContext(), message);
                String model = request.model() == null || request.model().isBlank() ? agent.defaultModel() : request.model();
                var start = new LinkedHashMap<String, Object>();
                start.put("event", "start");
                start.put("agentId", agent.id());
                start.put("agentName", agent.name());
                return Flux.concat(
                    Flux.just(new AgentChatEvent("start", start)),
                    Mono.fromCallable(() -> donePayload(agent, request, prompt, model))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(done -> new AgentChatEvent("done", done))
                        .onErrorResume(error -> Mono.just(new AgentChatEvent("error", Map.of(
                            "event", "error",
                            "error", error.getMessage()
                        ))))
                        .flux()
                );
            })
            .subscribe(
                event -> {
                    if (!SseStreamLifecycle.trySendSseEvent(emitter, event.name(), event.data())) {
                        guard.dispose();
                        SseStreamLifecycle.completeQuietly(emitter);
                        return;
                    }
                    if ("done".equals(event.name()) || "error".equals(event.name())) {
                        SseStreamLifecycle.completeQuietly(emitter);
                    }
                },
                error -> {
                    var errorPayload = new LinkedHashMap<String, Object>();
                    errorPayload.put("event", "error");
                    errorPayload.put("error", error.getMessage());
                    if (SseStreamLifecycle.trySendSseEvent(emitter, "error", errorPayload)) {
                        SseStreamLifecycle.completeQuietly(emitter);
                    }
                }
            );

        guard.set(subscription);
        return emitter;
    }

    private Map<String, Object> donePayload(AgentProfile agent, AgentChatRequest request, String prompt, String model) {
        ChatResponse response = chatService.chatAsAgent(agent, new ChatRequest.MsgRequest(
            request.conversationId(), prompt, model, null
        ));
        if (!(response instanceof ChatResponse.MsgResponse messageResponse)) {
            throw new IllegalStateException("agent chat returned an unsupported response");
        }
        var done = new LinkedHashMap<String, Object>();
        done.put("event", "done");
        done.put("agentId", agent.id());
        done.put("conversationId", messageResponse.conversationId());
        done.put("model", messageResponse.model());
        done.put("message", messageResponse.response());
        return done;
    }

    // ── Backward-compatible wrappers (delegate to new API, for test direct-call compatibility) ──

    public AgentSchedule schedule(@PathVariable String agentId, @Valid @RequestBody AgentSchedule schedule) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            return scheduleService.save(agentId, schedule);
        } catch (IllegalArgumentException | java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    public AgentSchedule updateSchedule(@PathVariable String agentId, @PathVariable String scheduleId,
                                         @Valid @RequestBody AgentSchedule schedule) {
        if (!schedulesEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Schedules are not available");
        }
        try {
            AgentSchedule existing = scheduleService.schedule(agentId, scheduleId);
            return scheduleService.save(agentId, new AgentSchedule(
                    scheduleId, agentId,
                    schedule.jobId() != null ? schedule.jobId() : existing.jobId(),
                    schedule.assignmentTemplate() != null ? schedule.assignmentTemplate() : existing.assignmentTemplate(),
                    schedule.cronExpression() != null ? schedule.cronExpression() : existing.cronExpression(),
                    schedule.timezone() != null ? schedule.timezone() : existing.timezone(),
                    schedule.enabled(), null, existing.createdAt(), existing.updatedAt()));
        } catch (IllegalArgumentException | IllegalStateException | java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    public AgentEventReaction reaction(@PathVariable String agentId, @Valid @RequestBody AgentEventReaction reaction) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            return reactionService.save(agentId, reaction);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    public AgentEventReaction updateReaction(@PathVariable String agentId, @PathVariable String reactionId,
                                              @Valid @RequestBody AgentEventReaction reaction) {
        if (!reactionsEnabled) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Event reactions are not available");
        }
        try {
            AgentEventReaction existing = reactionService.reaction(agentId, reactionId);
            return reactionService.save(agentId, new AgentEventReaction(
                    reactionId, agentId,
                    reaction.eventType() != null ? reaction.eventType() : existing.eventType(),
                    reaction.filter() != null ? reaction.filter() : existing.filter(),
                    reaction.actionType() != null ? reaction.actionType() : existing.actionType(),
                    reaction.assignmentTemplate() != null ? reaction.assignmentTemplate() : existing.assignmentTemplate(),
                    reaction.enabled(), existing.createdAt(), existing.updatedAt()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private AgentSchedule toLegacySchedule(ScheduleDefinition def) {
        return new AgentSchedule(def.id(), def.agentId(), def.jobId(),
                def.assignmentTemplate(), def.cronExpression(), def.timezone(),
                def.enabled(), null, def.createdAt(), def.updatedAt());
    }

    private AgentEventReaction toLegacyReaction(ReactionDefinition def) {
        return new AgentEventReaction(def.id(), def.agentId(), def.eventType(),
                def.filter(), def.actionType(), def.assignmentTemplate(),
                def.enabled(), def.createdAt(), def.updatedAt());
    }

    // ── Helpers ──

    private ResponseStatusException assignmentLifecycleException(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && (message.contains("not found") || message.contains("does not belong"))) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    // ── Value Types ──

    private record AgentChatEvent(String name, Map<String, Object> data) {}

    public record RunOperationRequest(String operationKey) { }

    public record AgentChatRequest(String conversationId, String message, String model, String pageContext) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AgentAssignmentCreateRequest(
        String jobId,
        String jobItemId,
        @NotNull AssignmentType assignmentType,
        Integer priority,
        String modelOverride,
        String projectId,
        String workspaceType,
        String workspaceKey,
        Map<String, Object> input,
        String operationKey
    ) {
        public AgentAssignmentCreateRequest(
            String jobId, String jobItemId, AssignmentType assignmentType, Integer priority,
            String modelOverride, String projectId, String workspaceType, String workspaceKey,
            Map<String, Object> input
        ) {
            this(jobId, jobItemId, assignmentType, priority, modelOverride, projectId,
                workspaceType, workspaceKey, input, null);
        }

        public AgentAssignmentCreateRequest(
            String jobId, String jobItemId, AssignmentType assignmentType, Integer priority,
            String modelOverride, String projectId, Map<String, Object> input
        ) {
            this(jobId, jobItemId, assignmentType, priority, modelOverride, projectId,
                null, null, input, null);
        }

        public AgentAssignmentCreateRequest {
            input = input == null ? Map.of() : Map.copyOf(input);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleCreateRequest(
            String cronExpression,
            String timezone,
            Boolean enabled,
            String jobId,
            Map<String, Object> assignmentTemplate) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReactionCreateRequest(
            EventType eventType,
            Map<String, Object> filter,
            ReactionActionType actionType,
            Boolean enabled,
            Map<String, Object> assignmentTemplate) {}
}
