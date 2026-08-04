package io.mindspice.magenta2.ai.orchestration.workflow;

import io.mindspice.magenta2.ai.chat.plan.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workflow v2 definition and run lifecycle service.
 */
@Service("orchestrationWorkflowService")
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowRepository repository;
    private final WorkflowRunner workflowRunner;
    private final WorkflowValidator validator;

    public WorkflowService(WorkflowRepository repository, PlanService planService,
                           WorkflowRunner workflowRunner) {
        this.repository = repository;
        this.workflowRunner = workflowRunner;
        this.validator = new WorkflowValidator(planService);
    }

    public List<WorkflowDefinition> listDefinitions() {
        return repository.findAllDefinitions();
    }

    public WorkflowDefinition getDefinition(String id) {
        return repository.findDefinition(id)
            .orElseThrow(() -> new IllegalArgumentException("Workflow not found: " + id));
    }

    public WorkflowDefinition getWorkflow(String id) {
        return getDefinition(id);
    }

    public Optional<WorkflowDefinition> findDefinition(String id) {
        return repository.findDefinition(id);
    }

    public WorkflowDefinition saveDefinition(WorkflowDefinition definition) {
        WorkflowDefinition normalized = normalizeDefinition(definition);
        WorkflowValidator.ValidationResult result = validator.validateDraft(normalized);
        if (!result.valid()) {
            throw new IllegalArgumentException(String.join("; ", result.errors()));
        }
        return repository.saveDefinition(normalized);
    }

    /**
     * Breaking v2 save gate: only schemaVersion=2 contract is accepted.
     */
    public WorkflowDefinition saveDefinitionValidated(WorkflowDefinition definition) {
        WorkflowDefinition normalized = normalizeDefinition(definition);
        WorkflowValidator.ValidationResult result = validator.validate(normalized);
        if (!result.valid()) {
            throw new IllegalArgumentException(String.join("; ", result.errors()));
        }
        return repository.saveDefinition(normalized);
    }

    public void deleteDefinition(String id) {
        repository.deleteDefinition(id);
    }

    public List<String> compatibilityWarnings(WorkflowDefinition definition) {
        WorkflowValidator.ValidationResult result = validateGraph(definition);
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(result.errors());
        return warnings;
    }

    public WorkflowValidator.ValidationResult validateGraph(WorkflowDefinition definition) {
        return validator.validate(normalizeDefinition(definition));
    }

    public WorkflowRun startRun(String workflowId) {
        return startRun(workflowId, null);
    }

    public WorkflowRun startRun(String workflowId, String modelOverride) {
        WorkflowDefinition definition = getDefinition(workflowId);
        requireExecutable(definition);
        return workflowRunner.startRun(definition, modelOverride);
    }

    public WorkflowRun getRun(String runId) {
        return repository.findRun(runId)
            .orElseThrow(() -> new IllegalArgumentException("Workflow run not found: " + runId));
    }

    public List<WorkflowRun> listRuns(String workflowId) {
        return repository.findRunsByWorkflowId(workflowId);
    }

    public WorkflowRun resumeRun(String runId) {
        WorkflowRun run = getRun(runId);
        if (run.status() != WorkflowRunStatus.WAITING) {
            throw new IllegalStateException("Workflow run is not waiting: " + runId + " (status: " + run.status().wireName() + ")");
        }
        return workflowRunner.resumeRun(run);
    }

    public WorkflowRun resumeRunSynchronously(String runId, String modelOverride, WorkflowExecutionObserver observer) {
        WorkflowRun run = getRun(runId);
        if (run.status() != WorkflowRunStatus.WAITING) {
            throw new IllegalStateException("Workflow run is not waiting: " + runId + " (status: " + run.status().wireName() + ")");
        }
        return workflowRunner.resumeRunSynchronously(run, modelOverride, observer);
    }

    public WorkflowRun runSynchronously(String workflowId) {
        return runSynchronously(workflowId, null);
    }

    public WorkflowRun runSynchronously(String workflowId, String modelOverride) {
        WorkflowDefinition def = getDefinition(workflowId);
        requireExecutable(def);
        return workflowRunner.runSynchronously(def, modelOverride);
    }

    public WorkflowRun runSynchronously(String workflowId, String modelOverride, WorkflowExecutionObserver observer) {
        WorkflowDefinition def = getDefinition(workflowId);
        requireExecutable(def);
        return workflowRunner.runSynchronously(def, modelOverride, observer);
    }

    private void requireExecutable(WorkflowDefinition definition) {
        WorkflowValidator.ValidationResult result = validateGraph(definition);
        if (!result.valid()) {
            throw new IllegalArgumentException(String.join("; ", result.errors()));
        }
    }

    private WorkflowDefinition normalizeDefinition(WorkflowDefinition definition) {
        String id = StringUtils.hasText(definition.id()) ? definition.id() : UUID.randomUUID().toString();
        return new WorkflowDefinition(
            id,
            definition.schemaVersion() <= 0 ? WorkflowDefinition.CURRENT_SCHEMA_VERSION : definition.schemaVersion(),
            definition.title(),
            definition.summary(),
            definition.maxConcurrency() <= 0 ? WorkflowDefinition.DEFAULT_MAX_CONCURRENCY : definition.maxConcurrency(),
            definition.nodes(),
            definition.routes(),
            definition.uiLayout(),
            definition.createdAt(),
            definition.updatedAt()
        );
    }
}
