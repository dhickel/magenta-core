package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrchestrationJobService {
    private final OrchestrationRuntimeRepository repository;
    private final AgentProfileService agentProfileService;

    public OrchestrationJobService(
        OrchestrationRuntimeRepository repository,
        AgentProfileService agentProfileService
    ) {
        this.repository = repository;
        this.agentProfileService = agentProfileService;
    }

    public List<OrchestrationJob> jobs(String agentId) {
        agentProfileService.get(agentId);
        return repository.findJobsForAgent(agentId);
    }

    public OrchestrationJob get(String jobId) {
        return repository.findJob(jobId).orElseThrow(() -> new IllegalStateException("Job not found: " + jobId));
    }

    public OrchestrationJob save(OrchestrationJob job) {
        if (!StringUtils.hasText(job.ownerAgentId())) {
            throw new IllegalArgumentException("ownerAgentId is required");
        }
        if (!StringUtils.hasText(job.title())) {
            throw new IllegalArgumentException("job title is required");
        }
        agentProfileService.get(job.ownerAgentId());
        String id = StringUtils.hasText(job.id()) ? job.id() : UUID.randomUUID().toString();
        String workspaceType = normalize(job.workspaceType());
        String workspaceKey = normalize(job.workspaceKey());
        if ((workspaceType == null) != (workspaceKey == null)) {
            throw new IllegalArgumentException("workspaceType and workspaceKey must be supplied together");
        }
        if (workspaceType == null) {
            workspaceType = "agent";
            workspaceKey = job.ownerAgentId();
        }
        return repository.saveJob(new OrchestrationJob(
            id, job.ownerAgentId(), job.title().trim(), normalize(job.summary()), normalize(job.defaultModel()),
            workspaceType, workspaceKey, null,
            job.status() == null ? OrchestrationStatus.QUEUED : job.status(),
            job.createdAt(),
            job.updatedAt()
        ));
    }

    public List<OrchestrationJobItem> items(String jobId) {
        get(jobId);
        return repository.findJobItems(jobId);
    }

    public OrchestrationJobItem saveItem(String jobId, OrchestrationJobItem item) {
        get(jobId);
        if (item.itemType() == null) {
            throw new IllegalArgumentException("itemType is required");
        }
        if (item.itemType() == AssignmentType.TASK_RUN && !StringUtils.hasText(item.taskId())) {
            throw new IllegalArgumentException("taskId is required for TASK_RUN job items");
        }
        if (item.itemType() == AssignmentType.WORKFLOW_RUN && !StringUtils.hasText(item.workflowId())) {
            throw new IllegalArgumentException("workflowId is required for WORKFLOW_RUN job items");
        }
        return repository.saveJobItem(new OrchestrationJobItem(
            StringUtils.hasText(item.id()) ? item.id() : UUID.randomUUID().toString(),
            jobId,
            item.itemOrder() <= 0 ? repository.findJobItems(jobId).size() + 1 : item.itemOrder(),
            item.itemType(),
            normalize(item.taskId()),
            normalize(item.workflowId()),
            normalize(item.modelOverride()),
            item.priority(),
            Math.max(0, item.retryCount()),
            item.continueOnFailure(),
            item.config() == null ? Map.of() : item.config(),
            item.createdAt(),
            item.updatedAt()
        ));
    }

    public List<WorkAssignment> runs(String jobId) {
        get(jobId);
        return repository.findAssignmentsForJob(jobId);
    }

    public List<OrchestrationEvent> events(String jobId) {
        get(jobId);
        return repository.findEventsForSource("JOB", jobId);
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
