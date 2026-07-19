package io.mindspice.magenta2.ai.orchestration.agents;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.tool.ChatToolRegistry;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.JobRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationRuntimeRepository;
import io.mindspice.magenta2.ai.orchestration.runtime.ProjectRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentProfileService {
    private static final Set<String> SHELL_WRAPPERS = Set.of(
        "sh", "bash", "dash", "zsh", "ksh", "fish", "csh", "tcsh",
        "env", "sudo", "su", "doas", "xargs", "parallel", "busybox"
    );

    private final AgentProfileRepository repository;
    private final AiConfig aiConfig;
    private final ObjectProvider<ChatToolRegistry> chatToolRegistry;
    private final ObjectProvider<WorkspaceService> workspaceService;
    private final ObjectProvider<WorkspaceDirectoryService> workspaceDirectoryService;
    private final ObjectProvider<WorkspaceRepository> workspaceRepository;
    private final ObjectProvider<OrchestrationRuntimeRepository> orchestrationRuntimeRepository;
    private final ObjectProvider<JobRepository> jobRepository;
    private final ObjectProvider<ProjectRepository> projectRepository;

    // Compatibility constructor for tests that instantiate the service directly.
    public AgentProfileService(
        AgentProfileRepository repository,
        AiConfig aiConfig,
        ObjectProvider<ChatToolRegistry> chatToolRegistry
    ) {
        this(
            repository,
            aiConfig,
            chatToolRegistry,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Autowired
    public AgentProfileService(
        AgentProfileRepository repository,
        AiConfig aiConfig,
        @Autowired(required = false) ObjectProvider<ChatToolRegistry> chatToolRegistry,
        @Autowired(required = false) ObjectProvider<WorkspaceService> workspaceService,
        @Autowired(required = false) ObjectProvider<WorkspaceDirectoryService> workspaceDirectoryService,
        @Autowired(required = false) ObjectProvider<WorkspaceRepository> workspaceRepository,
        @Autowired(required = false) ObjectProvider<OrchestrationRuntimeRepository> orchestrationRuntimeRepository,
        @Autowired(required = false) ObjectProvider<JobRepository> jobRepository,
        @Autowired(required = false) ObjectProvider<ProjectRepository> projectRepository
    ) {
        this.repository = repository;
        this.aiConfig = aiConfig;
        this.chatToolRegistry = chatToolRegistry;
        this.workspaceService = workspaceService;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.workspaceRepository = workspaceRepository;
        this.orchestrationRuntimeRepository = orchestrationRuntimeRepository;
        this.jobRepository = jobRepository;
        this.projectRepository = projectRepository;
    }

    public List<AgentProfile> list() {
        return repository.findAll();
    }

    public AgentProfile get(String id) {
        requireAgentId(id);
        return repository.findById(id).orElseThrow(() -> new IllegalStateException("Agent profile not found: " + id));
    }

    public AgentProfile defaultAgent(String defaultAgentId, String defaultAgentName) {
        if (StringUtils.hasText(defaultAgentId)) {
            return repository.findById(defaultAgentId)
                .orElseGet(() -> findNamedDefault(defaultAgentName));
        }
        return findNamedDefault(defaultAgentName);
    }

    public AgentProfile create(AgentProfile profile) {
        String id = profile.id() == null ? UUID.randomUUID().toString() : profile.id();
        AgentProfile created = save(new AgentProfile(
            id,
            profile.name(),
            profile.status() == null ? AgentProfileStatus.ACTIVE : profile.status(),
            profile.defaultModel(),
            profile.systemPrompt(),
            profile.approvedTools(),
            profile.allowedShellCommands(),
            profile.directLineEnabled(),
            null,
            null
        ));
        return created;
    }

    public AgentProfile update(String id, AgentProfile profile) {
        requireAgentId(id);
        AgentProfile current = get(id);
        AgentProfile updated = save(new AgentProfile(
            id,
            profile.name(),
            profile.status() == null ? current.status() : profile.status(),
            profile.defaultModel(),
            profile.systemPrompt(),
            profile.approvedTools(),
            profile.allowedShellCommands(),
            profile.directLineEnabled(),
            current.createdAt(),
            current.updatedAt()
        ));
        return updated;
    }

    public AgentProfile enable(String id, boolean prepareWorkspace) {
        requireAgentId(id);
        AgentProfile profile = get(id);
        AgentProfile enabled = save(new AgentProfile(
            profile.id(),
            profile.name(),
            AgentProfileStatus.ACTIVE,
            profile.defaultModel(),
            profile.systemPrompt(),
            profile.approvedTools(),
            profile.allowedShellCommands(),
            profile.directLineEnabled(),
            profile.createdAt(),
            profile.updatedAt()
        ));
        return enabled;
    }

    public AgentProfile disable(String id) {
        requireAgentId(id);
        AgentProfile profile = get(id);
        AgentProfile disabled = save(new AgentProfile(
            profile.id(),
            profile.name(),
            AgentProfileStatus.DISABLED,
            profile.defaultModel(),
            profile.systemPrompt(),
            profile.approvedTools(),
            profile.allowedShellCommands(),
            profile.directLineEnabled(),
            profile.createdAt(),
            profile.updatedAt()
        ));
        return disabled;
    }

    public AgentProfile archiveAndDisable(String id) {
        requireAgentId(id);
        AgentProfile disabled = disable(id);
        WorkspaceService ws = workspaceService();
        if (ws != null) {
            ws.archiveAgentWorkspaceData(id);
        }
        return disabled;
    }

    public void hardDelete(String id, String confirmationText) {
        requireAgentId(id);
        if (!("DELETE " + id).equals(confirmationText)) {
            throw new IllegalArgumentException("confirmation text must exactly match: DELETE " + id);
        }
        get(id);
        OrchestrationRuntimeRepository runtimeRepository = orchestrationRuntimeRepository();
        if (runtimeRepository != null) {
            runtimeRepository.purgeAgentOwnedReferences(id);
        }
        JobRepository jobRepository = jobRepository();
        if (jobRepository != null) {
            jobRepository.purgeDefinitionsOwnedByAgent(id);
        }
        ProjectRepository projectRepository = projectRepository();
        if (projectRepository != null) {
            projectRepository.purgeAgentReferences(id);
        }
        WorkspaceRepository wsRepository = workspaceRepository();
        if (wsRepository != null) {
            wsRepository.findByOwner(WorkspaceOwnerType.AGENT, id)
                .ifPresent(workspace -> wsRepository.releaseLeasesByWorkspaceId(workspace.id()));
            wsRepository.deleteByOwner(WorkspaceOwnerType.AGENT, id);
        }
        WorkspaceService ws = workspaceService();
        if (ws != null) {
            ws.deleteAgentWorkspaceData(id);
        }
        repository.delete(id);
    }

    public void deleteOrDisable(String id) {
        requireAgentId(id);
        disable(id);
    }

    public List<String> allowedShellCommands(String defaultAgentId, String defaultAgentName) {
        return defaultAgent(defaultAgentId, defaultAgentName).allowedShellCommands();
    }

    public String systemPrompt(String defaultAgentId, String defaultAgentName) {
        return defaultAgent(defaultAgentId, defaultAgentName).systemPrompt();
    }

    public List<String> approvedTools(String defaultAgentId, String defaultAgentName) {
        return defaultAgent(defaultAgentId, defaultAgentName).approvedTools();
    }

    AgentProfile save(AgentProfile profile) {
        validate(profile);
        return repository.save(profile);
    }

    private AgentProfile findNamedDefault(String defaultAgentName) {
        if (StringUtils.hasText(defaultAgentName)) {
            return repository.findByName(defaultAgentName)
                .orElseThrow(() -> new IllegalStateException("Default agent profile not found: " + defaultAgentName));
        }
        return repository.findByName("magenta")
            .orElseGet(() -> repository.findAll().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No agent profiles exist")));
    }

    private void validate(AgentProfile profile) {
        requireAgentId(profile.id());
        if (!StringUtils.hasText(profile.name())) {
            throw new IllegalArgumentException("agent name is required");
        }
        if (StringUtils.hasText(profile.defaultModel()) && !aiConfig.models().containsKey(profile.defaultModel())) {
            throw new IllegalArgumentException("unknown model key: " + profile.defaultModel());
        }
        ChatToolRegistry registry = chatToolRegistry == null ? null : chatToolRegistry.getIfAvailable();
        if (registry != null) {
            registry.validateToolNames(profile.approvedTools());
        }
        validateShellCommands(profile.allowedShellCommands());
    }

    private void requireAgentId(String id) {
        PlainPathSegmentValidator.requirePlainSegment(id, "agent id");
    }

    private void validateShellCommands(List<String> commands) {
        if (commands == null) {
            return;
        }
        for (String command : commands) {
            if (!StringUtils.hasText(command)) {
                continue;
            }
            if ("*".equals(command)) {
                if (!aiConfig.unsafeAllowWildcardShellCommandsEnabled()) {
                    throw new IllegalArgumentException(
                        "wildcard shell command allowlist requires unsafeAllowWildcardShellCommands=true"
                    );
                }
                continue;
            }
            if (command.contains("/") || command.contains("\\") || command.chars().anyMatch(Character::isWhitespace)) {
                throw new IllegalArgumentException("shell command allowlist entries must be bare executable names");
            }
            if (SHELL_WRAPPERS.contains(command)) {
                throw new IllegalArgumentException("shell wrapper commands are not allowed: " + command);
            }
        }
    }

    private WorkspaceService workspaceService() {
        return workspaceService == null ? null : workspaceService.getIfAvailable();
    }

    private WorkspaceDirectoryService workspaceDirectoryService() {
        return workspaceDirectoryService == null ? null : workspaceDirectoryService.getIfAvailable();
    }

    private WorkspaceRepository workspaceRepository() {
        return workspaceRepository == null ? null : workspaceRepository.getIfAvailable();
    }

    private OrchestrationRuntimeRepository orchestrationRuntimeRepository() {
        return orchestrationRuntimeRepository == null ? null : orchestrationRuntimeRepository.getIfAvailable();
    }

    private JobRepository jobRepository() {
        return jobRepository == null ? null : jobRepository.getIfAvailable();
    }

    private ProjectRepository projectRepository() {
        return projectRepository == null ? null : projectRepository.getIfAvailable();
    }
}
