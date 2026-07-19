package io.mindspice.magenta2.ai.orchestration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AgentConfig;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.config.user.EndpointType;
import io.mindspice.magenta2.ai.config.user.ModelConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileSeeder;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettings;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsRepository;
import io.mindspice.magenta2.ai.orchestration.settings.RuntimeSettingsService;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentEventReaction;
import io.mindspice.magenta2.ai.orchestration.runtime.AgentSchedule;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentType;
import io.mindspice.magenta2.ai.orchestration.runtime.EventReactionService;
import io.mindspice.magenta2.ai.orchestration.runtime.EventType;
import io.mindspice.magenta2.ai.orchestration.runtime.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRecurrence;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRun;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRunStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobWorkItem;
import io.mindspice.magenta2.ai.orchestration.runtime.JobWorkItemType;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationEventService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationJob;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationJobItem;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRuntimeRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRunnerService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationStatus;
import io.mindspice.magenta2.ai.orchestration.runtime.Project;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectAgentMembership;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectEvent;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectService;
import io.mindspice.magenta2.ai.orchestration.runtime.ReactionActionType;
import io.mindspice.magenta2.ai.orchestration.runtime.ScheduleService;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.chat.plan.PlanDefinition;
import io.mindspice.magenta2.ai.chat.plan.PlanKind;
import io.mindspice.magenta2.ai.chat.plan.PlanRepository;
import io.mindspice.magenta2.ai.chat.plan.PlanService;
import io.mindspice.magenta2.ai.chat.plan.PlanStatus;
import io.mindspice.magenta2.ai.chat.plan.PlanStep;
import io.mindspice.magenta2.ai.chat.repository.AuditRepository;
import io.mindspice.magenta2.ai.chat.repository.ChatMemoryRepository;
import io.mindspice.magenta2.ai.chat.rendering.ChatMarkdownRenderer;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.chat.service.TaskExecutionResult;
import io.mindspice.magenta2.ai.chat.task.TaskService;
import io.mindspice.magenta2.ai.chat.task.TaskRun;
import io.mindspice.magenta2.ai.chat.task.TaskRunStatus;
import io.mindspice.magenta2.ai.chat.tool.file.AgentFileToolService;
import io.mindspice.magenta2.ai.execution.MagentaWorkExecutor;
import io.mindspice.magenta2.ai.execution.MagentaWorkKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.EffectiveWorkspaceResolver;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.Workspace;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLease;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLeaseService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLink;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLinkType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowDefinition;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNode;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNodeRunStatus;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowNodeType;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRepository;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRoute;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRouteType;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRun;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRunStatus;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowRunner;
import io.mindspice.magenta2.ai.orchestration.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void runtimeSettingsSaveLoadAndModelResolutionPriority() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AiConfig aiConfig = aiConfig();
        AgentProfileRepository agentRepository = new AgentProfileRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = new AgentProfileService(agentRepository, aiConfig, null);
        AgentProfile agent = agentService.create(profile("magenta", "main"));
        RuntimeSettingsRepository settingsRepository = new RuntimeSettingsRepository(jdbcTemplate);
        RuntimeSettingsService settingsService = new RuntimeSettingsService(settingsRepository, aiConfig, agentService);

        RuntimeSettings saved = settingsService.save(new RuntimeSettings(
            agent.id(), agent.name(), "summary", "planning", "summary", "main", 20
        ));

        assertThat(settingsRepository.find()).contains(saved);
        assertThat(settingsService.resolveModel("planning", "main")).isEqualTo("planning-remote");
        assertThat(settingsService.resolveModel(null, "main")).isEqualTo("main-remote");
        assertThat(settingsService.defaultModel()).isEqualTo("summary-remote");
        assertThat(settingsService.contextBufferPercent()).isEqualTo(20);
    }

    @Test
    void agentProfileCrudDisableAndJsonLists() {
        AgentProfileService service = agentService(jdbcTemplate(), aiConfig());
        AgentProfile created = service.create(profile("magenta", "main"));

        AgentProfile updated = service.update(created.id(), new AgentProfile(
            created.id(), "magenta", AgentProfileStatus.ACTIVE, "planning", "Prompt 2",
            List.of("file_read"), List.of("printf"), true, null, null
        ));
        service.deleteOrDisable(created.id());

        assertThat(updated.approvedTools()).containsExactly("file_read");
        assertThat(updated.allowedShellCommands()).containsExactly("printf");
        assertThat(service.get(created.id()).status()).isEqualTo(AgentProfileStatus.DISABLED);
    }

    @Test
    void agentProfileRejectsWildcardShellAllowlistWithoutUnsafeOverride() {
        AgentProfileService service = agentService(jdbcTemplate(), aiConfig());

        assertThatThrownBy(() -> service.create(new AgentProfile(
            null, "magenta", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("*"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unsafeAllowWildcardShellCommands=true");
    }

    @Test
    void agentProfileAllowsWildcardShellAllowlistWithUnsafeOverride() {
        AgentProfileService service = agentService(jdbcTemplate(), aiConfig(true));

        AgentProfile created = service.create(new AgentProfile(
            null, "magenta", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("*"), true, null, null
        ));

        assertThat(created.allowedShellCommands()).containsExactly("*");
    }

    @Test
    void agentProfileRejectsShellWrapperAllowlistEntries() {
        AgentProfileService service = agentService(jdbcTemplate(), aiConfig(true));

        assertThatThrownBy(() -> service.create(new AgentProfile(
            null, "magenta", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("bash"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shell wrapper");
    }

    @Test
    void workspaceCreatesRootsAndRejectsEscapes() throws Exception {
        WorkspaceService service = new WorkspaceService(new WorkspaceRepository(jdbcTemplate()),
                WorkspaceDirectoryServiceTestSupport.aiConfig(tempDir),
                WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true),
                WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));

        Workspace workspace = service.agentWorkspace("agent-1", "Agent 1");
        WorkspaceLink link = service.addLink(workspace.id(), new WorkspaceLink(
            null, workspace.id(), "notes", WorkspaceLinkType.PATH, "notes", true, false, null, null
        ));

        assertThat(Files.isDirectory(tempDir.resolve("workspace/agent-1"))).isTrue();
        assertThat(link.label()).isEqualTo("notes");
        assertThatThrownBy(() -> service.addLink(workspace.id(), new WorkspaceLink(
            null, workspace.id(), "bad", WorkspaceLinkType.PATH, "../../../../escape", true, false, null, null
        ))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escapes data root");
    }

    @Test
    void projectCreationPersistsManagedProjectWorkspace() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig(), WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        ProjectService projectService = new ProjectService(
            new ProjectRepository(jdbcTemplate, new ObjectMapper()),
            WorkspaceDirectoryServiceTestSupport.directoryService(tempDir),
            workspaceService,
            new io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLeaseService(workspaceRepository),
            new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper())
        );

        Project project = projectService.createProject("Leaseable", "desc", "agent-1", null);
        Workspace workspace = workspaceRepository.findByOwner(WorkspaceOwnerType.PROJECT, project.id()).orElseThrow();

        assertThat(workspace.rootRelativePath()).isEqualTo("projects/" + project.id());
        assertThat(Files.isDirectory(tempDir.resolve(workspace.rootRelativePath()))).isTrue();
    }

    @Test
    void workspaceListBoundsAndActiveLeaseReads() throws Exception {
        WorkspaceRepository repository = new WorkspaceRepository(jdbcTemplate());
        WorkspaceService service = new WorkspaceService(repository, aiConfig(), WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));

        Workspace a1 = service.agentWorkspace("agent-1", "Agent 1");
        service.agentWorkspace("agent-2", "Agent 2");
        assertThatThrownBy(() -> service.jobWorkspace("job-1", "Job 1"))
            .hasMessageContaining("Job-owned workspaces are retired");
        repository.saveLease(new WorkspaceLease(
            "lease-1",
            a1.id(),
            "TASK_RUN",
            "run-1",
            io.mindspice.magenta2.ai.orchestration.workspaces.LeaseMode.READ,
            java.time.Instant.now().plusSeconds(300),
            false,
            null,
            java.time.Instant.now(),
            java.time.Instant.now()
        ));

        assertThat(service.list(io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType.AGENT, null, 500))
            .hasSize(2);
        assertThat(service.list(null, null, 1)).hasSize(1);
        assertThat(service.list(null, null, -10)).hasSize(2);
        assertThat(service.activeLeases(a1.id()))
            .extracting(WorkspaceLease::holderId)
            .containsExactly("run-1");
    }

    @Test
    void legacySeederCreatesOneDefaultAgentOnlyWhenEmpty() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        AiConfig aiConfig = aiConfig();
        AgentProfileRepository repository = new AgentProfileRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService service = new AgentProfileService(repository, aiConfig, null);
        RuntimeSettingsRepository settingsRepository = new RuntimeSettingsRepository(jdbcTemplate);
        AgentProfileSeeder seeder = new AgentProfileSeeder(repository, service, settingsRepository, aiConfig);

        seeder.run(null);
        seeder.run(null);

        assertThat(repository.findAll()).hasSize(1);
        AgentProfile agent = repository.findAll().getFirst();
        assertThat(agent.name()).isEqualTo("magenta");
        assertThat(agent.systemPrompt()).isEqualTo("Legacy prompt");
        assertThat(agent.approvedTools()).isEmpty();
        assertThat(agent.allowedShellCommands()).isEmpty();
        assertThat(settingsRepository.find().orElseThrow().defaultAgentId()).isEqualTo(agent.id());
    }

    @Test
    void hardDeletePurgesRuntimeJobReferencesAndClearsLegacyProjectOwner() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();

        AgentProfileRepository agentRepository = new AgentProfileRepository(jdbcTemplate, objectMapper);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        OrchestrationRuntimeRepository runtimeRepository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        JobRepository jobRepository = new JobRepository(jdbcTemplate, objectMapper);
        ProjectRepository projectRepository = new ProjectRepository(jdbcTemplate, objectMapper);

        AgentProfileService service = new AgentProfileService(
            agentRepository,
            aiConfig,
            null,
            provider(WorkspaceService.class, workspaceService),
            null,
            provider(WorkspaceRepository.class, workspaceRepository),
            provider(OrchestrationRuntimeRepository.class, runtimeRepository),
            provider(JobRepository.class, jobRepository),
            provider(ProjectRepository.class, projectRepository)
        );

        service.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of("printf"), true, null, null
        ));

        runtimeRepository.saveJob(new OrchestrationJob(
            "orch-job-1", "agent-1", "Owned Job", "summary", null, null, OrchestrationStatus.QUEUED, null, null
        ));
        runtimeRepository.saveJobItem(new OrchestrationJobItem(
            "orch-item-1", "orch-job-1", 0, AssignmentType.TASK_RUN, "task-1", null,
            null, 1, 0, false, Map.of(), null, null
        ));
        runtimeRepository.saveAssignment(new WorkAssignment(
            "assignment-1", "agent-1", "orch-job-1", "orch-item-1", AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));
        runtimeRepository.saveAssignment(new WorkAssignment(
            "assignment-2", "agent-2", "orch-job-1", "orch-item-1", AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));
        runtimeRepository.saveInboxMessage(new InboxMessage(
            "inbox-1", "agent-1", "agent-2", "INFO", "to agent", Map.of(), false, false, null, null
        ));
        runtimeRepository.saveInboxMessage(new InboxMessage(
            "inbox-2", "agent-2", "agent-1", "INFO", "from agent", Map.of(), false, false, null, null
        ));
        runtimeRepository.saveSchedule(new AgentSchedule(
            "schedule-1", "agent-1", "orch-job-1", Map.of(),
            "0 * * * *", "UTC", true, Instant.now(), null, null
        ));
        runtimeRepository.createScheduleFiring("firing-1", "schedule-1", Instant.now(), "assignment-1");
        runtimeRepository.saveReaction(new AgentEventReaction(
            "reaction-1", "agent-1", EventType.JOB_STATUS_CHANGED, Map.of(),
            ReactionActionType.ENQUEUE_ASSIGNMENT, Map.of(), true, null, null
        ));
        runtimeRepository.saveEvent(new OrchestrationEvent(
            "event-1", EventType.JOB_STATUS_CHANGED, "agent", "agent-1", Map.of("k", "v"), null, null
        ));

        jobRepository.saveDefinition(new JobDefinition(
            "job-def-1", "agent-1", null, null, false, "DRAFT", "Job", "summary", List.of(), null, null, null, null, null
        ));
        jobRepository.saveRun(new JobRun(
            "job-run-1", "job-def-1", JobRunStatus.RUNNING, null,
            "/tmp/ws", "/tmp/out", null, null, null, null, Instant.now(), null
        ));
        jobRepository.saveRecurrence(new JobRecurrence(
            "job-rec-1", "job-def-1", "*/5 * * * *", "UTC", Instant.now().plusSeconds(60), true, null, null
        ));

        projectRepository.save(new Project(
            "project-owned", "Owned Project", "desc", "agent-1", null, null, null, null, null, null
        ));
        projectRepository.saveEvent(new ProjectEvent("project-event-1", "project-owned", "CREATED", "{}", null));
        projectRepository.saveMembership(new ProjectAgentMembership(
            "project-membership-1", "project-owned", "agent-2", "member", null
        ));
        projectRepository.save(new Project(
            "project-shared", "Shared Project", "desc", "agent-2", null, null, null, null, null, null
        ));
        projectRepository.saveMembership(new ProjectAgentMembership(
            "project-membership-2", "project-shared", "agent-1", "member", null
        ));

        service.hardDelete("agent-1", "DELETE agent-1");

        assertThat(agentRepository.findById("agent-1")).isEmpty();
        assertThat(runtimeRepository.findJobsForAgent("agent-1")).isEmpty();
        assertThat(runtimeRepository.findAssignmentsForAgent("agent-1")).isEmpty();
        assertThat(runtimeRepository.findInboxMessages("agent-1")).isEmpty();
        assertThat(runtimeRepository.findSchedulesForAgent("agent-1")).isEmpty();
        assertThat(runtimeRepository.findReactionsForAgent("agent-1")).isEmpty();
        assertThat(runtimeRepository.findEventsForSource("agent", "agent-1")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from work_assignments where id = ?",
            Integer.class,
            "assignment-2"
        )).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from schedule_firings where schedule_id = ?",
            Integer.class,
            "schedule-1"
        )).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from agent_inbox_messages where from_id = ?",
            Integer.class,
            "agent-1"
        )).isEqualTo(0);
        assertThat(jobRepository.findDefinitions("agent-1", null, null)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from job_runs where job_id = ?",
            Integer.class,
            "job-def-1"
        )).isEqualTo(0);
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from job_recurrences where job_id = ?",
            Integer.class,
            "job-def-1"
        )).isEqualTo(0);
        assertThat(projectRepository.findByOwnerAgent("agent-1")).isEmpty();
        assertThat(projectRepository.findMembershipsByAgent("agent-1")).isEmpty();
        Project retainedProject = projectRepository.findById("project-owned").orElseThrow();
        assertThat(retainedProject.ownerAgentId()).isNull();
        assertThat(projectRepository.findMembershipsByProject("project-owned"))
            .extracting(ProjectAgentMembership::agentId)
            .containsExactly("agent-2");
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from project_events where project_id = ?",
            Integer.class,
            "project-owned"
        )).isEqualTo(1);
    }

    private AgentProfileService agentService(JdbcTemplate jdbcTemplate, AiConfig aiConfig) {
        return new AgentProfileService(new AgentProfileRepository(jdbcTemplate, new ObjectMapper()), aiConfig, null);
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean(type.getName(), bean);
        return factory.getBeanProvider(type);
    }

    private AgentProfile profile(String name, String model) {
        return new AgentProfile(
            null, name, AgentProfileStatus.ACTIVE, model, "Prompt", List.of(), List.of("printf"),
            true, null, null
        );
    }

    private AiConfig aiConfig() {
        return aiConfig(false);
    }

    private AiConfig aiConfig(boolean unsafeAllowWildcardShellCommands) {
        Map<String, ModelConfig> models = Map.of(
            "main", model("main-remote"),
            "planning", model("planning-remote"),
            "summary", model("summary-remote")
        );
        return new AiConfig(
            "legacy",
            "main",
            "summary",
            null,
            "planning",
            "main",
            10,
            tempDir,
            null,
            models,
            Map.of("legacy", new AgentConfig("main", "Legacy prompt", List.of(), List.of("*"))),
            unsafeAllowWildcardShellCommands
        );
    }

    private ModelConfig model(String remoteName) {
        return new ModelConfig(remoteName, "http://localhost:11434", EndpointType.OLLAMA, 4096, 0, null);
    }

    private Path resolveStored(WorkspaceDirectoryService directoryService, String value) {
        return directoryService.dataRoot().resolve(value.replace('\\', '/')).normalize();
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 2: OrchestrationTaskContext propagation
    // ════════════════════════════════════════════════════════════════

    @Test
    void orchestrationTaskContextIsBuiltAndCleared() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();

        AgentProfileRepository agentRepository = new AgentProfileRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = new AgentProfileService(agentRepository, aiConfig, null);
        AgentProfile agent = agentService.create(profile("runner-agent", "main"));

        OrchestrationRuntimeRepository runtimeRepository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        JobRepository jobRepository = new JobRepository(jdbcTemplate, objectMapper);

        // Create an assignment with agent, job, and workspace context
        WorkAssignment assignment = new WorkAssignment(
            "assign-ctx-1", agent.id(), "job-ctx-1", null, AssignmentType.REPORT, 1,
            OrchestrationStatus.QUEUED, null, "ws-ctx-1", 0,
            Map.of("message", "Test context propagation"),
            Map.of(), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        );
        runtimeRepository.saveAssignment(assignment);

        // Verify OrchestrationTaskContext can be set and cleared without leaking
        OrchestrationTaskContext ctx = new OrchestrationTaskContext(
            agent.id(), agent.name(), "job-ctx-1", null, "ws-ctx-1",
            "TASK_RUN", "/tmp/ws", "/tmp/out");

        assertThat(ctx.hasAgentContext()).isTrue();
        assertThat(ctx.hasContext()).isTrue();
        assertThat(ctx.agentId()).isEqualTo(agent.id());
        assertThat(ctx.agentName()).isEqualTo("runner-agent");
        assertThat(ctx.jobId()).isEqualTo("job-ctx-1");
        assertThat(ctx.workspaceId()).isEqualTo("ws-ctx-1");
        assertThat(ctx.runType()).isEqualTo("TASK_RUN");
        assertThat(ctx.hostWorkspacePath()).isEqualTo("/tmp/ws");
        assertThat(ctx.hostOutputPath()).isEqualTo("/tmp/out");

        // Set and clear via holder
        OrchestrationTaskContextHolder.set(ctx);
        assertThat(OrchestrationTaskContextHolder.current()).isEqualTo(ctx);
        OrchestrationTaskContextHolder.clear();
        assertThat(OrchestrationTaskContextHolder.current()).isNull();
    }

    @Test
    void orchestrationTaskContextHolderIsThreadLocal() throws Exception {
        OrchestrationTaskContext mainCtx = new OrchestrationTaskContext(
            "main-agent", "Main", null, null, null, "TASK_RUN", null, null);

        OrchestrationTaskContextHolder.set(mainCtx);

        // Verify thread isolation
        java.util.concurrent.atomic.AtomicReference<OrchestrationTaskContext> otherCtx =
            new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            otherCtx.set(OrchestrationTaskContextHolder.current());
        });
        other.start();
        other.join();

        assertThat(otherCtx.get()).isNull(); // other thread sees no context
        assertThat(OrchestrationTaskContextHolder.current()).isEqualTo(mainCtx); // main still has it

        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void assignmentHeartbeatDoesNotAdvanceProgress() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        Instant oldProgress = Instant.now().minusSeconds(1200);
        WorkAssignment saved = repository.saveAssignment(new WorkAssignment(
            "assignment-progress", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.RUNNING, null, null, 0,
            Map.of("phase", "blocking-call"), Map.of(), Map.of(), Map.of(),
            null, "owner-1", Instant.now().plusSeconds(300),
            null, null, Instant.now().minusSeconds(1300), null, oldProgress, oldProgress
        ));

        repository.extendRunningLease(saved.id(), "owner-1", Instant.now().plusSeconds(300));
        WorkAssignment updated = repository.findAssignment(saved.id()).orElseThrow();

        assertThat(updated.lastProgressAt()).isEqualTo(saved.lastProgressAt());
        assertThat(updated.lastHeartbeatAt()).isAfter(saved.lastHeartbeatAt());
    }

    @Test
    void forceInterruptedAssignmentRejectsLateLeasedCompletion() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        WorkAssignment running = repository.saveAssignment(new WorkAssignment(
            "assignment-interrupt", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.RUNNING, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(),
            null, "owner-1", Instant.now().plusSeconds(300),
            null, null, Instant.now(), null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);

        WorkAssignment interrupted = assignmentService.forceInterrupt("agent-1", running.id(), "blocked model call");
        WorkAssignment lateCompletion = new WorkAssignment(
            interrupted.id(), interrupted.agentId(), interrupted.jobId(), interrupted.jobItemId(),
            interrupted.assignmentType(), interrupted.priority(), OrchestrationStatus.COMPLETED,
            interrupted.modelOverride(), interrupted.workspaceId(), interrupted.currentItemIndex(),
            interrupted.checkpoint(), interrupted.input(), Map.of("message", "late"), interrupted.evidence(),
            null, null, null, interrupted.createdAt(), interrupted.updatedAt(), interrupted.startedAt(),
            Instant.now(), interrupted.lastProgressAt(), interrupted.lastHeartbeatAt()
        );

        assertThat(repository.saveAssignmentIfLeaseOwner(lateCompletion, "owner-1")).isEmpty();
        WorkAssignment result = repository.findAssignment(interrupted.id()).orElseThrow();

        assertThat(result.status()).isEqualTo(OrchestrationStatus.INTERRUPTED);
        assertThat(result.leaseOwner()).isNull();
        assertThat(result.leaseExpiresAt()).isNull();
        assertThat(result.errorText()).contains("blocked model call");
    }

    @Test
    void assignmentDeleteRemovesNonRunningAssignment() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);

        WorkAssignment queued = repository.saveAssignment(new WorkAssignment(
            "assignment-delete", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        service.delete(agent.id(), queued.id());

        assertThat(repository.findAssignment(queued.id())).isEmpty();
    }

    @Test
    void assignmentDeleteRejectsTerminalHistoryRows() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);
        repository.saveAssignment(new WorkAssignment(
            "assignment-terminal", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.FAILED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), "boom", null, null,
            null, null, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)
        ));

        assertThatThrownBy(() -> service.delete(agent.id(), "assignment-terminal"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("History");
        assertThat(repository.findAssignment("assignment-terminal")).isPresent();
    }

    @Test
    void queueAndHistoryFiltersSplitTerminalRows() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);
        repository.saveAssignment(new WorkAssignment(
            "assignment-queued", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));
        repository.saveAssignment(new WorkAssignment(
            "assignment-complete", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.COMPLETED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null,
            null, null, Instant.now().minusSeconds(120), Instant.now().minusSeconds(60)
        ));

        assertThat(service.queueAssignments(agent.id())).extracting(WorkAssignment::id)
            .containsExactly("assignment-queued");
        assertThat(service.historyAssignments(agent.id())).extracting(WorkAssignment::id)
            .containsExactly("assignment-complete");
    }

    @Test
    void manualHistoryPurgeRemovesOnlyOldTerminalRowsAndLinks() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);
        Instant old = Instant.now().minusSeconds(3 * 24 * 60 * 60);
        repository.saveAssignment(new WorkAssignment(
            "assignment-old-terminal", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.COMPLETED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, old, old, old, old
        ));
        repository.saveAssignmentConversationLink("assignment-old-terminal", "conversation-old");
        repository.saveAssignment(new WorkAssignment(
            "assignment-new-terminal", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.FAILED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, Instant.now(), Instant.now()
        ));
        repository.saveAssignment(new WorkAssignment(
            "assignment-old-queued", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, old, old, null, null
        ));

        int purged = service.purgeHistory(agent.id(), 1);

        assertThat(purged).isEqualTo(1);
        assertThat(repository.findAssignment("assignment-old-terminal")).isEmpty();
        assertThat(repository.findAssignmentConversationIds("assignment-old-terminal")).isEmpty();
        assertThat(repository.findAssignment("assignment-new-terminal")).isPresent();
        assertThat(repository.findAssignment("assignment-old-queued")).isPresent();
    }

    @Test
    void autoHistoryPurgeNoOpsWhenDisabledAndPurgesWhenConfigured() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AiConfig aiConfig = aiConfig();
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        RuntimeSettingsRepository settingsRepository = new RuntimeSettingsRepository(jdbcTemplate);
        RuntimeSettingsService settingsService = new RuntimeSettingsService(settingsRepository, aiConfig, agentService);
        AssignmentService service = new AssignmentService(repository, agentService, settingsService, null);
        Instant old = Instant.now().minusSeconds(3 * 24 * 60 * 60);
        repository.saveAssignment(new WorkAssignment(
            "assignment-auto-terminal", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.COMPLETED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, old, old, old, old
        ));

        service.autoPurgeHistory();
        assertThat(repository.findAssignment("assignment-auto-terminal")).isPresent();

        settingsService.save(new RuntimeSettings(
            agent.id(), agent.name(), "main", "planning", "summary", "main", 20,
            null, null, null, null, true, 1, false
        ));
        service.autoPurgeHistory();

        assertThat(repository.findAssignment("assignment-auto-terminal")).isEmpty();
    }

    @Test
    void disabledSchedulePollingNoOps() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        ScheduleService scheduleService = new ScheduleService(repository, agentService, assignmentService, eventService, false);
        Instant due = Instant.now().minusSeconds(60);
        repository.saveSchedule(new AgentSchedule(
            "schedule-disabled", agent.id(), null, Map.of("assignmentType", "REPORT"),
            "*/5 * * * * *", "UTC", true, due, null, null
        ));

        scheduleService.pollDueSchedules();

        assertThat(repository.findAssignmentsForAgent(agent.id())).isEmpty();
        assertThat(repository.findSchedule("schedule-disabled").orElseThrow().nextRunAt()).isEqualTo(due);
    }

    @Test
    void dueScheduleCreatesOneAssignmentAndAdvancesNextRun() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        ScheduleService scheduleService = new ScheduleService(repository, agentService, assignmentService, eventService, true);
        Instant due = Instant.now().minusSeconds(60);
        repository.saveSchedule(new AgentSchedule(
            "schedule-due", agent.id(), null, Map.of("assignmentType", "REPORT", "input", Map.of("source", "schedule")),
            "*/5 * * * * *", "UTC", true, due, null, null
        ));

        scheduleService.pollDueSchedules();
        scheduleService.pollDueSchedules();

        List<WorkAssignment> assignments = repository.findAssignmentsForAgent(agent.id());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().assignmentType()).isEqualTo(AssignmentType.REPORT);
        assertThat(repository.findSchedule("schedule-due").orElseThrow().nextRunAt()).isAfter(due);
    }

    @Test
    void scheduleSaveValidatesAssignmentTemplateBeforePersisting() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        ScheduleService scheduleService = new ScheduleService(repository, agentService, assignmentService, eventService, true);

        assertThatThrownBy(() -> scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), "job-1", Map.of("assignmentType", "NOT_A_TYPE"),
            "0 * * * * *", "UTC", true, null, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid assignmentType");
        assertThatThrownBy(() -> scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of(),
            "0 * * * * *", "UTC", true, null, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JOB_RUN assignments require jobId");
        assertThatThrownBy(() -> scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "TASK_RUN"),
            "0 * * * * *", "UTC", true, null, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TASK_RUN assignments require input.taskId");
        assertThatThrownBy(() -> scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "WORKFLOW_RUN"),
            "0 * * * * *", "UTC", true, null, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WORKFLOW_RUN assignments require input.workflowId");

        AgentSchedule defaultJobRun = scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), "job-1", Map.of(),
            "0 * * * * *", "UTC", true, null, null, null
        ));
        AgentSchedule taskRun = scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "TASK_RUN", "input", Map.of("taskId", "task-1")),
            "0 * * * * *", "UTC", true, null, null, null
        ));
        AgentSchedule workflowRun = scheduleService.save(agent.id(), new AgentSchedule(
            null, agent.id(), null, Map.of("assignmentType", "WORKFLOW_RUN", "input", Map.of("workflowId", "workflow-1")),
            "0 * * * * *", "UTC", true, null, null, null
        ));

        assertThat(repository.findSchedulesForAgent(agent.id()))
            .extracting(AgentSchedule::id)
            .containsExactlyInAnyOrder(defaultJobRun.id(), taskRun.id(), workflowRun.id());
    }

    @Test
    void disabledReactionsMarkEventsHandledWithoutEnqueuing() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, false);
        repository.saveReaction(new AgentEventReaction(
            "reaction-disabled", agent.id(), EventType.MANUAL_USER_EVENT, Map.of(),
            ReactionActionType.ENQUEUE_ASSIGNMENT, Map.of("assignmentType", "REPORT"), true, null, null
        ));

        OrchestrationEvent event = eventService.publish(EventType.MANUAL_USER_EVENT, "test", "event-1", Map.of());

        assertThat(repository.findAssignmentsForAgent(agent.id())).isEmpty();
        assertThat(repository.findEvent(event.id()).orElseThrow().handledAt()).isNotNull();
    }

    @Test
    void enabledReactionsMatchFiltersAndEnqueueAssignments() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        repository.saveReaction(new AgentEventReaction(
            "reaction-enabled", agent.id(), EventType.MANUAL_USER_EVENT, Map.of("kind", "match"),
            ReactionActionType.ENQUEUE_ASSIGNMENT, Map.of("assignmentType", "REPORT", "input", Map.of("from", "reaction")),
            true, null, null
        ));

        eventService.publish(EventType.MANUAL_USER_EVENT, "test", "event-1", Map.of("kind", "skip"));
        eventService.publish(EventType.MANUAL_USER_EVENT, "test", "event-2", Map.of("kind", "match"));

        List<WorkAssignment> assignments = repository.findAssignmentsForAgent(agent.id());
        assertThat(assignments).hasSize(1);
        assertThat(assignments.getFirst().input()).containsEntry("from", "reaction");
    }

    @Test
    void reactionSaveValidatesAssignmentTemplateBeforePersisting() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        EventReactionService reactionService = new EventReactionService(repository, agentService);

        assertThatThrownBy(() -> reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "NOT_A_TYPE"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid assignmentType");
        assertThatThrownBy(() -> reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "JOB_RUN"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JOB_RUN assignments require jobId");
        assertThatThrownBy(() -> reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "TASK_RUN"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("TASK_RUN assignments require input.taskId");
        assertThatThrownBy(() -> reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "WORKFLOW_RUN"), true, null, null
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("WORKFLOW_RUN assignments require input.workflowId");

        AgentEventReaction defaultReport = reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of(), true, null, null
        ));
        AgentEventReaction taskRun = reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "TASK_RUN", "input", Map.of("taskId", "task-1")), true, null, null
        ));
        AgentEventReaction workflowRun = reactionService.save(agent.id(), new AgentEventReaction(
            null, agent.id(), EventType.MANUAL_USER_EVENT, Map.of(), ReactionActionType.ENQUEUE_ASSIGNMENT,
            Map.of("assignmentType", "WORKFLOW_RUN", "input", Map.of("workflowId", "workflow-1")), true, null, null
        ));

        assertThat(repository.findReactionsForAgent(agent.id()))
            .extracting(AgentEventReaction::id)
            .containsExactlyInAnyOrder(defaultReport.id(), taskRun.id(), workflowRun.id());
    }

    @Test
    void assignmentDeleteRejectsRunningAndCancelRequested() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);

        repository.saveAssignment(new WorkAssignment(
            "assignment-running", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.RUNNING, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, "owner", Instant.now().plusSeconds(60),
            null, null, Instant.now(), null
        ));
        repository.saveAssignment(new WorkAssignment(
            "assignment-cancel", agent.id(), null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.CANCEL_REQUESTED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        assertThatThrownBy(() -> service.delete(agent.id(), "assignment-running"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> service.delete(agent.id(), "assignment-cancel"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be deleted");
    }

    @Test
    void assignmentDeleteRejectsWrongAgent() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        agentService.create(new AgentProfile("agent-2", "Agent 2", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);

        repository.saveAssignment(new WorkAssignment(
            "assignment-wrong-agent", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.COMPLETED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, Instant.now()
        ));

        assertThatThrownBy(() -> service.delete("agent-2", "assignment-wrong-agent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
        assertThat(repository.findAssignment("assignment-wrong-agent")).isPresent();
    }

    @Test
    void taskAssignmentCheckpointsConversationBeforeModelExecution() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AiConfig aiConfig = aiConfig();
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, null);
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                WorkAssignment inFlight = repository.findAssignment("assignment-task").orElseThrow();
                assertThat(inFlight.checkpoint()).containsEntry("activeConversationId", conversationId);
                assertThat(inFlight.checkpoint()).containsEntry("conversationId", conversationId);
                assertThat((List<?>) inFlight.checkpoint().get("conversationIds"))
                    .anySatisfy(value -> assertThat(value).isEqualTo(conversationId));
                TaskRun run = new TaskRun(
                    "task-run-1", taskId, TaskRunStatus.COMPLETED, inputValues, Map.of("done", true),
                    null, List.of("evidence"), List.of(), "done", null,
                    Instant.now(), Instant.now(), Instant.now(), Instant.now()
                );
                return new TaskExecutionResult(conversationId, run, null);
            }
        };
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, null, null, null, chatService, null, null,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            ))
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-task", "agent-1", null, null, AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("taskId", "task-1", "inputValues", Map.of("prompt", "go")),
            Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        WorkAssignment result = runner.runAssignment("assignment-task");

        assertThat(result.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(result.output()).containsKey("conversationIds");
        assertThat(repository.findAssignmentConversationIds("assignment-task"))
            .containsExactly(result.output().get("conversationId").toString());
    }

    @Test
    void workflowAssignmentWaitingStatusRemainsResumableAndReusesOriginalRun() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        EffectiveWorkspaceResolver effectiveWorkspaceResolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        OutputArtifactService outputArtifactService = new OutputArtifactService(workspaceRepository, directoryService, objectMapper);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, null);
        WorkflowRepository workflowRepository = new WorkflowRepository(jdbcTemplate, objectMapper);
        PlanService planService = new PlanService(new PlanRepository(jdbcTemplate, objectMapper),
            new ChatMemoryRepository(jdbcTemplate, objectMapper));
        InboxService workflowInboxService = new InboxService(workflowRepository, objectMapper);
        WorkflowRunner workflowRunner = new WorkflowRunner(
            workflowRepository, planService, workflowInboxService, directoryService, outputArtifactService,
            effectiveWorkspaceResolver
        );
        WorkflowService workflowService = new WorkflowService(workflowRepository, planService, workflowRunner);
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, null, null, workflowService, null, null, null,
            agentService, outputArtifactService, null, null, null, directoryService,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );
        WorkflowNode gate = new WorkflowNode("gate", WorkflowNodeType.USER_APPROVAL, null,
            "gate", null, List.of(), List.of(), Map.of(), false, List.of(), "approve?", null);
        WorkflowNode approved = new WorkflowNode("approved", WorkflowNodeType.FINAL_OUTPUT, null,
            "approved", null, List.of(), List.of(), Map.of(), false, List.of(), "approved", null);
        WorkflowNode rejected = new WorkflowNode("rejected", WorkflowNodeType.FINAL_OUTPUT, null,
            "rejected", null, List.of(), List.of(), Map.of(), false, List.of(), "rejected", null);
        WorkflowDefinition workflow = workflowService.saveDefinitionValidated(new WorkflowDefinition(
            null, 2, "Assignment Waiting Workflow", "", 1,
            List.of(gate, approved, rejected),
            List.of(
                new WorkflowRoute("approved-route", "gate", null, "approved", null,
                    WorkflowRouteType.CONTROL, "APPROVED"),
                new WorkflowRoute("rejected-route", "gate", null, "rejected", null,
                    WorkflowRouteType.CONTROL, "REJECTED")
            ),
            Map.of(), null, null
        ));
        repository.saveAssignment(new WorkAssignment(
            "assignment-workflow-wait", agent.id(), null, null, AssignmentType.WORKFLOW_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("workflowId", workflow.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        WorkAssignment waiting = runner.runAssignment("assignment-workflow-wait");

        assertThat(waiting.status()).as(waiting.errorText()).isEqualTo(OrchestrationStatus.WAITING);
        String workflowRunId = waiting.checkpoint().get("workflowRunId").toString();
        WorkflowRun waitingRun = workflowService.getRun(workflowRunId);
        assertThat(waitingRun.status()).isEqualTo(WorkflowRunStatus.WAITING);
        assertThat(waitingRun.workspacePath()).startsWith("workspace/agent-1/runs/");
        assertThat(waitingRun.outputDir()).startsWith("workspace/agent-1/runs/");
        assertThat(waitingRun.outputDir()).endsWith("/outputs");
        assertThat(Files.isDirectory(resolveStored(directoryService, waitingRun.workspacePath()))).isTrue();
        assertThat(Files.isDirectory(resolveStored(directoryService, waitingRun.outputDir()))).isTrue();
        String messageId = waitingRun.nodeRuns().stream()
            .filter(node -> node.nodeKey().equals("gate"))
            .findFirst()
            .orElseThrow()
            .outputValues()
            .get("messageId")
            .toString();

        assertThat(runner.runNextSynchronously()).isNull();
        assertThat(repository.findAssignment(waiting.id()).orElseThrow().status())
            .isEqualTo(OrchestrationStatus.WAITING);
        assertThat(repository.acquireLease(waiting.id(), "poller", Instant.now().plusSeconds(300)))
            .isEmpty();
        new OrchestrationEventService(repository, assignmentService, true).publish(
            EventType.INBOX_MESSAGE_RECEIVED,
            "INBOX_MESSAGE",
            "message-unrelated",
            Map.of("toAgentId", agent.id(), "messageId", "message-unrelated", "messageType", "direct")
        );
        assertThat(repository.findAssignment(waiting.id()).orElseThrow().status())
            .isEqualTo(OrchestrationStatus.WAITING);

        workflowInboxService.respondUserApproval(messageId, true, "yes");
        assignmentService.resume(agent.id(), waiting.id());
        WorkAssignment completed = runner.runAssignment(waiting.id());

        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(completed.checkpoint().get("workflowRunId")).isEqualTo(workflowRunId);
        WorkflowRun completedRun = workflowService.getRun(workflowRunId);
        assertThat(completedRun.status()).isEqualTo(WorkflowRunStatus.COMPLETED);
        assertThat(completedRun.nodeRuns()).anySatisfy(node -> {
            assertThat(node.nodeKey()).isEqualTo("approved");
            assertThat(node.status()).isEqualTo(WorkflowNodeRunStatus.COMPLETED);
        });
        assertThat(resolveStored(directoryService, completedRun.outputDir()))
            .startsWith(directoryService.dataRoot().resolve("workspace/agent-1/runs"));
        assertThat(Files.isDirectory(resolveStored(directoryService, completedRun.workspacePath()))).isTrue();
    }

    @Test
    void projectLeaseMaterializesPromisedWorkspacePathForTaskTools() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        WorkspaceLeaseService leaseService = new WorkspaceLeaseService(workspaceRepository);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of("file_read"), List.of(), true, null, null
        ));
        ProjectService projectService = new ProjectService(
            new ProjectRepository(jdbcTemplate, objectMapper),
            directoryService,
            workspaceService,
            leaseService,
            repository
        );
        Project project = projectService.createProject("Linked Project", "desc", agent.id(), null);
        Path projectWorkspace = directoryService.projectWorkspace(project.id());
        Files.writeString(projectWorkspace.resolve("shared.txt"), "leased project\n");
        JobService jobService = new JobService(new JobRepository(jdbcTemplate, objectMapper), directoryService, null, null);
        JobDefinition job = jobService.saveDefinition(new JobDefinition(
            "job-project", agent.id(), project.id(), null, false, "READY",
            "Project Job", "summary", List.of(), null, null, null, null, null
        ));
        PlanService planService = new PlanService(
            new PlanRepository(jdbcTemplate, objectMapper),
            new ChatMemoryRepository(jdbcTemplate, objectMapper),
            null,
            new ChatMarkdownRenderer(),
            directoryService,
            new OutputArtifactService(workspaceRepository, directoryService, objectMapper)
        );
        PlanDefinition task = planService.saveTask(new PlanDefinition(
            null, PlanKind.TASK_TEMPLATE, PlanStatus.APPROVED,
            "Project Link Task", "Read project data.", "Read project data.", null,
            List.of(), List.of(), List.of(), List.of(),
            List.of(new PlanStep(1, "Read linked project workspace.")),
            List.of("Project path is readable."), List.of(), List.of(),
            null, null, null, null, null, List.of(), 0, 0,
            null, null, null, null
        ));
        TaskService taskService = new TaskService(planService);
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, jobService);
        AtomicReference<Path> materializedLink = new AtomicReference<>();
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
                TaskRun started = taskService.startChatExecution(conversationId, taskId, inputValues, context);
                OrchestrationTaskContext updated = OrchestrationTaskContextHolder.current();
                Path link = Path.of(updated.hostWorkspacePath()).resolve("projects").resolve(project.id());
                materializedLink.set(link);
                try {
                    assertThat(Files.isSymbolicLink(link)).isTrue();
                    assertThat(link.toRealPath()).isEqualTo(projectWorkspace.toRealPath());
                    AgentFileToolService fileTool = new AgentFileToolService(aiConfig, directoryService);
                    AgentFileToolService.FileReadResult read = fileTool.read(
                        "projects/" + project.id() + "/shared.txt", 1, 10);
                    assertThat(read.lines().getFirst()).endsWith("|leased project");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                TaskRun completed = taskService.completeRun(
                    started.id(), Map.of(), "done", List.of("project link readable"));
                return new TaskExecutionResult(conversationId, completed, null);
            }
        };
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, jobService, taskService, null, chatService, null, null,
            agentService, null, projectService, workspaceService, leaseService, directoryService,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-project-link", agent.id(), job.id(), null, AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("taskId", task.id(), "inputValues", Map.of()),
            Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        WorkAssignment result = runner.runAssignment("assignment-project-link");

        assertThat(result.errorText()).isNull();
        assertThat(result.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(materializedLink.get()).isNotNull();
        assertThat(Files.exists(materializedLink.get())).isFalse();
        Workspace workspace = workspaceRepository.findByOwner(WorkspaceOwnerType.PROJECT, project.id()).orElseThrow();
        assertThat(workspaceService.activeLeases(workspace.id())).isEmpty();
        assertThat(Files.readString(projectWorkspace.resolve("shared.txt"))).isEqualTo("leased project\n");
    }

    @Test
    void projectScopedJobAssignmentsUseIsolatedPersistentWorkspacesAndProjectOutputs() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        WorkspaceLeaseService leaseService = new WorkspaceLeaseService(workspaceRepository);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-job", "Job Agent", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        ProjectService projectService = new ProjectService(
            new ProjectRepository(jdbcTemplate, objectMapper),
            directoryService,
            workspaceService,
            leaseService,
            repository
        );
        Project project = projectService.createProject("Job Project", "desc", agent.id(), null);
        EffectiveWorkspaceResolver resolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        JobService jobService = new JobService(
            new JobRepository(jdbcTemplate, objectMapper), directoryService, null, null, resolver);
        JobDefinition job = jobService.saveDefinition(new JobDefinition(
            "job-isolated", agent.id(), project.id(), null, true, "Isolated Job", "summary",
            List.of(), null, null, null, null, null
        ));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, jobService);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, jobService, null, null, null, null, eventService,
            agentService, null, projectService, workspaceService, leaseService, directoryService,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-job-a", agent.id(), job.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("jobId", job.id()),
            Map.of(), Map.of(), null, null, null, null, null, null, null
        ));
        repository.saveAssignment(new WorkAssignment(
            "assignment-job-b", agent.id(), job.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("jobId", job.id()),
            Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        WorkAssignment first = runner.runAssignment("assignment-job-a");
        WorkAssignment second = runner.runAssignment("assignment-job-b");

        assertThat(first.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(second.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        JobRun firstRun = jobService.getRun(first.checkpoint().get("jobRunId").toString());
        JobRun secondRun = jobService.getRun(second.checkpoint().get("jobRunId").toString());
        assertThat(firstRun.status()).isEqualTo(JobRunStatus.COMPLETED);
        assertThat(secondRun.status()).isEqualTo(JobRunStatus.COMPLETED);
        assertThat(firstRun.completedAt()).isNotNull();
        assertThat(secondRun.completedAt()).isNotNull();
        assertThat(firstRun.finalMessage()).isEqualTo("Job run completed");
        assertThat(secondRun.finalMessage()).isEqualTo("Job run completed");
        assertThat(firstRun.workspacePath()).isNull();
        assertThat(secondRun.workspacePath()).isNull();
        assertThat(firstRun.outputDir()).isEqualTo("projects/" + project.id() + "/outputs");
        assertThat(secondRun.outputDir()).isEqualTo("projects/" + project.id() + "/outputs");
        assertThat(Files.isDirectory(resolveStored(directoryService, firstRun.outputDir()))).isTrue();
    }

    @Test
    void jobWorkspacePathIsNotAvailableInChildTaskContext() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        EffectiveWorkspaceResolver resolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-job-context", "Job Context Agent", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        JobService jobService = new JobService(
            new JobRepository(jdbcTemplate, objectMapper), directoryService, null, null, resolver);
        JobDefinition job = jobService.saveDefinition(new JobDefinition(
            "job-context", agent.id(), null, null, true, "READY",
            "Job Context", "summary",
            List.of(new JobWorkItem(
                "task", JobWorkItemType.PLAN, "task-context", null, Map.of(), 0, null, null)),
            null, null, null, null, null
        ));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, jobService);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        AtomicReference<String> jobWorkspaceSeen = new AtomicReference<>();
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
                jobWorkspaceSeen.set(context == null ? null : context.hostJobWorkspacePath());
                TaskRun run = new TaskRun(
                    "task-run-context", taskId, TaskRunStatus.COMPLETED, inputValues, Map.of("ok", true),
                    null, List.of("evidence"), List.of(), "done", null,
                    Instant.now(), Instant.now(), Instant.now(), Instant.now()
                );
                return new TaskExecutionResult(conversationId, run, null);
            }
        };
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, jobService, null, null, chatService, null, eventService,
            agentService, null, null, null, null, directoryService,
            new RootRelativePathService(directoryService),
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-job-context", agent.id(), job.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("jobId", job.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        WorkAssignment result = runner.runAssignment("assignment-job-context");

        assertThat(result.status()).as(result.errorText()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(jobWorkspaceSeen.get()).isNull();
    }

    @Test
    void nonPersistentJobWorkspaceStillProvidesChildTaskJobAttributionContext() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        EffectiveWorkspaceResolver resolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-job-attribution", "Job Attribution Agent", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        JobService jobService = new JobService(
            new JobRepository(jdbcTemplate, objectMapper), directoryService, null, null, resolver);
        JobDefinition job = jobService.saveDefinition(new JobDefinition(
            "job-attribution", agent.id(), null, null, false, "READY",
            "Job Attribution", "summary",
            List.of(new JobWorkItem(
                "task", JobWorkItemType.PLAN, "task-attribution", null, Map.of(), 0, null, null)),
            null, null, null, null, null
        ));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, jobService);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        AtomicReference<OrchestrationTaskContext> contextSeen = new AtomicReference<>();
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                contextSeen.set(OrchestrationTaskContextHolder.current());
                TaskRun run = new TaskRun(
                    "task-run-attribution", taskId, TaskRunStatus.COMPLETED, inputValues, Map.of("ok", true),
                    null, List.of("evidence"), List.of(), "done", null,
                    Instant.now(), Instant.now(), Instant.now(), Instant.now()
                );
                return new TaskExecutionResult(conversationId, run, null);
            }
        };
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, jobService, null, null, chatService, null, eventService,
            agentService, null, null, null, null, directoryService,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-job-attribution", agent.id(), job.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("jobId", job.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        WorkAssignment result = runner.runAssignment("assignment-job-attribution");

        assertThat(result.status()).as(result.errorText()).isEqualTo(OrchestrationStatus.COMPLETED);
        OrchestrationTaskContext context = contextSeen.get();
        assertThat(context).isNotNull();
        assertThat(context.jobAssignmentId()).isEqualTo("assignment-job-attribution");
        assertThat(context.jobRunId()).isNotBlank();
        assertThat(context.hostJobWorkspacePath()).isNull();
    }

    @Test
    void jobAssignmentReusesAllocatedRunAndCheckpointsBeforeFirstItemExecution() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        AiConfig aiConfig = aiConfig();
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbcTemplate);
        WorkspaceService workspaceService = new WorkspaceService(workspaceRepository, aiConfig, WorkspaceDirectoryServiceTestSupport.agentProfileRepository(true), WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(true));
        EffectiveWorkspaceResolver resolver = new EffectiveWorkspaceResolver(directoryService, workspaceService);
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-job-resume", "Job Resume Agent", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        JobService jobService = new JobService(
            new JobRepository(jdbcTemplate, objectMapper), directoryService, null, null, resolver);
        JobDefinition job = jobService.saveDefinition(new JobDefinition(
            "job-resume", agent.id(), null, null, false, "READY",
            "Job Resume", "summary",
            List.of(new JobWorkItem(
                "task", JobWorkItemType.PLAN, "task-resume", null, Map.of(), 0, null, null)),
            null, null, null, null, null
        ));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, jobService);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        repository.saveAssignment(new WorkAssignment(
            "assignment-job-resume", agent.id(), job.id(), null, AssignmentType.JOB_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("jobId", job.id()), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));
        JobRun preallocated = jobService.startRun(job.id(), agent.id(), null, "assignment-job-resume");
        AtomicReference<String> checkpointedRunId = new AtomicReference<>();
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                WorkAssignment inFlight = repository.findAssignment("assignment-job-resume").orElseThrow();
                checkpointedRunId.set(inFlight.checkpoint().get("jobRunId").toString());
                TaskRun run = new TaskRun(
                    "task-run-resume", taskId, TaskRunStatus.COMPLETED, inputValues, Map.of("ok", true),
                    null, List.of("evidence"), List.of(), "done", null,
                    Instant.now(), Instant.now(), Instant.now(), Instant.now()
                );
                return new TaskExecutionResult(conversationId, run, null);
            }
        };
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, jobService, null, null, chatService, null, eventService,
            agentService, null, null, null, null, directoryService,
            new MagentaWorkExecutor(Map.of(
                MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
            )),
            300,
            60
        );

        WorkAssignment result = runner.runAssignment("assignment-job-resume");

        assertThat(result.status()).as(result.errorText()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(checkpointedRunId.get()).isEqualTo(preallocated.id());
        assertThat(result.checkpoint()).containsEntry("jobRunId", preallocated.id());
        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from job_runs where job_assignment_id = ?",
            Integer.class,
            "assignment-job-resume"
        )).isEqualTo(1);
    }

    @Test
    void assignmentTranscriptUsesCheckpointDurableAndLegacyConversationLinks() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AuditRepository auditRepository = new AuditRepository(jdbcTemplate);
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        AssignmentService assignmentService = new AssignmentService(
            repository, agentService, null, null, auditRepository, null, null);

        Instant started = Instant.now().minusSeconds(30);
        repository.saveAssignment(new WorkAssignment(
            "assignment-links", "agent-1", null, null, AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.RUNNING, null, null, 0,
            Map.of("conversationId", "conversation-checkpoint"),
            Map.of("taskId", "plan-legacy"), Map.of(), Map.of(),
            null, "owner-1", Instant.now().plusSeconds(300),
            started.minusSeconds(5), started, started, null, started, started
        ));
        repository.saveAssignmentConversationLink("assignment-links", "conversation-durable");
        jdbcTemplate.execute("create table plan_runs (id text primary key, plan_id text not null, created_at text not null)");
        jdbcTemplate.execute("create table ai_chat_session_metadata (conversation_id text primary key, active_task_run_id text, updated_at text)");
        jdbcTemplate.update(
            "insert into plan_runs (id, plan_id, created_at) values (?, ?, ?)",
            "legacy-run", "plan-legacy", started.plusSeconds(1).toString()
        );
        jdbcTemplate.update(
            "insert into ai_chat_session_metadata (conversation_id, active_task_run_id, updated_at) values (?, ?, ?)",
            "conversation-legacy", "legacy-run", started.plusSeconds(2).toString()
        );
        auditRepository.recordUserMessage("conversation-checkpoint", "checkpoint", "model");
        auditRepository.recordUserMessage("conversation-durable", "durable", "model");
        auditRepository.recordUserMessage("conversation-legacy", "legacy", "model");

        AssignmentService.AssignmentTranscript transcript = assignmentService.transcript("agent-1", "assignment-links");

        assertThat(transcript.conversationIds())
            .containsExactly("conversation-checkpoint", "conversation-durable", "conversation-legacy");
        assertThat(transcript.auditEvents())
            .extracting(AuditRepository.AuditEvent::messageText)
            .contains("checkpoint", "durable", "legacy");
    }

    @Test
    void queuedCancelTransitionsDirectlyToCancelled() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        repository.saveAssignment(new WorkAssignment(
            "assignment-cancel-queued", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        WorkAssignment cancelled = assignmentService.cancel("agent-1", "assignment-cancel-queued");

        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
    }

    @Test
    void runningCancelInterruptsLocalWorkAndFinalizesCancelled() throws Exception {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, objectMapper);
        AiConfig aiConfig = aiConfig();
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig);
        agentService.create(new AgentProfile("agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null));
        RuntimeSettingsService settingsService = new RuntimeSettingsService(
            new RuntimeSettingsRepository(jdbcTemplate), aiConfig, agentService);
        AssignmentService assignmentService = new AssignmentService(repository, agentService, settingsService, null);
        CountDownLatch enteredModel = new CountDownLatch(1);
        ChatService chatService = new ChatService(null, null, null, null, null) {
            @Override
            public TaskExecutionResult executeTaskBlocking(
                String taskId,
                Map<String, Object> inputValues,
                String conversationId,
                String modelOverride
            ) {
                enteredModel.countDown();
                try {
                    while (true) {
                        Thread.sleep(100);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("model call interrupted", e);
                }
            }
        };
        MagentaWorkExecutor executor = new MagentaWorkExecutor(Map.of(
            MagentaWorkKind.BACKGROUND_JOB, new MagentaWorkExecutor.LaneSettings("test-bg-", 1, 10)
        ));
        OrchestrationRunnerService runner = new OrchestrationRunnerService(
            repository, assignmentService, null, null, null, chatService, null, null, executor
        );
        repository.saveAssignment(new WorkAssignment(
            "assignment-cancel-running", "agent-1", null, null, AssignmentType.TASK_RUN, 1,
            OrchestrationStatus.QUEUED, null, null, 0,
            Map.of(), Map.of("taskId", "task-1"), Map.of(), Map.of(),
            null, null, null, null, null, null, null
        ));

        runner.pollQueuedWork();
        assertThat(enteredModel.await(5, TimeUnit.SECONDS)).isTrue();
        WorkAssignment cancelRequested = assignmentService.cancel("agent-1", "assignment-cancel-running");
        assertThat(cancelRequested.status()).isEqualTo(OrchestrationStatus.CANCEL_REQUESTED);
        assertThat(cancelRequested.leaseOwner()).isNotNull();

        WorkAssignment completed = waitForStatus(repository, "assignment-cancel-running", OrchestrationStatus.CANCELLED);
        assertThat(completed.errorText()).isEqualTo("Cancelled");
        executor.shutdown();
    }

    @Test
    void staleCancelRequestedAssignmentsRecoverToCancelled() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        repository.saveAssignment(new WorkAssignment(
            "assignment-stale-cancel", "agent-1", null, null, AssignmentType.REPORT, 1,
            OrchestrationStatus.CANCEL_REQUESTED, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, "owner-1", Instant.now().minusSeconds(5),
            null, null, Instant.now().minusSeconds(60), null
        ));

        int recovered = repository.markStaleCancelRequestedLeases(Instant.now());
        WorkAssignment assignment = repository.findAssignment("assignment-stale-cancel").orElseThrow();

        assertThat(recovered).isEqualTo(1);
        assertThat(assignment.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(assignment.leaseOwner()).isNull();
        assertThat(assignment.completedAt()).isNotNull();
    }

    @Test
    void scopedAssignmentLifecycleAcceptsSameAgentControls() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);
        repository.saveAssignment(assignment("scoped-cancel", agent.id(), OrchestrationStatus.QUEUED));
        repository.saveAssignment(assignment("scoped-pause", agent.id(), OrchestrationStatus.QUEUED));
        repository.saveAssignment(assignment("scoped-resume", agent.id(), OrchestrationStatus.PAUSED));
        repository.saveAssignment(assignment("scoped-force", agent.id(), OrchestrationStatus.RUNNING));

        WorkAssignment cancelled = service.cancel(agent.id(), "scoped-cancel");
        WorkAssignment paused = service.pause(agent.id(), "scoped-pause");
        WorkAssignment resumed = service.resume(agent.id(), "scoped-resume");
        WorkAssignment interrupted = service.forceInterrupt(agent.id(), "scoped-force", "stuck worker");

        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(paused.status()).isEqualTo(OrchestrationStatus.PAUSED);
        assertThat(resumed.status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(interrupted.status()).isEqualTo(OrchestrationStatus.INTERRUPTED);
        assertThat(interrupted.errorText()).contains("stuck worker");
    }

    @Test
    void inboxMessageEventResumesOnlyWaitForMessageAssignments() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        AgentProfile agent = agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService assignmentService = new AssignmentService(repository, agentService, null, null);
        OrchestrationEventService eventService = new OrchestrationEventService(repository, assignmentService, true);
        repository.saveAssignment(new WorkAssignment(
            "wait-message", agent.id(), null, null, AssignmentType.WAIT_FOR_MESSAGE, 1,
            OrchestrationStatus.WAITING, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));
        repository.saveAssignment(new WorkAssignment(
            "wait-workflow", agent.id(), null, null, AssignmentType.WORKFLOW_RUN, 1,
            OrchestrationStatus.WAITING, null, null, 0,
            Map.of(), Map.of(), Map.of(), Map.of(), null, null, null, null, null, null, null
        ));

        eventService.publish(EventType.INBOX_MESSAGE_RECEIVED, "INBOX_MESSAGE", "message-1",
            Map.of("toAgentId", agent.id(), "messageId", "message-1", "messageType", "direct"));

        assertThat(repository.findAssignment("wait-message").orElseThrow().status())
            .isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(repository.findAssignment("wait-workflow").orElseThrow().status())
            .isEqualTo(OrchestrationStatus.WAITING);
    }

    @Test
    void scopedAssignmentLifecycleRejectsCrossAgentControlsWithoutMutation() {
        JdbcTemplate jdbcTemplate = jdbcTemplate();
        OrchestrationRuntimeRepository repository = new OrchestrationRuntimeRepository(jdbcTemplate, new ObjectMapper());
        AgentProfileService agentService = agentService(jdbcTemplate, aiConfig());
        agentService.create(new AgentProfile(
            "agent-1", "Agent 1", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        agentService.create(new AgentProfile(
            "agent-2", "Agent 2", AgentProfileStatus.ACTIVE, "main", "Prompt",
            List.of(), List.of(), true, null, null
        ));
        AssignmentService service = new AssignmentService(repository, agentService, null, null);
        repository.saveAssignment(assignment("cross-cancel", "agent-2", OrchestrationStatus.QUEUED));
        repository.saveAssignment(assignment("cross-pause", "agent-2", OrchestrationStatus.QUEUED));
        repository.saveAssignment(assignment("cross-resume", "agent-2", OrchestrationStatus.PAUSED));
        repository.saveAssignment(assignment("cross-force", "agent-2", OrchestrationStatus.RUNNING));

        assertThatThrownBy(() -> service.cancel("agent-1", "cross-cancel"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Assignment does not belong to agent");
        assertThatThrownBy(() -> service.pause("agent-1", "cross-pause"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Assignment does not belong to agent");
        assertThatThrownBy(() -> service.resume("agent-1", "cross-resume"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Assignment does not belong to agent");
        assertThatThrownBy(() -> service.forceInterrupt("agent-1", "cross-force", "wrong route"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Assignment does not belong to agent");

        assertThat(repository.findAssignment("cross-cancel").orElseThrow().status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(repository.findAssignment("cross-pause").orElseThrow().status()).isEqualTo(OrchestrationStatus.QUEUED);
        assertThat(repository.findAssignment("cross-resume").orElseThrow().status()).isEqualTo(OrchestrationStatus.PAUSED);
        WorkAssignment forceCandidate = repository.findAssignment("cross-force").orElseThrow();
        assertThat(forceCandidate.status()).isEqualTo(OrchestrationStatus.RUNNING);
        assertThat(forceCandidate.errorText()).isNull();
    }

    private JdbcTemplate jdbcTemplate() {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true);
        return new JdbcTemplate(dataSource);
    }

    private WorkAssignment assignment(String id, String agentId, OrchestrationStatus status) {
        return new WorkAssignment(
            id, agentId, null, null, AssignmentType.REPORT, 1, status,
            null, null, 0, Map.of(), Map.of(), Map.of(), Map.of(),
            null,
            status == OrchestrationStatus.RUNNING ? "owner-1" : null,
            status == OrchestrationStatus.RUNNING ? Instant.now().plusSeconds(300) : null,
            null, null, status == OrchestrationStatus.RUNNING ? Instant.now() : null, null
        );
    }

    private WorkAssignment waitForStatus(
        OrchestrationRuntimeRepository repository,
        String assignmentId,
        OrchestrationStatus expected
    ) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            WorkAssignment assignment = repository.findAssignment(assignmentId).orElseThrow();
            if (assignment.status() == expected) {
                return assignment;
            }
            Thread.sleep(100);
        }
        return repository.findAssignment(assignmentId).orElseThrow();
    }
}
