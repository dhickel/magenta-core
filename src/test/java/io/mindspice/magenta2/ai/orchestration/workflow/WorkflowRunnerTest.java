package io.mindspice.magenta2.ai.orchestration.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.*;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.workspaces.EffectiveWorkspaceResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkflowRunnerTest {

    private JdbcTemplate jdbcTemplate;
    private PlanService planService;
    private WorkflowRunner workflowRunner;
    private WorkflowService workflowService;
    private InboxService inboxService;
    private OutputArtifactService outputArtifactService;
    private WorkspaceDirectoryService workspaceDirectoryService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));

        PlanRepository planRepository = new PlanRepository(jdbcTemplate, mapper);
        ChatMemoryRepository memoryRepository = new ChatMemoryRepository(jdbcTemplate, mapper);

        Path dataRoot = tempDir.resolve("data");
        java.nio.file.Files.createDirectories(dataRoot);
        workspaceDirectoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);

        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        outputArtifactService = new OutputArtifactService(
            workspaceRepository, workspaceDirectoryService, mapper);
        EffectiveWorkspaceResolver effectiveWorkspaceResolver = new EffectiveWorkspaceResolver(
            workspaceDirectoryService,
            new WorkspaceService(workspaceRepository,
                    WorkspaceDirectoryServiceTestSupport.aiConfig(dataRoot),
                    WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                    WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true))
        );
        planService = new PlanService(
            planRepository, memoryRepository, null, new ChatMarkdownRenderer(),
            workspaceDirectoryService, outputArtifactService, effectiveWorkspaceResolver);

        WorkflowRepository workflowRepository = new WorkflowRepository(jdbcTemplate, mapper);
        inboxService = new InboxService(workflowRepository, mapper);
        workflowRunner = new WorkflowRunner(workflowRepository, planService,
            inboxService, workspaceDirectoryService, outputArtifactService, effectiveWorkspaceResolver);
        workflowService = new WorkflowService(workflowRepository, planService, workflowRunner);
    }

    @Test
    void savesAndLoadsWorkflowV2Definition() {
        WorkflowDefinition saved = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null,
            2,
            "Workflow V2",
            "test",
            4,
            List.of(simpleFinalNode("final")),
            List.of(),
            Map.of("nodes", Map.of("final", Map.of("x", 40, "y", 40))),
            null,
            null
        ));

        WorkflowDefinition found = workflowService.getDefinition(saved.id());
        assertThat(found.schemaVersion()).isEqualTo(2);
        assertThat(found.maxConcurrency()).isEqualTo(4);
        assertThat(found.uiLayout()).containsKey("nodes");
    }

    @Test
    void draftSaveAllowsEmptyWorkflowButExecutableValidationRejectsIt() {
        WorkflowDefinition draft = workflowService.saveDefinition(new WorkflowDefinition(
            null,
            2,
            "Empty Draft",
            "test",
            4,
            List.of(),
            List.of(),
            Map.of(),
            null,
            null
        ));

        WorkflowValidator.ValidationResult validation = workflowService.validateGraph(draft);
        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors())
            .contains("Workflow must contain at least one executable node before validation, submission, or run");

        assertThatThrownBy(() -> workflowService.saveDefinitionValidated(draft))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one executable node");
        assertThatThrownBy(() -> workflowService.startRun(draft.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one executable node");
    }

    @Test
    void executableValidationRejectsDisconnectedWorkflowRoots() {
        WorkflowDefinition disconnected = workflowService.saveDefinition(new WorkflowDefinition(
            null,
            2,
            "Disconnected",
            "test",
            4,
            List.of(simpleFinalNode("left"), simpleFinalNode("right")),
            List.of(),
            Map.of(),
            null,
            null
        ));

        WorkflowValidator.ValidationResult validation = workflowService.validateGraph(disconnected);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors())
            .contains("Workflow must have exactly one start node; found: left, right");
    }

    @Test
    void executableValidationRejectsWorkflowWithoutStartPath() {
        WorkflowNode first = simpleFinalNode("first");
        WorkflowNode second = simpleFinalNode("second");
        WorkflowDefinition noStart = new WorkflowDefinition(
            null,
            2,
            "No Start",
            "test",
            4,
            List.of(first, second),
            List.of(
                new WorkflowRoute("r1", "first", "out", "second", "in", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r2", "second", "out", "first", "in", WorkflowRouteType.MAP_OUTPUT, null)
            ),
            Map.of(),
            null,
            null
        );

        WorkflowValidator.ValidationResult validation = workflowService.validateGraph(noStart);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.errors()).contains(
            "Workflow contains a cycle; v2 graph must be a DAG",
            "Workflow must have a start node with no incoming dependency routes"
        );
    }

    @Test
    void rejectsLegacyInputBindingsInV2() {
        WorkflowNode legacy = new WorkflowNode(
            "legacy", WorkflowNodeType.TASK, "missing", "legacy", null,
            List.of(), List.of(), Map.of(), false,
            List.of(new WorkflowBinding("in", "source", "out", null)),
            null, null
        );

        assertThatThrownBy(() -> workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Bad", "", 4,
            List.of(legacy), List.of(), Map.of(), null, null
        ))).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("legacy inputBindings");
    }

    @Test
    void gateRequiresApproveAndRejectBranches() {
        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode done = simpleFinalNode("done");

        WorkflowDefinition def = new WorkflowDefinition(
            null, 2, "Gate", "", 4,
            List.of(gate, done),
            List.of(new WorkflowRoute("r1", "gate", null, "done", null, WorkflowRouteType.CONTROL, "APPROVED")),
            Map.of(), null, null
        );

        assertThatThrownBy(() -> workflowService.saveDefinitionValidated(def))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing REJECTED control route");
    }

    @Test
    void draftSaveAllowsIncrementalApprovalWorkflowBeforeExecutableValidation() {
        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = simpleFinalNode("approved");
        WorkflowNode rejected = simpleFinalNode("rejected");

        WorkflowDefinition gateOnly = workflowService.saveDefinition(new WorkflowDefinition(
            null, 2, "Incremental Gate", "", 4,
            List.of(gate), List.of(), Map.of(), null, null
        ));
        WorkflowValidator.ValidationResult gateOnlyValidation = workflowService.validateGraph(gateOnly);
        assertThat(gateOnlyValidation.valid()).isFalse();
        assertThat(gateOnlyValidation.errors()).contains(
            "Gate node 'gate' is missing APPROVED control route",
            "Gate node 'gate' is missing REJECTED control route"
        );

        WorkflowDefinition oneBranch = workflowService.saveDefinition(new WorkflowDefinition(
            gateOnly.id(), 2, "Incremental Gate", "", 4,
            List.of(gate, approved, rejected),
            List.of(new WorkflowRoute("approved-route", "gate", null, "approved", null,
                WorkflowRouteType.CONTROL, WorkflowRoute.OUTCOME_APPROVED)),
            Map.of(), null, null
        ));
        WorkflowValidator.ValidationResult oneBranchValidation = workflowService.validateGraph(oneBranch);
        assertThat(oneBranchValidation.valid()).isFalse();
        assertThat(oneBranchValidation.errors()).contains("Gate node 'gate' is missing REJECTED control route");

        WorkflowDefinition complete = workflowService.saveDefinition(new WorkflowDefinition(
            gateOnly.id(), 2, "Incremental Gate", "", 4,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("approved-route", "gate", null, "approved", null,
                    WorkflowRouteType.CONTROL, WorkflowRoute.OUTCOME_APPROVED),
                new WorkflowRoute("rejected-route", "gate", null, "rejected", null,
                    WorkflowRouteType.CONTROL, WorkflowRoute.OUTCOME_REJECTED)
            ),
            Map.of(), null, null
        ));

        assertThat(workflowService.validateGraph(complete).valid()).isTrue();
        assertThat(workflowService.saveDefinitionValidated(complete).id()).isEqualTo(gateOnly.id());
    }

    @Test
    void draftSaveAllowsTaskBeforeRequiredRuntimeInputsAreConnected() {
        PlanDefinition source = task("source", List.of(), List.of(field("topic", PlanFieldType.STRING, true)));
        PlanDefinition worker = task("worker", List.of(field("topic", PlanFieldType.STRING, true)), List.of());

        WorkflowNode sourceNode = taskNode("source", source.id());
        WorkflowNode workerNode = taskNode("worker", worker.id());

        WorkflowDefinition incomplete = workflowService.saveDefinition(new WorkflowDefinition(
            null, 2, "Incremental Task Inputs", "", 4,
            List.of(sourceNode, workerNode), List.of(), Map.of(), null, null
        ));
        WorkflowValidator.ValidationResult incompleteValidation = workflowService.validateGraph(incomplete);
        assertThat(incompleteValidation.valid()).isFalse();
        assertThat(incompleteValidation.errors())
            .contains("TASK node 'worker': required input 'topic' is not satisfied by incoming routes or config");

        WorkflowDefinition complete = workflowService.saveDefinition(new WorkflowDefinition(
            incomplete.id(), 2, "Incremental Task Inputs", "", 4,
            List.of(sourceNode, workerNode),
            List.of(new WorkflowRoute("topic-route", "source", "topic", "worker", "topic",
                WorkflowRouteType.MAP_OUTPUT, null)),
            Map.of(), null, null
        ));

        assertThat(workflowService.validateGraph(complete).valid()).isTrue();
    }

    @Test
    void runsParallelFanOutAndProducesFinalOutputsAndArtifacts() throws Exception {
        PlanDefinition source = task("source", List.of(), List.of(field("result", PlanFieldType.STRING, true)));
        PlanDefinition worker = task("worker", List.of(field("in", PlanFieldType.STRING, true)),
            List.of(field("value", PlanFieldType.STRING, true)));

        WorkflowNode sourceNode = taskNode("source", source.id());
        WorkflowNode a = taskNode("a", worker.id());
        WorkflowNode b = taskNode("b", worker.id());
        WorkflowNode c = taskNode("c", worker.id());
        WorkflowNode d = taskNode("d", worker.id());
        WorkflowNode finalNode = new WorkflowNode(
            "final",
            WorkflowNodeType.FINAL_OUTPUT,
            null,
            "Final",
            null,
            List.of(
                new WorkflowPort("a_out", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("b_out", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("c_out", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("d_out", PlanFieldType.STRING, false, false, null)
            ),
            List.of(
                new WorkflowPort("a", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("b", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("c", PlanFieldType.STRING, false, false, null),
                new WorkflowPort("d", PlanFieldType.STRING, false, false, null)
            ),
            Map.of("finalOutputs", Map.of(
                "a", "a_out",
                "b", "b_out",
                "c", "c_out",
                "d", "d_out"
            )),
            false,
            List.of(),
            null,
            null
        );

        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Parallel Fanout", "", 4,
            List.of(sourceNode, a, b, c, d, finalNode),
            List.of(
                new WorkflowRoute("r1", "source", "result", "a", "in", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r2", "source", "result", "b", "in", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r3", "source", "result", "c", "in", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r4", "source", "result", "d", "in", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r5", "a", "value", "final", "a_out", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r6", "b", "value", "final", "b_out", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r7", "c", "value", "final", "c_out", WorkflowRouteType.MAP_OUTPUT, null),
                new WorkflowRoute("r8", "d", "value", "final", "d_out", WorkflowRouteType.MAP_OUTPUT, null)
            ),
            Map.of(),
            null,
            null
        ));

        AtomicInteger counter = new AtomicInteger();
        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { }
            if (planId.equals(source.id())) {
                return completedPlanRun(planRunId, planId, Map.of(), Map.of("result", "seed"));
            }
            int idx = counter.incrementAndGet();
            return completedPlanRun(planRunId, planId, inputs, Map.of("value", "worker-" + idx));
        });

        Instant started = Instant.now();
        WorkflowRun run = workflowService.startRun(def.id());
        WorkflowRun finished = pollForTerminal(run.id());
        long elapsedMs = Duration.between(started, Instant.now()).toMillis();

        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(elapsedMs).isLessThan(900); // source + parallel workers + final
        assertThat(finished.finalOutputs()).containsKeys("a", "b", "c", "d");
        assertThat(finished.artifactIds()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void finalOutputsMaterializeIntoDurableWorkflowOutputDirectory() throws Exception {
        WorkflowNode finalNode = new WorkflowNode(
            "final",
            WorkflowNodeType.FINAL_OUTPUT,
            null,
            "Final",
            null,
            List.of(new WorkflowPort("message", PlanFieldType.STRING, false, false, null)),
            List.of(),
            Map.of(),
            false,
            List.of(),
            "final message",
            null
        );
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Temp Output Workflow", "", 1,
            List.of(finalNode), List.of(), Map.of(), null, null
        ));

        WorkflowRun run = workflowService.startRun(def.id());
        WorkflowRun finished = pollForTerminal(run.id());

        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertStoredRelative(finished.workspacePath(), "workspace/system/runs/");
        Path resolvedWorkspace = resolveStored(finished.workspacePath());
        assertThat(resolvedWorkspace)
            .startsWith(workspaceDirectoryService.dataRoot().resolve("workspace/system/runs"));
        assertThat(finished.outputDir()).isNotEqualTo(finished.workspacePath());
        assertStoredRelative(finished.outputDir(), "workspace/system/runs/");
        Path resolvedOutput = resolveStored(finished.outputDir());
        assertThat(resolvedOutput)
            .isEqualTo(resolvedWorkspace.resolve("outputs"));
        assertThat(finished.artifactIds()).hasSize(2);
        List<RunOutputArtifact> artifacts = finished.artifactIds().stream()
            .map(outputArtifactService::getArtifact)
            .toList();
        assertThat(artifacts)
            .extracting(RunOutputArtifact::filePath)
            .anySatisfy(path -> assertStoredRelative(path, "workspace/system/runs/"))
            .anySatisfy(path -> assertStoredRelative(path, "workspace/system/outputs/"));
        assertThat(Files.exists(resolvedWorkspace)).isTrue();
        assertThat(Files.isDirectory(resolvedOutput)).isTrue();
        assertThat(artifacts)
            .allSatisfy(artifact -> assertThat(Files.isRegularFile(resolveStored(artifact.filePath()))).isTrue());
    }

    @Test
    void workflowOutputsCarryDirectAssignmentAttribution() throws Exception {
        WorkflowNode finalNode = new WorkflowNode(
            "final",
            WorkflowNodeType.FINAL_OUTPUT,
            null,
            "Final",
            null,
            List.of(new WorkflowPort("message", PlanFieldType.STRING, false, false, null)),
            List.of(),
            Map.of(),
            false,
            List.of(),
            "final message",
            null
        );
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Attributed Workflow", "", 1,
            List.of(finalNode), List.of(), Map.of(), null, null
        ));

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "Agent 1", "job-1", "project-1", "workspace-effective-1", "JOB_WORKFLOW_ITEM",
            null, null
        ).withJobRun("assignment-1", "job-run-1"));
        try {
            WorkflowRun run = workflowService.startRun(def.id());
            WorkflowRun finished = pollForTerminal(run.id());

            assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            assertThat(finished.agentId()).isEqualTo("agent-1");
            assertThat(finished.jobId()).isEqualTo("job-1");
            assertThat(finished.jobAssignmentId()).isEqualTo("assignment-1");
            assertThat(finished.jobRunId()).isEqualTo("job-run-1");
            assertThat(finished.projectId()).isEqualTo("project-1");
            assertThat(finished.workspaceId()).isEqualTo("workspace-effective-1");
            assertThat(finished.runType()).isEqualTo("JOB_WORKFLOW_ITEM");

            RunOutputArtifact artifact = outputArtifactService.getArtifact(finished.artifactIds().getFirst());
            assertThat(artifact.agentId()).isEqualTo("agent-1");
            assertThat(artifact.jobId()).isEqualTo("job-1");
            assertThat(artifact.jobAssignmentId()).isEqualTo("assignment-1");
            assertThat(artifact.jobRunId()).isEqualTo("job-run-1");
            assertThat(artifact.projectId()).isEqualTo("project-1");
            assertThat(artifact.workspaceId()).isEqualTo("workspace-effective-1");
            assertThat(artifact.runType()).isEqualTo("JOB_WORKFLOW_ITEM");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void taskNodesInheritCallerOrchestrationContextAcrossAsyncExecution() throws Exception {
        PlanDefinition task = task("context", List.of(), List.of(field("status", PlanFieldType.STRING, true)));
        WorkflowNode node = taskNode("context-node", task.id());
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Async Context Characterization", "", 1,
            List.of(node), List.of(), Map.of(), null, null
        ));

        AtomicReference<OrchestrationTaskContext> contextSeenByTaskNode = new AtomicReference<>();
        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) -> {
            contextSeenByTaskNode.set(OrchestrationTaskContextHolder.current());
            return completedPlanRun(planRunId, planId, inputs, Map.of("status", "ok"));
        });

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "Agent 1", "job-1", "project-1", "workspace-1", "WORKFLOW_RUN",
            tempDir.resolve("caller-workspace").toString(),
            tempDir.resolve("caller-outputs").toString()
        ));
        try {
            WorkflowRun run = workflowService.startRun(def.id());
            WorkflowRun finished = pollForTerminal(run.id());

            assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            OrchestrationTaskContext seen = contextSeenByTaskNode.get();
            assertThat(seen).isNotNull();
            assertThat(seen.agentId()).isEqualTo("agent-1");
            assertThat(seen.projectId()).isEqualTo("project-1");
            assertStoredRelative(finished.workspacePath(), "workspace/agent-1/runs/");
            assertStoredRelative(finished.outputDir(), "workspace/agent-1/runs/");
            assertThat(seen.hostWorkspacePath()).isEqualTo(resolveStored(finished.workspacePath()).toString());
            assertThat(seen.hostRunPath()).isEqualTo(resolveStored(finished.workspacePath()).toString());
            assertThat(seen.hostOutputPath()).isEqualTo(resolveStored(finished.outputDir()).toString());
            assertThat(seen.hostDurableWorkspacePath())
                .isEqualTo(workspaceDirectoryService.dataRoot().resolve("projects/project-1").toString());
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void workflowAndDelegatedTaskRunsCopyDisplayNameFromOrchestrationContext() throws Exception {
        PlanDefinition childTask = task("named child", List.of(), List.of());
        WorkflowNode node = delegationNode("named-child-node", childTask.id());
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Named Workflow", "", 1,
            List.of(node), List.of(), Map.of(), null, null
        ));

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "Agent 1", null, null, "workspace-1", "WORKFLOW_RUN",
            "Daily workflow run",
            tempDir.resolve("caller-workspace").toString(),
            tempDir.resolve("caller-outputs").toString(),
            null, null, null, null, null, null, null, null
        ));
        try {
            WorkflowRun run = workflowService.startRun(def.id());
            WorkflowRun finished = pollForTerminal(run.id());

            assertThat(finished.runDisplayName()).isEqualTo("Daily workflow run");
            String childRunId = finished.nodeRuns().getFirst().outputValues().get("childRunId").toString();
            assertThat(planService.getRun(childRunId).runDisplayName()).isEqualTo("Daily workflow run");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void delegationChildRunUsesActiveEffectiveWorkspaceContext() throws Exception {
        PlanDefinition childTask = task("delegated", List.of(), List.of());
        WorkflowNode delegation = new WorkflowNode(
            "delegate",
            WorkflowNodeType.DELEGATION,
            childTask.id(),
            "delegate",
            null,
            List.of(),
            List.of(),
            Map.of(),
            false,
            List.of(),
            null,
            null
        );
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Delegation Context", "", 1,
            List.of(delegation), List.of(), Map.of(), null, null
        ));

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "Agent 1", "job-1", "project-1", "workspace-1", "WORKFLOW_RUN",
            null, null
        ));
        try {
            WorkflowRun run = workflowService.startRun(def.id());
            WorkflowRun finished = pollForTerminal(run.id());

            assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
            String childRunId = finished.nodeRuns().stream()
                .filter(node -> node.nodeKey().equals("delegate"))
                .findFirst()
                .orElseThrow()
                .outputValues()
                .get("childRunId")
                .toString();
            PlanRun childRun = planService.getRun(childRunId);
            assertStoredRelative(childRun.outputDirectory(), "workspace/agent-1/runs/");
            assertThat(resolveStored(childRun.outputDirectory()))
                .startsWith(workspaceDirectoryService.dataRoot()
                    .resolve("workspace/agent-1/runs"));
            assertThat(childRun.workspaceId()).isNotBlank();
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void resumeFollowsApprovedBranchAndSkipsRejectedBranch() throws Exception {
        PlanDefinition branchTask = task("branch", List.of(), List.of(field("status", PlanFieldType.STRING, true)));

        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = taskNode("approved", branchTask.id());
        WorkflowNode rejected = taskNode("rejected", branchTask.id());

        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Approval Branch", "", 2,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("c1", "gate", null, "approved", null, WorkflowRouteType.CONTROL, "APPROVED"),
                new WorkflowRoute("c2", "gate", null, "rejected", null, WorkflowRouteType.CONTROL, "REJECTED")
            ),
            Map.of(), null, null
        ));

        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) ->
            completedPlanRun(planRunId, planId, inputs, Map.of("status", "ok")));

        WorkflowRun run = workflowService.startRun(def.id());
        WorkflowRun waiting = pollForWaiting(run.id());
        assertThat(waiting.outputDir()).isNotEqualTo(waiting.workspacePath());
        assertStoredRelative(waiting.workspacePath(), "workspace/system/runs/");
        assertStoredRelative(waiting.outputDir(), "workspace/system/runs/");
        assertThat(Files.isDirectory(resolveStored(waiting.workspacePath()))).isTrue();
        assertThat(Files.isDirectory(resolveStored(waiting.outputDir()))).isTrue();
        String messageId = String.valueOf(waiting.nodeRuns().stream()
            .filter(n -> n.nodeKey().equals("gate"))
            .findFirst().orElseThrow().outputValues().get("messageId"));

        inboxService.respondUserApproval(messageId, true, "yes");
        workflowService.resumeRun(waiting.id());

        WorkflowRun finished = pollForTerminal(waiting.id());
        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(nodeStatus(finished, "approved")).isEqualTo(WorkflowNodeRunStatus.COMPLETED);
        assertThat(nodeStatus(finished, "rejected")).isEqualTo(WorkflowNodeRunStatus.SKIPPED);
    }

    @Test
    void resumeFollowsRejectedBranchAndSkipsApprovedBranch() throws Exception {
        PlanDefinition branchTask = task("branch", List.of(), List.of(field("status", PlanFieldType.STRING, true)));

        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = taskNode("approved", branchTask.id());
        WorkflowNode rejected = taskNode("rejected", branchTask.id());

        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Reject Branch", "", 2,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("c1", "gate", null, "approved", null, WorkflowRouteType.CONTROL, "APPROVED"),
                new WorkflowRoute("c2", "gate", null, "rejected", null, WorkflowRouteType.CONTROL, "REJECTED")
            ),
            Map.of(), null, null
        ));

        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) ->
            completedPlanRun(planRunId, planId, inputs, Map.of("status", "rejected-path")));

        WorkflowRun run = workflowService.startRun(def.id());
        WorkflowRun waiting = pollForWaiting(run.id());
        String messageId = String.valueOf(waiting.nodeRuns().stream()
            .filter(n -> n.nodeKey().equals("gate"))
            .findFirst().orElseThrow().outputValues().get("messageId"));

        inboxService.respondUserApproval(messageId, false, "no");
        workflowService.resumeRun(waiting.id());

        WorkflowRun finished = pollForTerminal(waiting.id());
        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(nodeStatus(finished, "approved")).isEqualTo(WorkflowNodeRunStatus.SKIPPED);
        assertThat(nodeStatus(finished, "rejected")).isEqualTo(WorkflowNodeRunStatus.COMPLETED);
    }

    @Test
    void resumeSupportsLegacyAbsoluteCurrentRootWorkflowPaths() throws Exception {
        PlanDefinition branchTask = task("legacy-current", List.of(), List.of(field("status", PlanFieldType.STRING, true)));
        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = taskNode("approved", branchTask.id());
        WorkflowNode rejected = taskNode("rejected", branchTask.id());
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Legacy Current Resume", "", 2,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("c1", "gate", null, "approved", null, WorkflowRouteType.CONTROL, "APPROVED"),
                new WorkflowRoute("c2", "gate", null, "rejected", null, WorkflowRouteType.CONTROL, "REJECTED")
            ),
            Map.of(), null, null
        ));
        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) -> {
            assertThat(Path.of(workspacePath).isAbsolute()).isTrue();
            return completedPlanRun(planRunId, planId, inputs, Map.of("status", "ok"));
        });

        WorkflowRun waiting = pollForWaiting(workflowService.startRun(def.id()).id());
        Path workspacePath = resolveStored(waiting.workspacePath()).toRealPath();
        Path outputDir = resolveStored(waiting.outputDir()).toRealPath();
        jdbcTemplate.update("update workflow_runs set workspace_path = ?, output_dir = ? where id = ?",
            workspacePath.toString(), outputDir.toString(), waiting.id());
        String messageId = String.valueOf(waiting.nodeRuns().stream()
            .filter(n -> n.nodeKey().equals("gate"))
            .findFirst().orElseThrow().outputValues().get("messageId"));

        inboxService.respondUserApproval(messageId, true, "yes");
        WorkflowRun finished = workflowService.resumeRunSynchronously(waiting.id(), null, WorkflowExecutionObserver.NOOP);

        assertThat(finished.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(outputArtifactService.artifactsForRun(finished.id())).isNotEmpty();
    }

    @Test
    void staleOldRootAbsoluteWorkflowOutputPathFailsWithoutMutatingOldRoot() throws Exception {
        PlanDefinition branchTask = task("stale-output", List.of(), List.of(field("status", PlanFieldType.STRING, true)));
        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = taskNode("approved", branchTask.id());
        WorkflowNode rejected = taskNode("rejected", branchTask.id());
        WorkflowDefinition def = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Stale Resume", "", 2,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("c1", "gate", null, "approved", null, WorkflowRouteType.CONTROL, "APPROVED"),
                new WorkflowRoute("c2", "gate", null, "rejected", null, WorkflowRouteType.CONTROL, "REJECTED")
            ),
            Map.of(), null, null
        ));
        workflowRunner.setTaskNodeExecutor((planId, planRunId, inputs, workspacePath) ->
            completedPlanRun(planRunId, planId, inputs, Map.of("status", "ok")));
        Path oldOutput = Files.createDirectories(tempDir.resolve("old-root/root/agents/system/workspace/outputs/workflows/old/run"));

        WorkflowRun waiting = pollForWaiting(workflowService.startRun(def.id()).id());
        jdbcTemplate.update("update workflow_runs set output_dir = ? where id = ?",
            oldOutput.toString(), waiting.id());
        String messageId = String.valueOf(waiting.nodeRuns().stream()
            .filter(n -> n.nodeKey().equals("gate"))
            .findFirst().orElseThrow().outputValues().get("messageId"));

        inboxService.respondUserApproval(messageId, true, "yes");

        assertThatThrownBy(() -> workflowService.resumeRunSynchronously(waiting.id(), null, WorkflowExecutionObserver.NOOP))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stale or outside current data root");
        try (var stream = Files.list(oldOutput)) {
            assertThat(stream).isEmpty();
        }
    }

    private WorkflowNode simpleFinalNode(String key) {
        return new WorkflowNode(key, WorkflowNodeType.FINAL_OUTPUT, null,
            key, null, List.of(), List.of(), Map.of(), false, List.of(), null, null);
    }

    private WorkflowNode taskNode(String key, String planId) {
        return new WorkflowNode(
            key,
            WorkflowNodeType.TASK,
            planId,
            key,
            null,
            List.of(),
            List.of(),
            Map.of(),
            false,
            List.of(),
            null,
            null
        );
    }

    private WorkflowNode delegationNode(String key, String planId) {
        return new WorkflowNode(
            key,
            WorkflowNodeType.DELEGATION,
            planId,
            key,
            null,
            List.of(),
            List.of(),
            Map.of(),
            false,
            List.of(),
            null,
            null
        );
    }

    private PlanFieldDefinition field(String name, PlanFieldType type, boolean required) {
        return new PlanFieldDefinition(name, type, false, name, required, null);
    }

    private PlanDefinition task(String title, List<PlanFieldDefinition> inputs, List<PlanFieldDefinition> outputs) {
        return planService.saveTask(new PlanDefinition(
            null, PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            title, title, "goal", null,
            List.of(), inputs, outputs,
            List.of(), List.of(new PlanStep(1, "step")), List.of("criterion"),
            List.of(), List.of(), null, null, null, null,
            null, List.of(), 0, 0, null, null, null, null
        ));
    }

    private PlanRun completedPlanRun(String runId, String planId, Map<String, Object> inputs, Map<String, Object> outputs) {
        return new PlanRun(
            runId,
            planId,
            PlanRunStatus.COMPLETED,
            inputs,
            outputs,
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            List.of(),
            null,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            Instant.now()
        );
    }

    private WorkflowRun pollForWaiting(String runId) throws Exception {
        for (int i = 0; i < 80; i++) {
            WorkflowRun run = workflowService.getRun(runId);
            if (run.status() == WorkflowRunStatus.WAITING) {
                return run;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Run did not reach WAITING: " + runId);
    }

    private WorkflowRun pollForTerminal(String runId) throws Exception {
        for (int i = 0; i < 120; i++) {
            WorkflowRun run = workflowService.getRun(runId);
            if (run.isTerminal()) {
                return run;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Run did not complete: " + runId);
    }

    private void assertStoredRelative(String value, String expectedPrefix) {
        assertThat(value).isNotBlank();
        assertThat(Path.of(value).isAbsolute()).isFalse();
        assertThat(value).startsWith(expectedPrefix);
        assertThat(value).doesNotContain(workspaceDirectoryService.dataRoot().toString());
        assertThat(value).doesNotContain("\\");
    }

    private Path resolveStored(String value) {
        return workspaceDirectoryService.dataRoot().resolve(value.replace('\\', '/')).normalize();
    }

    private WorkflowNodeRunStatus nodeStatus(WorkflowRun run, String nodeKey) {
        return run.nodeRuns().stream()
            .filter(n -> n.nodeKey().equals(nodeKey))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing node: " + nodeKey))
            .status();
    }
}
