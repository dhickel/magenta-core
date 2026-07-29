package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

/**
 * Manages projects, agent memberships, and project-network gating.
 * Project ids remain metadata and membership context; they never
 * select, allocate, link, lease, or delete a filesystem root.
 */
@Service
public class ProjectService {
    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final OrchestrationRuntimeRepository runtimeRepository;
    private final JobRepository jobRepository;
    private final ProjectOperationsAccessService accessService;
    private final AssignmentQueryService queryService;

    public ProjectService(ProjectRepository projectRepository,
                          WorkspaceDirectoryService workspaceDirectoryService) {
        this(projectRepository, workspaceDirectoryService, null, null, null, null);
    }

    @Autowired
    public ProjectService(ProjectRepository projectRepository,
                          WorkspaceDirectoryService workspaceDirectoryService,
                          OrchestrationRuntimeRepository runtimeRepository,
                          @Autowired(required = false) JobRepository jobRepository,
                          @Autowired(required = false) ProjectOperationsAccessService accessService,
                          @Autowired(required = false) AssignmentQueryService queryService) {
        this.projectRepository = projectRepository;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.runtimeRepository = runtimeRepository;
        this.jobRepository = jobRepository;
        this.accessService = accessService;
        this.queryService = queryService;
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
            normalize(gitRepoUrl), null, null, null, false, now, now
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
            existing.archived(),
            existing.createdAt(),
            Instant.now()
        ));
    }

    public void deleteProject(String id) {
        getProject(id);
        requireProjectDeletionAllowed(id);
        projectRepository.delete(id);
    }

    // ════════════════════════════════════════════════════════════════
    //  Agent Membership
    // ════════════════════════════════════════════════════════════════

    public ProjectAgentMembership addAgent(String projectId, String agentId, String role) {
        return addAgent(projectId, agentId, role, Instant.now());
    }

    private ProjectAgentMembership addAgent(String projectId, String agentId,
                                             String role, Instant joinedAt) {
        if (accessService != null) {
            accessService.requireActiveProject(projectId);
        } else {
            getProject(projectId);
        }
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
        requireAccessService().removeMember(projectId, agentId);
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

    public boolean agentsShareProject(String agentAId, String agentBId) {
        List<String> projectsA = projectRepository.findProjectIdsByAgent(agentAId);
        List<String> projectsB = projectRepository.findProjectIdsByAgent(agentBId);
        return projectsA.stream().anyMatch(projectsB::contains);
    }

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

    public boolean canMessage(String senderAgentId, String recipientAgentId) {
        return agentsShareProject(senderAgentId, recipientAgentId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Project Archive / Restore
    // ════════════════════════════════════════════════════════════════

    public void archiveProject(String projectId) {
        requireAccessService().archiveProject(projectId);
    }

    public void restoreProject(String projectId) {
        requireAccessService().restoreProject(projectId);
    }

    // ════════════════════════════════════════════════════════════════
    //  Project Events
    // ════════════════════════════════════════════════════════════════

    public List<ProjectEvent> listEvents(String projectId) {
        return projectRepository.findEvents(projectId);
    }

    public ProjectEvent recordEvent(String projectId, String type, String payloadJson) {
        getProject(projectId);
        return projectRepository.saveEvent(new ProjectEvent(
            UUID.randomUUID().toString(), projectId, type,
            payloadJson, Instant.now()
        ));
    }

    public void requireProjectDeletionAllowed(String projectId) {
        if (runtimeRepository != null && runtimeRepository.countActiveAssignmentsForProject(projectId) > 0) {
            throw new IllegalStateException("Project has active assignments and cannot be deleted: " + projectId);
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
    }

    // ── Helpers ──

    private ProjectOperationsAccessService requireAccessService() {
        if (accessService == null) {
            throw new IllegalStateException("Project operations access service is unavailable");
        }
        return accessService;
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
