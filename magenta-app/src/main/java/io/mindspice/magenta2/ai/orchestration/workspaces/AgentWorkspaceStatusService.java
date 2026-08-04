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
 * Resolves the canonical descriptor without creating its root.
 * Derives output counts from OutputArtifactRepository and
 * assignment state from {@link AssignmentService}.
 */
@Service
public class AgentWorkspaceStatusService {
    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceStatusService.class);

    private final WorkspaceDirectoryService directoryService;
    private final OutputArtifactService outputArtifactService;
    private final AssignmentService assignmentService;

    public AgentWorkspaceStatusService(
        WorkspaceDirectoryService directoryService,
        OutputArtifactService outputArtifactService,
        AssignmentService assignmentService
    ) {
        this.directoryService = directoryService;
        this.outputArtifactService = outputArtifactService;
        this.assignmentService = assignmentService;
    }

    /**
     * Produce a workspace status snapshot for a single agent.
     * Resolves the canonical descriptor without creating the root.
     */
    public AgentWorkspaceStatus statusFor(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId is required");
        }

        WorkspaceDescriptor descriptor;
        try {
            descriptor = directoryService.resolveForRead(
                    WorkspaceKind.AGENT, agentId);
        } catch (WorkspaceResolutionException e) {
            return AgentWorkspaceStatus.error(agentId, e.getMessage());
        }

        Path workspacePath = descriptor.root();
        boolean exists = Files.isDirectory(workspacePath);
        boolean writable = exists && Files.isWritable(workspacePath);

        if (!exists) {
            return AgentWorkspaceStatus.missing(agentId);
        }
        if (!writable) {
            return new AgentWorkspaceStatus(
                agentId,
                descriptor.identity(),
                WorkspaceHealth.READ_ONLY,
                true, false, 0, 0, 0, null,
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
            descriptor.identity(),
            health,
            true,
            true,
            activeRunCount,
            outputArtifactCount,
            outputBytes,
            lastActivity,
            message
        );
    }
}
