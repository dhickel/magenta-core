package io.mindspice.magenta2.ai.orchestration.runtime;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Server-owned capability and scoped-access boundary for project operations.
 * <p>
 * Every sensitive entry point that acts on a project (archive, member removal,
 * submission gating) calls through this service. The {@code trustedDeployment}
 * capability defaults to {@code false} and must be explicitly enabled when the
 * deployment owner intends to permit project-level mutations.
 * <p>
 * This service does not hold any lease, root, or identity authority. It is a
 * pure guard: project association is trusted attribution/context only.
 */
@Service
public class ProjectOperationsAccessService {
    private static final Logger log = LoggerFactory.getLogger(ProjectOperationsAccessService.class);

    private final ProjectRepository projectRepository;
    private final OrchestrationRuntimeRepository runtimeRepository;
    private final JobRepository jobRepository;

    /** Controlled gate — off by default. */
    private volatile boolean trustedDeployment = false;

    public ProjectOperationsAccessService(ProjectRepository projectRepository,
                                          OrchestrationRuntimeRepository runtimeRepository,
                                          JobRepository jobRepository) {
        this.projectRepository = projectRepository;
        this.runtimeRepository = runtimeRepository;
        this.jobRepository = jobRepository;
    }

    // ── Capability gate ──

    public void enableTrustedDeployment() {
        log.warn("Trusted deployment capability enabled — project mutation gates are open");
        this.trustedDeployment = true;
    }

    public void disableTrustedDeployment() {
        log.info("Trusted deployment capability disabled");
        this.trustedDeployment = false;
    }

    public boolean isTrustedDeployment() {
        return trustedDeployment;
    }

    private void requireTrustedDeployment(String operation) {
        if (!trustedDeployment) {
            throw new ProjectAccessDeniedException(
                "Project operation '" + operation + "' requires trusted deployment capability");
        }
    }

    // ── Project existence and eligibility ──

    /**
     * Require the project exists and is active (not archived).
     *
     * @return the project for chaining
     */
    public Project requireActiveProject(String projectId) {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.archived()) {
            throw new ProjectAccessDeniedException("Project is archived: " + projectId);
        }
        return project;
    }

    /**
     * Require the project exists and the agent is a current member.
     */
    public Project requireActiveProjectWithMember(String projectId, String agentId) {
        Project project = requireActiveProject(projectId);
        if (!projectRepository.isMember(projectId, agentId)) {
            throw new ProjectAccessDeniedException(
                "Agent " + agentId + " is not a member of project " + projectId);
        }
        return project;
    }

    // ── Project archive ──

    /**
     * Non-destructive atomic project archive. Blocks when active assignments
     * (queued, running, interrupted, paused, waiting) would be stranded.
     * Historical terminal rows are retained.
     */
    @Transactional
    public void archiveProject(String projectId) {
        requireTrustedDeployment("archive");
        Project project = requireActiveProject(projectId);

        long activeCount = runtimeRepository.countActiveAssignmentsForProject(projectId);
        if (activeCount > 0) {
            throw new ProjectAccessDeniedException(
                "Project has " + activeCount + " active assignment(s) and cannot be archived: " + projectId);
        }
        if (jobRepository != null && jobRepository.countActiveRunsByProject(projectId) > 0) {
            throw new ProjectAccessDeniedException(
                "Project has active job runs and cannot be archived: " + projectId);
        }
        projectRepository.archive(projectId);
        log.info("Project archived: {}", projectId);
    }

    /**
     * Restore an archived project to active.
     */
    @Transactional
    public void restoreProject(String projectId) {
        requireTrustedDeployment("restore");
        projectRepository.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        projectRepository.restore(projectId);
        log.info("Project restored: {}", projectId);
    }

    // ── Member removal guard ──

    /**
     * Remove an agent from a project. Blocked when the agent has active
     * assignments attributed to this project.
     */
    @Transactional
    public void removeMember(String projectId, String agentId) {
        requireTrustedDeployment("remove-member");
        requireActiveProject(projectId);

        if (!projectRepository.isMember(projectId, agentId)) {
            throw new IllegalArgumentException(
                "Agent " + agentId + " is not a member of project " + projectId);
        }
        long activeCount = runtimeRepository.countActiveAssignmentsForProjectAndAgent(projectId, agentId);
        if (activeCount > 0) {
            throw new ProjectAccessDeniedException(
                "Agent " + agentId + " has " + activeCount
                + " active assignment(s) in project " + projectId + " and cannot be removed");
        }
        projectRepository.deleteMembership(projectId, agentId);
        log.info("Agent {} removed from project {}", agentId, projectId);
    }

    // ── Submission gate ──

    /**
     * Require that a project-attributed submission targets an active project
     * whose executor agent is a current member.
     */
    public void requireEligibleSubmission(String projectId, String agentId) {
        requireTrustedDeployment("submission");
        requireActiveProjectWithMember(projectId, agentId);
    }

    /** Guard membership changes so archived projects cannot gain executors. */
    public Project requireMemberMutation(String projectId) {
        requireTrustedDeployment("member-mutation");
        return requireActiveProject(projectId);
    }

    // ── Query helpers ──

    public List<String> projectIdsForAgent(String agentId) {
        return projectRepository.findProjectIdsByAgent(agentId);
    }

    // ── Exception ──

    public static final class ProjectAccessDeniedException extends RuntimeException {
        public ProjectAccessDeniedException(String message) {
            super(message);
        }
    }
}
