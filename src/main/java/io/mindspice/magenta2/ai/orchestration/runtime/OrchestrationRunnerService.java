package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.execution.MagentaWorkExecutor;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;

import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNodeRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowExecutionObserver;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRunStatus;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunWriteCapability;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrchestrationRunnerService {
    private static final Logger logger = LoggerFactory.getLogger(OrchestrationRunnerService.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofMinutes(1);

    private final OrchestrationRuntimeRepository repository;
    private final AssignmentService assignmentService;
    private final JobService jobService;
    private final TaskService taskService;
    private final WorkflowService workflowService;
    private final ChatService chatService;
    private final InboxService inboxService;
    private final OrchestrationEventService eventService;
    private final OutputArtifactService outputArtifactService;
    private final ProjectService projectService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final RootRelativePathService rootRelativePathService;
    private final AgentProfileService agentProfileService;
    private final TaskRunLifecycleService taskRunLifecycleService;
    private final AgentContextService agentContextService;

    private final MagentaWorkExecutor executor;
    private final Duration leaseDuration;
    private final Duration heartbeatInterval;
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "orchestration-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<?>> activeAssignmentFutures = new ConcurrentHashMap<>();
    private final String leaseOwner = UUID.randomUUID().toString();

    public OrchestrationRunnerService(
        OrchestrationRuntimeRepository repository,
        AssignmentService assignmentService,
        JobService jobService,
        TaskService taskService,
        WorkflowService workflowService,
        InboxService inboxService,
        OrchestrationEventService eventService,
        MagentaWorkExecutor executor
    ) {
        this(repository, assignmentService, jobService, taskService, workflowService, null, inboxService,
            eventService, null, null, null, null, null, executor,
            DEFAULT_LEASE_DURATION.toSeconds(), DEFAULT_HEARTBEAT_INTERVAL.toSeconds(), null, null);
    }

    @Autowired
    public OrchestrationRunnerService(
        OrchestrationRuntimeRepository repository,
        AssignmentService assignmentService,
        JobService jobService,
        TaskService taskService,
        WorkflowService workflowService,
        ChatService chatService,
        InboxService inboxService,
        OrchestrationEventService eventService,
        @Autowired(required = false) AgentProfileService agentProfileService,
        @Autowired(required = false) OutputArtifactService outputArtifactService,
        @Autowired(required = false) ProjectService projectService,
        @Autowired(required = false) WorkspaceDirectoryService workspaceDirectoryService,
        @Autowired(required = false) RootRelativePathService rootRelativePathService,
        MagentaWorkExecutor executor,
        @Value("${magenta.orchestration.lease-seconds:300}") long leaseSeconds,
        @Value("${magenta.orchestration.heartbeat-seconds:60}") long heartbeatSeconds,
        @Autowired(required = false) TaskRunLifecycleService taskRunLifecycleService,
        @Autowired(required = false) AgentContextService agentContextService
    ) {
        this.repository = repository;
        this.assignmentService = assignmentService;
        this.jobService = jobService;
        this.taskService = taskService;
        this.workflowService = workflowService;
        this.chatService = chatService;
        this.inboxService = inboxService;
        this.eventService = eventService;
        this.agentProfileService = agentProfileService;
        this.outputArtifactService = outputArtifactService;
        this.projectService = projectService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.rootRelativePathService = workspaceDirectoryService != null
            ? new RootRelativePathService(workspaceDirectoryService)
            : null;
        this.executor = executor;
        this.leaseDuration = secondsOrDefault(leaseSeconds, DEFAULT_LEASE_DURATION);
        this.heartbeatInterval = secondsOrDefault(heartbeatSeconds, DEFAULT_HEARTBEAT_INTERVAL);
        this.taskRunLifecycleService = taskRunLifecycleService;
        this.agentContextService = agentContextService;
        this.assignmentService.registerLocalInterruptHandler(this::cancelLocalAssignment);
    }

    @Scheduled(fixedDelayString = "${magenta.orchestration.runner-delay-ms:2000}")
    public void pollQueuedWork() {
        recoverStaleLeases();
        for (WorkAssignment queued : repository.findRecoverableAssignments(4)) {
            var leased = repository.acquireLeaseFenced(queued.id(), leaseOwner, Instant.now().plus(leaseDuration));
            if (leased.isEmpty()) continue;
            WorkAssignment assignment = leased.get();
            try {
                CompletableFuture<?> future = executor.submitBackground(assignment.id(), assignment.priority(),
                    "orchestration assignment " + assignment.id(), () -> {
                    executeWithLease(assignment);
                    return null;
                });
                activeAssignmentFutures.put(assignment.id(), future);
                future.whenComplete((ignored, error) -> activeAssignmentFutures.remove(assignment.id(), future));
            } catch (RejectedExecutionException e) {
                repository.releaseAssignmentFenced(assignment.id(), leaseOwner, assignment.leaseFence());
            }
        }
    }

    public int recoverStaleLeases() {
        Instant now = Instant.now();
        int recovered = repository.markStaleRunningLeases(now)
            + repository.markStaleCancelRequestedLeases(now);
        if (recovered > 0 && taskRunLifecycleService != null) {
            taskRunLifecycleService.interruptWritersForStaleAssignments(
                "Assignment lease expired or cancellation became stale");
        }
        return recovered;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        int interrupted = recoverStaleLeases();
        logger.info("Startup recovery: marked {} stale RUNNING assignments as INTERRUPTED", interrupted);
        if (interrupted > 0) {
            logger.info("INTERRUPTED assignments will be picked up by the normal polling loop via findRecoverableAssignments");
        }
    }

    public WorkAssignment runNextSynchronously() {
        recoverStaleLeases();
        return repository.findRecoverableAssignments(1).stream()
            .findFirst()
            .map(assignment -> runAssignment(assignment.id()))
            .orElse(null);
    }

    public WorkAssignment runAssignment(String assignmentId) {
        return repository.acquireLeaseFenced(assignmentId, leaseOwner, Instant.now().plus(leaseDuration))
            .map(this::executeWithLease)
            .orElseGet(() -> assignmentService.get(assignmentId));
    }

    private WorkAssignment executeWithLease(WorkAssignment leased) {
        AgentProfile profile = null;
        if (agentProfileService != null) {
            profile = agentProfileService.get(leased.agentId());
            if (profile.status() == AgentProfileStatus.DISABLED) {
                return fail(assignmentService.get(leased.id()),
                    "Agent " + leased.agentId() + " is disabled and cannot execute assignments.");
            }
        }

        WorkspaceDescriptor workspaceDescriptor = resolveWorkspaceDescriptor(leased);
        if (workspaceDescriptor == null) {
            return fail(assignmentService.get(leased.id()), "Workspace selection cannot be resolved");
        }

        TaskRunLocation taskRunLocation = null;
        TaskRunWriteCapability writerCapability = null;
        String attemptId = null;
        if (isTaskRunAssignment(leased.assignmentType())) {
            if (taskRunLifecycleService == null) {
                return fail(assignmentService.get(leased.id()), "Task-run lifecycle service is unavailable");
            }
            String taskRunId = text(leased.checkpoint().get("taskRunId"), null);
            if (!StringUtils.hasText(taskRunId)) {
                return fail(assignmentService.get(leased.id()),
                    "Assignment has no reserved native task-run identity");
            }
            try {
                validateImmutableBinding(leased, taskRunId, workspaceDescriptor);
                LifecycleResult lifecycleResult = taskRunLifecycleService.findById(taskRunId);
                int attemptNumber = integer(leased.checkpoint().get("attemptNumber"),
                    lifecycleResult.attemptNumber());
                taskRunLocation = taskRunLifecycleService.provisionLocation(taskRunId);
                if (!taskRunLocation.workspaceIdentity().equals(workspaceDescriptor.identity())) {
                    throw new IllegalStateException("Reserved run workspace does not match assignment workspace");
                }
                try {
                    writerCapability = taskRunLifecycleService.acquireWriterClaim(
                        taskRunLocation, attemptNumber, leaseOwner);
                } catch (IllegalStateException staleClaim) {
                    String recoveryDigest = taskRunLifecycleService.canonicalInputDigest(Map.of(
                        "assignmentId", leased.id(), "taskRunId", taskRunId,
                        "assignmentFence", leased.leaseFence()));
                    lifecycleResult = taskRunLifecycleService.recoverForAssignment(taskRunId, leased.id(),
                        "lease-recovery:" + leased.id() + ":" + leased.leaseFence(), recoveryDigest);
                    attemptNumber = lifecycleResult.attemptNumber();
                    taskRunLocation = taskRunLifecycleService.provisionLocation(taskRunId);
                    writerCapability = taskRunLifecycleService.acquireWriterClaim(
                        taskRunLocation, attemptNumber, leaseOwner);
                    Map<String, Object> checkpoint = new LinkedHashMap<>(leased.checkpoint());
                    checkpoint.put("attemptNumber", attemptNumber);
                    checkpoint.put("lifecycleOperationKey", lifecycleResult.operationKey());
                    checkpoint.put("lifecycleInputDigest", recoveryDigest);
                    leased = assignmentService.saveIfLeaseOwner(checkpointed(leased,
                        leased.currentItemIndex(), checkpoint, leased.output(), leased.evidence()), leaseOwner);
                }
                attemptId = taskRunId + ":" + attemptNumber;
            } catch (RuntimeException lifecycleFailure) {
                logger.error("Lifecycle setup for assignment {} failed closed", leased.id(), lifecycleFailure);
                if (writerCapability != null && taskRunLifecycleService != null) {
                    try { taskRunLifecycleService.releaseWriterClaim(writerCapability); } catch (Exception ignored) { }
                }
                return fail(assignmentService.get(leased.id()),
                    "Task-run lifecycle setup failed: " + lifecycleFailure.getMessage());
            }
        }

        final TaskRunWriteCapability activeWriter = writerCapability;
        ScheduledFuture<?> heartbeat = null;
        try {
            heartbeat = startLeaseHeartbeat(
                leased.id(), leased.leaseFence(), activeWriter);
            OrchestrationTaskContext taskContext = new OrchestrationTaskContext(
                leased.agentId(), profile != null ? profile.name() : null, leased.jobId(),
                leased.id(), null, resolveProjectId(leased),
                leased.workspaceType(), leased.workspaceKey(), leased.workspaceIdentity(),
                leased.assignmentType().name(), leased.runDisplayName(),
                taskRunLocation != null ? taskRunLocation.confinedPath().toString() : workspaceDescriptor.root().toString(),
                null, taskRunLocation,
                taskRunLocation == null ? null : workspaceDirectoryService.resolveRunOutput(taskRunLocation),
                attemptId, writerCapability, workspaceDescriptor,
                bindingKeyForAssignment(leased)
            );

            // Validate project membership (projectId is metadata only)
            String projectId = resolveProjectId(leased);
            if (StringUtils.hasText(projectId) && projectService != null) {
                if (!projectService.isMember(projectId, leased.agentId())) {
                    heartbeat.cancel(false);
                    WorkAssignment failed = fail(assignmentService.get(leased.id()),
                        "Agent " + leased.agentId() + " is not a member of project " + projectId);
                    finishWriterForResult(activeWriter, failed);
                    return failed;
                }
            }
            OrchestrationTaskContextHolder.set(taskContext);
            WorkAssignment result = switch (leased.assignmentType()) {
                case TASK_RUN -> runTask(leased);
                case WORKFLOW_RUN -> runWorkflow(leased);
                case JOB_RUN -> runJob(leased);
                case AGENT_MESSAGE -> runAgentMessage(leased);
                case WAIT_FOR_MESSAGE -> waiting(leased);
                case REPORT -> complete(leased,
                    Map.of("message", text(leased.input().get("message"), "Report completed.")),
                    Map.of("reports", List.of(text(leased.input().get("message"), "Report completed."))));
            };
            finishWriterForResult(activeWriter, result);
            return result;
        } catch (TaskRunLifecycleService.StaleWriterException
                 | AssignmentService.StaleAssignmentLeaseException stale) {
            logger.warn("Stale worker stopped for assignment {}: {}", leased.id(), stale.getMessage());
            if (activeWriter != null && taskRunLifecycleService != null) {
                try { taskRunLifecycleService.releaseWriterClaim(activeWriter); } catch (Exception ignored) { }
            }
            return assignmentService.get(leased.id());
        } catch (RuntimeException exception) {
            WorkAssignment current = assignmentService.get(leased.id());
            if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
                WorkAssignment cancelled = cancel(current);
                finishWriterForResult(activeWriter, cancelled);
                return cancelled;
            }
            WorkAssignment failed = fail(current, exception.getMessage());
            finishWriterForResult(activeWriter, failed);
            return failed;
        } finally {
            OrchestrationTaskContextHolder.clear();
            if (heartbeat != null) heartbeat.cancel(false);
        }
    }

    /** Durable owner links, never the mutable checkpoint, authorize a run binding. */
    private void validateImmutableBinding(WorkAssignment assignment, String taskRunId,
                                          WorkspaceDescriptor selectedDescriptor) {
        if (agentContextService == null) {
            throw new IllegalStateException("Immutable context binding service is unavailable");
        }
        String bindingKey = bindingKeyForAssignment(assignment);
        var assignmentLink = agentContextService.getOwnerLink("ASSIGNMENT", assignment.id());
        var runLink = agentContextService.getOwnerLink("TASK_RUN", taskRunId);
        if (assignmentLink.outcome() != AgentContextService.ContextOutcome.AVAILABLE || assignmentLink.value() == null
                || runLink.outcome() != AgentContextService.ContextOutcome.AVAILABLE || runLink.value() == null
                || !bindingKey.equals(assignmentLink.value().bindingKey())
                || !bindingKey.equals(runLink.value().bindingKey())) {
            throw new IllegalStateException("Assignment and task run do not share a durable immutable binding");
        }
        String checkpointKey = text(assignment.checkpoint().get("contextBindingKey"), null);
        if (StringUtils.hasText(checkpointKey) && !bindingKey.equals(checkpointKey)) {
            throw new IllegalStateException("Mutable assignment checkpoint disagrees with durable binding owner link");
        }
        if (!assignment.id().equals(taskRunLifecycleService.findAssignmentId(taskRunId))) {
            throw new IllegalStateException("Task run is not owned by its assignment");
        }
        var binding = agentContextService.getBinding(bindingKey);
        if (binding.outcome() != AgentContextService.ContextOutcome.AVAILABLE || binding.value() == null
                || !assignment.agentId().equals(binding.value().agentId())) {
            throw new IllegalStateException("Assignment binding is unavailable or belongs to another agent");
        }
        if (binding.value().selectionKind() == SelectionKind.MATERIALIZATION) {
            WorkspaceDescriptor agentDescriptor = workspaceDirectoryService.resolveForRead(
                WorkspaceKind.AGENT, binding.value().agentId());
            var resolution = agentContextService.resolveBinding(agentDescriptor, bindingKey);
            if (resolution.outcome() != AgentContextService.ContextOutcome.AVAILABLE) {
                throw new IllegalStateException("Assignment binding is stale, revoked, or not ready: "
                    + resolution.outcome());
            }
        }
        if (!selectedDescriptor.identity().equals(assignment.workspaceIdentity())) {
            throw new IllegalStateException("Selected descriptor does not match persisted assignment selection");
        }
    }

    private String bindingKeyForAssignment(WorkAssignment assignment) {
        if (agentContextService == null) {
            throw new IllegalStateException("Immutable context binding service is unavailable");
        }
        var link = agentContextService.getOwnerLink("ASSIGNMENT", assignment.id());
        if (link.outcome() != AgentContextService.ContextOutcome.AVAILABLE || link.value() == null) {
            throw new IllegalStateException("Assignment has no durable immutable binding owner link");
        }
        return link.value().bindingKey();
    }

    private WorkspaceDescriptor resolveWorkspaceDescriptor(WorkAssignment assignment) {
        if (workspaceDirectoryService == null) return null;
        if (!StringUtils.hasText(assignment.workspaceType())) {
            // Default to agent
            try {
                return workspaceDirectoryService.resolveForWrite(
                    WorkspaceKind.AGENT, assignment.agentId());
            } catch (WorkspaceResolutionException e) {
                return null;
            }
        }
        try {
            WorkspaceKind kind = WorkspaceKind.fromToken(assignment.workspaceType());
            if (kind == WorkspaceKind.CHAT) {
                logger.warn("Chat workspace selection is not supported for execution");
                return null;
            }
            return workspaceDirectoryService.resolveForWrite(kind, assignment.workspaceKey());
        } catch (Exception e) {
            logger.warn("Failed to resolve workspace descriptor for assignment {}: {}",
                assignment.id(), e.getMessage());
            return null;
        }
    }

    private String resolveProjectId(WorkAssignment assignment) {
        String assignmentProjectId = assignment.projectId();
        if (StringUtils.hasText(assignmentProjectId)) return assignmentProjectId;
        String projectId = text(assignment.input().get("projectId"), null);
        if (StringUtils.hasText(projectId)) return projectId;
        String jobId = assignment.jobId();
        if (!StringUtils.hasText(jobId)) {
            jobId = text(assignment.input().get("jobId"), null);
        }
        if (StringUtils.hasText(jobId)) {
            try {
                return jobService.getDefinition(jobId).projectId();
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    private void cancelLocalAssignment(String assignmentId) {
        CompletableFuture<?> future = activeAssignmentFutures.remove(assignmentId);
        if (future != null) future.cancel(true);
    }

    @PreDestroy
    void shutdownHeartbeatExecutor() {
        heartbeatExecutor.shutdownNow();
    }

    private ScheduledFuture<?> startLeaseHeartbeat(String assignmentId, long fence,
                                                     TaskRunWriteCapability writerCapability) {
        long heartbeatMillis = Math.max(1, heartbeatInterval.toMillis());
        return heartbeatExecutor.scheduleAtFixedRate(() -> {
            int updated = repository.extendRunningLeaseFenced(assignmentId, leaseOwner, fence,
                Instant.now().plus(leaseDuration));
            if (updated == 0 && writerCapability != null && taskRunLifecycleService != null
                    && taskRunLifecycleService.verifyWriterClaimCurrent(writerCapability)) {
                try {
                    taskRunLifecycleService.interruptAttempt(writerCapability,
                        "Assignment lease became stale");
                } catch (TaskRunLifecycleService.StaleWriterException ignored) { }
                cancelLocalAssignment(assignmentId);
            }
        }, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    private WorkAssignment runTask(WorkAssignment assignment) {
        String taskId = text(assignment.input().get("taskId"), null);
        TaskExecution taskExecution = runTaskThroughModel(taskId,
            mapValue(assignment.input().get("inputValues")), assignment,
            assignmentService.resolveModel(assignment, null));
        TaskRun taskRun = taskExecution.run();
        backfillTaskRunAttribution(taskRun.id(), assignment, assignment.assignmentType().name());
        Map<String, Object> checkpoint = mergeConversationCheckpoint(assignment.checkpoint(),
            taskExecution.conversationId(),
            Map.of("taskRunId", taskRun.id(), "status", taskRun.status().name()));
        Map<String, Object> output = mergeConversationOutput(
            Map.of("taskRunId", taskRun.id(), "outputValues", taskRun.outputValues()),
            taskExecution.conversationId());
        return complete(checkpointed(assignment, assignment.currentItemIndex(), checkpoint, output, evidence(taskRun)),
            output, evidence(taskRun));
    }

    private WorkAssignment runWorkflow(WorkAssignment assignment) {
        String workflowId = text(assignment.input().get("workflowId"), null);
        String model = assignmentService.resolveModel(assignment, null);
        WorkflowRun workflowRun = resumeOrStartWorkflow(assignment, workflowId, model);
        backfillWorkflowRunAttribution(workflowRun, assignment, "WORKFLOW_RUN");
        WorkAssignment current = assignmentService.get(assignment.id());
        if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) return cancel(current);
        Map<String, Object> checkpoint = mergeCheckpoint(current.checkpoint(),
            Map.of("workflowRunId", workflowRun.id(), "status", workflowRun.status().name()));
        Map<String, Object> output = mergeConversationOutput(
            Map.of("workflowRunId", workflowRun.id(), "finalOutputs", finalOutputs(workflowRun)),
            conversationIds(current.checkpoint()));
        if (workflowRun.status() == WorkflowRunStatus.COMPLETED) {
            return complete(checkpointed(current, current.currentItemIndex(), checkpoint, output, output), output, output);
        }
        if (workflowRun.status() == WorkflowRunStatus.WAITING) {
            return waiting(checkpointed(current, current.currentItemIndex(), checkpoint, output, output));
        }
        return fail(checkpointed(current, current.currentItemIndex(), checkpoint, output, output), workflowRun.errorText());
    }

    private WorkflowRun resumeOrStartWorkflow(WorkAssignment assignment, String workflowId, String model) {
        String existingRunId = text(assignment.checkpoint().get("workflowRunId"), null);
        if (StringUtils.hasText(existingRunId)) {
            WorkflowRun existing = workflowService.getRun(existingRunId);
            if (existing.status() == WorkflowRunStatus.WAITING) {
                return workflowService.resumeRunSynchronously(existingRunId, model,
                    assignmentConversationObserver(assignment));
            }
            return existing;
        }
        return workflowService.runSynchronously(workflowId, model, assignmentConversationObserver(assignment));
    }

    private WorkAssignment runJob(WorkAssignment assignment) {
        String jobId = StringUtils.hasText(assignment.jobId())
            ? assignment.jobId() : text(assignment.input().get("jobId"), null);
        JobDefinition job = jobService.getDefinition(jobId);
        jobService.updateDefinitionStatus(job.id(), "RUNNING");
        String projectId = resolveProjectId(assignment);
        JobRun jobRun = resumeOrStartJobRun(assignment, job, projectId);
        installJobWorkspaceContext(jobRun);
        List<JobWorkItem> items = job.items();
        Map<String, Object> outputs = new LinkedHashMap<>(assignment.output());
        Map<String, Object> evidence = new LinkedHashMap<>(assignment.evidence());
        WorkAssignment current = checkpointed(assignment, assignment.currentItemIndex(),
            mergeCheckpoint(assignment.checkpoint(), Map.of(
                "jobId", job.id(),
                "jobRunId", jobRun.id(),
                "jobAssignmentId", jobRun.jobAssignmentId(),
                "workspaceIdentity", nullableText(jobRun.workspaceIdentity())
            )), outputs, evidence);
        current = assignmentService.saveIfLeaseOwner(current, leaseOwner);
        if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
            jobService.cancelRunFromAssignment(jobRun.id());
            jobService.updateDefinitionStatus(job.id(), "CANCELLED");
            return cancel(current);
        }
        int start = Math.max(assignment.currentItemIndex(),
            integer(assignment.checkpoint().get("nextItemIndex"), 0));
        for (int i = start; i < items.size(); i++) {
            current = assignmentService.get(current.id());
            if (current.status() == OrchestrationStatus.INTERRUPTED) return current;
            if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
                jobService.cancelRunFromAssignment(jobRun.id());
                jobService.updateDefinitionStatus(job.id(), "CANCELLED");
                return cancel(current);
            }
            JobWorkItem item = items.get(i);
            jobRun = jobService.updateWorkItemRun(jobRun.id(), item.key(), "RUNNING", null, Map.of(), null);
            JobItemResult itemResult = runJobItem(current, assignment, item, jobRun.id());
            outputs.put(item.key(), itemResult.output());
            evidence.put(item.key(), itemResult.evidence());
            if (!itemResult.succeeded()) {
                jobService.updateWorkItemRun(jobRun.id(), item.key(), "FAILED",
                    itemResult.childRunId(), mapValue(itemResult.output()), itemResult.errorText());
                jobService.updateDefinitionStatus(job.id(), "FAILED");
                String errorText = itemResult.errorText();
                current = checkpointed(current, i + 1, mergeCheckpoint(current.checkpoint(), Map.of(
                    "jobId", job.id(), "jobRunId", jobRun.id(),
                    "nextItemIndex", i + 1, "failedItemKey", item.key(),
                    "model", assignmentService.resolveModel(current, item)
                )), outputs, evidence);
                return fail(current, errorText);
            }
            jobRun = jobService.updateWorkItemRun(jobRun.id(), item.key(), "COMPLETED",
                itemResult.childRunId(), mapValue(itemResult.output()), null);
            current = checkpointed(current, i + 1, mergeCheckpoint(current.checkpoint(), Map.of(
                "jobId", job.id(), "jobRunId", jobRun.id(),
                "nextItemIndex", i + 1, "completedItemKey", item.key(),
                "model", assignmentService.resolveModel(current, item)
            )), outputs, evidence);
            current = assignmentService.saveIfLeaseOwner(current, leaseOwner);
        }
        jobRun = jobService.completeRun(jobRun.id(), "Job run completed");
        jobService.updateDefinitionStatus(job.id(), "COMPLETED");
        eventService.publish(EventType.JOB_STATUS_CHANGED, "JOB", job.id(),
            Map.of("jobId", job.id(), "status", "COMPLETED"));
        return complete(current, outputs, evidence);
    }

    private JobRun resumeOrStartJobRun(WorkAssignment assignment, JobDefinition job, String projectId) {
        String existingRunId = text(assignment.checkpoint().get("jobRunId"), null);
        if (StringUtils.hasText(existingRunId)) {
            JobRun existing = jobService.getRun(existingRunId);
            return existing.status() == JobRunStatus.QUEUED
                ? jobService.markRunning(existing.id()) : existing;
        }
        JobRun run = jobService.startRun(job.id(), assignment.agentId(), projectId, assignment.id());
        return run.status() == JobRunStatus.QUEUED ? jobService.markRunning(run.id()) : run;
    }

    void installJobWorkspaceContext(JobRun jobRun) {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context == null || jobRun == null) return;
        OrchestrationTaskContextHolder.set(context.withJobRun(jobRun.jobAssignmentId(), jobRun.id()));
    }

    private JobItemResult runJobItem(WorkAssignment assignment, WorkAssignment current, JobWorkItem item, String jobRunId) {
        ChildExecution child = null;
        OrchestrationTaskContext parentContext = OrchestrationTaskContextHolder.current();
        try {
            child = beginJobChild(parentContext, assignment, item, jobRunId);
            if (child != null) OrchestrationTaskContextHolder.set(child.context());
            JobItemOutput output = switch (item.type()) {
                case PLAN -> {
                    if (!StringUtils.hasText(item.planId())) {
                        throw new IllegalArgumentException("PLAN work item '" + item.key() + "' has no planId");
                    }
                    TaskExecution taskExecution = runTaskThroughModel(item.planId(), item.inputBindings(),
                        assignment, assignmentService.resolveModel(current, item));
                    TaskRun run = taskExecution.run();
                    backfillTaskRunAttribution(run.id(), assignment, "JOB_PLAN_ITEM", jobRunId);
                    yield new JobItemOutput(run.id(), mergeConversationOutput(
                        Map.of("planRunId", run.id(), "outputValues", run.outputValues()),
                        taskExecution.conversationId()));
                }
                case WORKFLOW -> {
                    if (!StringUtils.hasText(item.workflowId())) {
                        throw new IllegalArgumentException("WORKFLOW work item '" + item.key() + "' has no workflowId");
                    }
                    WorkflowRun run = workflowService.runSynchronously(
                        item.workflowId(), assignmentService.resolveModel(current, item),
                        assignmentConversationObserver(assignment));
                    if (run.status() != WorkflowRunStatus.COMPLETED) {
                        throw new IllegalStateException("Workflow job item failed: " + run.errorText());
                    }
                    backfillWorkflowRunAttribution(run, assignment, "JOB_WORKFLOW_ITEM", jobRunId);
                    WorkAssignment latest = assignmentService.get(assignment.id());
                    yield new JobItemOutput(run.id(), mergeConversationOutput(
                        Map.of("workflowRunId", run.id(), "finalOutputs", finalOutputs(run)),
                        conversationIds(latest.checkpoint())));
                }
            };
            if (child != null) taskRunLifecycleService.completeAttempt(child.capability());
            return new JobItemResult(true, output.payload(), Map.of(
                "itemType", item.type().name(), "itemKey", item.key(),
                "completedAt", Instant.now().toString()), null, output.runId());
        } catch (RuntimeException exception) {
            String error = exception.getMessage();
            if (child != null && taskRunLifecycleService.verifyWriterClaimCurrent(child.capability())) {
                taskRunLifecycleService.failAttempt(child.capability(), error);
            }
            return new JobItemResult(false, Map.of("failed", true, "error", error), Map.of(
                "itemType", item.type().name(), "itemKey", item.key(),
                "failedAt", Instant.now().toString(), "error", error), error, null);
        } finally {
            if (parentContext == null) OrchestrationTaskContextHolder.clear();
            else OrchestrationTaskContextHolder.set(parentContext);
        }
    }

    private ChildExecution beginJobChild(OrchestrationTaskContext parent, WorkAssignment assignment,
                                         JobWorkItem item, String jobRunId) {
        if (taskRunLifecycleService == null || parent == null || parent.taskRunLocation() == null
                || parent.writerCapability() == null) return null;
        taskRunLifecycleService.requireCurrentWriter(parent.writerCapability());
        TaskSourceKind childKind = item.type() == JobWorkItemType.PLAN
            ? TaskSourceKind.TASK : TaskSourceKind.WORKFLOW;
        String sourceId = item.type() == JobWorkItemType.PLAN ? item.planId() : item.workflowId();
        if (!StringUtils.hasText(sourceId)) {
            throw new IllegalArgumentException("Job work item has no executable source id: " + item.key());
        }
        String linkKey = jobRunId + ":" + item.key();
        String digest = taskRunLifecycleService.canonicalInputDigest(Map.of(
            "jobRunId", jobRunId, "itemKey", item.key(), "inputs", item.inputBindings()));
        LifecycleResult lifecycle = taskRunLifecycleService.reserveChild(parent.taskRunId(),
            "JOB_WORK_ITEM", linkKey, "job-work-item:" + linkKey, childKind, sourceId,
            UUID.randomUUID().toString(), digest);
        TaskRunLocation location = taskRunLifecycleService.provisionLocation(lifecycle.taskRunId());
        TaskRunWriteCapability capability;
        try {
            capability = taskRunLifecycleService.acquireWriterClaim(location,
                lifecycle.attemptNumber(), parent.writerCapability().ownerId());
        } catch (IllegalStateException stale) {
            lifecycle = taskRunLifecycleService.recoverForAssignment(lifecycle.taskRunId(), assignment.id(),
                "job-work-item-recovery:" + linkKey + ":" + assignment.leaseFence(), digest);
            capability = taskRunLifecycleService.acquireWriterClaim(location,
                lifecycle.attemptNumber(), parent.writerCapability().ownerId());
        }
        OrchestrationTaskContext context = new OrchestrationTaskContext(
            parent.agentId(), parent.agentName(), parent.jobId(), assignment.id(), jobRunId,
            parent.projectId(), parent.workspaceType(), parent.workspaceKey(), parent.workspaceIdentity(),
            "JOB_WORK_ITEM", parent.runDisplayName(), location.confinedPath().toString(), null,
            location, workspaceDirectoryService.resolveRunOutput(location),
            lifecycle.taskRunId() + ":" + lifecycle.attemptNumber(), capability,
            parent.workspaceDescriptor(), parent.contextBindingKey());
        return new ChildExecution(context, capability);
    }

    private TaskExecution runTaskThroughModel(
        String taskId, Map<String, Object> inputValues,
        WorkAssignment assignment, String modelOverride
    ) {
        if (chatService == null) {
            throw new IllegalStateException("Task execution requires model-backed chat execution");
        }
        String conversationId = UUID.randomUUID().toString();
        checkpointActiveConversation(assignment, conversationId);
        TaskRun run = chatService.executeTaskBlocking(taskId, inputValues, conversationId, modelOverride).run();
        WorkAssignment current = assignmentService.get(assignment.id());
        if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("Assignment cancellation was requested");
        }
        if (run.status() != TaskRunStatus.COMPLETED) {
            throw new IllegalStateException("Task run did not complete: "
                + (run.errorText() == null ? run.status().name() : run.errorText()));
        }
        return new TaskExecution(run, conversationId);
    }

    private WorkAssignment runAgentMessage(WorkAssignment assignment) {
        String toAgentId = text(assignment.input().get("toAgentId"), assignment.agentId());
        InboxMessage message = inboxService.send(toAgentId, new InboxMessage(
            null, toAgentId, assignment.agentId(),
            text(assignment.input().get("messageType"), "assignment_message"),
            text(assignment.input().get("body"), ""), mapValue(assignment.input().get("metadata")),
            false, false, null, null));
        return complete(assignment,
            Map.of("messageId", message.id()), Map.of("messageId", message.id()));
    }

    private WorkAssignment checkpointed(WorkAssignment assignment, int currentItemIndex,
                                        Map<String, Object> checkpoint, Map<String, Object> output,
                                        Map<String, Object> evidence) {
        return assignmentService.copy(assignment, OrchestrationStatus.RUNNING, currentItemIndex,
            checkpoint, output, evidence, null, leaseOwner, Instant.now().plus(leaseDuration), null);
    }

    private void checkpointActiveConversation(WorkAssignment assignment, String conversationId) {
        verifyExecutionCapabilities(assignment);
        repository.saveAssignmentConversationLink(assignment.id(), conversationId);
        WorkAssignment current = assignmentService.get(assignment.id());
        Map<String, Object> checkpoint = mergeConversationCheckpoint(
            current.checkpoint(), conversationId, Map.of());
        assignmentService.saveIfLeaseOwner(checkpointed(current, current.currentItemIndex(),
            checkpoint, current.output(), current.evidence()), leaseOwner);
    }

    private WorkflowExecutionObserver assignmentConversationObserver(WorkAssignment assignment) {
        return (workflowRunId, nodeKey, conversationId) -> {
            verifyExecutionCapabilities(assignment);
            repository.saveAssignmentConversationLink(assignment.id(), conversationId);
            WorkAssignment current = assignmentService.get(assignment.id());
            Map<String, Object> checkpoint = mergeConversationCheckpoint(current.checkpoint(),
                conversationId,
                Map.of("workflowRunId", workflowRunId, "activeWorkflowNodeKey", nodeKey));
            assignmentService.saveIfLeaseOwner(checkpointed(current, current.currentItemIndex(),
                checkpoint, current.output(), current.evidence()), leaseOwner);
        };
    }

    private Map<String, Object> mergeConversationCheckpoint(
        Map<String, Object> existing, String conversationId, Map<String, Object> extra) {
        Map<String, Object> merged = mergeCheckpoint(existing, extra);
        if (StringUtils.hasText(conversationId)) {
            merged.put("activeConversationId", conversationId);
            merged.put("conversationId", conversationId);
            merged.put("conversationIds", appendConversationId(conversationIds(merged), conversationId));
        }
        return merged;
    }

    private Map<String, Object> mergeCheckpoint(Map<String, Object> existing, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        if (extra != null) merged.putAll(extra);
        return merged;
    }

    private Map<String, Object> mergeConversationOutput(Map<String, Object> output, String conversationId) {
        return mergeConversationOutput(output,
            StringUtils.hasText(conversationId) ? List.of(conversationId) : List.of());
    }

    private Map<String, Object> mergeConversationOutput(Map<String, Object> output, List<String> conversationIds) {
        Map<String, Object> merged = new LinkedHashMap<>(output == null ? Map.of() : output);
        List<String> ids = appendConversationIds(conversationIds(merged), conversationIds);
        if (!ids.isEmpty()) {
            merged.put("conversationId", ids.getLast());
            merged.put("conversationIds", ids);
        }
        return merged;
    }

    private List<String> appendConversationId(List<String> existing, String conversationId) {
        return appendConversationIds(existing,
            StringUtils.hasText(conversationId) ? List.of(conversationId) : List.of());
    }

    private List<String> appendConversationIds(List<String> existing, List<String> additions) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (existing != null) existing.stream().filter(StringUtils::hasText).forEach(ids::add);
        if (additions != null) additions.stream().filter(StringUtils::hasText).forEach(ids::add);
        return List.copyOf(ids);
    }

    private WorkAssignment complete(WorkAssignment assignment, Map<String, Object> output,
                                    Map<String, Object> evidence) {
        verifyExecutionCapabilities(assignment);
        return assignmentService.saveIfLeaseOwner(assignmentService.copy(assignment,
            OrchestrationStatus.COMPLETED, assignment.currentItemIndex(), assignment.checkpoint(),
            output, evidence, null, null, null, Instant.now()), leaseOwner);
    }

    private WorkAssignment fail(WorkAssignment assignment, String errorText) {
        verifyExecutionCapabilities(assignment);
        return assignmentService.saveIfLeaseOwner(assignmentService.copy(assignment,
            OrchestrationStatus.FAILED, assignment.currentItemIndex(), assignment.checkpoint(),
            assignment.output(), assignment.evidence(), errorText, null, null, Instant.now()), leaseOwner);
    }

    private WorkAssignment waiting(WorkAssignment assignment) {
        verifyExecutionCapabilities(assignment);
        return assignmentService.saveIfLeaseOwner(assignmentService.copy(assignment,
            OrchestrationStatus.WAITING, assignment.currentItemIndex(), assignment.checkpoint(),
            assignment.output(), assignment.evidence(), null, null, null, null), leaseOwner);
    }

    private WorkAssignment cancel(WorkAssignment assignment) {
        verifyExecutionCapabilities(assignment);
        return assignmentService.saveIfLeaseOwner(assignmentService.copy(assignment,
            OrchestrationStatus.CANCELLED, assignment.currentItemIndex(), assignment.checkpoint(),
            assignment.output(), assignment.evidence(), "Cancelled", null, null, Instant.now()), leaseOwner);
    }

    private Map<String, Object> evidence(TaskRun taskRun) {
        return Map.of("taskRunId", taskRun.id(), "evidence", taskRun.executionEvidence());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private List<String> conversationIds(Map<String, Object> values) {
        Object ids = values == null ? null : values.get("conversationIds");
        if (ids instanceof Iterable<?> iterable) {
            List<String> result = new java.util.ArrayList<>();
            for (Object value : iterable) {
                String id = text(value, null);
                if (StringUtils.hasText(id)) result.add(id);
            }
            return result;
        }
        String id = text(values == null ? null : values.get("conversationId"), null);
        return StringUtils.hasText(id) ? List.of(id) : List.of();
    }

    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private String text(Object value, String fallback) {
        if (value == null) return fallback;
        String text = value.toString();
        return StringUtils.hasText(text) ? text : fallback;
    }

    private String firstText(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private String nullableText(String value) {
        return StringUtils.hasText(value) ? value : "";
    }

    private Duration secondsOrDefault(long seconds, Duration fallback) {
        return seconds > 0 ? Duration.ofSeconds(seconds) : fallback;
    }

    private void backfillTaskRunAttribution(String taskRunId, WorkAssignment assignment, String runType) {
        backfillTaskRunAttribution(taskRunId, assignment, runType, null);
    }

    private void backfillTaskRunAttribution(String taskRunId, WorkAssignment assignment, String runType,
                                            String jobRunId) {
        if (outputArtifactService == null || !StringUtils.hasText(taskRunId)) return;
        outputArtifactService.backfillAttribution(taskRunId, outputContextFor(assignment, runType, jobRunId));
    }

    private void backfillWorkflowRunAttribution(WorkflowRun workflowRun, WorkAssignment assignment, String runType) {
        backfillWorkflowRunAttribution(workflowRun, assignment, runType, null);
    }

    private void backfillWorkflowRunAttribution(WorkflowRun workflowRun, WorkAssignment assignment,
                                                 String runType, String jobRunId) {
        if (outputArtifactService == null || workflowRun == null) return;
        OutputArtifactContext context = outputContextFor(assignment, runType, jobRunId);
        outputArtifactService.backfillAttribution(workflowRun.id(), context);
        for (WorkflowNodeRun nodeRun : workflowRun.nodeRuns()) {
            Object taskRunId = nodeRun.outputValues().get("taskRunId");
            if (taskRunId instanceof String id && StringUtils.hasText(id)) {
                outputArtifactService.backfillAttribution(id, context);
            }
        }
    }

    private Map<String, Object> finalOutputs(WorkflowRun workflowRun) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        for (WorkflowNodeRun nodeRun : workflowRun.nodeRuns()) {
            if (nodeRun.outputValues().isEmpty()) continue;
            outputs.put(nodeRun.nodeKey(), nodeRun.outputValues());
        }
        return outputs;
    }

    private OutputArtifactContext outputContextFor(WorkAssignment assignment, String runType) {
        return outputContextFor(assignment, runType, null);
    }

    private OutputArtifactContext outputContextFor(WorkAssignment assignment, String runType, String jobRunId) {
        String projectId = firstText(assignment.projectId(),
            text(assignment.input().get("projectId"), null));
        String jobId = assignment.jobId();
        if (!StringUtils.hasText(jobId)) {
            jobId = text(assignment.input().get("jobId"), null);
        }
        if (!StringUtils.hasText(projectId) && StringUtils.hasText(jobId)) {
            try { projectId = jobService.getDefinition(jobId).projectId(); }
            catch (RuntimeException ignored) { projectId = null; }
        }
        return new OutputArtifactContext(
            assignment.agentId(), jobId,
            StringUtils.hasText(jobId) ? assignment.id() : null, jobRunId, projectId,
            assignment.workspaceType(), assignment.workspaceKey(), assignment.workspaceIdentity(), runType
        );
    }

    // ── Lifecycle helpers ──

    private void verifyExecutionCapabilities(WorkAssignment assignment) {
        if (!repository.isAssignmentLeaseCurrent(assignment.id(), leaseOwner, assignment.leaseFence())) {
            throw new AssignmentService.StaleAssignmentLeaseException(
                "Assignment lease is stale: " + assignment.id());
        }
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context != null && context.writerCapability() != null) {
            taskRunLifecycleService.requireCurrentWriter(context.writerCapability());
        }
    }

    private void finishWriterForResult(TaskRunWriteCapability capability, WorkAssignment result) {
        if (capability == null || taskRunLifecycleService == null
                || !taskRunLifecycleService.verifyWriterClaimCurrent(capability)) return;
        switch (result.status()) {
            case COMPLETED -> taskRunLifecycleService.completeAttempt(capability);
            case WAITING, PAUSED -> taskRunLifecycleService.waitAttempt(capability);
            case FAILED, CANCELLED, NEEDS_REVIEW -> taskRunLifecycleService.failAttempt(
                capability, result.errorText());
            case INTERRUPTED -> taskRunLifecycleService.interruptAttempt(capability, result.errorText());
            default -> taskRunLifecycleService.releaseWriterClaim(capability);
        }
    }

    private static boolean isTaskRunAssignment(AssignmentType type) {
        return type == AssignmentType.TASK_RUN || type == AssignmentType.WORKFLOW_RUN
            || type == AssignmentType.JOB_RUN;
    }

    private record ChildExecution(OrchestrationTaskContext context,
                                  TaskRunWriteCapability capability) {}
    private record JobItemResult(boolean succeeded, Object output, Map<String, Object> evidence,
                                  String errorText, String childRunId) {}
    private record JobItemOutput(String runId, Map<String, Object> payload) {}
    private record TaskExecution(TaskRun run, String conversationId) {}
}
