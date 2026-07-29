package io.mindspice.magenta2.api.web;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowValidator;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class PublicRunSubmissionControllerTest {

    @Test
    void planRunStreamSubmitsHighPriorityTaskAssignment() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        PlanController controller = new PlanController(
            new StubPlanService(),
            null,
            assignmentService,
            new StubAgentProfileService()
        );

        controller.streamRun("plan-1", new PlanController.PlanRunRequest(
            Map.of("prompt", "ship it"),
            "conversation-1",
            null,
            null,
            "project-1",
            "agent",
            "agent-1",
            "model-a",
            null
        ));

        assertThat(assignmentService.lastRequest.assignmentType()).isEqualTo(AssignmentType.TASK_RUN);
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.projectId()).isEqualTo("project-1");
        assertThat(assignmentService.lastRequest.workspaceType()).isEqualTo("agent");
        assertThat(assignmentService.lastRequest.workspaceKey()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.modelOverride()).isEqualTo("model-a");
        assertThat(assignmentService.lastRequest.input()).containsEntry("taskId", "plan-1");
        assertThat(assignmentService.lastRequest.input()).containsEntry("conversationId", "conversation-1");
        assertThat(assignmentService.lastRequest.input()).containsEntry("inputValues", Map.of("prompt", "ship it"));
    }

    @Test
    void planRunStreamEmitsSubmittedSseEventName() throws Exception {
        PlanController controller = new PlanController(
            new StubPlanService(),
            null,
            new CapturingAssignmentService(),
            new StubAgentProfileService()
        );

        SseEmitter emitter = controller.streamRun("plan-1", new PlanController.PlanRunRequest(
            Map.of("prompt", "ship it"),
            "conversation-1",
            null,
            null,
            null,
            "model-a",
            null
        ));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anyMatch(event -> event.contains("event:submitted"));
        assertThat(captured.events).noneMatch(event -> event.contains("event:TaskExecutionEvent"));
    }

    @Test
    void planRunStreamEmitsFailedSseEventNameForSubmissionErrors() throws Exception {
        PlanController controller = new PlanController(
            new StubPlanService(),
            null,
            new CapturingAssignmentService(),
            new EmptyAgentProfileService()
        );

        SseEmitter emitter = controller.streamRun("plan-1", new PlanController.PlanRunRequest(
            Map.of("prompt", "ship it"),
            "conversation-1",
            null,
            null,
            null,
            null,
            null
        ));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anyMatch(event -> event.contains("event:failed"));
        assertThat(captured.events).noneMatch(event -> event.contains("event:TaskExecutionEvent"));
    }

    @Test
    void taskRunStreamSubmitsHighPriorityTaskAssignment() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        TaskController controller = new TaskController(
            new TaskService(new StubPlanService()),
            null,
            null,
            assignmentService,
            new StubAgentProfileService()
        );

        controller.streamRun("plan-1", new TaskController.TaskRunRequest(
            Map.of("prompt", "ship it"),
            null,
            null,
            "job-1",
            "project-1",
            null,
            null
        ));

        assertThat(assignmentService.lastRequest.assignmentType()).isEqualTo(AssignmentType.TASK_RUN);
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.jobId()).isEqualTo("job-1");
        assertThat(assignmentService.lastRequest.projectId()).isEqualTo("project-1");
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.input()).containsEntry("taskId", "plan-1");
        assertThat(assignmentService.lastRequest.input()).containsEntry("inputValues", Map.of("prompt", "ship it"));
    }

    @Test
    void taskRunStreamRejectsMissingRunNameForNonJobSubmission() throws Exception {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        TaskController controller = new TaskController(
            new TaskService(new StubPlanService()),
            null,
            null,
            assignmentService,
            new StubAgentProfileService()
        );

        SseEmitter emitter = controller.streamRun("plan-1", new TaskController.TaskRunRequest(
            Map.of("prompt", "ship it"),
            null,
            null,
            null,
            "project-1",
            null,
            null
        ));
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anyMatch(event -> event.contains("Run name is required for task submissions."));
        assertThat(assignmentService.lastRequest).isNull();
    }

    @Test
    void workflowRunSubmitsHighPriorityWorkflowAssignment() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        WorkflowController controller = new WorkflowController(
            new StubWorkflowService(),
            (InboxService) null,
            assignmentService,
            new StubAgentProfileService()
        );

        WorkAssignment assignment = controller.startRun("workflow-1", new WorkflowController.WorkflowRunRequest(
            null,
            null,
            "project-1",
            null,
            null,
            "Daily workflow run",
            null,
            null
        ));

        assertThat(assignment.assignmentType()).isEqualTo(AssignmentType.WORKFLOW_RUN);
        assertThat(assignment.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.runDisplayName()).isEqualTo("Daily workflow run");
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.projectId()).isEqualTo("project-1");
        assertThat(assignmentService.lastRequest.input()).containsEntry("workflowId", "workflow-1");
    }

    @Test
    void workflowRunRejectsMissingRunNameForNonJobSubmission() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        WorkflowController controller = new WorkflowController(
            new StubWorkflowService(),
            (InboxService) null,
            assignmentService,
            new StubAgentProfileService()
        );

        assertThatThrownBy(() -> controller.startRun("workflow-1", new WorkflowController.WorkflowRunRequest(
            null,
            null,
            "project-1",
            null,
            null
        )))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Run name is required for workflow submissions.");
        assertThat(assignmentService.lastRequest).isNull();
    }

    @Test
    void jobRunSubmitsHighPriorityJobAssignment() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        StubJobService jobService = new StubJobService();
        JobController controller = new JobController(
            jobService,
            new StubOutputArtifactService(),
            assignmentService,
            new StubAgentProfileService()
        );

        WorkAssignment assignment = controller.startRun("job-1", null);

        assertThat(assignment.assignmentType()).isEqualTo(AssignmentType.JOB_RUN);
        assertThat(assignment.priority()).isEqualTo(9);
        assertThat(assignment.jobId()).isEqualTo("job-1");
        assertThat(assignmentService.lastRequest.assignmentType()).isEqualTo(AssignmentType.JOB_RUN);
        assertThat(assignmentService.lastRequest.agentId()).isEqualTo("agent-1");
        assertThat(assignmentService.lastRequest.jobId()).isEqualTo("job-1");
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.input()).containsEntry("jobId", "job-1");
        assertThat(jobService.startRunCalls).isZero();
    }

    @Test
    void planSubmitDefaultsToHighPriority() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        PlanController controller = new PlanController(
            new StubPlanService(),
            null,
            assignmentService,
            new StubAgentProfileService()
        );

        WorkAssignment assignment = controller.submitToAgent("plan-1", new PlanController.SubmitRequest(
            "agent-1",
            null,
            null,
            null
        ));

        assertThat(assignment.priority()).isEqualTo(9);
        assertThat(assignmentService.lastRequest.priority()).isEqualTo(9);
    }

    @Test
    void workflowRunRejectsWhenNoAgentAvailable() {
        WorkflowController controller = new WorkflowController(
            new StubWorkflowService(),
            (InboxService) null,
            new CapturingAssignmentService(),
            new EmptyAgentProfileService()
        );

        assertThatThrownBy(() -> controller.startRun("workflow-1", new WorkflowController.WorkflowRunRequest(
            null,
            null,
            null,
            null,
            null,
            "No agent workflow run",
            null,
            null
        )))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("No active agents available");
    }

    @Test
    void workflowRunRejectsNonExecutableWorkflowBeforeAssignmentSubmission() {
        CapturingAssignmentService assignmentService = new CapturingAssignmentService();
        WorkflowController controller = new WorkflowController(
            new EmptyWorkflowService(),
            (InboxService) null,
            assignmentService,
            new StubAgentProfileService()
        );

        assertThatThrownBy(() -> controller.startRun("workflow-1", null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("at least one executable node");
        assertThat(assignmentService.lastRequest).isNull();
    }

    @Test
    void workflowRunStreamEmitsFailedEventForNonExecutableWorkflow() throws Exception {
        WorkflowController controller = new WorkflowController(
            new EmptyWorkflowService(),
            (InboxService) null,
            new CapturingAssignmentService(),
            new StubAgentProfileService()
        );

        SseEmitter emitter = controller.streamRun("workflow-1", null);
        CapturedSse captured = initializeEmitter(emitter);

        assertThat(captured.completed.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.events).anyMatch(event -> event.contains("event:failed"));
        assertThat(captured.events).anyMatch(event -> event.contains("at least one executable node"));
    }

    private static PlanDefinition plan(String id) {
        return new PlanDefinition(
            id,
            PlanKind.TASK_TEMPLATE,
            PlanStatus.APPROVED,
            "Test Plan",
            null,
            "Goal",
            null,
            List.of("Deliverable"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("Validate"),
            List.of(),
            List.of(),
            null,
            null,
            null,
            null,
            null,
            List.of(),
            0,
            0,
            null,
            null,
            Instant.now(),
            Instant.now()
        );
    }

    private static class StubPlanService extends PlanService {
        StubPlanService() {
            super(null, null);
        }

        @Override
        public PlanDefinition getTask(String id) {
            if ("plan-1".equals(id)) {
                return plan(id);
            }
            throw new IllegalStateException("Task not found: " + id);
        }
    }

    private static class StubWorkflowService extends WorkflowService {
        StubWorkflowService() {
            super(null, null, null);
        }

        @Override
        public WorkflowDefinition getDefinition(String id) {
            if ("workflow-1".equals(id)) {
                return new WorkflowDefinition(id, WorkflowDefinition.CURRENT_SCHEMA_VERSION,
                    "Workflow", null, WorkflowDefinition.DEFAULT_MAX_CONCURRENCY,
                    List.of(), List.of(), Map.of(), Instant.now(), Instant.now());
            }
            throw new IllegalArgumentException("Workflow not found: " + id);
        }

        @Override
        public WorkflowValidator.ValidationResult validateGraph(WorkflowDefinition definition) {
            return new WorkflowValidator.ValidationResult(List.of(), List.of());
        }
    }

    private static class EmptyWorkflowService extends StubWorkflowService {
        @Override
        public WorkflowDefinition getDefinition(String id) {
            return new WorkflowDefinition(id, WorkflowDefinition.CURRENT_SCHEMA_VERSION,
                "Workflow", null, WorkflowDefinition.DEFAULT_MAX_CONCURRENCY,
                List.of(), List.of(), Map.of(), Instant.now(), Instant.now());
        }

        @Override
        public WorkflowValidator.ValidationResult validateGraph(WorkflowDefinition definition) {
            return new WorkflowValidator.ValidationResult(
                List.of("Workflow must contain at least one executable node before validation, submission, or run"),
                List.of()
            );
        }
    }

    private static class StubJobService extends JobService {
        private int startRunCalls;

        StubJobService() {
            super(null, null, null, null);
        }

        @Override
        public JobDefinition getDefinition(String id) {
            if ("job-1".equals(id)) {
                return new JobDefinition(
                    id,
                    "agent-1",
                    null,
                    "agent",
                    "agent-1",
                    null,
                    "DRAFT",
                    "Job",
                    null,
                    List.of(),
                    "CODING_CENTRIC",
                    "model-a",
                    null,
                    1,
                    Instant.now(),
                    Instant.now()
                );
            }
            throw new IllegalArgumentException("Job not found: " + id);
        }

        @Override
        public io.mindspice.magenta2.ai.orchestration.runtime.JobRun startRun(String jobId) {
            startRunCalls++;
            throw new AssertionError("Public job run routes must submit assignments");
        }
    }

    private static class StubOutputArtifactService extends OutputArtifactService {
        StubOutputArtifactService() {
            this(outputArtifactDeps());
        }

        private StubOutputArtifactService(OutputArtifactDeps deps) {
            super(deps.repository(), deps.directoryService(),
                new RootRelativePathService(deps.directoryService()), deps.objectMapper());
        }
    }

    private static OutputArtifactDeps outputArtifactDeps() {
        try {
            Path dataRoot = Files.createTempDirectory("public-run-output");
            WorkspaceDirectoryService directories = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
            OutputArtifactRepository repository = new OutputArtifactRepository(new JdbcTemplate(
                new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true)
            ));
            return new OutputArtifactDeps(repository, directories, new ObjectMapper());
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create output artifact test dependencies", exception);
        }
    }

    private record OutputArtifactDeps(
        OutputArtifactRepository repository,
        WorkspaceDirectoryService directoryService,
        ObjectMapper objectMapper
    ) {
    }

    private static class StubAgentProfileService extends AgentProfileService {
        StubAgentProfileService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public List<AgentProfile> list() {
            return List.of(new AgentProfile(
                "agent-1",
                "Agent",
                AgentProfileStatus.ACTIVE,
                "model-a",
                null,
                List.of(),
                List.of(),
                true,
                Instant.now(),
                Instant.now()
            ));
        }
    }

    private static class EmptyAgentProfileService extends StubAgentProfileService {
        @Override
        public List<AgentProfile> list() {
            return List.of();
        }
    }

    private CapturedSse initializeEmitter(SseEmitter emitter) throws Exception {
        CapturedSse captured = new CapturedSse();
        Class<?> handlerType = Class.forName(
            "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler"
        );
        Object handler = Proxy.newProxyInstance(
            handlerType.getClassLoader(),
            new Class<?>[] { handlerType },
            (proxy, method, args) -> {
                if ("send".equals(method.getName()) && args[0] instanceof Set<?> set) {
                    for (Object item : set) {
                        captured.events.add(String.valueOf(item.getClass().getMethod("getData").invoke(item)));
                    }
                } else if ("send".equals(method.getName())) {
                    captured.events.add(String.valueOf(args[0]));
                } else if ("complete".equals(method.getName()) || "completeWithError".equals(method.getName())) {
                    captured.completed.countDown();
                }
                return null;
            }
        );
        var initialize = org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.class
            .getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
        return captured;
    }

    private static final class CapturedSse {
        private final List<String> events = new ArrayList<>();
        private final CountDownLatch completed = new CountDownLatch(1);
    }

    private static class CapturingAssignmentService extends AssignmentService {
        private AssignmentRequest lastRequest;

        CapturingAssignmentService() {
            super(null, null, null, null);
        }

        @Override
        public WorkAssignment create(AssignmentRequest request) {
            lastRequest = request;
            return new WorkAssignment(
                "assignment-1",
                request.agentId(),
                request.jobId(),
                request.jobItemId(),
                request.assignmentType(),
                request.priority() == null ? 0 : request.priority(),
                OrchestrationStatus.QUEUED,
                request.modelOverride(),
                request.workspaceType(),
                request.workspaceKey(),
                null,
                request.projectId(),
                0,
                Map.<String, Object>of(),
                request.input() == null ? Map.of() : request.input(),
                Map.<String, Object>of(),
                Map.<String, Object>of(),
                null,
                null,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null
            );
        }
    }
}
