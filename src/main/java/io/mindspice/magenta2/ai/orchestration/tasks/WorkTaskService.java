package io.mindspice.magenta2.ai.orchestration.tasks;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Owns creation, reads, edits, and promotion of lightweight
 * {@link WorkTaskDefinition} instances. Task display state is derived from
 * the designated attempt's {@code WorkAssignment.status}, not stored on the
 * task row.
 *
 * <p>Subphase B adds guarded CRUD, project binding, pin enforcement,
 * preferred-agent validation, archive gating, operation-key idempotency
 * on all mutations, and promotion through {@link PlanService} to create a
 * minimal {@code TASK_TEMPLATE} {@link PlanDefinition}.
 */
@Service
public class WorkTaskService {
    private static final Logger log = LoggerFactory.getLogger(WorkTaskService.class);
    private static final Pattern ATTEMPT_ID_RESULT = Pattern.compile("\\\"attemptId\\\":\\\"([^\\\"]+)\\\"");

    private final WorkTaskRepository repository;
    private final AgentProfileService agentProfileService;
    private final ProjectService projectService;
    private final PlanService planService;
    private final AssignmentService assignmentService;

    public WorkTaskService(WorkTaskRepository repository) {
        this(repository, null, null, null, null);
    }

    /** 4-arg constructor for callers that don't need AssignmentService. */
    public WorkTaskService(WorkTaskRepository repository,
                           AgentProfileService agentProfileService,
                           ProjectService projectService,
                           PlanService planService) {
        this(repository, agentProfileService, projectService, planService, null);
    }

    @Autowired
    public WorkTaskService(WorkTaskRepository repository,
                           @Autowired(required = false) AgentProfileService agentProfileService,
                           @Autowired(required = false) ProjectService projectService,
                           @Autowired(required = false) PlanService planService,
                           @Autowired(required = false) AssignmentService assignmentService) {
        this.repository = repository;
        this.agentProfileService = agentProfileService;
        this.projectService = projectService;
        this.planService = planService;
        this.assignmentService = assignmentService;
    }

    // ── Create ──

    /**
     * Create a new task definition. Validates title, enforces pin-requires-project,
     * validates preferred agent membership/eligibility, and checks project archive
     * status when a projectId is supplied.
     */
    @Transactional
    WorkTaskDefinition create(String title, String description,
                                      String projectId, int defaultPriority,
                                      String preferredAgentId) {
        return create(title, description, projectId, false, defaultPriority, preferredAgentId);
    }

    /**
     * Create a new task definition with explicit pin.
     */
    @Transactional
    WorkTaskDefinition create(String title, String description,
                                      String projectId, boolean pin,
                                      int defaultPriority,
                                      String preferredAgentId) {
        String id = UUID.randomUUID().toString();
        String normalizedTitle = requireTitle(title);
        String normalizedDesc = StringUtils.hasText(description) ? description.strip() : null;
        String normalizedProjectId = StringUtils.hasText(projectId) ? projectId : null;
        String normalizedAgent = StringUtils.hasText(preferredAgentId) ? preferredAgentId : null;

        validatePin(pin, normalizedProjectId);
        validatePreferredAgent(normalizedAgent, normalizedProjectId);

        // Archive guard on create: reject tasks for archived projects
        if (normalizedProjectId != null) {
            requireProjectService().requireActiveProject(normalizedProjectId);
        }

        Instant now = Instant.now();
        WorkTaskDefinition task = new WorkTaskDefinition(
            id, normalizedTitle, normalizedDesc, normalizedProjectId,
            pin, defaultPriority, normalizedAgent,
            null, null, 1, now, now
        );
        return repository.save(task);
    }

    /** Create exactly once for an operation key and canonical request. */
    @Transactional
    public WorkTaskDefinition createOperation(String operationKey, String title, String description,
                                               String projectId, boolean pin, int defaultPriority,
                                               String preferredAgentId) {
        requireOperationKey(operationKey);
        String digest = sha256(buildCreateDigestPayload(title, description, projectId, pin,
            defaultPriority, preferredAgentId));
        var completed = repository.findCompletedOperation("CREATE", operationKey, digest);
        if (completed.isPresent() && StringUtils.hasText(completed.get().taskId())) {
            return getById(completed.get().taskId());
        }
        var existing = repository.findOperationDigest("CREATE", operationKey);
        if (existing.isPresent() && !existing.get().equals(digest)) {
            throw new IllegalStateException("CREATE operation key was reused with contradictory request");
        }
        if (!repository.reserveOperation("CREATE", operationKey, digest)) {
            throw new IllegalStateException("CREATE operation key is reserved by another process");
        }
        WorkTaskDefinition task = create(title, description, projectId, pin, defaultPriority, preferredAgentId);
        repository.completeOperation("CREATE", operationKey, digest, task.id(), "{}");
        return task;
    }

    // ── Read ──

    public WorkTaskDefinition getById(String id) {
        return repository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + id));
    }

    /**
     * List all tasks in server-defined structural order
     * (most-recently-updated first, then by title).
     */
    public List<WorkTaskDefinition> listAll() {
        return repository.findAll();
    }

    /**
     * List tasks bound to a project, pinned first, then by update time and title.
     */
    public List<WorkTaskDefinition> listByProject(String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return List.of();
        }
        return repository.findByProject(projectId);
    }

    // ── Edit ──

    /**
     * Update mutable task fields (title, description, priority, pin,
     * preferredAgent) using definition-version CAS.
     *
     * <p>Archive guard: rejects edits on tasks bound to archived projects.
     * Pin validation: pin=true requires a project binding.
     * Preferred-agent validation: project-bound requires membership;
     * unbound requires agent eligibility.
     *
     * @return the updated task
     * @throws IllegalStateException if the expectedVersion does not match
     */
    @Transactional
    WorkTaskDefinition edit(String taskId, int expectedVersion,
                                    String title, String description,
                                    Integer defaultPriority, Boolean pin,
                                    String preferredAgentId) {
        WorkTaskDefinition existing = getById(taskId);
        if (existing.definitionVersion() != expectedVersion) {
            throw new IllegalStateException(
                "Definition version mismatch: expected " + expectedVersion
                + " but current is " + existing.definitionVersion());
        }

        // Archive guard
        requireProjectNotArchived(existing);

        String resolvedProjectId = existing.projectId();
        boolean resolvedPin = pin != null ? pin : existing.pin();
        String resolvedAgent = preferredAgentId != null ? preferredAgentId.strip() : existing.preferredAgentId();

        validatePin(resolvedPin, resolvedProjectId);
        validatePreferredAgent(StringUtils.hasText(resolvedAgent) ? resolvedAgent : null, resolvedProjectId);

        String normalizedTitle = (title != null) ? title.strip() : null;
        String normalizedDesc = (description != null) ? description.strip() : null;
        String normalizedAgent = (preferredAgentId != null) ? preferredAgentId.strip() : null;
        if (preferredAgentId != null && normalizedAgent != null && normalizedAgent.isBlank()) {
            normalizedAgent = null;
        }

        int updated = repository.casUpdateFields(
            taskId, expectedVersion,
            normalizedTitle, normalizedDesc,
            defaultPriority, pin, normalizedAgent
        );

        if (updated == 0) {
            throw new IllegalStateException(
                "CAS update failed for task " + taskId + " at version " + expectedVersion);
        }

        return getById(taskId);
    }

    /**
     * Idempotent edit using operation-key + canonical-digest pattern.
     * Same key/digest replays the stored result; changed digest conflicts.
     */
    @Transactional
    public WorkTaskDefinition editOperation(String operationKey, String taskId, int expectedVersion,
                                             String title, String description,
                                             Integer defaultPriority, Boolean pin,
                                             String preferredAgentId) {
        requireOperationKey(operationKey);
        String digest = sha256(buildEditDigestPayload(taskId, expectedVersion,
            title, description, defaultPriority, pin, preferredAgentId));

        // Check if already completed
        var completed = repository.findCompletedOperation("EDIT", operationKey, digest);
        if (completed.isPresent() && completed.get().taskId() != null
                && completed.get().taskId().equals(taskId)) {
            return getById(taskId);
        }

        // Check for digest conflict: same key, different digest
        var existingDigest = repository.findOperationDigest("EDIT", operationKey);
        if (existingDigest.isPresent() && !existingDigest.get().equals(digest)) {
            throw new IllegalStateException(
                "EDIT operation key was reused with contradictory request");
        }

        // Reserve
        boolean reserved = repository.reserveOperation("EDIT", operationKey, digest);
        if (!reserved) {
            // Already reserved by another process — the completed check above
            // would have caught a completion. This is a concurrent reservation.
            // Wait-and-retry pattern: check again after a brief moment.
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            var retryCompleted = repository.findCompletedOperation("EDIT", operationKey, digest);
            if (retryCompleted.isPresent() && retryCompleted.get().taskId() != null
                    && retryCompleted.get().taskId().equals(taskId)) {
                return getById(taskId);
            }
            throw new IllegalStateException("EDIT operation key is reserved by another process");
        }

        WorkTaskDefinition result = edit(taskId, expectedVersion,
            title, description, defaultPriority, pin, preferredAgentId);

        String resultJson = "{\"definitionVersion\":" + result.definitionVersion() + "}";
        int updated = repository.completeOperation("EDIT", operationKey, digest, taskId, resultJson);
        if (updated == 0) {
            log.warn("Failed to complete EDIT operation {} — may have been reserved by another process", operationKey);
        }

        return result;
    }

    // ── Project binding (immutable after first attempt) ──

    /**
     * Set the project id on a task if not already set. Binding is immutable
     * once the first attempt has been created. The target project must be
     * active (not archived).
     *
     * @return true if the project was set, false if it was already set
     */
    @Transactional
    boolean bindProject(String taskId, String projectId) {
        WorkTaskDefinition existing = getById(taskId);
        if (existing.projectId() != null) {
            return false;
        }
        if (!StringUtils.hasText(projectId)) {
            throw new IllegalArgumentException("projectId is required for binding");
        }

        // Archive guard: target project must be active
        requireProjectService().requireActiveProject(projectId);

        List<WorkTaskAttempt> attempts = repository.findAttemptsByTask(taskId);
        if (!attempts.isEmpty()) {
            throw new IllegalStateException(
                "Cannot change project binding after attempts exist");
        }
        int updated = repository.updateProjectIdIfNull(taskId, projectId);
        return updated > 0;
    }

    /**
     * Idempotent project binding using operation-key + canonical-digest pattern.
     */
    @Transactional
    public boolean bindProjectOperation(String operationKey, String taskId, String projectId) {
        requireOperationKey(operationKey);
        String digest = sha256(buildBindDigestPayload(taskId, projectId));

        // Check if already completed
        var completed = repository.findCompletedOperation("BIND", operationKey, digest);
        if (completed.isPresent() && completed.get().taskId() != null
                && completed.get().taskId().equals(taskId)) {
            return getById(taskId).projectId() != null
                && getById(taskId).projectId().equals(projectId);
        }

        // Check for digest conflict
        var existingDigest = repository.findOperationDigest("BIND", operationKey);
        if (existingDigest.isPresent() && !existingDigest.get().equals(digest)) {
            throw new IllegalStateException(
                "BIND operation key was reused with contradictory request");
        }

        // Reserve
        boolean reserved = repository.reserveOperation("BIND", operationKey, digest);
        if (!reserved) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            var retryCompleted = repository.findCompletedOperation("BIND", operationKey, digest);
            if (retryCompleted.isPresent() && retryCompleted.get().taskId() != null
                    && retryCompleted.get().taskId().equals(taskId)) {
                return getById(taskId).projectId() != null
                    && getById(taskId).projectId().equals(projectId);
            }
            throw new IllegalStateException("BIND operation key is reserved by another process");
        }

        boolean result = bindProject(taskId, projectId);

        String resultJson = "{\"projectId\":\"" + projectId + "\"}";
        int updated = repository.completeOperation("BIND", operationKey, digest, taskId, resultJson);
        if (updated == 0) {
            log.warn("Failed to complete BIND operation {} — may have been reserved by another process", operationKey);
        }

        return result;
    }

    // ── Promotion ──

    /**
     * Link a task to an existing promoted Plan. This method is retained for
     * backward compatibility with tests that supply a pre-existing plan id.
     * New callers should use {@link #promoteOperation} which creates the
     * TASK_TEMPLATE Plan through {@link PlanService}.
     *
     * @param taskId      the task to promote
     * @param planId      the ID of the promoted PlanDefinition (TASK_TEMPLATE)
     * @param expectedVersion the current definition version
     * @return the updated task
     */
    @Transactional
    WorkTaskDefinition promote(String taskId, String planId, int expectedVersion) {
        WorkTaskDefinition existing = getById(taskId);
        if (existing.promotedPlanId() != null) {
            throw new IllegalStateException(
                "Task " + taskId + " has already been promoted to plan " + existing.promotedPlanId());
        }
        if (existing.definitionVersion() != expectedVersion) {
            throw new IllegalStateException(
                "Definition version mismatch: expected " + expectedVersion
                + " but current is " + existing.definitionVersion());
        }

        // Archive guard
        requireProjectNotArchived(existing);

        int updated = repository.setPromotedPlanId(taskId, planId, expectedVersion);
        if (updated == 0) {
            throw new IllegalStateException(
                "Promotion failed for task " + taskId + " at version " + expectedVersion);
        }

        return getById(taskId);
    }

    /**
     * Promote a task by creating a minimal TASK_TEMPLATE Plan through
     * {@link PlanService} and persisting the immutable promotedPlanId link.
     *
     * <p>Idempotency: same key/digest replays the stored result. Changed
     * digest conflicts. If promotion already exists, returns the existing
     * link without creating another Plan.
     *
     * <p>Independent edits: task edits never update the Plan. Plan edits
     * never update the task.
     *
     * <p>Archive guard: rejects promotion of tasks bound to archived projects.
     *
     * @param operationKey    idempotency key
     * @param taskId          the task to promote
     * @param expectedVersion the current definition version
     * @return the updated task with promotedPlanId set
     */
    @Transactional
    public WorkTaskDefinition promoteOperation(String operationKey, String taskId,
                                                int expectedVersion) {
        requireOperationKey(operationKey);
        String digest = sha256(buildPromoteDigestPayload(taskId, expectedVersion));

        // Check if already completed
        var completed = repository.findCompletedOperation("PROMOTE", operationKey, digest);
        if (completed.isPresent() && completed.get().taskId() != null
                && completed.get().taskId().equals(taskId)) {
            return getById(taskId);
        }

        // Check for digest conflict
        var existingDigest = repository.findOperationDigest("PROMOTE", operationKey);
        if (existingDigest.isPresent() && !existingDigest.get().equals(digest)) {
            throw new IllegalStateException(
                "PROMOTE operation key was reused with contradictory request");
        }

        WorkTaskDefinition task = getById(taskId);

        // If already promoted, return existing link (idempotent replay
        // for an operation completed by a prior key)
        if (task.promotedPlanId() != null) {
            // This task was promoted via another operation; still complete
            // this operation so the caller sees a definitive result
            String resultJson = "{\"planId\":\"" + task.promotedPlanId() + "\"}";
            repository.reserveOperation("PROMOTE", operationKey, digest);
            repository.completeOperation("PROMOTE", operationKey, digest,
                taskId, resultJson);
            return task;
        }

        if (task.definitionVersion() != expectedVersion) {
            throw new IllegalStateException(
                "Definition version mismatch: expected " + expectedVersion
                + " but current is " + task.definitionVersion());
        }

        // Archive guard
        requireProjectNotArchived(task);

        // Reserve
        boolean reserved = repository.reserveOperation("PROMOTE", operationKey, digest);
        if (!reserved) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            var retryCompleted = repository.findCompletedOperation("PROMOTE", operationKey, digest);
            if (retryCompleted.isPresent() && retryCompleted.get().taskId() != null
                    && retryCompleted.get().taskId().equals(taskId)) {
                return getById(taskId);
            }
            throw new IllegalStateException("PROMOTE operation key is reserved by another process");
        }

        // Create minimal TASK_TEMPLATE Plan
        PlanDefinition plan = createMinimalPlan(task);
        if (planService == null) {
            throw new IllegalStateException("PlanService is unavailable — cannot create promoted plan");
        }
        PlanDefinition saved = planService.saveTask(plan);

        // Set promotedPlanId on task
        int updated = repository.setPromotedPlanId(taskId, saved.id(), expectedVersion);
        if (updated == 0) {
            throw new IllegalStateException(
                "Promotion CAS failed for task " + taskId + " at version " + expectedVersion);
        }

        // Complete operation
        String resultJson = "{\"planId\":\"" + saved.id() + "\"}";
        repository.completeOperation("PROMOTE", operationKey, digest, taskId, resultJson);

        log.info("Task {} promoted to Plan {}", taskId, saved.id());
        return getById(taskId);
    }

    /**
     * Build a minimal TASK_TEMPLATE PlanDefinition from a WorkTaskDefinition.
     * The plan carries the task's title and description but no structured
     * sections, steps, or validations. Task and Plan are independently
     * editable after promotion.
     */
    private PlanDefinition createMinimalPlan(WorkTaskDefinition task) {
        Instant now = Instant.now();
        return new PlanDefinition(
            UUID.randomUUID().toString(),
            PlanKind.TASK_TEMPLATE,
            PlanStatus.APPROVED,
            task.title(),
            task.description(),  // summary
            null,                // goal
            null,                // notes
            List.of(),           // deliverables
            List.of(),           // inputs
            List.of(),           // outputs
            List.of(),           // assumptions
            List.of(),           // steps
            List.of(),           // validationCriteria
            List.of(),           // executionEvidence
            List.of(),           // validationFeedback
            null,                // promptProfile
            null,                // planningModel
            null,                // executionModel
            null,                // settingsOverrideJson
            null,                // planningTask
            List.of(),           // pendingQuestions
            0,                   // pendingQuestionIndex
            0,                   // planStartMessageOrder
            null,                // finalMessage
            null,                // conversationId
            now,                 // createdAt
            now                  // updatedAt
        );
    }

    // ── Submission pre-check ──

    /**
     * Verify that a task is ready for submission. The task must be promoted
     * and the promoted Plan must exist and be executable (APPROVED). Archived
     * projects are rejected.
     *
     * <p>Subphase C will call this before creating work assignments from tasks.
     *
     * @throws IllegalStateException if the task is not ready for submission
     */
    public void submissionPreCheck(String taskId) {
        WorkTaskDefinition task = getById(taskId);

        if (task.promotedPlanId() == null) {
            throw new IllegalStateException(
                "Task is not promoted to an executable plan: " + taskId);
        }

        // Archive guard
        requireProjectNotArchived(task);

        if (planService == null) {
            throw new IllegalStateException("PlanService is unavailable — cannot validate promoted plan");
        }
        {
            PlanDefinition plan;
            try {
                plan = planService.getTask(task.promotedPlanId());
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                    "Promoted plan not found: " + task.promotedPlanId()
                    + " for task " + taskId, e);
            }

            if (plan.status() != PlanStatus.APPROVED) {
                throw new IllegalStateException(
                    "Promoted plan is not executable (status: " + plan.status()
                    + "): " + task.promotedPlanId());
            }
        }
    }

    // ── Submit ──

    /**
     * Submit a promoted work task for execution. Resolves the task, validates
     * the explicit executor, project/member state, and executable Plan, then
     * creates a {@code WORK_TASK} assignment through {@link AssignmentService}.
     * The database-level unique constraint on {@code source_definition_id}
     * prevents multiple concurrent non-terminal assignments for the same task.
     *
     * @param taskId         the promoted work task
     * @param agentId        explicit executor (required; never auto-selected)
     * @param runDisplayName optional run display name (falls back to task title)
     * @param workspaceType  optional workspace type
     * @param workspaceKey   optional workspace key
     * @param operationKey   optional idempotency key
     * @return the newly created attempt
     * @throws IllegalStateException if the task is not promoted, the plan is not
     *                               executable, or an active assignment exists
     */
    @Transactional
    WorkTaskAttempt submit(String taskId, String agentId, String runDisplayName,
                                   String workspaceType, String workspaceKey,
                                   String operationKey) {
        WorkTaskDefinition task = getById(taskId);

        // Require promoted
        if (task.promotedPlanId() == null) {
            throw new IllegalStateException("Task is not promoted to an executable plan: " + taskId);
        }

        // Archive guard
        requireProjectNotArchived(task);

        // Validate explicit executor
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required for submission");
        }

        // Capability gate: project membership
        if (task.projectId() != null) {
            requireProjectService().requireActiveProjectWithMember(task.projectId(), agentId);
        } else if (agentProfileService != null) {
            // Unbound task: validate agent eligibility
            AgentProfile agent = agentProfileService.get(agentId);
            if (agent.status() == AgentProfileStatus.DISABLED) {
                throw new IllegalArgumentException("Agent is disabled: " + agentId);
            }
        }

        // Validate executable Plan
        submissionPreCheck(taskId);

        // Check for existing active assignment (defensive; DB unique index is authoritative)
        int activeCount = repository.countActiveAttempts(taskId);
        if (activeCount > 0) {
            throw new IllegalStateException(
                "Task already has an active assignment: " + taskId);
        }

        // Build input
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", task.promotedPlanId());

        AssignmentRequest request = new AssignmentRequest(
            agentId,
            null,                                  // jobId
            null,                                  // jobItemId
            AssignmentType.TASK_RUN,
            StringUtils.hasText(runDisplayName) ? runDisplayName : task.title(),
            task.defaultPriority(),
            null,                                  // modelOverride
            task.projectId(),                      // immutable project attribution
            workspaceType,
            workspaceKey,
            input,
            operationKey,
            "PLAN/" + task.promotedPlanId(),       // executable source
            "WORK_TASK/" + task.id(),              // immutable submission origin
            null                                    // contextBindingKey
        );

        WorkAssignment assignment = requireAssignmentService().create(request);

        // Record attempt after accepted assignment
        return recordAttempt(taskId, assignment.id(), null);
    }

    /**
     * Idempotent submit using operation-key + canonical-digest pattern.
     * Same key/digest replays the stored attempt; changed digest conflicts.
     */
    @Transactional
    public WorkTaskAttempt submitOperation(String operationKey, String taskId,
                                            String agentId, String runDisplayName,
                                            String workspaceType, String workspaceKey) {
        requireOperationKey(operationKey);
        String digest = sha256(buildSubmitDigestPayload(taskId, agentId,
            runDisplayName, workspaceType, workspaceKey));

        // Check if already completed
        var completed = repository.findCompletedOperation("SUBMIT", operationKey, digest);
        if (completed.isPresent() && completed.get().taskId() != null
                && completed.get().taskId().equals(taskId)) {
            return replayAttempt(completed.get(), taskId, "SUBMIT");
        }

        // Check for digest conflict
        var existingDigest = repository.findOperationDigest("SUBMIT", operationKey);
        if (existingDigest.isPresent() && !existingDigest.get().equals(digest)) {
            throw new IllegalStateException(
                "SUBMIT operation key was reused with contradictory request");
        }

        // Reserve
        boolean reserved = repository.reserveOperation("SUBMIT", operationKey, digest);
        if (!reserved) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            var retryCompleted = repository.findCompletedOperation("SUBMIT", operationKey, digest);
            if (retryCompleted.isPresent() && retryCompleted.get().taskId() != null
                    && retryCompleted.get().taskId().equals(taskId)) {
                return replayAttempt(retryCompleted.get(), taskId, "SUBMIT");
            }
            throw new IllegalStateException("SUBMIT operation key is reserved by another process");
        }

        WorkTaskAttempt attempt = submit(taskId, agentId, runDisplayName,
            workspaceType, workspaceKey, operationKey);

        String resultJson = "{\"attemptId\":\"" + attempt.id()
            + "\",\"assignmentId\":\"" + attempt.assignmentId() + "\"}";
        int updated = repository.completeOperation("SUBMIT", operationKey, digest,
            taskId, resultJson);
        if (updated == 0) {
            log.warn("Failed to complete SUBMIT operation {} — may have been reserved by another process", operationKey);
        }

        return attempt;
    }

    // ── Retry ──

    /**
     * Retry the latest terminal assignment for a work task. Creates a lineage
     * successor assignment via {@link AssignmentService#retry} and appends a
     * new attempt with predecessor linkage.
     *
     * <p>Rejects retry when the current designated assignment is non-terminal.
     * Never mutates the predecessor assignment or transfers workspace/artifacts.
     *
     * @param taskId       the work task
     * @param operationKey optional idempotency key for the retry
     * @return the newly created successor attempt
     * @throws IllegalStateException if no attempts exist or the latest is non-terminal
     */
    @Transactional
    WorkTaskAttempt retry(String taskId, String operationKey) {
        WorkTaskDefinition task = getById(taskId);

        // Archive guard
        requireProjectNotArchived(task);

        // Get latest attempt
        var latest = repository.findLatestAttempt(taskId);
        if (latest.isEmpty()) {
            throw new IllegalStateException("No attempts exist for task: " + taskId);
        }
        WorkTaskAttempt latestAttempt = latest.get();

        // Get the assignment
        WorkAssignment assignment = requireAssignmentService().get(latestAttempt.assignmentId());

        // Verify terminal state
        OrchestrationStatus status = assignment.status();
        if (status != null && !status.isTerminal()) {
            throw new IllegalStateException(
                "Cannot retry: current assignment is not terminal (status: " + status + ")");
        }

        // Retry via AssignmentService
        WorkAssignment retried = requireAssignmentService().retry(
            assignment.agentId(), assignment.id(), operationKey);

        // Record new attempt with predecessor linkage
        return recordAttempt(taskId, retried.id(), latestAttempt.id());
    }

    /**
     * Idempotent retry using operation-key + canonical-digest pattern.
     */
    @Transactional
    public WorkTaskAttempt retryOperation(String operationKey, String taskId) {
        requireOperationKey(operationKey);
        String digest = sha256(buildRetryDigestPayload(taskId));

        // Check if already completed
        var completed = repository.findCompletedOperation("RETRY", operationKey, digest);
        if (completed.isPresent() && completed.get().taskId() != null
                && completed.get().taskId().equals(taskId)) {
            return replayAttempt(completed.get(), taskId, "RETRY");
        }

        // Check for digest conflict
        var existingDigest = repository.findOperationDigest("RETRY", operationKey);
        if (existingDigest.isPresent() && !existingDigest.get().equals(digest)) {
            throw new IllegalStateException(
                "RETRY operation key was reused with contradictory request");
        }

        // Reserve
        boolean reserved = repository.reserveOperation("RETRY", operationKey, digest);
        if (!reserved) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            var retryCompleted = repository.findCompletedOperation("RETRY", operationKey, digest);
            if (retryCompleted.isPresent() && retryCompleted.get().taskId() != null
                    && retryCompleted.get().taskId().equals(taskId)) {
                return replayAttempt(retryCompleted.get(), taskId, "RETRY");
            }
            throw new IllegalStateException("RETRY operation key is reserved by another process");
        }

        WorkTaskAttempt attempt = retry(taskId, operationKey);

        String resultJson = "{\"attemptId\":\"" + attempt.id()
            + "\",\"assignmentId\":\"" + attempt.assignmentId() + "\"}";
        int updated = repository.completeOperation("RETRY", operationKey, digest,
            taskId, resultJson);
        if (updated == 0) {
            log.warn("Failed to complete RETRY operation {} — may have been reserved by another process", operationKey);
        }

        return attempt;
    }

    // ── Attempts ──

    /**
     * Record a new execution attempt for a task. Each attempt links to
     * exactly one WorkAssignment. Attempt order is auto-incremented.
     */
    @Transactional
    WorkTaskAttempt recordAttempt(String taskId, String assignmentId,
                                          String predecessorAttemptId) {
        WorkTaskDefinition task = getById(taskId);

        // Archive guard: reject attempts on tasks bound to archived projects
        requireProjectNotArchived(task);

        int order = repository.nextAttemptOrder(taskId);
        String id = UUID.randomUUID().toString();

        WorkTaskAttempt attempt = new WorkTaskAttempt(
            id, taskId, assignmentId, order,
            StringUtils.hasText(predecessorAttemptId) ? predecessorAttemptId : null,
            Instant.now()
        );
        return repository.appendAndDesignateAttempt(attempt);
    }

    public List<WorkTaskAttempt> listAttempts(String taskId) {
        getById(taskId); // validate existence
        return repository.findAttemptsByTask(taskId);
    }

    // ── Operation idempotency (CREATE) ──

    /**
     * Reserve an operation for idempotent task creation. Returns true if
     * the reservation was new; false if the operation key already existed.
     */
    boolean reserveCreateOperation(String operationKey, String title,
                                           String description, String projectId,
                                           boolean pin, int defaultPriority,
                                           String preferredAgentId) {
        String digest = sha256(buildCreateDigestPayload(title, description, projectId,
            pin, defaultPriority, preferredAgentId));
        return repository.reserveOperation("CREATE", operationKey, digest);
    }

    /**
     * Complete a previously reserved create operation with the resulting task id.
     */
    @Transactional
    WorkTaskDefinition completeCreateOperation(String operationKey,
                                                       String title, String description,
                                                       String projectId, boolean pin,
                                                       int defaultPriority,
                                                       String preferredAgentId) {
        String digest = sha256(buildCreateDigestPayload(title, description, projectId,
            pin, defaultPriority, preferredAgentId));

        // Check if already completed
        var existing = repository.findCompletedTaskId("CREATE", operationKey, digest);
        if (existing.isPresent() && existing.get() != null) {
            return getById(existing.get());
        }

        WorkTaskDefinition task = create(title, description, projectId, pin,
            defaultPriority, preferredAgentId);

        int updated = repository.completeOperation("CREATE", operationKey, digest,
            task.id(), null);
        if (updated == 0) {
            log.warn("Failed to complete CREATE operation {} — may have been reserved by another process", operationKey);
        }

        return task;
    }

    private WorkTaskAttempt replayAttempt(WorkTaskRepository.CompletedOperation operation,
                                          String taskId, String operationType) {
        Matcher matcher = ATTEMPT_ID_RESULT.matcher(
            operation.resultJson() == null ? "" : operation.resultJson());
        if (!matcher.find()) {
            throw new IllegalStateException(operationType
                + " operation completed without a recorded attempt result");
        }
        return repository.findAttemptById(matcher.group(1))
            .filter(attempt -> attempt.taskId().equals(taskId))
            .orElseThrow(() -> new IllegalStateException(operationType
                + " operation completed with an unavailable attempt result"));
    }

    // ── Guard helpers ──

    /**
     * Require that a task's bound project (if any) is not archived.
     */
    private void requireProjectNotArchived(WorkTaskDefinition task) {
        if (task.projectId() != null) {
            requireProjectService().requireActiveProject(task.projectId());
        }
    }

    /**
     * Validate that pin=true only when a projectId is set.
     */
    private void validatePin(boolean pin, String projectId) {
        if (pin && !StringUtils.hasText(projectId)) {
            throw new IllegalArgumentException(
                "Pin requires an existing project binding");
        }
    }

    /**
     * Validate the preferred agent: project-bound tasks require the agent
     * to be a current project member; unbound tasks require the named
     * agent to be eligible (exists and is not disabled).
     */
    private void validatePreferredAgent(String preferredAgentId, String projectId) {
        if (!StringUtils.hasText(preferredAgentId)) {
            return;
        }

        if (StringUtils.hasText(projectId)) {
            // Project-bound: require current member
            requireProjectService().requireActiveProjectWithMember(projectId, preferredAgentId);
        } else {
            // Unbound: require agent eligibility
            if (agentProfileService != null) {
                AgentProfile agent = agentProfileService.get(preferredAgentId);
                if (agent.status() == AgentProfileStatus.DISABLED) {
                    throw new IllegalArgumentException(
                        "Preferred agent is disabled: " + preferredAgentId);
                }
            }
        }
    }

    /**
     * Validate and normalize the title field.
     */
    private String requireTitle(String title) {
        String normalized = StringUtils.hasText(title) ? title.strip() : null;
        if (normalized == null || normalized.isBlank()) {
            throw new IllegalArgumentException("Task title is required");
        }
        return normalized;
    }

    // ── Service helpers ──

    private ProjectService requireProjectService() {
        if (projectService == null) {
            throw new IllegalStateException("Project service is unavailable");
        }
        return projectService;
    }

    private void requireOperationKey(String operationKey) {
        if (!StringUtils.hasText(operationKey)) {
            throw new IllegalArgumentException("operationKey is required");
        }
    }

    private AssignmentService requireAssignmentService() {
        if (assignmentService == null) {
            throw new IllegalStateException("AssignmentService is unavailable");
        }
        return assignmentService;
    }

    // ── Digest helpers ──

    private static String sha256(String input) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String buildCreateDigestPayload(String title, String description,
                                                     String projectId, boolean pin,
                                                     int defaultPriority,
                                                     String preferredAgentId) {
        return "CREATE|" + (title != null ? title : "") + "|"
            + (description != null ? description : "") + "|"
            + (projectId != null ? projectId : "") + "|"
            + pin + "|" + defaultPriority + "|"
            + (preferredAgentId != null ? preferredAgentId : "");
    }

    private static String buildEditDigestPayload(String taskId, int expectedVersion,
                                                   String title, String description,
                                                   Integer defaultPriority, Boolean pin,
                                                   String preferredAgentId) {
        return "EDIT|" + taskId + "|" + expectedVersion + "|"
            + (title != null ? title : "") + "|"
            + (description != null ? description : "") + "|"
            + defaultPriority + "|" + pin + "|"
            + (preferredAgentId != null ? preferredAgentId : "");
    }

    private static String buildBindDigestPayload(String taskId, String projectId) {
        return "BIND|" + taskId + "|" + (projectId != null ? projectId : "");
    }

    private static String buildPromoteDigestPayload(String taskId, int expectedVersion) {
        return "PROMOTE|" + taskId + "|" + expectedVersion;
    }

    private static String buildSubmitDigestPayload(String taskId, String agentId,
                                                     String runDisplayName,
                                                     String workspaceType,
                                                     String workspaceKey) {
        return "SUBMIT|" + taskId + "|" + (agentId != null ? agentId : "") + "|"
            + (runDisplayName != null ? runDisplayName : "") + "|"
            + (workspaceType != null ? workspaceType : "") + "|"
            + (workspaceKey != null ? workspaceKey : "");
    }

    private static String buildRetryDigestPayload(String taskId) {
        return "RETRY|" + taskId;
    }
}
