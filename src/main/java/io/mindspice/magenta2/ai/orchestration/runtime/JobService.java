package io.mindspice.magenta2.ai.orchestration.runtime;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.Workspace;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Manages job definitions, runs, and recurrence scheduling.
 * Jobs coordinate task-like plan/workflow work items. Legacy persistent
 * workspace fields are retained for compatibility only and are not allocated
 * by active runtime paths.
 */
@Service
public class JobService {
    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final PlanService planService;
    private final WorkflowService workflowService;
    private final OrchestrationRuntimeRepository runtimeRepository;
    private final ObjectProvider<AssignmentService> assignmentServiceProvider;
    private final AgentProfileService agentProfileService;
    private final ProjectService projectService;
    private final OutputArtifactService outputArtifactService;
    private final RootRelativePathService rootRelativePathService;

    public JobService(JobRepository jobRepository,
                      WorkspaceDirectoryService workspaceDirectoryService,
                      PlanService planService,
                      WorkflowService workflowService) {
        this(jobRepository, workspaceDirectoryService, planService, workflowService, null);
    }

    public JobService(JobRepository jobRepository,
                      WorkspaceDirectoryService workspaceDirectoryService,
                      PlanService planService,
                      WorkflowService workflowService,
                      OrchestrationRuntimeRepository runtimeRepository) {
        this(jobRepository, workspaceDirectoryService, planService, workflowService,
            runtimeRepository, null, null, null, null, null);
    }

    @Autowired
    public JobService(JobRepository jobRepository,
                      WorkspaceDirectoryService workspaceDirectoryService,
                      PlanService planService,
                      WorkflowService workflowService,
                      @Autowired(required = false) OrchestrationRuntimeRepository runtimeRepository,
                      @Autowired(required = false) ObjectProvider<AssignmentService> assignmentServiceProvider,
                      @Autowired(required = false) AgentProfileService agentProfileService,
                      @Autowired(required = false) ProjectService projectService,
                      @Autowired(required = false) OutputArtifactService outputArtifactService,
                      @Autowired(required = false) RootRelativePathService rootRelativePathService) {
        this.jobRepository = jobRepository;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.planService = planService;
        this.workflowService = workflowService;
        this.runtimeRepository = runtimeRepository;
        this.assignmentServiceProvider = assignmentServiceProvider;
        this.agentProfileService = agentProfileService;
        this.projectService = projectService;
        this.outputArtifactService = outputArtifactService;
        this.rootRelativePathService = rootRelativePathService != null
            ? rootRelativePathService
            : workspaceDirectoryService == null ? null : new RootRelativePathService(workspaceDirectoryService);
    }

    // ════════════════════════════════════════════════════════════════
    //  Definition CRUD
    // ════════════════════════════════════════════════════════════════

    public List<JobDefinition> listDefinitions() {
        return jobRepository.findAllDefinitions();
    }

    public List<JobDefinition> listDefinitions(String agentId, String projectId, String status) {
        if (!StringUtils.hasText(agentId) && !StringUtils.hasText(projectId) && !StringUtils.hasText(status)) {
            return listDefinitions();
        }
        return jobRepository.findDefinitions(agentId, projectId, status);
    }

    public JobDefinition getDefinition(String id) {
        return jobRepository.findDefinition(id)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + id));
    }

    public JobDefinition saveDefinition(JobDefinition def) {
        String id = StringUtils.hasText(def.id()) ? def.id() : UUID.randomUUID().toString();
        if (!StringUtils.hasText(def.title())) {
            throw new IllegalArgumentException("Job title is required");
        }
        List<JobWorkItem> items = def.items() == null ? List.of() : def.items();
        // Validate items reference valid plan or workflow ids
        for (JobWorkItem item : items) {
            if (item.type() == JobWorkItemType.PLAN && !StringUtils.hasText(item.planId())) {
                throw new IllegalArgumentException(
                    "Work item '" + item.key() + "' has type PLAN but no planId");
            }
            if (item.type() == JobWorkItemType.WORKFLOW && !StringUtils.hasText(item.workflowId())) {
                throw new IllegalArgumentException(
                    "Work item '" + item.key() + "' has type WORKFLOW but no workflowId");
            }
        }
        Instant now = Instant.now();
        Instant createdAt = def.createdAt() == null ? now : def.createdAt();
        jobRepository.findDefinition(id)
            .filter(existing -> jobExecutionAffectingChanged(existing, def, items))
            .ifPresent(existing -> requireJobExecutionMutationAllowed(id));
        return jobRepository.saveDefinition(new JobDefinition(
            id,
            normalize(def.ownerAgentId()),
            normalize(def.projectId()),
            normalize(def.workspaceType()),
            normalize(def.workspaceKey()),
            null,
            StringUtils.hasText(def.status()) ? def.status().trim() : "DRAFT",
            def.title(), def.summary(),
            orderedItems(items),
            def.promptProfile(), def.model(), def.settingsOverrideJson(),
            createdAt, def.updatedAt()
        ));
    }

    public List<JobWorkItem> listItems(String jobId) {
        return getDefinition(jobId).items();
    }

    public JobWorkItem addItem(String jobId, JobWorkItem item) {
        requireJobExecutionMutationAllowed(jobId);
        JobDefinition definition = getDefinition(jobId);
        JobWorkItem normalized = normalizeItem(item, definition.items().size());
        List<JobWorkItem> items = new ArrayList<>(definition.items());
        items.add(normalized);
        saveDefinition(withItems(definition, items));
        return normalized;
    }

    public JobWorkItem updateItem(String jobId, String itemKey, JobWorkItem item) {
        requireJobExecutionMutationAllowed(jobId);
        JobDefinition definition = getDefinition(jobId);
        List<JobWorkItem> items = new ArrayList<>(definition.items());
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key().equals(itemKey)) {
                JobWorkItem normalized = normalizeItem(item, i);
                items.set(i, normalized);
                saveDefinition(withItems(definition, items));
                return normalized;
            }
        }
        throw new IllegalArgumentException("Work item not found: " + itemKey);
    }

    public void deleteItem(String jobId, String itemKey) {
        requireJobExecutionMutationAllowed(jobId);
        JobDefinition definition = getDefinition(jobId);
        List<JobWorkItem> items = new ArrayList<>(definition.items());
        boolean removed = items.removeIf(item -> item.key().equals(itemKey));
        if (!removed) {
            throw new IllegalArgumentException("Work item not found: " + itemKey);
        }
        saveDefinition(withItems(definition, items));
    }

    /**
     * Update a job definition's status. Used by the runner to synchronize
     * definition status with run lifecycle.
     */
    public void updateDefinitionStatus(String jobId, String status) {
        JobDefinition def = getDefinition(jobId);
        saveDefinition(new JobDefinition(
            def.id(), def.ownerAgentId(), def.projectId(), def.workspaceType(), def.workspaceKey(),
            def.workspaceIdentity(), status, def.title(), def.summary(), def.items(), def.promptProfile(),
            def.model(), def.settingsOverrideJson(), def.createdAt(), def.updatedAt()
        ));
    }

    public void deleteDefinition(String id) {
        requireJobDeletionAllowed(id);
        jobRepository.deleteRecurrence(id);
        jobRepository.deleteDefinition(id);
    }

    // ════════════════════════════════════════════════════════════════
    //  Runs
    // ════════════════════════════════════════════════════════════════

    /**
     * Direct job run allocation is intentionally not available to public or
     * scheduler paths. Create a JOB_RUN assignment and let the runner allocate
     * the run after it owns the assignment lease.
     */
    public JobRun startRun(String jobId) {
        throw new IllegalStateException("Job runs must be started from an assignment context");
    }

    public JobRun startRun(String jobId, String agentId, String projectId, String jobAssignmentId) {
        if (!StringUtils.hasText(jobAssignmentId)) {
            throw new IllegalStateException("Job runs require an assignment context");
        }
        JobDefinition def = getDefinition(jobId);
        String assignmentKey = normalize(jobAssignmentId);
        JobRun existingRun = jobRepository.findRunByAssignmentId(assignmentKey).orElse(null);
        if (existingRun != null) {
            if (!def.id().equals(existingRun.jobId())) {
                throw new IllegalStateException("Assignment already owns a run for a different job: " + assignmentKey);
            }
            return existingRun;
        }
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        String workspaceType = context == null ? def.workspaceType() : context.workspaceType();
        String workspaceKey = context == null ? def.workspaceKey() : context.workspaceKey();
        if (!StringUtils.hasText(workspaceType) && StringUtils.hasText(agentId)) {
            workspaceType = "agent";
            workspaceKey = agentId.trim();
        }
        if ((StringUtils.hasText(workspaceType)) != (StringUtils.hasText(workspaceKey))) {
            throw new IllegalStateException("Job run has an incomplete workspace selection");
        }
        String workspaceIdentity = StringUtils.hasText(workspaceType)
            ? workspaceType + "/" + workspaceKey : null;
        Instant now = Instant.now();
        List<JobWorkItemRun> itemRuns = def.items().stream()
            .map(item -> new JobWorkItemRun(item.key(), item.type().name(), item.planId(), item.workflowId(),
                "QUEUED", null, Map.of(), Map.of(), null, null, null))
            .toList();
        return jobRepository.saveRun(new JobRun(
            UUID.randomUUID().toString(), jobId, assignmentKey,
            workspaceType, workspaceKey, workspaceIdentity, JobRunStatus.QUEUED,
            itemRuns, null, null, now, now, null, null
        ));
    }

    /**
     * Transitions a job run from QUEUED to RUNNING.
     */
    public JobRun markRunning(String runId) {
        JobRun run = getRun(runId);
        if (run.status() != JobRunStatus.QUEUED) {
            throw new IllegalStateException("Job run must be QUEUED to start: " + runId);
        }
        return jobRepository.saveRun(new JobRun(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), JobRunStatus.RUNNING, run.workItemRuns(),
            run.finalMessage(), run.errorText(),
            run.createdAt(), Instant.now(), Instant.now(), run.completedAt()
        ));
    }

    /**
     * Updates the status of a single work item within the job run.
     */
    public JobRun updateWorkItemRun(String jobRunId, String itemKey,
                                     String status, String itemRunId,
                                     Map<String, Object> outputValues, String errorText) {
        JobRun run = getRun(jobRunId);
        List<JobWorkItemRun> updated = new ArrayList<>(run.workItemRuns());
        boolean found = false;
        for (int i = 0; i < updated.size(); i++) {
            if (updated.get(i).key().equals(itemKey)) {
                JobWorkItemRun existing = updated.get(i);
                updated.set(i, new JobWorkItemRun(
                    existing.key(), existing.type(),
                    existing.planId(), existing.workflowId(),
                    status, itemRunId != null ? itemRunId : existing.runId(),
                    existing.inputValues(),
                    outputValues != null ? outputValues : existing.outputValues(),
                    errorText != null ? errorText : existing.errorText(),
                    "RUNNING".equals(status) && existing.startedAt() == null ? Instant.now() : existing.startedAt(),
                    isTerminalStatus(status) ? Instant.now() : existing.completedAt()
                ));
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("Work item not found in job run: " + itemKey);
        }

        // Check if all items are terminal → complete the job run
        JobRunStatus newStatus = run.status();
        if (allTerminal(updated)) {
            boolean anyFailed = updated.stream().anyMatch(wi -> "FAILED".equals(wi.status()));
            newStatus = anyFailed ? JobRunStatus.FAILED : JobRunStatus.COMPLETED;
        }

        return jobRepository.saveRun(new JobRun(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), newStatus, updated,
            newStatus.isTerminal() ? "Job run " + newStatus.name().toLowerCase() : run.finalMessage(),
            run.errorText(),
            run.createdAt(), Instant.now(),
            run.startedAt(),
            newStatus.isTerminal() ? Instant.now() : run.completedAt()
        ));
    }

    public JobRun failRun(String runId, String errorText) {
        JobRun run = getRun(runId);
        return jobRepository.saveRun(new JobRun(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), JobRunStatus.FAILED, run.workItemRuns(), run.finalMessage(), errorText,
            run.createdAt(), Instant.now(),
            run.startedAt(), Instant.now()
        ));
    }

    public JobRun completeRun(String runId, String finalMessage) {
        JobRun run = getRun(runId);
        if (run.status().isTerminal()) {
            return run;
        }
        return jobRepository.saveRun(new JobRun(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), JobRunStatus.COMPLETED, run.workItemRuns(),
            StringUtils.hasText(finalMessage) ? finalMessage : "Job run completed",
            run.errorText(),
            run.createdAt(), Instant.now(),
            run.startedAt(), Instant.now()
        ));
    }

    public JobRun cancelRun(String runId) {
        JobRun run = getRun(runId);
        if (run.status().isTerminal()) {
            throw new IllegalStateException("Cannot cancel terminal job run: " + runId);
        }
        if (StringUtils.hasText(run.jobAssignmentId()) && runtimeRepository != null) {
            WorkAssignment assignment = runtimeRepository.findAssignment(run.jobAssignmentId()).orElse(null);
            AssignmentService assignmentService = assignmentServiceProvider == null
                ? null
                : assignmentServiceProvider.getIfAvailable();
            if (assignment != null && assignmentService != null && !assignment.status().isTerminal()) {
                assignmentService.cancel(assignment.agentId(), assignment.id());
            }
        }
        return cancelRunFromAssignment(runId);
    }

    JobRun cancelRunFromAssignment(String runId) {
        JobRun run = getRun(runId);
        if (run.status().isTerminal()) {
            return run;
        }
        return jobRepository.saveRun(new JobRun(
            run.id(), run.jobId(), run.jobAssignmentId(), run.workspaceType(), run.workspaceKey(),
            run.workspaceIdentity(), JobRunStatus.CANCELLED, run.workItemRuns(), "Cancelled", run.errorText(),
            run.createdAt(), Instant.now(),
            run.startedAt(), Instant.now()
        ));
    }

    public JobRun getRun(String runId) {
        return jobRepository.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Job run not found: " + runId));
    }

    public List<JobRun> listRuns(String jobId) {
        return jobRepository.findRunsByJobId(jobId);
    }

    public List<JobExecutionSummary> executionSummaries(String jobId) {
        JobDefinition job = getDefinition(jobId);
        List<WorkAssignment> assignments = runtimeRepository == null
            ? List.of()
            : runtimeRepository.findAssignmentsForJob(jobId);
        Map<String, JobRun> runsByAssignment = new java.util.LinkedHashMap<>();
        List<JobRun> unassignedRuns = new ArrayList<>();
        for (JobRun run : sortedRuns(jobId)) {
            if (StringUtils.hasText(run.jobAssignmentId())) {
                runsByAssignment.putIfAbsent(run.jobAssignmentId(), run);
            } else {
                unassignedRuns.add(run);
            }
        }
        List<JobExecutionSummary> summaries = new ArrayList<>();
        for (WorkAssignment assignment : assignments) {
            summaries.add(executionSummary(job, assignment, runsByAssignment.get(assignment.id())));
        }
        for (JobRun run : unassignedRuns) {
            summaries.add(executionSummary(job, null, run));
        }
        summaries.sort(Comparator.comparing(JobExecutionSummary::queuedAt,
            Comparator.nullsLast(Comparator.reverseOrder())));
        return summaries;
    }

    public Optional<JobExecutionSummary> executionSummaryByAssignmentId(String assignmentId) {
        WorkAssignment assignment = runtimeRepository == null
            ? null
            : runtimeRepository.findAssignment(assignmentId).orElse(null);
        JobRun run = assignment != null && StringUtils.hasText(assignment.jobId())
            ? sortedRuns(assignment.jobId()).stream()
                .filter(candidate -> assignmentId.equals(candidate.jobAssignmentId()))
                .findFirst()
                .orElse(null)
            : jobRepository.findRunByAssignmentId(assignmentId).orElse(null);
        String jobId = assignment != null ? assignment.jobId() : run == null ? null : run.jobId();
        if (!StringUtils.hasText(jobId)) {
            return Optional.empty();
        }
        return Optional.of(executionSummary(getDefinition(jobId), assignment, run));
    }

    public Optional<JobExecutionSummary> latestExecutionSummary(String jobId) {
        return executionSummaries(jobId).stream().findFirst();
    }

    public List<String> outputRunIds(String jobId) {
        return listRuns(jobId).stream()
            .flatMap(run -> java.util.stream.Stream.concat(
                java.util.stream.Stream.of(run.id()),
                run.workItemRuns().stream().map(JobWorkItemRun::runId)
            ))
            .filter(runId -> runId != null && !runId.isBlank())
            .toList();
    }

    private List<JobRun> sortedRuns(String jobId) {
        return listRuns(jobId).stream()
            .sorted(Comparator
                .comparing(JobRun::createdAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(JobRun::id, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private JobExecutionSummary executionSummary(JobDefinition job, WorkAssignment assignment, JobRun run) {
        String agentId = firstText(
            assignment == null ? null : assignment.agentId(),
            job.ownerAgentId()
        );
        AgentProfile agent = agent(agentId);
        String projectId = firstText(
            assignment == null ? null : assignment.projectId(),
            job.projectId()
        );
        Project project = project(projectId);
        String workspaceType = firstText(
            assignment == null ? null : assignment.workspaceType(),
            run == null ? null : run.workspaceType(), job.workspaceType(),
            StringUtils.hasText(agentId) ? "agent" : null);
        String workspaceKey = firstText(
            assignment == null ? null : assignment.workspaceKey(),
            run == null ? null : run.workspaceKey(), job.workspaceKey(), agentId);
        String workspaceIdentity = StringUtils.hasText(workspaceType) && StringUtils.hasText(workspaceKey)
            ? workspaceType + "/" + workspaceKey : null;
        OutputStats outputStats = outputStats(run);
        return new JobExecutionSummary(
            job.id(),
            job.title(),
            job.status(),
            assignment == null ? run == null ? null : run.jobAssignmentId() : assignment.id(),
            assignment == null ? null : assignment.status(),
            assignment == null ? null : assignment.assignmentType(),
            assignment == null ? 0 : assignment.priority(),
            assignment == null ? null : assignment.modelOverride(),
            agentId,
            agent == null ? null : agent.name(),
            agent == null || agent.status() == null ? null : agent.status().name(),
            projectId,
            project == null ? null : project.name(),
            workspaceType,
            workspaceKey,
            workspaceIdentity,
            run == null ? null : run.id(),
            run == null ? null : run.status(),
            childRunIds(run),
            outputStats.count(),
            outputStats.latestAt(),
            firstInstant(assignment == null ? null : assignment.createdAt(), run == null ? null : run.createdAt()),
            firstInstant(assignment == null ? null : assignment.startedAt(), run == null ? null : run.startedAt()),
            firstInstant(assignment == null ? null : assignment.completedAt(), run == null ? null : run.completedAt()),
            latestInstant(assignment == null ? null : assignment.updatedAt(), run == null ? null : run.updatedAt())
        );
    }

    private AgentProfile agent(String agentId) {
        if (!StringUtils.hasText(agentId) || agentProfileService == null) {
            return null;
        }
        try {
            return agentProfileService.get(agentId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Project project(String projectId) {
        if (!StringUtils.hasText(projectId) || projectService == null) {
            return null;
        }
        try {
            return projectService.getProject(projectId);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<String> childRunIds(JobRun run) {
        if (run == null || run.workItemRuns() == null) {
            return List.of();
        }
        return run.workItemRuns().stream()
            .map(JobWorkItemRun::runId)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private OutputStats outputStats(JobRun run) {
        if (run == null || outputArtifactService == null) {
            return new OutputStats(0, null);
        }
        LinkedHashSet<String> runIds = new LinkedHashSet<>();
        if (StringUtils.hasText(run.id())) {
            runIds.add(run.id());
        }
        if (run.workItemRuns() != null) {
            run.workItemRuns().stream()
                .map(JobWorkItemRun::runId)
                .filter(StringUtils::hasText)
                .forEach(runIds::add);
        }
        int count = 0;
        Instant latest = null;
        for (String runId : runIds) {
            for (RunOutputArtifact artifact : outputArtifactService.artifactsForRun(runId)) {
                count++;
                latest = latestInstant(latest, artifact.createdAt());
            }
        }
        return new OutputStats(count, latest);
    }

    // ════════════════════════════════════════════════════════════════
    //  Recurrence
    // ════════════════════════════════════════════════════════════════

    /**
     * Sets or updates a cron-based recurrence for a job.
     * A JOB_RUN assignment is enqueued each time the cron fires.
     */
    public JobRecurrence setRecurrence(String jobId, String cronExpression,
                                        String timezone, Instant nextFireTime) {
        getDefinition(jobId); // validate exists
        requireJobExecutionMutationAllowed(jobId);
        if (!StringUtils.hasText(cronExpression)) {
            throw new IllegalArgumentException("cronExpression is required");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        JobRecurrence existing = jobRepository.findRecurrence(jobId).orElse(null);
        if (existing != null) {
            id = existing.id();
            now = existing.createdAt();
        }
        return jobRepository.saveRecurrence(new JobRecurrence(
            id, jobId, cronExpression,
            StringUtils.hasText(timezone) ? timezone : "UTC",
            nextFireTime, true, now, Instant.now()
        ));
    }

    public Optional<JobRecurrence> getRecurrence(String jobId) {
        return jobRepository.findRecurrence(jobId);
    }

    public void disableRecurrence(String jobId) {
        requireJobExecutionMutationAllowed(jobId);
        JobRecurrence existing = jobRepository.findRecurrence(jobId)
            .orElseThrow(() -> new IllegalArgumentException("No recurrence for job: " + jobId));
        jobRepository.saveRecurrence(new JobRecurrence(
            existing.id(), existing.jobId(),
            existing.cronExpression(), existing.timezone(),
            existing.nextFireTime(), false,
            existing.createdAt(), Instant.now()
        ));
    }

    /**
     * Finds recurrences due by the given time and enqueues a JOB_RUN assignment
     * for each due recurrence.
     */
    public List<WorkAssignment> fireDueRecurrences(Instant before) {
        List<JobRecurrence> dueList = jobRepository.findDueRecurrences(before);
        List<WorkAssignment> assignments = new ArrayList<>();
        AssignmentService assignmentService = assignmentServiceProvider == null
            ? null
            : assignmentServiceProvider.getIfAvailable();
        if (assignmentService == null) {
            throw new IllegalStateException("Job recurrence firing requires assignment services");
        }
        for (JobRecurrence rec : dueList) {
            try {
                JobDefinition job = getDefinition(rec.jobId());
                WorkAssignment assignment = assignmentService.create(new AssignmentRequest(
                    firstText(job.ownerAgentId(), "system"),
                    job.id(),
                    null,
                    AssignmentType.JOB_RUN,
                    0,
                    job.model(),
                    job.projectId(),
                    null,
                    null,
                    Map.of("jobId", job.id(), "recurrenceId", rec.id())
                ));
                assignments.add(assignment);
                jobRepository.saveRecurrence(new JobRecurrence(
                    rec.id(), rec.jobId(), rec.cronExpression(), rec.timezone(),
                    nextFireTime(rec), rec.enabled(), rec.createdAt(), Instant.now()
                ));
            } catch (Exception e) {
                log.error("Failed to fire recurrence for job={}: {}", rec.jobId(), e.getMessage());
            }
        }
        return assignments;
    }

    // ── Helpers ──

    private List<JobWorkItem> orderedItems(List<JobWorkItem> items) {
        List<JobWorkItem> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Integer.compare(a.order(), b.order()));
        return List.copyOf(sorted);
    }

    private JobDefinition withItems(JobDefinition definition, List<JobWorkItem> items) {
        return new JobDefinition(
            definition.id(), definition.ownerAgentId(), definition.projectId(),
            definition.workspaceType(), definition.workspaceKey(), definition.workspaceIdentity(),
            definition.status(), definition.title(),
            definition.summary(), items, definition.promptProfile(), definition.model(),
            definition.settingsOverrideJson(), definition.createdAt(), definition.updatedAt()
        );
    }

    private JobWorkItem normalizeItem(JobWorkItem item, int fallbackOrder) {
        if (item == null) {
            throw new IllegalArgumentException("Job item is required");
        }
        JobWorkItemType type = item.type();
        if (type == null) {
            throw new IllegalArgumentException("Job item type is required");
        }
        if (type == JobWorkItemType.PLAN && !StringUtils.hasText(item.planId())) {
            throw new IllegalArgumentException("Job PLAN item requires planId");
        }
        if (type == JobWorkItemType.WORKFLOW && !StringUtils.hasText(item.workflowId())) {
            throw new IllegalArgumentException("Job WORKFLOW item requires workflowId");
        }
        // Validate referenced plan/workflow exists
        if (type == JobWorkItemType.PLAN && planService != null) {
            planService.getTask(item.planId());
        }
        if (type == JobWorkItemType.WORKFLOW && workflowService != null) {
            workflowService.getWorkflow(item.workflowId());
        }
        String key = StringUtils.hasText(item.key()) ? item.key().trim() : UUID.randomUUID().toString();
        int order = item.order() > 0 ? item.order() : fallbackOrder + 1;
        return new JobWorkItem(
            key, type, normalize(item.planId()), normalize(item.workflowId()),
            item.inputBindings() == null ? Map.of() : item.inputBindings(),
            order, normalize(item.modelOverride()), item.priority()
        );
    }

    private boolean allTerminal(List<JobWorkItemRun> items) {
        return items.stream().allMatch(wi -> isTerminalStatus(wi.status()));
    }

    private boolean isTerminalStatus(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status)
            || "CANCELLED".equals(status);
    }

    public void requireJobDeletionAllowed(String jobId) {
        if (hasActiveJobWork(jobId)) {
            throw new IllegalStateException("Job has active assignments or runs and cannot be deleted: " + jobId);
        }
    }

    public void requireJobExecutionMutationAllowed(String jobId) {
        if (hasActiveJobWork(jobId)) {
            throw new IllegalStateException(
                "Job has active assignments or runs; execution-affecting edits must wait for active work to finish: "
                + jobId);
        }
    }

    public boolean hasActiveJobWork(String jobId) {
        long activeAssignments = runtimeRepository == null ? 0 : runtimeRepository.countActiveAssignmentsForJob(jobId);
        long activeRuns = jobRepository.countActiveRunsByJobId(jobId);
        return activeAssignments > 0 || activeRuns > 0;
    }

    private boolean jobExecutionAffectingChanged(JobDefinition existing, JobDefinition incoming, List<JobWorkItem> incomingItems) {
        return !same(existing.ownerAgentId(), incoming.ownerAgentId())
            || !same(existing.projectId(), incoming.projectId())
            || !same(existing.workspaceType(), incoming.workspaceType())
            || !same(existing.workspaceKey(), incoming.workspaceKey())
            || !same(existing.promptProfile(), incoming.promptProfile())
            || !same(existing.model(), incoming.model())
            || !same(existing.settingsOverrideJson(), incoming.settingsOverrideJson())
            || !existing.items().equals(orderedItems(incomingItems));
    }

    private boolean same(String left, String right) {
        return java.util.Objects.equals(normalize(left), normalize(right));
    }

    private String slug(String title) {
        if (!StringUtils.hasText(title)) return "run";
        String s = title.toLowerCase().replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (s.length() > 48) s = s.substring(0, 48).replaceAll("-$", "");
        return s.isEmpty() ? "run" : s;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Instant nextFireTime(JobRecurrence recurrence) {
        CronExpression cron = CronExpression.parse(cronExpression(recurrence.cronExpression()));
        ZoneId zoneId = StringUtils.hasText(recurrence.timezone())
            ? ZoneId.of(recurrence.timezone().trim())
            : ZoneId.systemDefault();
        Instant after = recurrence.nextFireTime() == null ? Instant.now() : recurrence.nextFireTime();
        ZonedDateTime next = cron.next(ZonedDateTime.ofInstant(after.plusMillis(1), zoneId));
        if (next == null) {
            return null;
        }
        return next.toInstant();
    }

    private String cronExpression(String value) {
        String expression = value.trim();
        return expression.split("\\s+").length == 5 ? "0 " + expression : expression;
    }

    private Instant firstInstant(Instant... values) {
        if (values == null) {
            return null;
        }
        for (Instant value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Instant latestInstant(Instant... values) {
        Instant latest = null;
        if (values == null) {
            return null;
        }
        for (Instant value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (StringUtils.hasText(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private record OutputStats(int count, Instant latestAt) {
    }
}
