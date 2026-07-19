package io.mindspice.magenta2.ai.orchestration.workflow;

import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanFieldType;
import io.mindspice.magenta2.ai.chat.plan.PlanRun;
import io.mindspice.magenta2.ai.chat.plan.PlanRunStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactContext;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Deterministic workflow v2 runner with parallel fan-out execution.
 */
@Service
public class WorkflowRunner {
    private static final Logger log = LoggerFactory.getLogger(WorkflowRunner.class);

    private final WorkflowRepository repository;
    private final PlanService planService;
    private final InboxService inboxService;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final OutputArtifactService outputArtifactService;
    private final WorkflowTaskExecutor workflowTaskExecutor;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private volatile TaskNodeExecutor taskNodeExecutor;

    public WorkflowRunner(WorkflowRepository repository, PlanService planService,
                          InboxService inboxService,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          OutputArtifactService outputArtifactService) {
        this(repository, planService, inboxService, workspaceDirectoryService, outputArtifactService, null);
    }

    @Autowired
    public WorkflowRunner(WorkflowRepository repository, PlanService planService,
                          InboxService inboxService,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          OutputArtifactService outputArtifactService,
                          ObjectProvider<WorkflowTaskExecutor> workflowTaskExecutorProvider) {
        this.repository = repository;
        this.planService = planService;
        this.inboxService = inboxService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.outputArtifactService = outputArtifactService;
        this.workflowTaskExecutor = workflowTaskExecutorProvider == null ? null : workflowTaskExecutorProvider.getIfAvailable();
    }

    @FunctionalInterface
    public interface TaskNodeExecutor {
        PlanRun execute(String planId, String planRunId, Map<String, Object> inputs, String workspacePath);
    }

    public void setTaskNodeExecutor(TaskNodeExecutor executor) {
        this.taskNodeExecutor = executor;
    }

    public WorkflowRun startRun(WorkflowDefinition definition) {
        return startRun(definition, null);
    }

    public WorkflowRun startRun(WorkflowDefinition definition, String modelOverride) {
        return createRun(definition, modelOverride, true);
    }

    private WorkflowRun createRun(WorkflowDefinition definition, String modelOverride, boolean submitAsync) {
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        OrchestrationTaskContext currentContext = OrchestrationTaskContextHolder.current();
        WorkspaceDescriptor descriptor = resolveDescriptor(currentContext);
        OutputArtifactContext attribution = outputContext(currentContext);
        List<WorkflowNodeRun> nodeRuns = definition.nodes().stream()
            .map(node -> new WorkflowNodeRun(
                node.key(), node.type(), WorkflowNodeRunStatus.PENDING,
                Map.of(), Map.of(), List.of(), null, null
            ))
            .toList();

        WorkflowRun run = repository.saveRun(new WorkflowRun(
            runId,
            definition.id(),
            currentContext == null ? null : currentContext.runDisplayName(),
            WorkflowRunStatus.RUNNING,
            0,
            nodeRuns,
            attribution.agentId(),
            attribution.jobId(),
            attribution.jobAssignmentId(),
            attribution.jobRunId(),
            attribution.projectId(),
            descriptor.kind().token(),
            descriptor.key(),
            descriptor.identity(),
            attribution.runType(),
            definition,
            Map.of(),
            List.of(),
            null,
            null,
            now,
            now,
            now,
            null
        ));

        if (submitAsync) {
            WorkflowRun initial = run;
            OrchestrationTaskContext asyncContext = executionContextFor(initial, OrchestrationTaskContextHolder.current());
            executor.submit(() -> runWithContext(asyncContext, () -> {
                executeSafely(initial, modelOverride);
                return null;
            }));
        }
        return run;
    }

    public WorkflowRun resumeRun(WorkflowRun run) {
        WorkflowRun resumed = prepareResume(run);
        OrchestrationTaskContext asyncContext = executionContextFor(resumed, OrchestrationTaskContextHolder.current());
        executor.submit(() -> runWithContext(asyncContext, () -> {
            executeSafely(resumed, null);
            return null;
        }));
        return resumed;
    }

    public WorkflowRun resumeRunSynchronously(WorkflowRun run, String modelOverride, WorkflowExecutionObserver observer) {
        WorkflowRun resumed = prepareResume(run);
        executeFromCheckpoint(resumed, null, modelOverride, observer == null ? WorkflowExecutionObserver.NOOP : observer);
        return repository.findRun(run.id()).orElse(resumed);
    }

    private WorkflowRun prepareResume(WorkflowRun run) {
        WorkflowNodeRun waitingNode = findWaitingNode(run);
        if (waitingNode == null) {
            throw new IllegalStateException("No waiting node found in run: " + run.id());
        }

        Object messageIdObj = waitingNode.outputValues().get("messageId");
        if (!(messageIdObj instanceof String messageId) || messageId.isBlank()) {
            throw new IllegalStateException("Waiting node '" + waitingNode.nodeKey() + "' has no messageId");
        }

        InboxMessage message = inboxService.findMessageById(messageId)
            .orElseThrow(() -> new IllegalStateException("Approval message not found: " + messageId));

        if (!StringUtils.hasText(message.responseJson())) {
            throw new IllegalStateException(
                "Cannot resume workflow run " + run.id()
                    + ": approval message " + messageId + " has not been responded to yet"
            );
        }

        boolean approved = inboxService.parseApprovalFromResponse(message.responseJson());
        String outcome = approved ? WorkflowRoute.OUTCOME_APPROVED : WorkflowRoute.OUTCOME_REJECTED;

        List<WorkflowNodeRun> updatedRuns = new ArrayList<>(run.nodeRuns());
        int index = nodeIndex(run, waitingNode.nodeKey());
        Map<String, Object> gateOutputs = new LinkedHashMap<>(waitingNode.outputValues());
        gateOutputs.put("gateOutcome", outcome);
        gateOutputs.put("approved", approved);
        updatedRuns.set(index, new WorkflowNodeRun(
            waitingNode.nodeKey(), waitingNode.type(), WorkflowNodeRunStatus.COMPLETED,
            waitingNode.inputValues(), gateOutputs, waitingNode.routeContext(),
            waitingNode.startedAt(), Instant.now()
        ));

        WorkflowRun resumed = repository.saveRun(new WorkflowRun(
            run.id(), run.workflowId(), run.runDisplayName(), WorkflowRunStatus.RUNNING,
            Math.max(0, index), updatedRuns,
            run.agentId(), run.jobId(), run.jobAssignmentId(), run.jobRunId(), run.projectId(),
            run.workspaceType(), run.workspaceKey(), run.workspaceIdentity(), run.runType(),
            run.workflowSnapshot(),
            run.finalOutputs(), run.artifactIds(),
            run.finalMessage(), run.errorText(),
            run.createdAt(), Instant.now(), run.startedAt(), null
        ));

        inboxService.markHandled(messageId);
        return resumed;
    }

    public WorkflowRun runSynchronously(WorkflowDefinition definition) {
        return runSynchronously(definition, null);
    }

    public WorkflowRun runSynchronously(WorkflowDefinition definition, String modelOverride) {
        return runSynchronously(definition, modelOverride, WorkflowExecutionObserver.NOOP);
    }

    public WorkflowRun runSynchronously(WorkflowDefinition definition, String modelOverride, WorkflowExecutionObserver observer) {
        WorkflowRun run = createRun(definition, modelOverride, false);
        run = repository.findRun(run.id()).orElse(run);
        executeFromCheckpoint(run, null, modelOverride, observer == null ? WorkflowExecutionObserver.NOOP : observer);
        return repository.findRun(run.id()).orElse(run);
    }

    private void executeSafely(WorkflowRun run, String modelOverride) {
        try {
            executeFromCheckpoint(run, null, modelOverride, WorkflowExecutionObserver.NOOP);
        } catch (Exception e) {
            log.error("Workflow run {} failed", run.id(), e);
            WorkflowRun current = repository.findRun(run.id()).orElse(run);
            repository.saveRun(new WorkflowRun(
                current.id(), current.workflowId(), current.runDisplayName(), WorkflowRunStatus.FAILED,
                current.currentNodeIndex(), current.nodeRuns(),
                current.agentId(), current.jobId(), current.jobAssignmentId(), current.jobRunId(), current.projectId(),
                current.workspaceType(), current.workspaceKey(), current.workspaceIdentity(), current.runType(),
                current.workflowSnapshot(),
                current.finalOutputs(), current.artifactIds(),
                current.finalMessage(), e.getMessage(),
                current.createdAt(), Instant.now(), current.startedAt(), Instant.now()
            ));
        }
    }

    private void executeFromCheckpoint(WorkflowRun run, Consumer<String> sseEventCallback, String modelOverride) {
        executeFromCheckpoint(run, sseEventCallback, modelOverride, WorkflowExecutionObserver.NOOP);
    }

    private void executeFromCheckpoint(
        WorkflowRun run,
        Consumer<String> sseEventCallback,
        String modelOverride,
        WorkflowExecutionObserver observer
    ) {
        OrchestrationTaskContext previousContext = OrchestrationTaskContextHolder.current();
        OrchestrationTaskContext executionContext = executionContextFor(run, previousContext);
        setOrClearContext(executionContext);
        try {
            doExecuteFromCheckpoint(run, sseEventCallback, modelOverride, observer);
        } finally {
            setOrClearContext(previousContext);
        }
    }

    private void doExecuteFromCheckpoint(
        WorkflowRun run,
        Consumer<String> sseEventCallback,
        String modelOverride,
        WorkflowExecutionObserver observer
    ) {
        WorkflowDefinition def = run.workflowSnapshot();
        Map<String, WorkflowNodeRun> nodeRuns = toNodeRunMap(run.nodeRuns());
        Map<String, Map<String, Object>> outputsByNode = new LinkedHashMap<>();
        Map<String, String> gateOutcomeByNode = new HashMap<>();

        for (WorkflowNodeRun nr : nodeRuns.values()) {
            if (nr.status() == WorkflowNodeRunStatus.COMPLETED) {
                outputsByNode.put(nr.nodeKey(), nr.outputValues());
                Object outcome = nr.outputValues().get("gateOutcome");
                if (outcome instanceof String s && !s.isBlank()) {
                    gateOutcomeByNode.put(nr.nodeKey(), s);
                }
            }
        }

        while (true) {
            List<WorkflowNode> ready = computeReadyNodes(def, nodeRuns, gateOutcomeByNode).stream()
                .map(def::nodeByKey)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(WorkflowNode::key))
                .toList();

            if (ready.isEmpty()) {
                if (skipInactiveBranchNodes(def, nodeRuns, gateOutcomeByNode)) {
                    run = persistState(run, nodeRuns, WorkflowRunStatus.RUNNING, run.finalOutputs(), run.artifactIds(), null, null, false);
                    continue;
                }
                if (hasWaitingNode(nodeRuns)) {
                    persistState(run, nodeRuns, WorkflowRunStatus.WAITING, run.finalOutputs(), run.artifactIds(), null, null, false);
                    return;
                }
                if (allDone(nodeRuns)) {
                    completeRun(run, def, nodeRuns, outputsByNode);
                    return;
                }
                failRun(run, nodeRuns, "No ready nodes remain but workflow is not complete");
                return;
            }

            int maxConcurrency = Math.max(1, def.maxConcurrency());
            List<WorkflowNode> batch = ready.stream().limit(maxConcurrency).toList();

            for (WorkflowNode node : batch) {
                WorkflowNodeRun existing = nodeRuns.get(node.key());
                List<String> routeContext = activeRouteContext(def, node.key(), nodeRuns, gateOutcomeByNode);
                nodeRuns.put(node.key(), new WorkflowNodeRun(
                    node.key(), node.type(), WorkflowNodeRunStatus.RUNNING,
                    existing.inputValues(), existing.outputValues(), routeContext,
                    Instant.now(), null
                ));
            }
            run = persistState(run, nodeRuns, WorkflowRunStatus.RUNNING, run.finalOutputs(), run.artifactIds(), null, null, false);
            WorkflowRun runSnapshot = run;
            OrchestrationTaskContext asyncContext = OrchestrationTaskContextHolder.current();

            List<CompletableFuture<NodeExecutionResult>> futures = batch.stream()
                .map(node -> CompletableFuture.supplyAsync(withContext(asyncContext, () -> executeNode(
                    node, def, outputsByNode, runSnapshot, modelOverride, observer)), executor))
                .toList();

            List<NodeExecutionResult> results = futures.stream().map(CompletableFuture::join).toList();
            boolean waiting = false;
            for (NodeExecutionResult result : results) {
                WorkflowNode node = def.nodeByKey(result.nodeKey);
                if (node == null) continue;

                if (result.status == WorkflowNodeRunStatus.FAILED) {
                    nodeRuns.put(result.nodeKey, new WorkflowNodeRun(
                        result.nodeKey, node.type(), WorkflowNodeRunStatus.FAILED,
                        result.inputs, Map.of(), result.routeContext,
                        nodeRuns.get(result.nodeKey).startedAt(), Instant.now()
                    ));
                    failRun(run, nodeRuns, result.errorText);
                    return;
                }

                if (result.status == WorkflowNodeRunStatus.WAITING) {
                    waiting = true;
                    nodeRuns.put(result.nodeKey, new WorkflowNodeRun(
                        result.nodeKey, node.type(), WorkflowNodeRunStatus.WAITING,
                        result.inputs, result.outputs, result.routeContext,
                        nodeRuns.get(result.nodeKey).startedAt(), null
                    ));
                    continue;
                }

                nodeRuns.put(result.nodeKey, new WorkflowNodeRun(
                    result.nodeKey, node.type(), WorkflowNodeRunStatus.COMPLETED,
                    result.inputs, result.outputs, result.routeContext,
                    nodeRuns.get(result.nodeKey).startedAt(), Instant.now()
                ));
                outputsByNode.put(result.nodeKey, result.outputs);
                Object outcome = result.outputs.get("gateOutcome");
                if (outcome instanceof String s && !s.isBlank()) {
                    gateOutcomeByNode.put(result.nodeKey, s);
                }
            }

            run = persistState(run, nodeRuns,
                waiting ? WorkflowRunStatus.WAITING : WorkflowRunStatus.RUNNING,
                run.finalOutputs(), run.artifactIds(), null, null, false);
            if (waiting) {
                return;
            }
        }
    }

    private NodeExecutionResult executeNode(
        WorkflowNode node,
        WorkflowDefinition def,
        Map<String, Map<String, Object>> outputsByNode,
        WorkflowRun run,
        String modelOverride,
        WorkflowExecutionObserver observer
    ) {
        Map<String, Object> inputs = resolveNodeInputs(node, def, outputsByNode);
        List<String> routeContext = activeRouteContext(def, node.key(), toNodeRunMap(run.nodeRuns()), Map.of());
        try {
            return switch (node.type()) {
                case TASK -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeTaskNode(node, inputs, run, modelOverride, observer), routeContext, null);
                case REPORT, FINAL_OUTPUT -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeFinalOutputNode(node, inputs, run), routeContext, null);
                case USER_APPROVAL, AGENT_APPROVAL -> {
                    String messageId = executeGateNode(node, run);
                    yield new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.WAITING,
                        inputs, Map.of("messageId", messageId), routeContext, null);
                }
                case USER_MESSAGE, AGENT_MESSAGE -> {
                    executeMessageNode(node, run);
                    yield new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                        inputs, Map.of(), routeContext, null);
                }
                case DELEGATION -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeDelegationNode(node, inputs), routeContext, null);
                case VALIDATION -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeValidationNode(node, inputs), routeContext, null);
                case COPY, FAN_OUT -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeCopyNode(node, inputs), routeContext, null);
                case LOG -> new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.COMPLETED,
                    inputs, executeLogNode(node, inputs, run), routeContext, null);
            };
        } catch (Exception e) {
            log.error("Workflow node {} failed", node.key(), e);
            return new NodeExecutionResult(node.key(), WorkflowNodeRunStatus.FAILED, inputs, Map.of(), routeContext, e.getMessage());
        }
    }

    private Map<String, Object> executeTaskNode(
        WorkflowNode node,
        Map<String, Object> inputs,
        WorkflowRun run,
        String modelOverride,
        WorkflowExecutionObserver observer
    ) {
        if (workflowTaskExecutor != null) {
            String conversationId = UUID.randomUUID().toString();
            if (observer != null) {
                observer.taskConversationStarted(run.id(), node.key(), conversationId);
            }
            var taskRun = workflowTaskExecutor.execute(node.planId(), inputs, conversationId, modelOverride);
            if (!workflowTaskExecutor.succeeded(taskRun.status())) {
                throw new IllegalStateException("Task node '" + node.key() + "' failed with status " + taskRun.status().name());
            }
            return taskRun.outputValues();
        }

        if (taskNodeExecutor == null) {
            throw new IllegalStateException("Task node execution requires model-backed task execution");
        }

        String planRunId = UUID.randomUUID().toString();
        PlanRun planRun = taskNodeExecutor.execute(node.planId(), planRunId, inputs, descriptorRoot(run).toString());
        if (planRun.status() == PlanRunStatus.FAILED || planRun.status() == PlanRunStatus.NEEDS_REVIEW) {
            throw new IllegalStateException("Task node '" + node.key() + "' failed with status " + planRun.status().name());
        }
        return planRun.outputValues();
    }

    private Map<String, Object> executeFinalOutputNode(WorkflowNode node, Map<String, Object> inputs, WorkflowRun run) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (StringUtils.hasText(node.messageTemplate())) {
            outputs.put("message", node.messageTemplate());
        }

        // Explicit output selection config: {outputName: sourceInputName}
        Object select = node.config().get("finalOutputs");
        if (select instanceof Map<?, ?> map && !map.isEmpty()) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String outputName = String.valueOf(entry.getKey());
                String inputName = String.valueOf(entry.getValue());
                if (inputs.containsKey(inputName)) {
                    outputs.put(outputName, inputs.get(inputName));
                }
            }
        } else {
            outputs.putAll(inputs);
        }
        return outputs;
    }

    private String executeGateNode(WorkflowNode node, WorkflowRun run) {
        InboxMessageToType toType = node.type() == WorkflowNodeType.USER_APPROVAL
            ? InboxMessageToType.USER
            : InboxMessageToType.AGENT;
        String body = StringUtils.hasText(node.messageTemplate())
            ? node.messageTemplate()
            : "Approval required for workflow node: " + node.key();
        InboxMessage message = inboxService.createApprovalMessage(
            toType,
            toType == InboxMessageToType.AGENT ? node.planId() : null,
            null,
            body,
            run.id(),
            nodeIndex(run, node.key())
        );
        return message.id();
    }

    private void executeMessageNode(WorkflowNode node, WorkflowRun run) {
        InboxMessageToType toType = node.type() == WorkflowNodeType.USER_MESSAGE
            ? InboxMessageToType.USER
            : InboxMessageToType.AGENT;
        String body = StringUtils.hasText(node.messageTemplate())
            ? node.messageTemplate()
            : "Message from workflow node: " + node.key();
        inboxService.createInfoMessage(
            toType,
            toType == InboxMessageToType.AGENT ? node.planId() : null,
            null,
            body,
            toJson(Map.of("workflowRunId", run.id(), "nodeKey", node.key()))
        );
    }

    private Map<String, Object> executeDelegationNode(WorkflowNode node, Map<String, Object> inputs) {
        Map<String, Object> outputs = new LinkedHashMap<>();
        if (StringUtils.hasText(node.planId())) {
            PlanRun childRun = planService.startRun(node.planId(), inputs, OrchestrationTaskContextHolder.current());
            PlanRun completed = planService.completeRun(childRun.id(), Map.of(), "Delegated run completed", List.of());
            outputs.put("childRunId", completed.id());
            outputs.put("childStatus", completed.status().name());
        }
        return outputs;
    }

    private Map<String, Object> executeValidationNode(WorkflowNode node, Map<String, Object> inputs) {
        Object requiredObj = node.config().get("requiredInputs");
        if (requiredObj instanceof List<?> requiredInputs) {
            List<String> missing = requiredInputs.stream()
                .map(Object::toString)
                .filter(name -> !inputs.containsKey(name) || inputs.get(name) == null
                    || (inputs.get(name) instanceof String text && !StringUtils.hasText(text)))
                .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("Validation node '" + node.key() + "' missing required values: "
                    + String.join(", ", missing));
            }
        }
        Map<String, Object> outputs = new LinkedHashMap<>(inputs);
        outputs.put("valid", true);
        return outputs;
    }

    private Map<String, Object> executeCopyNode(WorkflowNode node, Map<String, Object> inputs) {
        Map<String, Object> outputs = new LinkedHashMap<>(inputs);
        Object copiesObj = node.config().get("copies");
        if (copiesObj instanceof Map<?, ?> copies) {
            for (var entry : copies.entrySet()) {
                String target = String.valueOf(entry.getKey());
                String source = String.valueOf(entry.getValue());
                if (inputs.containsKey(source)) {
                    outputs.put(target, inputs.get(source));
                }
            }
        }
        return outputs;
    }

    private Map<String, Object> executeLogNode(WorkflowNode node, Map<String, Object> inputs, WorkflowRun run) {
        try {
            Path outputDir = outputPathFor(run);
            for (Map.Entry<String, Object> entry : inputs.entrySet()) {
                outputArtifactService.materialize(
                    run.id(),
                    run.workflowId(),
                    node.key() + "_" + entry.getKey(),
                    PlanFieldType.STRING,
                    entry.getValue(),
                    outputDir,
                    outputContext()
                );
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to materialize log node outputs", e);
        }
        return new LinkedHashMap<>(inputs);
    }

    private void completeRun(
        WorkflowRun run,
        WorkflowDefinition def,
        Map<String, WorkflowNodeRun> nodeRuns,
        Map<String, Map<String, Object>> outputsByNode
    ) {
        Map<String, Object> finalOutputs = collectFinalOutputs(def, nodeRuns, outputsByNode);
        List<String> artifactIds = materializeFinalOutputs(run, finalOutputs);

        persistState(run, nodeRuns, WorkflowRunStatus.COMPLETED,
            finalOutputs, artifactIds,
            "Workflow completed: " + def.title(),
            null,
            true);
    }

    private Map<String, Object> collectFinalOutputs(
        WorkflowDefinition def,
        Map<String, WorkflowNodeRun> nodeRuns,
        Map<String, Map<String, Object>> outputsByNode
    ) {
        Map<String, Object> finalOutputs = new LinkedHashMap<>();

        for (WorkflowNode node : def.nodes()) {
            if (!node.type().isFinalOutputNode()) {
                continue;
            }
            WorkflowNodeRun run = nodeRuns.get(node.key());
            if (run != null && run.status() == WorkflowNodeRunStatus.COMPLETED) {
                finalOutputs.putAll(run.outputValues());
            }
        }

        if (finalOutputs.isEmpty()) {
            for (WorkflowNode node : def.nodes()) {
                List<WorkflowRoute> outgoingDeps = def.outgoingRoutes(node.key()).stream()
                    .filter(WorkflowRoute::createsDependency)
                    .toList();
                if (!outgoingDeps.isEmpty()) continue;
                WorkflowNodeRun nr = nodeRuns.get(node.key());
                if (nr != null && nr.status() == WorkflowNodeRunStatus.COMPLETED) {
                    finalOutputs.putAll(nr.outputValues());
                }
            }
        }

        return finalOutputs;
    }

    private List<String> materializeFinalOutputs(WorkflowRun run, Map<String, Object> finalOutputs) {
        if (finalOutputs.isEmpty()) {
            return List.of();
        }
        Path outputDir = outputPathFor(run);
        List<String> artifactIds = new ArrayList<>();
        for (Map.Entry<String, Object> entry : finalOutputs.entrySet()) {
            try {
                PlanFieldType type = inferType(entry.getValue());
                RunOutputArtifact artifact = outputArtifactService.materialize(
                    run.id(),
                    run.workflowId(),
                    entry.getKey(),
                    type,
                    entry.getValue(),
                    outputDir,
                    outputContext()
                );
                artifactIds.add(artifact.id());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to materialize final output '" + entry.getKey() + "'", e);
            }
        }
        return artifactIds;
    }

    private PlanFieldType inferType(Object value) {
        if (value instanceof Number) {
            return PlanFieldType.NUMBER;
        }
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return PlanFieldType.JSON;
        }
        return PlanFieldType.STRING;
    }

    private void failRun(WorkflowRun run, Map<String, WorkflowNodeRun> nodeRuns, String errorText) {
        persistState(run, nodeRuns, WorkflowRunStatus.FAILED, run.finalOutputs(), run.artifactIds(), null, errorText, true);
    }

    private WorkflowRun persistState(
        WorkflowRun base,
        Map<String, WorkflowNodeRun> nodeRuns,
        WorkflowRunStatus status,
        Map<String, Object> finalOutputs,
        List<String> artifactIds,
        String finalMessage,
        String errorText,
        boolean terminal
    ) {
        List<WorkflowNodeRun> orderedRuns = base.workflowSnapshot().nodes().stream()
            .map(n -> nodeRuns.getOrDefault(n.key(), new WorkflowNodeRun(
                n.key(), n.type(), WorkflowNodeRunStatus.PENDING, Map.of(), Map.of(), List.of(), null, null)))
            .toList();

        WorkflowRun persisted = repository.saveRun(new WorkflowRun(
            base.id(),
            base.workflowId(),
            base.runDisplayName(),
            status,
            currentNodeIndex(orderedRuns),
            orderedRuns,
            base.agentId(),
            base.jobId(),
            base.jobAssignmentId(),
            base.jobRunId(),
            base.projectId(),
            base.workspaceType(),
            base.workspaceKey(),
            base.workspaceIdentity(),
            base.runType(),
            base.workflowSnapshot(),
            finalOutputs,
            artifactIds,
            finalMessage,
            errorText,
            base.createdAt(),
            Instant.now(),
            base.startedAt(),
            terminal ? Instant.now() : null
        ));
        return persisted;
    }

    private Path outputPathFor(WorkflowRun run) {
        return workspaceDirectoryService.outputs(descriptorForRun(run));
    }

    private WorkspaceDescriptor resolveDescriptor(OrchestrationTaskContext context) {
        WorkspaceKind kind;
        String key;
        if (context != null && StringUtils.hasText(context.workspaceType())
            && StringUtils.hasText(context.workspaceKey())) {
            kind = WorkspaceKind.fromToken(context.workspaceType());
            key = context.workspaceKey();
        } else {
            kind = WorkspaceKind.AGENT;
            key = context != null && StringUtils.hasText(context.agentId()) ? context.agentId() : "system";
        }
        if (kind == WorkspaceKind.CHAT) {
            throw new IllegalArgumentException("Chat workspaces cannot execute workflows");
        }
        return workspaceDirectoryService.resolveForWrite(kind, key);
    }

    private WorkspaceDescriptor descriptorForRun(WorkflowRun run) {
        if (!StringUtils.hasText(run.workspaceType()) || !StringUtils.hasText(run.workspaceKey())) {
            throw new IllegalStateException("Workflow run has no executable workspace selection: " + run.id());
        }
        WorkspaceKind kind = WorkspaceKind.fromToken(run.workspaceType());
        if (kind == WorkspaceKind.CHAT) {
            throw new IllegalStateException("Chat workspaces cannot execute workflows");
        }
        return workspaceDirectoryService.resolveForWrite(kind, run.workspaceKey());
    }

    private Path descriptorRoot(WorkflowRun run) {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context != null && StringUtils.hasText(context.hostDurableWorkspacePath())) {
            return Path.of(context.hostDurableWorkspacePath());
        }
        return descriptorForRun(run).root();
    }

    private OrchestrationTaskContext executionContextFor(WorkflowRun run, OrchestrationTaskContext base) {
        WorkspaceDescriptor descriptor = descriptorForRun(run);
        return new OrchestrationTaskContext(
            run.agentId() != null ? run.agentId() : base == null ? null : base.agentId(),
            base == null ? null : base.agentName(),
            run.jobId(), run.jobAssignmentId(), run.jobRunId(), run.projectId(),
            descriptor.kind().token(), descriptor.key(), descriptor.identity(),
            StringUtils.hasText(run.runType()) ? run.runType() : "WORKFLOW_RUN",
            run.runDisplayName(), descriptor.root().toString(), null, null, descriptor.root().toString()
        );
    }

    private OutputArtifactContext outputContext() {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        return outputContext(context);
    }

    private OutputArtifactContext outputContext(OrchestrationTaskContext context) {
        if (context == null) {
            return OutputArtifactContext.EMPTY;
        }
        return new OutputArtifactContext(
            context.agentId(),
            context.jobId(),
            context.jobAssignmentId(),
            context.jobRunId(),
            context.projectId(),
            context.workspaceType(),
            context.workspaceKey(),
            context.workspaceIdentity(),
            StringUtils.hasText(context.runType()) ? context.runType() : "WORKFLOW_RUN"
        );
    }

    private <T> Supplier<T> withContext(OrchestrationTaskContext context, Supplier<T> supplier) {
        return () -> runWithContext(context, supplier);
    }

    private <T> T runWithContext(OrchestrationTaskContext context, Supplier<T> supplier) {
        OrchestrationTaskContext previous = OrchestrationTaskContextHolder.current();
        setOrClearContext(context);
        try {
            return supplier.get();
        } finally {
            setOrClearContext(previous);
        }
    }

    private void setOrClearContext(OrchestrationTaskContext context) {
        if (context == null) {
            OrchestrationTaskContextHolder.clear();
        } else {
            OrchestrationTaskContextHolder.set(context);
        }
    }

    private int currentNodeIndex(List<WorkflowNodeRun> runs) {
        for (int i = 0; i < runs.size(); i++) {
            if (runs.get(i).status() == WorkflowNodeRunStatus.RUNNING
                || runs.get(i).status() == WorkflowNodeRunStatus.WAITING
                || runs.get(i).status() == WorkflowNodeRunStatus.PENDING) {
                return i;
            }
        }
        return runs.isEmpty() ? 0 : runs.size() - 1;
    }

    private boolean allDone(Map<String, WorkflowNodeRun> nodeRuns) {
        return nodeRuns.values().stream().allMatch(n ->
            n.status() == WorkflowNodeRunStatus.COMPLETED || n.status() == WorkflowNodeRunStatus.SKIPPED);
    }

    private boolean hasWaitingNode(Map<String, WorkflowNodeRun> nodeRuns) {
        return nodeRuns.values().stream().anyMatch(n -> n.status() == WorkflowNodeRunStatus.WAITING);
    }

    private boolean skipInactiveBranchNodes(
        WorkflowDefinition def,
        Map<String, WorkflowNodeRun> nodeRuns,
        Map<String, String> gateOutcomeByNode
    ) {
        boolean changed = false;
        Instant now = Instant.now();
        for (WorkflowNode node : def.nodes()) {
            WorkflowNodeRun run = nodeRuns.get(node.key());
            if (run == null || run.status() != WorkflowNodeRunStatus.PENDING) {
                continue;
            }

            List<WorkflowRoute> incomingDeps = def.incomingRoutes(node.key()).stream()
                .filter(WorkflowRoute::createsDependency)
                .toList();
            if (incomingDeps.isEmpty()) {
                continue;
            }

            List<WorkflowRoute> controlRoutes = incomingDeps.stream()
                .filter(r -> r.routeType() == WorkflowRouteType.CONTROL)
                .toList();

            boolean skipForControlMismatch = false;
            if (!controlRoutes.isEmpty()) {
                boolean unresolved = false;
                int activeCount = 0;
                for (WorkflowRoute route : controlRoutes) {
                    WorkflowNodeRun sourceRun = nodeRuns.get(route.fromNodeKey());
                    String outcome = gateOutcomeByNode.get(route.fromNodeKey());
                    if (sourceRun == null || sourceRun.status() == WorkflowNodeRunStatus.PENDING
                        || sourceRun.status() == WorkflowNodeRunStatus.RUNNING
                        || sourceRun.status() == WorkflowNodeRunStatus.WAITING
                        || outcome == null) {
                        unresolved = true;
                        break;
                    }
                    if (outcome.equals(route.controlOutcome())) {
                        activeCount++;
                    }
                }
                skipForControlMismatch = !unresolved && activeCount == 0;
            }

            boolean skipForSkippedDependency = false;
            if (!skipForControlMismatch) {
                boolean allDepsResolved = true;
                for (WorkflowRoute route : incomingDeps) {
                    WorkflowNodeRun sourceRun = nodeRuns.get(route.fromNodeKey());
                    if (sourceRun == null || sourceRun.status() == WorkflowNodeRunStatus.PENDING
                        || sourceRun.status() == WorkflowNodeRunStatus.RUNNING
                        || sourceRun.status() == WorkflowNodeRunStatus.WAITING) {
                        allDepsResolved = false;
                        break;
                    }
                    if (sourceRun.status() == WorkflowNodeRunStatus.SKIPPED) {
                        skipForSkippedDependency = true;
                    }
                }
                skipForSkippedDependency = allDepsResolved && skipForSkippedDependency;
            }

            if (skipForControlMismatch || skipForSkippedDependency) {
                nodeRuns.put(node.key(), new WorkflowNodeRun(
                    run.nodeKey(), run.type(), WorkflowNodeRunStatus.SKIPPED,
                    run.inputValues(), run.outputValues(), run.routeContext(),
                    run.startedAt() == null ? now : run.startedAt(),
                    now
                ));
                changed = true;
            }
        }
        return changed;
    }

    private List<String> computeReadyNodes(
        WorkflowDefinition def,
        Map<String, WorkflowNodeRun> nodeRuns,
        Map<String, String> gateOutcomeByNode
    ) {
        Set<String> completed = nodeRuns.values().stream()
            .filter(n -> n.status() == WorkflowNodeRunStatus.COMPLETED)
            .map(WorkflowNodeRun::nodeKey)
            .collect(java.util.stream.Collectors.toSet());

        List<String> ready = new ArrayList<>();
        for (WorkflowNode node : def.nodes()) {
            WorkflowNodeRun run = nodeRuns.get(node.key());
            if (run == null || run.status() != WorkflowNodeRunStatus.PENDING) {
                continue;
            }

            List<WorkflowRoute> incoming = def.incomingRoutes(node.key()).stream()
                .filter(WorkflowRoute::createsDependency)
                .toList();
            if (incoming.isEmpty()) {
                ready.add(node.key());
                continue;
            }

            boolean hasControl = incoming.stream().anyMatch(r -> r.routeType() == WorkflowRouteType.CONTROL);
            int activeControl = 0;
            boolean allSatisfied = true;

            for (WorkflowRoute route : incoming) {
                if (!StringUtils.hasText(route.fromNodeKey())) {
                    allSatisfied = false;
                    break;
                }

                if (route.routeType() == WorkflowRouteType.CONTROL) {
                    String outcome = gateOutcomeByNode.get(route.fromNodeKey());
                    if (outcome == null) {
                        allSatisfied = false;
                        break;
                    }
                    if (outcome.equals(route.controlOutcome())) {
                        activeControl++;
                        if (!completed.contains(route.fromNodeKey())) {
                            allSatisfied = false;
                            break;
                        }
                    }
                    continue;
                }

                if (!completed.contains(route.fromNodeKey())) {
                    allSatisfied = false;
                    break;
                }
            }

            if (allSatisfied && (!hasControl || activeControl > 0)) {
                ready.add(node.key());
            }
        }

        return ready;
    }

    private List<String> activeRouteContext(
        WorkflowDefinition def,
        String nodeKey,
        Map<String, WorkflowNodeRun> nodeRuns,
        Map<String, String> gateOutcomeByNode
    ) {
        Set<String> completed = nodeRuns.values().stream()
            .filter(n -> n.status() == WorkflowNodeRunStatus.COMPLETED)
            .map(WorkflowNodeRun::nodeKey)
            .collect(java.util.stream.Collectors.toSet());

        List<String> routes = new ArrayList<>();
        for (WorkflowRoute route : def.incomingRoutes(nodeKey)) {
            if (!route.createsDependency() || !StringUtils.hasText(route.fromNodeKey())) continue;
            if (route.routeType() == WorkflowRouteType.CONTROL) {
                String outcome = gateOutcomeByNode.get(route.fromNodeKey());
                if (outcome != null && outcome.equals(route.controlOutcome()) && completed.contains(route.fromNodeKey())) {
                    routes.add(route.id());
                }
            } else if (completed.contains(route.fromNodeKey())) {
                routes.add(route.id());
            }
        }
        return routes;
    }

    private Map<String, Object> resolveNodeInputs(
        WorkflowNode node,
        WorkflowDefinition def,
        Map<String, Map<String, Object>> outputsByNode
    ) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (WorkflowRoute route : def.incomingRoutes(node.key())) {
            if (route.routeType() == WorkflowRouteType.CONTROL || route.routeType() == WorkflowRouteType.LOG) {
                continue;
            }
            if (!StringUtils.hasText(route.fromNodeKey())) {
                continue;
            }
            Map<String, Object> sourceOutputs = outputsByNode.get(route.fromNodeKey());
            if (sourceOutputs == null) {
                continue;
            }

            if (route.routeType() == WorkflowRouteType.MAP_OUTPUT) {
                if (StringUtils.hasText(route.sourcePort()) && sourceOutputs.containsKey(route.sourcePort())) {
                    values.put(route.targetPort(), sourceOutputs.get(route.sourcePort()));
                }
            } else if (route.routeType() == WorkflowRouteType.PASS_THROUGH) {
                if (StringUtils.hasText(route.sourcePort()) && sourceOutputs.containsKey(route.sourcePort())) {
                    values.put(route.targetPort(), sourceOutputs.get(route.sourcePort()));
                }
            }
        }
        values.putAll(node.config());
        return values;
    }

    private int nodeIndex(WorkflowRun run, String nodeKey) {
        for (int i = 0; i < run.nodeRuns().size(); i++) {
            if (run.nodeRuns().get(i).nodeKey().equals(nodeKey)) return i;
        }
        return -1;
    }

    private WorkflowNodeRun findWaitingNode(WorkflowRun run) {
        return run.nodeRuns().stream()
            .filter(nr -> nr.status() == WorkflowNodeRunStatus.WAITING)
            .findFirst()
            .orElse(null);
    }

    private Map<String, WorkflowNodeRun> toNodeRunMap(List<WorkflowNodeRun> nodeRuns) {
        Map<String, WorkflowNodeRun> map = new LinkedHashMap<>();
        for (WorkflowNodeRun run : nodeRuns) {
            map.put(run.nodeKey(), run);
        }
        return map;
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private record NodeExecutionResult(
        String nodeKey,
        WorkflowNodeRunStatus status,
        Map<String, Object> inputs,
        Map<String, Object> outputs,
        List<String> routeContext,
        String errorText
    ) {}
}
