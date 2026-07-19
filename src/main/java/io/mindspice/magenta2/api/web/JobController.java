package io.mindspice.magenta2.api.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobExecutionSummary;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRecurrence;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRun;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobWorkItem;
import io.mindspice.magenta2.ai.orchestration.runtime.JobWorkItemType;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class JobController {
    private static final int PUBLIC_SUBMIT_PRIORITY = 9;

    private final JobService jobService;
    private final OutputArtifactService outputArtifactService;
    private final AssignmentService assignmentService;
    private final AgentProfileService agentProfileService;

    public JobController(JobService jobService, OutputArtifactService outputArtifactService) {
        this(jobService, outputArtifactService, null, null);
    }

    @Autowired
    public JobController(JobService jobService, OutputArtifactService outputArtifactService,
                         AssignmentService assignmentService, AgentProfileService agentProfileService) {
        this.jobService = jobService;
        this.outputArtifactService = outputArtifactService;
        this.assignmentService = assignmentService;
        this.agentProfileService = agentProfileService;
    }

    // ════════════════════════════════════════════════════════════════
    //  Job Definitions
    // ════════════════════════════════════════════════════════════════

    @GetMapping("/api/jobs")
    public List<JobDefinition> list(@RequestParam(required = false) String agentId,
                                    @RequestParam(required = false) String projectId,
                                    @RequestParam(required = false) String status) {
        return jobService.listDefinitions(agentId, projectId, status);
    }

    @PostMapping("/api/jobs")
    public JobDefinition create(@RequestBody JobDefinition definition) {
        try {
            return jobService.saveDefinition(definition);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/api/jobs/{jobId}")
    public JobDefinition get(@PathVariable String jobId) {
        try {
            return jobService.getDefinition(jobId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PutMapping("/api/jobs/{jobId}")
    public JobDefinition update(@PathVariable String jobId,
                                 @RequestBody JobDefinition definition) {
        try {
            return jobService.saveDefinition(new JobDefinition(
                jobId, definition.ownerAgentId(), definition.projectId(),
                definition.workspaceType(), definition.workspaceKey(), definition.workspaceIdentity(), definition.status(),
                definition.title(), definition.summary(), definition.items(), definition.promptProfile(),
                definition.model(), definition.settingsOverrideJson(), definition.createdAt(), definition.updatedAt()
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/api/jobs/{jobId}")
    public void delete(@PathVariable String jobId) {
        try {
            jobService.deleteDefinition(jobId);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/api/jobs/{jobId}/items")
    public List<JobWorkItem> items(@PathVariable String jobId) {
        try {
            return jobService.listItems(jobId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/api/jobs/{jobId}/items")
    public JobWorkItem addItem(@PathVariable String jobId, @RequestBody JobItemRequest request) {
        try {
            return jobService.addItem(jobId, request.toItem(null));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PutMapping("/api/jobs/{jobId}/items/{itemId}")
    public JobWorkItem updateItem(@PathVariable String jobId,
                                  @PathVariable String itemId,
                                  @RequestBody JobItemRequest request) {
        try {
            return jobService.updateItem(jobId, itemId, request.toItem(itemId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @DeleteMapping("/api/jobs/{jobId}/items/{itemId}")
    public void deleteItem(@PathVariable String jobId, @PathVariable String itemId) {
        try {
            jobService.deleteItem(jobId, itemId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Job Runs
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/jobs/{jobId}/runs")
    public WorkAssignment startRun(@PathVariable String jobId, @RequestBody(required = false) JobRunRequest request) {
        try {
            requireSubmissionServices();
            JobDefinition job = jobService.getDefinition(jobId);
            return assignmentService.create(new AssignmentRequest(
                resolveAgentId(request == null ? null : request.agentId(), job),
                jobId,
                null,
                AssignmentType.JOB_RUN,
                request == null || request.priority() == null ? PUBLIC_SUBMIT_PRIORITY : request.priority(),
                normalize(request == null ? null : request.modelOverride(), job.model()),
                normalize(request == null ? null : request.projectId(), job.projectId()),
                normalize(request == null ? null : request.workspaceType(), null),
                normalize(request == null ? null : request.workspaceKey(), null),
                Map.of("jobId", jobId)
            ));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/api/jobs/{jobId}/runs")
    public List<JobRun> listRuns(@PathVariable String jobId) {
        return jobService.listRuns(jobId);
    }

    @GetMapping("/api/jobs/{jobId}/execution-summaries")
    public List<JobExecutionSummary> executionSummaries(@PathVariable String jobId) {
        return jobService.executionSummaries(jobId);
    }

    @GetMapping("/api/jobs/{jobId}/latest-execution-summary")
    public JobExecutionSummary latestExecutionSummary(@PathVariable String jobId) {
        return jobService.latestExecutionSummary(jobId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No job executions found"));
    }

    @GetMapping("/api/job-assignments/{assignmentId}/execution-summary")
    public JobExecutionSummary executionSummaryByAssignment(@PathVariable String assignmentId) {
        return jobService.executionSummaryByAssignmentId(assignmentId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job execution summary not found"));
    }

    @GetMapping("/api/job-runs/{runId}")
    public JobRun getRun(@PathVariable String runId) {
        try {
            return jobService.getRun(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/api/jobs/{jobId}/outputs")
    public List<RunOutputArtifact> outputs(@PathVariable String jobId) {
        List<RunOutputArtifact> artifacts = new ArrayList<>();
        for (String runId : jobService.outputRunIds(jobId)) {
            artifacts.addAll(outputArtifactService.artifactsForRun(runId));
        }
        return artifacts;
    }

    @GetMapping("/api/jobs/{jobId}/events")
    public List<JobEventResponse> events(@PathVariable String jobId) {
        return jobService.listRuns(jobId).stream()
            .map(run -> new JobEventResponse(
                run.id(),
                "JOB_RUN_" + run.status().name(),
                run.status().name(),
                run.createdAt(),
                run.updatedAt()
            ))
            .toList();
    }

    @PostMapping("/api/job-runs/{runId}/cancel")
    public JobRun cancelRun(@PathVariable String runId) {
        try {
            return jobService.cancelRun(runId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  Recurrence
    // ════════════════════════════════════════════════════════════════

    @PostMapping("/api/jobs/{jobId}/recurrence")
    public JobRecurrence setRecurrence(@PathVariable String jobId,
                                        @RequestBody RecurrenceRequest request) {
        try {
            return jobService.setRecurrence(jobId, request.cronExpression(),
                request.timezone(), request.nextFireTime());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/api/jobs/{jobId}/recurrence")
    public Object getRecurrence(@PathVariable String jobId) {
        return jobService.getRecurrence(jobId)
            .map(r -> (Object) r)
            .orElse(Map.of("message", "No recurrence set"));
    }

    // ── DTOs ──

    public record RecurrenceRequest(
        String cronExpression,
        String timezone,
        Instant nextFireTime
    ) {}

    public record JobRunRequest(
        String agentId,
        String modelOverride,
        String projectId,
        String workspaceType,
        String workspaceKey,
        Integer priority
    ) {
        public JobRunRequest(
            String agentId,
            String modelOverride,
            String projectId,
            Integer priority
        ) {
            this(agentId, modelOverride, projectId, null, null, priority);
        }
    }

    public record JobItemRequest(
        String key,
        JobWorkItemType type,
        JobWorkItemType itemType,
        String planId,
        String workflowId,
        Map<String, Object> inputBindings,
        Integer order,
        Integer itemOrder,
        String modelOverride,
        Integer priority
    ) {
        JobWorkItem toItem(String fallbackKey) {
            return new JobWorkItem(
                key != null && !key.isBlank() ? key : fallbackKey,
                type != null ? type : itemType,
                planId,
                workflowId,
                inputBindings == null ? Map.of() : inputBindings,
                order != null ? order : itemOrder == null ? 0 : itemOrder,
                modelOverride,
                priority
            );
        }
    }

    public record JobEventResponse(
        String id,
        String type,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private void requireSubmissionServices() {
        if (assignmentService == null || agentProfileService == null) {
            throw new IllegalStateException("Job run submission requires assignment services");
        }
    }

    private String resolveAgentId(String requestedAgentId, JobDefinition job) {
        String normalized = normalize(requestedAgentId, null);
        if (normalized != null) {
            return normalized;
        }
        normalized = normalize(job.ownerAgentId(), null);
        if (normalized != null) {
            return normalized;
        }
        return agentProfileService.list().stream()
            .filter(agent -> agent.status() != null && !"DISABLED".equals(agent.status().name()))
            .findFirst()
            .map(agent -> agent.id())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No active agents available"));
    }

    private String normalize(String value, String fallback) {
        if (StringUtils.hasText(value)) {
            return value.trim();
        }
        return StringUtils.hasText(fallback) ? fallback.trim() : null;
    }
}
