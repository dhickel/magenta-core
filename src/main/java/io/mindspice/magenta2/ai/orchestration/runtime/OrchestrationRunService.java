package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OrchestrationRunService {
    private final AssignmentService assignmentService;
    private final JobService jobService;
    private final OrchestrationRunnerService runnerService;

    public OrchestrationRunService(
        AssignmentService assignmentService,
        JobService jobService,
        OrchestrationRunnerService runnerService
    ) {
        this.assignmentService = assignmentService;
        this.jobService = jobService;
        this.runnerService = runnerService;
    }

    public OrchestrationRunResult runTask(String taskId, Map<String, Object> inputValues, OrchestrationRunContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("taskId", taskId);
        input.put("inputValues", inputValues == null ? Map.of() : inputValues);
        WorkAssignment assignment = createAssignment(AssignmentType.TASK_RUN, input, context);
        WorkAssignment completed = runnerService.runAssignment(assignment.id());
        return new OrchestrationRunResult(completed, text(completed.output().get("taskRunId")), mapValue(completed.output().get("outputValues")));
    }

    public OrchestrationRunResult runWorkflow(String workflowId, OrchestrationRunContext context) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("workflowId", workflowId);
        WorkAssignment assignment = createAssignment(AssignmentType.WORKFLOW_RUN, input, context);
        WorkAssignment completed = runnerService.runAssignment(assignment.id());
        return new OrchestrationRunResult(completed, text(completed.output().get("workflowRunId")), mapValue(completed.output().get("finalOutputs")));
    }

    private WorkAssignment createAssignment(AssignmentType type, Map<String, Object> input, OrchestrationRunContext context) {
        OrchestrationRunContext effective = context == null ? new OrchestrationRunContext(null, null, null, null, null, null, null) : context;
        JobDefinition job = StringUtils.hasText(effective.jobId()) ? jobService.getDefinition(effective.jobId()) : null;
        String agentId = StringUtils.hasText(effective.agentId())
            ? effective.agentId()
            : job == null ? null : job.ownerAgentId();
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required for orchestration-context runs");
        }
        return assignmentService.create(new AssignmentRequest(
            agentId,
            effective.jobId(),
            null,
            type,
            effective.priority(),
            effective.modelOverride(),
            effective.projectId(),
            effective.workspaceType(),
            effective.workspaceKey(),
            input
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private String text(Object value) {
        return value == null ? null : value.toString();
    }
}
