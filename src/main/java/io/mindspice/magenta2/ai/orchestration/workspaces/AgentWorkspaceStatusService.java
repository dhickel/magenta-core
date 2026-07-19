package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.workspaces.AgentWorkspaceStatus.WorkspaceHealth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Produces workspace health/activity read models for agents.
 * Reports filesystem-derived workspace facts.
 */
@Service
public class AgentWorkspaceStatusService {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceStatusService.class);

    private final WorkspaceDirectoryService directoryService;
    private final WorkspaceService workspaceService;
    private final WorkspaceLeaseService leaseService;
    private final OutputArtifactService outputArtifactService;
    private final AssignmentService assignmentService;

    public AgentWorkspaceStatusService(
        WorkspaceDirectoryService directoryService,
        WorkspaceService workspaceService,
        WorkspaceLeaseService leaseService,
        OutputArtifactService outputArtifactService,
        AssignmentService assignmentService
    ) {
        this.directoryService = directoryService;
        this.workspaceService = workspaceService;
        this.leaseService = leaseService;
        this.outputArtifactService = outputArtifactService;
        this.assignmentService = assignmentService;
    }

    /**
     * Produce a workspace status snapshot for a single agent.
     * Prefers deterministic query/update over an always-on file watcher.
     */
    public AgentWorkspaceStatus statusFor(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }

        Path workspacePath = directoryService.agentWorkspace(agentId);
        boolean exists = Files.isDirectory(workspacePath);
        boolean writable = exists && Files.isWritable(workspacePath);

        if (!exists) {
            return AgentWorkspaceStatus.missing(agentId);
        }
        if (!writable) {
            return new AgentWorkspaceStatus(
                agentId,
                "agents/" + agentId + "/workspace",
                WorkspaceHealth.READ_ONLY,
                true, false, 0, 0, List.of(), 0, 0, null,
                "Workspace exists but is not writable"
            );
        }

        int activeRunCount;
        try {
            activeRunCount = assignmentService.queueAssignments(agentId).size();
        } catch (Exception e) {
            log.debug("Failed to query assignments for agent {}: {}", agentId, e.getMessage());
            activeRunCount = 0;
        }

        int activeLeaseCount;
        List<String> linkedProjectIds;
        try {
            Workspace ws = workspaceService.agentWorkspace(agentId, null);
            activeLeaseCount = workspaceService.activeLeases(ws.id()).size();
            linkedProjectIds = workspaceService.links(ws.id()).stream()
                .filter(link -> link.linkType() == WorkspaceLinkType.PATH && link.target().contains("projects/"))
                .map(link -> {
                    String target = link.target();
                    int idx = target.lastIndexOf("projects/");
                    if (idx >= 0) {
                        String sub = target.substring(idx + "projects/".length());
                        int slash = sub.indexOf('/');
                        return slash > 0 ? sub.substring(0, slash) : sub;
                    }
                    return link.label();
                })
                .distinct()
                .toList();
        } catch (Exception e) {
            log.debug("Failed to query workspace metadata for agent {}: {}", agentId, e.getMessage());
            activeLeaseCount = 0;
            linkedProjectIds = List.of();
        }

        long outputArtifactCount;
        long outputBytes;
        try {
            List<RunOutputArtifact> artifacts = outputArtifactService.query(
                OutputArtifactQuery.of(agentId, null, null, null, null, null, null, null, null, 200));
            outputArtifactCount = artifacts.size();
            outputBytes = artifacts.stream()
                .mapToLong(a -> {
                    try {
                        Path artifactPath = outputArtifactService.resolveArtifactFile(a);
                        return Files.exists(artifactPath) ? Files.size(artifactPath) : 0;
                    } catch (Exception ignored) {
                        return 0;
                    }
                })
                .sum();
        } catch (Exception e) {
            log.debug("Failed to query output artifacts for agent {}: {}", agentId, e.getMessage());
            outputArtifactCount = 0;
            outputBytes = 0;
        }

        Instant lastActivity = null;
        try {
            List<RunOutputArtifact> recent = outputArtifactService.query(
                OutputArtifactQuery.of(agentId, null, null, null, null, null, null, null, null, 1));
            if (!recent.isEmpty() && recent.getFirst().createdAt() != null) {
                lastActivity = recent.getFirst().createdAt();
            }
        } catch (Exception ignored) {
            // leave null
        }

        WorkspaceHealth health;
        String message;
        if (activeRunCount > 0) {
            health = WorkspaceHealth.BUSY;
            message = "Active runs: " + activeRunCount;
        } else {
            health = WorkspaceHealth.READY;
            message = "Workspace ready";
        }

        return new AgentWorkspaceStatus(
            agentId,
            "agents/" + agentId + "/workspace",
            health,
            true,
            true,
            activeRunCount,
            activeLeaseCount,
            linkedProjectIds,
            outputArtifactCount,
            outputBytes,
            lastActivity,
            message
        );
    }
}
