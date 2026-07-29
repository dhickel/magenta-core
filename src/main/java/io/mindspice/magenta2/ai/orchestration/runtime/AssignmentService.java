package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import io.mindspice.magenta2.ai.chat.plan.PlanRun;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskSourceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AssignmentService {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AssignmentService.class);
    // SQLite permits one writer. Serialize local submissions before they take
    // transaction locks; the durable operation row remains the cross-process arbiter.
    private static final Object SUBMISSION_MUTEX = new Object();

    private final OrchestrationRuntimeRepository repository;
    private final AgentProfileService agentProfileService;
    private final RuntimeSettingsService runtimeSettingsService;
    private final JobService jobService;
    private final AuditRepository auditRepository;
    private final PlanService planService;
    private final WorkflowService workflowService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final TaskRunLifecycleService taskRunLifecycleService;
    private final AgentContextService agentContextService;
    private final ProjectOperationsAccessService projectAccessService;
    private volatile Consumer<String> localInterruptHandler = ignored -> { };

    public AssignmentService(
        OrchestrationRuntimeRepository repository,
        AgentProfileService agentProfileService,
        RuntimeSettingsService runtimeSettingsService,
        JobService jobService
    ) {
        this(repository, agentProfileService, runtimeSettingsService, jobService, null, null, null, null, null, null, null);
    }

    public AssignmentService(
        OrchestrationRuntimeRepository repository,
        AgentProfileService agentProfileService,
        RuntimeSettingsService runtimeSettingsService,
        JobService jobService,
        AuditRepository auditRepository,
        PlanService planService,
        WorkflowService workflowService,
        WorkspaceDirectoryService workspaceDirectoryService
    ) {
        this(repository, agentProfileService, runtimeSettingsService, jobService, auditRepository,
            planService, workflowService, workspaceDirectoryService, null, null, null);
    }

    @Autowired
    public AssignmentService(
        OrchestrationRuntimeRepository repository,
        AgentProfileService agentProfileService,
        RuntimeSettingsService runtimeSettingsService,
        JobService jobService,
        @Autowired(required = false) AuditRepository auditRepository,
        @Autowired(required = false) PlanService planService,
        @Autowired(required = false) WorkflowService workflowService,
        @Autowired(required = false) WorkspaceDirectoryService workspaceDirectoryService,
        @Autowired(required = false) TaskRunLifecycleService taskRunLifecycleService,
        @Autowired(required = false) AgentContextService agentContextService,
        @Autowired(required = false) ProjectOperationsAccessService projectAccessService
    ) {
        this.repository = repository;
        this.agentProfileService = agentProfileService;
        this.runtimeSettingsService = runtimeSettingsService;
        this.jobService = jobService;
        this.auditRepository = auditRepository;
        this.planService = planService;
        this.workflowService = workflowService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.taskRunLifecycleService = taskRunLifecycleService;
        this.agentContextService = agentContextService;
        this.projectAccessService = projectAccessService;
    }

    @Transactional
    public WorkAssignment create(AssignmentRequest request) {
        synchronized (SUBMISSION_MUTEX) {
            return create(UUID.randomUUID().toString(), request);
        }
    }

    WorkAssignment create(String assignmentId, AssignmentRequest request) {
        if (request.assignmentType() == null) {
            throw new IllegalArgumentException("assignmentType is required");
        }
        if (!StringUtils.hasText(request.agentId())) {
            throw new IllegalArgumentException("agentId is required");
        }
        AgentProfile agent = agentProfileService.get(request.agentId());
        if (agent.status() == io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus.DISABLED) {
            throw new IllegalStateException("Agent is disabled and cannot accept new assignments: " + request.agentId());
        }
        if (StringUtils.hasText(request.jobId())) {
            jobService.getDefinition(request.jobId());
        }
        Map<String, Object> input = inputWithProjectContext(request);
        String projectId = normalize(request.projectId());
        String inputProjectId = normalize(text(input.get("projectId")));
        if (StringUtils.hasText(inputProjectId) && !java.util.Objects.equals(projectId, inputProjectId)) {
            throw new IllegalArgumentException("Contradictory projectId in assignment input");
        }
        // Project identity is typed submission attribution, never an input-map authority.
        // Keep a matching transport value for compatibility, but it is not read
        // when attribution is persisted or later evaluated.
        if (projectId != null) {
            input.put("projectId", projectId);
        }

        // Project safety gate: require active project and eligible executor for project-attributed submission
        if (StringUtils.hasText(projectId)) {
            if (projectAccessService == null) {
                throw new IllegalStateException("Project access capability is unavailable");
            }
            projectAccessService.requireEligibleSubmission(projectId, request.agentId());
        }

        // Resolve workspace selection: type/key both absent or both present
        ResolvedSelection selection = resolveWorkspaceSelection(
            request.agentId(), request.workspaceType(), request.workspaceKey());

        AssignmentTemplateParser.validate(new AssignmentRequest(
            request.agentId(),
            request.jobId(),
            request.jobItemId(),
            request.assignmentType(),
            request.runDisplayName(),
            request.priority(),
            request.modelOverride(),
            projectId,
            selection.workspaceType(),
            selection.workspaceKey(),
            input
        ));
        String operationKey = StringUtils.hasText(request.operationKey())
            ? request.operationKey().trim() : "submission:" + UUID.randomUUID();
        String intentDigest = submissionIntentDigest(request, projectId, selection, input);
        requireContextService();
        var operation = agentContextService.beginAssignmentSubmission(operationKey, intentDigest);
        if (operation.status() == io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextOperationStatus.CONFLICT) {
            throw new TaskRunLifecycleService.OperationConflictException(
                "Assignment operation key was reused with contradictory submission intent");
        }
        if (operation.status() == io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.ContextOperationStatus.REPLAYED) {
            return replayAssignmentSubmission(operation, request, projectId, selection, input);
        }

        String bindingKey = "assignment:" + assignmentId;
        if (StringUtils.hasText(request.contextBindingKey())
                && !bindingKey.equals(request.contextBindingKey().trim())) {
            throw new IllegalArgumentException("contextBindingKey is server-derived and cannot be selected by the caller");
        }
        String sourceDefinitionId = sourceDefinitionId(request, input);
        // The capture and owner-link are mandatory. This transaction rolls back the
        // operation reservation if either one cannot be persisted.
        agentContextService.captureDefaultOrPersonalHome(request.agentId(), bindingKey,
            "assignment-capture:" + operationKey);
        agentContextService.linkOwner("ASSIGNMENT", assignmentId, bindingKey,
            "assignment-owner-link:" + operationKey);

        WorkAssignment assignment = repository.insertAssignment(new WorkAssignment(
            assignmentId, request.agentId(), normalize(request.jobId()), normalize(request.jobItemId()),
            request.assignmentType(), normalize(request.runDisplayName()), request.priority() == null ? 0 : request.priority(),
            OrchestrationStatus.QUEUED, normalize(request.modelOverride()), selection.workspaceType(),
            selection.workspaceKey(), selection.workspaceIdentity(), projectId,
            sourceDefinitionId, bindingKey, 0, 0, Map.of(), input, Map.of(), Map.of(),
            null, null, null, 0L, null, null, null, null, null, null
        ));
        WorkAssignment reserved = reserveLifecycleAtSubmission(assignment, operationKey, bindingKey);
        String taskRunId = text(reserved.checkpoint().get("taskRunId"));
        agentContextService.completeAssignmentSubmission(operationKey, intentDigest,
            bindingKey, reserved.id(), taskRunId);
        return reserved;
    }

    private void requireContextService() {
        if (agentContextService == null) {
            throw new IllegalStateException("Immutable context binding service is unavailable");
        }
    }

    private WorkAssignment replayAssignmentSubmission(
            io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.AssignmentSubmissionOperation operation,
            AssignmentRequest request, String projectId, ResolvedSelection selection, Map<String, Object> input) {
        if (!StringUtils.hasText(operation.assignmentId()) || !StringUtils.hasText(operation.bindingKey())) {
            throw new IllegalStateException("Recorded assignment operation has no stable result");
        }
        WorkAssignment existing = get(operation.assignmentId());
        if (!existing.agentId().equals(request.agentId()) || existing.assignmentType() != request.assignmentType()
                || !java.util.Objects.equals(existing.jobId(), normalize(request.jobId()))
                || !java.util.Objects.equals(existing.jobItemId(), normalize(request.jobItemId()))
                || !java.util.Objects.equals(existing.projectId(), projectId)
                || !java.util.Objects.equals(existing.workspaceType(), selection.workspaceType())
                || !java.util.Objects.equals(existing.workspaceKey(), selection.workspaceKey())
                || !java.util.Objects.equals(existing.input(), input)) {
            throw new TaskRunLifecycleService.OperationConflictException(
                "Assignment operation key was reused with contradictory submission intent");
        }
        requireOwnerBinding("ASSIGNMENT", existing.id(), operation.bindingKey());
        var binding = agentContextService.getBinding(operation.bindingKey());
        if (binding.outcome() != io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome.AVAILABLE
                || binding.value() == null || !existing.agentId().equals(binding.value().agentId())) {
            throw new IllegalStateException("Recorded assignment binding is unavailable or incompatible");
        }
        if (StringUtils.hasText(operation.taskRunId())) {
            requireOwnerBinding("TASK_RUN", operation.taskRunId(), operation.bindingKey());
            if (!operation.taskRunId().equals(text(existing.checkpoint().get("taskRunId")))) {
                throw new IllegalStateException("Recorded assignment lifecycle result is incompatible");
            }
        }
        return existing;
    }

    private String bindingKeyForAssignment(WorkAssignment assignment) {
        var link = agentContextService.getOwnerLink("ASSIGNMENT", assignment.id());
        if (link.outcome() != io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome.AVAILABLE
                || link.value() == null) {
            throw new IllegalStateException("Assignment has no durable immutable binding owner link");
        }
        return link.value().bindingKey();
    }

    private void requireOwnerBinding(String ownerType, String ownerId, String bindingKey) {
        var link = agentContextService.getOwnerLink(ownerType, ownerId);
        if (link.outcome() != io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome.AVAILABLE
                || link.value() == null || !bindingKey.equals(link.value().bindingKey())) {
            throw new IllegalStateException("Missing or incompatible immutable binding owner link for " + ownerType);
        }
    }

    /** Link a task-run reservation to the exact binding of its owning assignment. */
    private void linkTaskRunBinding(String taskRunId, String assignmentId, String bindingKey) {
        requireOwnerBinding("ASSIGNMENT", assignmentId, bindingKey);
        agentContextService.linkOwner("TASK_RUN", taskRunId, bindingKey,
            "task-run-owner-link:" + taskRunId);
        requireOwnerBinding("TASK_RUN", taskRunId, bindingKey);
    }

    private WorkAssignment reserveLifecycleAtSubmission(WorkAssignment assignment, String operationKey, String bindingKey) {
        if (!isTaskRunAssignment(assignment.assignmentType())) {
            Map<String, Object> checkpoint = Map.of("contextBindingKey", bindingKey);
            return repository.updateUnleasedAssignment(copy(assignment, assignment.status(), assignment.currentItemIndex(), checkpoint,
                assignment.output(), assignment.evidence(), assignment.errorText(), null,
                null, assignment.completedAt()), assignment.status()).orElseThrow(() ->
                    new IllegalStateException("Assignment changed before lifecycle reservation"));
        }
        if (taskRunLifecycleService == null) {
            throw new IllegalStateException("Task-run lifecycle service is unavailable");
        }
        TaskSourceKind sourceKind = sourceKind(assignment.assignmentType());
        String sourceId = sourceId(assignment);
        String nativeRunId = UUID.randomUUID().toString();
        var binding = agentContextService.getBinding(bindingKey).value();
        if (binding == null) throw new IllegalStateException("Assignment binding disappeared before lifecycle reservation");
        String inputDigest = taskRunLifecycleService.canonicalInputDigest(Map.of(
            "assignmentType", assignment.assignmentType().name(), "sourceId", sourceId,
            "workspaceType", assignment.workspaceType(), "workspaceKey", assignment.workspaceKey(),
            "input", assignment.input(), "bindingKey", binding.bindingKey(),
            "bindingSelection", binding.selectionKind().name(),
            "bindingMaterializationKey", binding.materializationKey() == null ? "" : binding.materializationKey().serialized(),
            "bindingMaterializationGeneration", binding.materializationGeneration() == null ? 0 : binding.materializationGeneration(),
            "bindingPersonalSelection", binding.personalSelection() == null ? "" : binding.personalSelection().name()
        ));
        LifecycleResult lifecycle = taskRunLifecycleService.reserve(
            LifecycleReservationRequest.reuseNative(operationKey, sourceKind, sourceId,
                WorkspaceKind.fromToken(assignment.workspaceType()), assignment.workspaceKey(),
                nativeRunId, assignment.id(), inputDigest));
        String owningAssignmentId = taskRunLifecycleService.findAssignmentId(lifecycle.taskRunId());
        if (!assignment.id().equals(owningAssignmentId)) {
            throw new IllegalStateException("Lifecycle reservation belongs to another assignment");
        }
        linkTaskRunBinding(lifecycle.taskRunId(), assignment.id(), bindingKey);
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("taskRunId", lifecycle.taskRunId());
        checkpoint.put("nativeRunId", lifecycle.nativeRunId());
        checkpoint.put("lifecycleOperationKey", lifecycle.operationKey());
        checkpoint.put("lifecycleInputDigest", inputDigest);
        checkpoint.put("attemptNumber", lifecycle.attemptNumber());
        checkpoint.put("contextBindingKey", bindingKey); // compatibility only; runner verifies owner links.
        return repository.updateUnleasedAssignment(copy(assignment, assignment.status(), assignment.currentItemIndex(),
            checkpoint, assignment.output(), assignment.evidence(), assignment.errorText(),
            null, null, assignment.completedAt()), assignment.status()).orElseThrow(() ->
                new IllegalStateException("Assignment changed before lifecycle reservation"));
    }

    private String submissionIntentDigest(AssignmentRequest request, String projectId,
                                          ResolvedSelection selection, Map<String, Object> input) {
        String canonicalInput = taskRunLifecycleService == null ? String.valueOf(input)
            : taskRunLifecycleService.canonicalInputDigest(input);
        return TaskRunLifecycleService.sha256Hex(String.join("\\u001f", List.of(
            request.agentId(), request.assignmentType().name(), String.valueOf(normalize(request.jobId())),
            String.valueOf(normalize(request.jobItemId())), String.valueOf(projectId), selection.workspaceType(),
            selection.workspaceKey(), canonicalInput)));
    }

    private String sourceDefinitionId(AssignmentRequest request, Map<String, Object> input) {
        String supplied = normalize(request.sourceDefinitionId());
        if (!isTaskRunAssignment(request.assignmentType())) {
            return supplied;
        }
        String derived = switch (request.assignmentType()) {
            case TASK_RUN -> requireSourceId(input, "taskId", "planId");
            case WORKFLOW_RUN -> requireSourceId(input, "workflowId");
            case JOB_RUN -> StringUtils.hasText(request.jobId())
                ? request.jobId().trim() : requireSourceId(input, "jobId");
            default -> throw new IllegalArgumentException("Assignment has no source definition");
        };
        if (supplied != null && !supplied.equals(derived)) {
            throw new IllegalArgumentException("sourceDefinitionId conflicts with the typed executable source");
        }
        return derived;
    }

    private boolean isTaskRunAssignment(AssignmentType type) {
        return type == AssignmentType.TASK_RUN || type == AssignmentType.WORKFLOW_RUN || type == AssignmentType.JOB_RUN;
    }

    private TaskSourceKind sourceKind(AssignmentType type) {
        return switch (type) {
            case TASK_RUN -> TaskSourceKind.TASK;
            case WORKFLOW_RUN -> TaskSourceKind.WORKFLOW;
            case JOB_RUN -> TaskSourceKind.JOB;
            default -> throw new IllegalArgumentException("Assignment has no task-run lifecycle: " + type);
        };
    }

    private String sourceId(WorkAssignment assignment) {
        return switch (assignment.assignmentType()) {
            case TASK_RUN -> requireSourceId(assignment.input(), "taskId", "planId");
            case WORKFLOW_RUN -> requireSourceId(assignment.input(), "workflowId");
            case JOB_RUN -> StringUtils.hasText(assignment.jobId())
                ? assignment.jobId() : requireSourceId(assignment.input(), "jobId");
            default -> throw new IllegalArgumentException("Assignment has no native source");
        };
    }

    private String requireSourceId(Map<String, Object> input, String... keys) {
        for (String key : keys) {
            String value = text(input.get(key));
            if (StringUtils.hasText(value)) return value.trim();
        }
        throw new IllegalArgumentException("Executable assignment is missing its native source id");
    }

    // ── Centralized workspace selection ──

    private ResolvedSelection resolveWorkspaceSelection(
        String agentId, String workspaceType, String workspaceKey
    ) {
        boolean hasType = StringUtils.hasText(workspaceType);
        boolean hasKey = StringUtils.hasText(workspaceKey);

        // Both absent → default to agent
        if (!hasType && !hasKey) {
            return new ResolvedSelection(
                WorkspaceKind.AGENT.token(), agentId, WorkspaceKind.AGENT.token() + "/" + agentId);
        }
        // One field only → reject
        if (hasType != hasKey) {
            throw new IllegalArgumentException(
                "workspaceType and workspaceKey must both be present or both absent");
        }
        WorkspaceKind kind;
        try {
            kind = WorkspaceKind.fromToken(workspaceType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid workspaceType: " + workspaceType);
        }
        return switch (kind) {
            case AGENT -> {
                if (!agentId.equals(workspaceKey)) {
                    throw new IllegalArgumentException(
                        "Agent workspace key must match bound agent: " + agentId);
                }
                yield new ResolvedSelection(kind.token(), workspaceKey, kind.token() + "/" + workspaceKey);
            }
            case USER -> {
                if (workspaceDirectoryService == null) {
                    throw new IllegalStateException("Workspace directory service is unavailable");
                }
                try {
                    WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(
                        WorkspaceKind.USER, workspaceKey);
                    yield new ResolvedSelection(kind.token(), workspaceKey, descriptor.identity());
                } catch (WorkspaceResolutionException e) {
                    throw new IllegalArgumentException("User workspace not found: " + workspaceKey);
                }
            }
            case CHAT -> throw new IllegalArgumentException("Chat workspace selection is not supported");
        };
    }

    private record ResolvedSelection(String workspaceType, String workspaceKey, String workspaceIdentity) {}

    // ── CRUD ──

    public WorkAssignment get(String assignmentId) {
        return repository.findAssignment(assignmentId)
            .orElseThrow(() -> new IllegalStateException("Assignment not found: " + assignmentId));
    }

    public java.util.List<WorkAssignment> assignments(String agentId) {
        agentProfileService.get(agentId);
        return repository.findAssignmentsForAgent(agentId);
    }

    public java.util.List<WorkAssignment> queueAssignments(String agentId) {
        agentProfileService.get(agentId);
        return repository.findQueueAssignmentsForAgent(agentId);
    }

    public java.util.List<WorkAssignment> historyAssignments(String agentId) {
        agentProfileService.get(agentId);
        return repository.findTerminalAssignmentsForAgent(agentId);
    }

    public void delete(String agentId, String assignmentId) {
        agentProfileService.get(agentId);
        WorkAssignment assignment = get(assignmentId);
        if (!agentId.equals(assignment.agentId())) {
            throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
        }
        if (assignment.status() != null && assignment.status().isTerminal()) {
            throw new IllegalStateException("Terminal assignments are retained in History; use history purge to remove them: " + assignmentId);
        }
        if (!deletable(assignment.status())) {
            throw new IllegalStateException("Running assignments cannot be deleted: " + assignmentId);
        }
        if (!repository.deleteAssignment(agentId, assignmentId)) {
            throw new IllegalStateException("Assignment not found: " + assignmentId);
        }
    }

    public int purgeHistory(String agentId, int olderThanDays) {
        agentProfileService.get(agentId);
        if (olderThanDays < 1) {
            throw new IllegalArgumentException("olderThanDays must be at least 1");
        }
        return repository.purgeTerminalAssignmentHistory(agentId, Instant.now().minus(Duration.ofDays(olderThanDays)));
    }

    @Scheduled(fixedDelayString = "${magenta.orchestration.assignment-history-purge-delay-ms:3600000}")
    public void autoPurgeHistory() {
        RuntimeSettingsSnapshot settings = RuntimeSettingsSnapshot.from(runtimeSettingsService.get());
        if (settings.assignmentHistoryAutoPurgeDays() == -1) {
            return;
        }
        repository.purgeTerminalAssignmentHistory(Instant.now().minus(Duration.ofDays(settings.assignmentHistoryAutoPurgeDays())));
    }

    // ── Lifecycle ──

    WorkAssignment cancel(String assignmentId) {
        return cancel(get(assignmentId));
    }

    public WorkAssignment cancel(String agentId, String assignmentId) {
        return cancel(requireAgentAssignment(agentId, assignmentId));
    }

    private WorkAssignment cancel(WorkAssignment current) {
        if (isTerminal(current.status())) {
            return current;
        }
        if (current.status() == OrchestrationStatus.RUNNING) {
            String assignmentId = current.id();
            WorkAssignment cancelRequested = repository.requestCancel(assignmentId).orElseGet(() -> get(assignmentId));
            localInterruptHandler.accept(assignmentId);
            return cancelRequested;
        }
        if (current.status() == OrchestrationStatus.CANCEL_REQUESTED) {
            return current;
        }
        return saveStatus(current, OrchestrationStatus.CANCELLED);
    }

    WorkAssignment pause(String assignmentId) {
        return pause(get(assignmentId));
    }

    public WorkAssignment pause(String agentId, String assignmentId) {
        return pause(requireAgentAssignment(agentId, assignmentId));
    }

    private WorkAssignment pause(WorkAssignment current) {
        if (isTerminal(current.status())) {
            return current;
        }
        return saveStatus(current, OrchestrationStatus.PAUSED);
    }

    WorkAssignment resume(String assignmentId) {
        return resume(get(assignmentId));
    }

    public WorkAssignment resume(String agentId, String assignmentId) {
        return resume(requireAgentAssignment(agentId, assignmentId));
    }

    private WorkAssignment resume(WorkAssignment current) {
        if (current.status() != OrchestrationStatus.PAUSED && current.status() != OrchestrationStatus.INTERRUPTED
            && current.status() != OrchestrationStatus.WAITING) {
            throw new IllegalStateException("Assignment is not resumable: " + current.id());
        }
        if (taskRunLifecycleService != null && isTaskRunAssignment(current.assignmentType())) {
            String taskRunId = text(current.checkpoint().get("taskRunId"));
            if (!StringUtils.hasText(taskRunId)) {
                throw new IllegalStateException("Legacy assignment has no resumable lifecycle identity: " + current.id());
            }
            requireContextService();
            String bindingKey = bindingKeyForAssignment(current);
            requireOwnerBinding("TASK_RUN", taskRunId, bindingKey);
            if (!current.id().equals(taskRunLifecycleService.findAssignmentId(taskRunId))) {
                throw new IllegalStateException("Task run is not owned by assignment: " + current.id());
            }
            int currentAttempt = integer(current.checkpoint().get("attemptNumber"),
                taskRunLifecycleService.currentAttemptNumber(taskRunId));
            String operationKey = "assignment-resume:" + current.id() + ":" + currentAttempt;
            String inputDigest = taskRunLifecycleService.canonicalInputDigest(Map.of(
                "assignmentId", current.id(), "taskRunId", taskRunId, "fromAttempt", currentAttempt));
            LifecycleResult resumed = taskRunLifecycleService.resume(
                new LifecycleResumeRequest(operationKey, taskRunId, inputDigest));
            Map<String, Object> checkpoint = new LinkedHashMap<>(current.checkpoint());
            checkpoint.put("lifecycleOperationKey", resumed.operationKey());
            checkpoint.put("lifecycleInputDigest", inputDigest);
            checkpoint.put("attemptNumber", resumed.attemptNumber());
            current = copy(current, current.status(), current.currentItemIndex(), checkpoint,
                current.output(), current.evidence(), current.errorText(), current.leaseOwner(),
                current.leaseExpiresAt(), current.completedAt());
        }
        return saveStatus(current, OrchestrationStatus.QUEUED);
    }

    public WorkAssignment retry(String agentId, String assignmentId, String operationKey) {
        return createLineage(requireAgentAssignment(agentId, assignmentId),
            RunLineageMode.RETRY_OF, operationKey);
    }

    public WorkAssignment replay(String agentId, String assignmentId, String operationKey) {
        return createLineage(requireAgentAssignment(agentId, assignmentId),
            RunLineageMode.REPLAY_OF, operationKey);
    }

    @Transactional
    WorkAssignment createLineage(WorkAssignment source, RunLineageMode mode, String requestedOperationKey) {
        if (!isTaskRunAssignment(source.assignmentType())) {
            throw new IllegalArgumentException("Assignment has no task-run lineage: " + source.id());
        }
        String parentTaskRunId = text(source.checkpoint().get("taskRunId"));
        if (!StringUtils.hasText(parentTaskRunId)) {
            throw new IllegalStateException("Legacy assignment has no lineage identity: " + source.id());
        }
        if (!source.status().isTerminal() && source.status() != OrchestrationStatus.INTERRUPTED) {
            throw new IllegalStateException("Only terminal or interrupted assignments can be retried or replayed");
        }
        String newAssignmentId = UUID.randomUUID().toString();
        WorkAssignment child = repository.insertAssignment(new WorkAssignment(
            newAssignmentId, source.agentId(), source.jobId(), source.jobItemId(), source.assignmentType(),
            source.runDisplayName(), source.priority(), OrchestrationStatus.QUEUED, source.modelOverride(),
            source.workspaceType(), source.workspaceKey(), source.workspaceIdentity(), source.projectId(),
            source.sourceDefinitionId(), "assignment:" + newAssignmentId, 0,
            0, Map.of(), source.input(), Map.of(), Map.of(), null, null, null, 0L,
            null, null, null, null, null, null));

        // Copy immutable source facts through durable owner links only.
        String newBindingKey = "assignment:" + newAssignmentId;
        captureLineageBinding(source, newAssignmentId, newBindingKey);

        String key = StringUtils.hasText(requestedOperationKey)
            ? requestedOperationKey.trim() : mode.name().toLowerCase() + ":" + UUID.randomUUID();
        String digest = taskRunLifecycleService.canonicalInputDigest(Map.of(
            "mode", mode.name(), "parentTaskRunId", parentTaskRunId,
            "workspaceIdentity", source.workspaceIdentity(), "input", source.input()));
        String nativeRunId = UUID.randomUUID().toString();
        LifecycleLineageRequest request = new LifecycleLineageRequest(key, parentTaskRunId, mode,
            nativeRunId, nativeRunId, newAssignmentId, digest);
        LifecycleResult lifecycle = mode == RunLineageMode.RETRY_OF
            ? taskRunLifecycleService.retry(request) : taskRunLifecycleService.replay(request);
        String owningAssignmentId = taskRunLifecycleService.findAssignmentId(lifecycle.taskRunId());
        if (!newAssignmentId.equals(owningAssignmentId)) {
            repository.deleteAssignment(source.agentId(), newAssignmentId);
            return get(owningAssignmentId);
        }
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("taskRunId", lifecycle.taskRunId());
        checkpoint.put("nativeRunId", lifecycle.nativeRunId());
        checkpoint.put("lifecycleOperationKey", lifecycle.operationKey());
        checkpoint.put("lifecycleInputDigest", digest);
        checkpoint.put("attemptNumber", lifecycle.attemptNumber());
        checkpoint.put("lineageParentTaskRunId", parentTaskRunId);
        checkpoint.put("lineageMode", mode.name());
        checkpoint.put("contextBindingKey", newBindingKey);
        linkTaskRunBinding(lifecycle.taskRunId(), newAssignmentId, newBindingKey);
        return repository.updateUnleasedAssignment(copy(child, child.status(), 0, checkpoint, Map.of(), Map.of(),
            null, null, null, null), child.status()).orElseThrow(() ->
                new IllegalStateException("Lineage assignment changed before reservation"));
    }

    /**
     * Copies the source assignment's immutable binding facts into a new binding
     * for a lineage child, then creates the owner link.
     */
    private void captureLineageBinding(WorkAssignment source, String newAssignmentId, String newBindingKey) {
        requireContextService();
        var sourceLink = agentContextService.getOwnerLink("ASSIGNMENT", source.id());
        if (sourceLink.outcome() != io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome.AVAILABLE
                || sourceLink.value() == null) {
            throw new IllegalStateException("Legacy assignment has no unambiguous immutable binding: " + source.id());
        }
        String sourceBindingKey = sourceLink.value().bindingKey();
        var bindingLookup = agentContextService.getBinding(sourceBindingKey);
        if (bindingLookup.outcome() != io.mindspice.magenta2.ai.orchestration.context.AgentContextService.ContextOutcome.AVAILABLE
                || bindingLookup.value() == null) {
            throw new IllegalStateException("Source assignment binding is unavailable: " + source.id());
        }
        var sourceBinding = bindingLookup.value();
        if (sourceBinding.selectionKind()
                == io.mindspice.magenta2.ai.orchestration.context.AgentContextRepository.SelectionKind.MATERIALIZATION) {
            agentContextService.captureMaterialization(sourceBinding.agentId(), newBindingKey,
                sourceBinding.materializationKey(), sourceBinding.materializationGeneration(),
                "lineage-capture:" + newAssignmentId);
        } else {
            agentContextService.capturePersonal(sourceBinding.agentId(), newBindingKey,
                sourceBinding.personalSelection(), "lineage-capture:" + newAssignmentId);
        }
        agentContextService.linkOwner("ASSIGNMENT", newAssignmentId, newBindingKey,
            "lineage-owner-link:" + newAssignmentId);
        requireOwnerBinding("ASSIGNMENT", newAssignmentId, newBindingKey);
    }

    WorkAssignment forceInterrupt(String assignmentId, String reason) {
        return forceInterrupt(get(assignmentId), reason);
    }

    public WorkAssignment forceInterrupt(String agentId, String assignmentId, String reason) {
        return forceInterrupt(requireAgentAssignment(agentId, assignmentId), reason);
    }

    private WorkAssignment forceInterrupt(WorkAssignment current, String reason) {
        if (isTerminal(current.status()) || current.status() == OrchestrationStatus.INTERRUPTED) {
            return current;
        }
        if (current.status() != OrchestrationStatus.RUNNING && current.status() != OrchestrationStatus.CANCEL_REQUESTED) {
            throw new IllegalStateException("Assignment is not force-interruptible: " + current.id());
        }
        String operatorReason = StringUtils.hasText(reason) ? reason.trim() : "operator requested force interrupt";
        boolean updated = repository.forceInterruptAssignmentFenced(current.id(), current.leaseOwner(),
            current.leaseFence(), "Force interrupted: " + operatorReason);
        if (updated && taskRunLifecycleService != null) {
            taskRunLifecycleService.interruptCurrentWriterForAssignment(current.id(),
                "Force interrupted: " + operatorReason);
        }
        localInterruptHandler.accept(current.id());
        return updated ? get(current.id()) : get(current.id());
    }

    public void registerLocalInterruptHandler(Consumer<String> handler) {
        this.localInterruptHandler = handler == null ? ignored -> { } : handler;
    }

    // ── Diagnostics ──

    public AssignmentDiagnostics diagnostics(String assignmentId) {
        WorkAssignment assignment = get(assignmentId);
        Instant now = Instant.now();
        Instant progressAt = assignment.lastProgressAt() != null ? assignment.lastProgressAt() : assignment.updatedAt();
        Instant heartbeatAt = assignment.lastHeartbeatAt() != null ? assignment.lastHeartbeatAt() : assignment.updatedAt();
        Duration progressAge = age(now, progressAt);
        Duration heartbeatAge = age(now, heartbeatAt);
        boolean suspectedStuck = assignment.status() == OrchestrationStatus.RUNNING
            && progressAge != null
            && progressAge.compareTo(Duration.ofMinutes(15)) >= 0
            && heartbeatAge != null
            && heartbeatAge.compareTo(Duration.ofMinutes(5)) < 0;

        List<LinkedRunStatus> linkedRuns = linkedRuns(assignment);
        List<String> conversationIds = conversationIds(assignment);
        String conversationId = conversationIds.isEmpty() ? null : String.join(", ", conversationIds);
        List<AuditRepository.AuditEvent> auditEvents = auditRepository == null || conversationIds.isEmpty()
            ? List.of()
            : auditRepository.findByConversationIds(conversationIds).stream()
                .sorted(Comparator.comparingInt(AuditRepository.AuditEvent::sequence).reversed())
                .limit(12)
                .toList();
        return new AssignmentDiagnostics(
            assignment,
            progressAt,
            heartbeatAt,
            progressAge,
            heartbeatAge,
            suspectedStuck,
            linkedRuns,
            auditEvents,
            conversationId,
            buildCommit()
        );
    }

    private WorkAssignment requireAgentAssignment(String agentId, String assignmentId) {
        agentProfileService.get(agentId);
        WorkAssignment assignment = get(assignmentId);
        if (!agentId.equals(assignment.agentId())) {
            throw new IllegalArgumentException("Assignment does not belong to agent: " + assignmentId);
        }
        return assignment;
    }

    public AssignmentTranscript transcript(String agentId, String assignmentId) {
        agentProfileService.get(agentId);
        WorkAssignment assignment = requireAgentAssignment(agentId, assignmentId);
        List<String> conversationIds = conversationIds(assignment);
        List<AuditRepository.AuditEvent> auditEvents = auditRepository == null || conversationIds.isEmpty()
            ? List.of()
            : auditRepository.findByConversationIds(conversationIds);
        return new AssignmentTranscript(assignment, conversationIds, auditEvents);
    }

    /**
     * Returns transcript facts linked directly to an assignment by durable
     * assignment-conversation rows. Project Queue uses this strict reader and
     * deliberately excludes checkpoint/input/evidence and legacy inference.
     */
    public AssignmentTranscript exactLinkTranscript(String agentId, String assignmentId) {
        agentProfileService.get(agentId);
        WorkAssignment assignment = requireAgentAssignment(agentId, assignmentId);
        List<String> conversationIds = repository.findAssignmentConversationIds(assignment.id());
        List<AuditRepository.AuditEvent> auditEvents = auditRepository == null || conversationIds.isEmpty()
            ? List.of()
            : auditRepository.findByConversationIds(conversationIds).stream().limit(12).toList();
        return new AssignmentTranscript(assignment, conversationIds, auditEvents);
    }

    public String resolveModel(WorkAssignment assignment, JobWorkItem item) {
        String explicit = assignment.modelOverride();
        String itemOverride = item == null ? null : item.modelOverride();
        JobDefinition job = StringUtils.hasText(assignment.jobId()) ? jobService.getDefinition(assignment.jobId()) : null;
        AgentProfile agent = agentProfileService.get(assignment.agentId());
        String key = firstText(explicit, itemOverride, job == null ? null : job.model(), agent.defaultModel());
        return runtimeSettingsService.resolveModel(key, null);
    }

    WorkAssignment saveIfLeaseOwner(WorkAssignment assignment, String leaseOwner) {
        return repository.saveAssignmentIfLeaseOwner(assignment, leaseOwner)
            .orElseThrow(() -> new StaleAssignmentLeaseException(
                "Assignment lease is stale: " + assignment.id()));
    }

    public static final class StaleAssignmentLeaseException extends RuntimeException {
        public StaleAssignmentLeaseException(String message) {
            super(message);
        }
    }

    public AssignmentSummary summary(String assignmentId) {
        return summary(get(assignmentId));
    }

    public List<AssignmentSummary> summariesForAgent(String agentId) {
        return assignments(agentId).stream().map(this::summary).toList();
    }

    public List<AssignmentSummary> activeSummariesForProject(String projectId) {
        return repository.findActiveAssignmentsForProject(projectId).stream().map(this::summary).toList();
    }

    public List<AssignmentSummary> activeSummariesForWorkspaceIdentity(String workspaceIdentity) {
        return repository.findActiveAssignmentsForWorkspaceIdentity(workspaceIdentity).stream().map(this::summary).toList();
    }

    public boolean hasActiveAssignmentsForProject(String projectId) {
        return repository.countActiveAssignmentsForProject(projectId) > 0;
    }

    public boolean hasActiveAssignmentsForJob(String jobId) {
        return repository.countActiveAssignmentsForJob(jobId) > 0;
    }

    public boolean hasActiveAssignmentsForWorkspaceIdentity(String workspaceIdentity) {
        return repository.countActiveAssignmentsForWorkspaceIdentity(workspaceIdentity) > 0;
    }

    WorkAssignment saveStatus(WorkAssignment assignment, OrchestrationStatus status) {
        Instant completedAt = isTerminal(status) ? Instant.now() : assignment.completedAt();
        return repository.updateUnleasedAssignment(copy(
            assignment,
            status,
            assignment.currentItemIndex(),
            assignment.checkpoint(),
            assignment.output(),
            assignment.evidence(),
            assignment.errorText(),
            null,
            null,
            completedAt
        ), assignment.status()).orElseThrow(() ->
            new IllegalStateException("Assignment changed before lifecycle transition"));
    }

    WorkAssignment copy(
        WorkAssignment assignment,
        OrchestrationStatus status,
        int currentItemIndex,
        Map<String, Object> checkpoint,
        Map<String, Object> output,
        Map<String, Object> evidence,
        String errorText,
        String leaseOwner,
        Instant leaseExpiresAt,
        Instant completedAt
    ) {
        return new WorkAssignment(
            assignment.id(), assignment.agentId(), assignment.jobId(), assignment.jobItemId(),
            assignment.assignmentType(), assignment.runDisplayName(), assignment.priority(), status,
            assignment.modelOverride(), assignment.workspaceType(), assignment.workspaceKey(),
            assignment.workspaceIdentity(), assignment.projectId(),
            assignment.sourceDefinitionId(), assignment.contextBindingKey(), assignment.rowVersion(),
            currentItemIndex, checkpoint == null ? Map.of() : checkpoint, assignment.input(),
            output == null ? Map.of() : output, evidence == null ? Map.of() : evidence, errorText,
            leaseOwner, leaseExpiresAt, assignment.leaseFence(),
            assignment.createdAt(), assignment.updatedAt(), assignment.startedAt(),
            completedAt, assignment.lastProgressAt(), assignment.lastHeartbeatAt()
        );
    }

    private List<LinkedRunStatus> linkedRuns(WorkAssignment assignment) {
        List<LinkedRunStatus> runs = new ArrayList<>();
        addTaskRun(runs, firstText(
            text(assignment.checkpoint().get("taskRunId")),
            text(assignment.output().get("taskRunId")),
            text(assignment.checkpoint().get("planRunId")),
            text(assignment.output().get("planRunId"))
        ));
        addWorkflowRun(runs, firstText(
            text(assignment.checkpoint().get("workflowRunId")),
            text(assignment.output().get("workflowRunId"))
        ));
        addJobRun(runs, firstText(
            text(assignment.checkpoint().get("jobRunId")),
            text(assignment.output().get("jobRunId"))
        ));
        return runs;
    }

    private void addTaskRun(List<LinkedRunStatus> runs, String runId) {
        if (!StringUtils.hasText(runId)) return;
        try {
            PlanRun planRun = planService == null ? null : planService.getRun(runId);
            if (planRun != null) {
                runs.add(new LinkedRunStatus("PLAN_RUN", planRun.id(), planRun.planId(), planRun.status().name(), planRun.errorText()));
            }
        } catch (RuntimeException ignored) {
            runs.add(new LinkedRunStatus("TASK_RUN", runId, null, "missing", null));
        }
    }

    private void addWorkflowRun(List<LinkedRunStatus> runs, String runId) {
        if (!StringUtils.hasText(runId)) return;
        try {
            WorkflowRun run = workflowService == null ? null : workflowService.getRun(runId);
            if (run != null) {
                runs.add(new LinkedRunStatus("WORKFLOW_RUN", run.id(), run.workflowId(), run.status().name(), run.errorText()));
            }
        } catch (RuntimeException ignored) {
            runs.add(new LinkedRunStatus("WORKFLOW_RUN", runId, null, "missing", null));
        }
    }

    private void addJobRun(List<LinkedRunStatus> runs, String runId) {
        if (!StringUtils.hasText(runId)) return;
        try {
            JobRun run = jobService == null ? null : jobService.getRun(runId);
            if (run != null) {
                runs.add(new LinkedRunStatus("JOB_RUN", run.id(), run.jobId(), run.status().name(), run.errorText()));
            }
        } catch (RuntimeException ignored) {
            runs.add(new LinkedRunStatus("JOB_RUN", runId, null, "missing", null));
        }
    }

    private boolean isTerminal(OrchestrationStatus status) {
        return status == OrchestrationStatus.COMPLETED || status == OrchestrationStatus.CANCELLED
            || status == OrchestrationStatus.FAILED || status == OrchestrationStatus.NEEDS_REVIEW;
    }

    private AssignmentSummary summary(WorkAssignment assignment) {
        return new AssignmentSummary(
            assignment.id(),
            assignment.agentId(),
            assignment.jobId(),
            assignment.jobItemId(),
            assignment.assignmentType(),
            assignment.priority(),
            assignment.status(),
            assignment.projectId(),
            assignment.workspaceType(),
            assignment.workspaceKey(),
            assignment.workspaceIdentity(),
            text(assignment.checkpoint().get("jobRunId")),
            text(assignment.checkpoint().get("workflowRunId")),
            firstText(text(assignment.checkpoint().get("taskRunId")), text(assignment.checkpoint().get("planRunId"))),
            assignment.createdAt(),
            assignment.updatedAt(),
            assignment.startedAt(),
            assignment.completedAt()
        );
    }

    public boolean deletable(OrchestrationStatus status) {
        return status != null
            && !status.isTerminal()
            && status != OrchestrationStatus.RUNNING
            && status != OrchestrationStatus.CANCEL_REQUESTED;
    }

    private record RuntimeSettingsSnapshot(int assignmentHistoryAutoPurgeDays) {
        private static RuntimeSettingsSnapshot from(io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings settings) {
            Integer days = settings.assignmentHistoryAutoPurgeDays();
            return new RuntimeSettingsSnapshot(days == null ? -1 : days);
        }
    }

    private List<String> conversationIds(WorkAssignment assignment) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        collectConversationIds(ids, assignment.checkpoint());
        collectConversationIds(ids, assignment.output());
        repository.findAssignmentConversationIds(assignment.id()).forEach(ids::add);
        collectConversationIds(ids, assignment.evidence());
        collectConversationIds(ids, assignment.input());
        String taskId = text(assignment.input().get("taskId"));
        Instant windowStart = assignment.startedAt() != null ? assignment.startedAt() : assignment.createdAt();
        Instant windowEnd = firstInstant(assignment.completedAt(), assignment.updatedAt(), Instant.now());
        repository.findLegacyTaskConversationIds(taskId, windowStart, windowEnd).forEach(ids::add);
        return List.copyOf(ids);
    }

    @SuppressWarnings("unchecked")
    private void collectConversationIds(LinkedHashSet<String> ids, Object value) {
        if (value == null) return;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toString();
                Object child = entry.getValue();
                if ("conversationId".equals(key) || "activeConversationId".equals(key)) {
                    addConversationId(ids, child);
                } else if ("conversationIds".equals(key)) {
                    collectConversationIds(ids, child);
                } else if (child instanceof Map<?, ?> || child instanceof Iterable<?>) {
                    collectConversationIds(ids, child);
                }
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                if (child instanceof Map<?, ?> || child instanceof Iterable<?>) {
                    collectConversationIds(ids, child);
                } else {
                    addConversationId(ids, child);
                }
            }
        }
    }

    private void addConversationId(LinkedHashSet<String> ids, Object value) {
        String text = text(value);
        if (StringUtils.hasText(text)) ids.add(text);
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value;
        }
        return null;
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }

    private int integer(Object value, int fallback) {
        if (value == null) return fallback;
        return value instanceof Number number ? number.intValue() : Integer.parseInt(value.toString());
    }

    private Duration age(Instant now, Instant instant) {
        return instant == null ? null : Duration.between(instant, now);
    }

    private Instant firstInstant(Instant... values) {
        for (Instant value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String buildCommit() {
        String commit = firstText(
            System.getenv("MAGENTA_BUILD_COMMIT"),
            System.getenv("GIT_COMMIT"),
            System.getProperty("magenta.build.commit"),
            System.getProperty("git.commit")
        );
        return commit == null ? "unknown" : commit;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Map<String, Object> inputWithProjectContext(AssignmentRequest request) {
        return new LinkedHashMap<>(request.input() == null ? Map.of() : request.input());
    }

    public record LinkedRunStatus(String type, String id, String parentId, String status, String errorText) {}

    public record AssignmentSummary(
        String id,
        String agentId,
        String jobId,
        String jobItemId,
        AssignmentType assignmentType,
        int priority,
        OrchestrationStatus status,
        String projectId,
        String workspaceType,
        String workspaceKey,
        String workspaceIdentity,
        String jobRunId,
        String workflowRunId,
        String taskRunId,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant completedAt
    ) {}

    public record AssignmentDiagnostics(
        WorkAssignment assignment,
        Instant lastProgressAt,
        Instant lastHeartbeatAt,
        Duration progressAge,
        Duration heartbeatAge,
        boolean suspectedStuck,
        List<LinkedRunStatus> linkedRuns,
        List<AuditRepository.AuditEvent> auditEvents,
        String conversationId,
        String buildCommit
    ) {}

    public record AssignmentTranscript(
        WorkAssignment assignment,
        List<String> conversationIds,
        List<AuditRepository.AuditEvent> auditEvents
    ) {}
}
