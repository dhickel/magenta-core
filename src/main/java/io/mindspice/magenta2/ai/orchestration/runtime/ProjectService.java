package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLease;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceLeaseService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Manages projects, agent memberships, and project-network gating.
 * Legacy project owners are preserved as nullable compatibility metadata.
 */
@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final WorkspaceService workspaceService;
    private final WorkspaceLeaseService workspaceLeaseService;
    private final OrchestrationRuntimeRepository runtimeRepository;
    private final JobRepository jobRepository;

    public ProjectService(ProjectRepository projectRepository,
                          WorkspaceDirectoryService workspaceDirectoryService) {
        this(projectRepository, workspaceDirectoryService, null, null, null, null);
    }

    public ProjectService(ProjectRepository projectRepository,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          WorkspaceService workspaceService,
                          WorkspaceLeaseService workspaceLeaseService,
                          OrchestrationRuntimeRepository runtimeRepository) {
        this(projectRepository, workspaceDirectoryService, workspaceService, workspaceLeaseService, runtimeRepository, null);
    }

    @Autowired
    public ProjectService(ProjectRepository projectRepository,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          WorkspaceService workspaceService,
                          WorkspaceLeaseService workspaceLeaseService,
                          OrchestrationRuntimeRepository runtimeRepository,
                          @Autowired(required = false) JobRepository jobRepository) {
        this.projectRepository = projectRepository;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.workspaceService = workspaceService;
        this.workspaceLeaseService = workspaceLeaseService;
        this.runtimeRepository = runtimeRepository;
        this.jobRepository = jobRepository;
    }

    // ════════════════════════════════════════════════════════════════
    //  Project CRUD
    // ════════════════════════════════════════════════════════════════

    public List<Project> listProjects() {
        return projectRepository.findAll();
    }

    public Project getProject(String id) {
        return projectRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
    }

    public List<Project> listProjectsByOwner(String agentId) {
        return projectRepository.findByOwnerAgent(agentId);
    }

    public Project createProject(String name, String description,
                                  String ownerAgentId, String gitRepoUrl) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Project name is required");
        }
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String legacyOwnerAgentId = normalize(ownerAgentId);

        Project project = new Project(
            id, name.trim(), normalize(description), legacyOwnerAgentId,
            normalize(gitRepoUrl), null, null, null, now, now
        );
        Project saved = projectRepository.save(project);

        if (StringUtils.hasText(legacyOwnerAgentId)) {
            addAgent(saved.id(), legacyOwnerAgentId, "owner", now);
        }

        log.info("Created project {} with legacy owner agent {}", id, legacyOwnerAgentId);
        return saved;
    }

    public Project updateProject(String id, String name, String description,
                                  String gitRepoUrl, String promptProfile,
                                  String model, String settingsOverrideJson) {
        Project existing = getProject(id);
        return projectRepository.save(new Project(
            id,
            StringUtils.hasText(name) ? name.trim() : existing.name(),
            description != null ? normalize(description) : existing.description(),
            existing.ownerAgentId(),
            gitRepoUrl != null ? normalize(gitRepoUrl) : existing.gitRepoUrl(),
            promptProfile != null ? normalize(promptProfile) : existing.promptProfile(),
            model != null ? normalize(model) : existing.model(),
            settingsOverrideJson != null ? settingsOverrideJson : existing.settingsOverrideJson(),
            existing.createdAt(),
            Instant.now()
        ));
    }

    public void deleteProject(String id) {
        // verify it exists
        getProject(id);
        requireProjectDeletionAllowed(id);
        projectRepository.delete(id);
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent Membership
    // ════════════════════════════════════════════════════════════════

    /**
     * Add an agent to a project. The owner is added automatically on creation.
     */
    public ProjectAgentMembership addAgent(String projectId, String agentId, String role) {
        return addAgent(projectId, agentId, role, Instant.now());
    }

    private ProjectAgentMembership addAgent(String projectId, String agentId,
                                             String role, Instant joinedAt) {
        getProject(projectId); // validate exists
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("Agent ID is required");
        }
        String membershipId = UUID.randomUUID().toString();
        String effectiveRole = StringUtils.hasText(role) ? role : "member";
        return projectRepository.saveMembership(new ProjectAgentMembership(
            membershipId, projectId, agentId.trim(), effectiveRole, joinedAt
        ));
    }

    public void removeAgent(String projectId, String agentId) {
        getProject(projectId);
        requireProjectMembershipRemovalAllowed(projectId, agentId);
        projectRepository.deleteMembership(projectId, agentId);
    }

    public List<ProjectAgentMembership> listMembers(String projectId) {
        return projectRepository.findMembershipsByProject(projectId);
    }

    public List<String> listAgentProjects(String agentId) {
        return projectRepository.findProjectIdsByAgent(agentId);
    }

    public boolean isMember(String projectId, String agentId) {
        return projectRepository.isMember(projectId, agentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Network gating
    // ════════════════════════════════════════════════════════════════

    /**
     * Checks whether two agents share at least one project and thus may
     * exchange project-scoped messages.
     */
    public boolean agentsShareProject(String agentAId, String agentBId) {
        List<String> projectsA = projectRepository.findProjectIdsByAgent(agentAId);
        List<String> projectsB = projectRepository.findProjectIdsByAgent(agentBId);
        return projectsA.stream().anyMatch(projectsB::contains);
    }

    /**
     * Validates that sender and recipient share the given project.
     * Throws if they do not share the project network.
     */
    public void requireProjectNetwork(String projectId, String senderAgentId,
                                       String recipientAgentId) {
        if (!isMember(projectId, senderAgentId)) {
            throw new IllegalArgumentException(
                "Sender agent " + senderAgentId + " is not a member of project " + projectId);
        }
        if (!isMember(projectId, recipientAgentId)) {
            throw new IllegalArgumentException(
                "Recipient agent " + recipientAgentId + " is not a member of project " + projectId);
        }
    }

    /**
     * Returns true if the given agent may send a project-scoped message
     * to the given recipient. The agents must share at least one project.
     */
    public boolean canMessage(String senderAgentId, String recipientAgentId) {
        return agentsShareProject(senderAgentId, recipientAgentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Project Events
    // ════════════════════════════════════════════════════════════════

    public List<ProjectEvent> listEvents(String projectId) {
        return projectRepository.findEvents(projectId);
    }

    public ProjectEvent recordEvent(String projectId, String type, String payloadJson) {
        getProject(projectId); // validate exists
        return projectRepository.saveEvent(new ProjectEvent(
            UUID.randomUUID().toString(), projectId, type,
            payloadJson, Instant.now()
        ));
    }

    public ProjectWorkspaceSummary workspaceSummary(String projectId) {
        Project project = getProject(projectId);
        String displayPath = "projects/" + projectId + "/workspace";
        var workspace = workspaceService == null ? null : workspaceService.projectWorkspace(projectId, project.name());
        WorkspaceLease activeLease = workspace == null || workspaceLeaseService == null
            ? null : workspaceLeaseService.activeWritableLease(workspace.id()).orElse(null);
        WorkAssignment holder = activeLease == null || runtimeRepository == null
            ? null : runtimeRepository.findAssignment(activeLease.holderId()).orElse(null);
        return new ProjectWorkspaceSummary(
            workspace == null ? projectId : workspace.id(),
            project.ownerAgentId(),
            "PROJECT",
            displayPath,
            listMembers(projectId).size(),
            activeLease == null ? null : activeLease.id(),
            activeLease == null ? null : activeLease.holderId(),
            holder == null ? null : holder.agentId(),
            activeLease != null && activeLease.releaseRequested()
        );
    }

    public WorkspaceLease requestWorkspaceRelease(String projectId) {
        Project project = getProject(projectId);
        if (workspaceService == null || workspaceLeaseService == null) {
            throw new IllegalStateException("Project workspace lease management is unavailable");
        }
        var workspace = workspaceService.projectWorkspace(projectId, project.name());
        return workspaceLeaseService.requestGracefulRelease(workspace.id());
    }

    public void requireProjectDeletionAllowed(String projectId) {
        if (runtimeRepository != null && runtimeRepository.countActiveAssignmentsForProject(projectId) > 0) {
            throw new IllegalStateException("Project has active assignments and cannot be deleted: " + projectId);
        }
        WorkspaceLease lease = activeProjectLease(projectId);
        if (lease != null) {
            throw new IllegalStateException("Project workspace is actively leased by assignment " + lease.holderId());
        }
        if (jobRepository != null && jobRepository.countActiveRunsByProject(projectId) > 0) {
            throw new IllegalStateException("Project has active job runs and cannot be deleted: " + projectId);
        }
    }

    public void requireProjectMembershipRemovalAllowed(String projectId, String agentId) {
        if (runtimeRepository != null && runtimeRepository.countActiveAssignmentsForProjectAndAgent(projectId, agentId) > 0) {
            throw new IllegalStateException(
                "Agent has active assignments in project and cannot be removed: " + agentId);
        }
        WorkspaceLease lease = activeProjectLease(projectId);
        if (lease != null && runtimeRepository != null) {
            WorkAssignment holder = runtimeRepository.findAssignment(lease.holderId()).orElse(null);
            if (holder != null && agentId.equals(holder.agentId())) {
                throw new IllegalStateException(
                    "Agent holds the active project workspace lease and cannot be removed: " + agentId);
            }
        }
    }

    private WorkspaceLease activeProjectLease(String projectId) {
        if (workspaceService == null || workspaceLeaseService == null) {
            return null;
        }
        try {
            Project project = getProject(projectId);
            var workspace = workspaceService.projectWorkspace(projectId, project.name());
            return workspaceLeaseService.activeWritableLease(workspace.id()).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ── Helpers ──

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record ProjectWorkspaceSummary(
        String workspaceId,
        String ownerAgentId,
        String rootKind,
        String displayPath,
        int linkCount,
        String leaseId,
        String leaseHolderAssignmentId,
        String mountedAgentId,
        boolean releaseRequested
    ) {}
}
