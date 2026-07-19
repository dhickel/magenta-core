package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentRequest;
import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class OutputDirectoryService {
    private final EffectiveWorkspaceResolver effectiveWorkspaceResolver;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final WorkAreaService workAreaService;

    public OutputDirectoryService(
        EffectiveWorkspaceResolver effectiveWorkspaceResolver,
        WorkspaceDirectoryService workspaceDirectoryService
    ) {
        this(effectiveWorkspaceResolver, workspaceDirectoryService, null);
    }

    @Autowired
    public OutputDirectoryService(
        EffectiveWorkspaceResolver effectiveWorkspaceResolver,
        WorkspaceDirectoryService workspaceDirectoryService,
        @Autowired(required = false) WorkAreaService workAreaService
    ) {
        this.effectiveWorkspaceResolver = effectiveWorkspaceResolver;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.workAreaService = workAreaService;
    }

    public ResolvedOutputDirectory resolve(OutputPublicationTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("target is required");
        }
        EffectiveWorkspace workspace = effectiveWorkspaceResolver.resolve(target.agentId(), target.projectId());
        WorkAreaPaths workAreaPaths = resolveWorkAreaPaths(target, workspace);
        requireTargetIdentifiers(target);
        Path outputDirectory = finalOutputDirectory(workAreaPaths, workspace);
        return new ResolvedOutputDirectory(
            workspace.ownerType(),
            workspace.ownerId(),
            workspace.agentId(),
            workspace.projectId(),
            workspace.workspaceId(),
            workAreaPaths.ownerRoot(),
            workAreaPaths.executionRoot(),
            outputDirectory,
            artifactContext(target, workspace)
        );
    }

    private Path finalOutputDirectory(WorkAreaPaths workAreaPaths, EffectiveWorkspace workspace) {
        if (workAreaPaths.routed()) {
            return ensureRealDirectory(workAreaPaths.outputRoot());
        }
        return ensureRealDirectory(workspace.outputsDir());
    }

    private void requireTargetIdentifiers(OutputPublicationTarget target) {
        switch (target.kind()) {
            case TASK -> {
                requireSegment(target.workUnitId(), "taskId");
                requireSegment(target.runId(), "runId");
            }
            case WORKFLOW -> {
                requireSegment(target.workUnitId(), "workflowId");
                requireSegment(target.runId(), "runId");
            }
            case JOB -> {
                requireSegment(target.jobAssignmentId(), "jobAssignmentId");
                requireSegment(target.jobRunId(), "jobRunId");
            }
        }
    }

    private WorkAreaPaths resolveWorkAreaPaths(OutputPublicationTarget target, EffectiveWorkspace workspace) {
        if (workAreaService == null || !StringUtils.hasText(target.selectedWorkAreaId())) {
            return new WorkAreaPaths(workspace.root(), workspace.root(), workspace.root(), false);
        }
        WorkspaceOwnerType ownerType = workspace.ownerType();
        String ownerId = workspace.ownerId();
        WorkArea selected = workAreaService.requireActiveOwned(
            target.selectedWorkAreaId(), ownerType, ownerId, "selected Work Area");
        Path ownerRoot = workAreaService.ownerRoot(ownerType, ownerId);
        Path executionRoot = workAreaService.resolve(selected);
        String routeType = StringUtils.hasText(target.outputRouteType())
            ? target.outputRouteType()
            : "";
        boolean routed = StringUtils.hasText(routeType);
        Path outputRoot = executionRoot;
        if ("WORK_AREA".equals(routeType)) {
            WorkArea outputArea = workAreaService.requireActiveOwned(
                target.outputWorkAreaId(), ownerType, ownerId, "output Work Area");
            outputRoot = workAreaService.resolve(outputArea);
        } else if ("DIRECT_DIRECTORY".equals(routeType)) {
            outputRoot = ownerRoot.resolve(workAreaService.requireExistingOwnerDirectory(
                ownerType, ownerId, target.outputDirectRelativePath(), "direct output directory")).normalize();
        }
        outputRoot = ensureRealDirectory(outputRoot);
        return new WorkAreaPaths(ownerRoot, executionRoot, outputRoot, routed || StringUtils.hasText(target.selectedWorkAreaId()));
    }

    private Path ensureRealDirectory(Path path) {
        try {
            return Files.createDirectories(path).toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to create output directory: " + path, exception);
        }
    }

    private OutputArtifactContext artifactContext(OutputPublicationTarget target, EffectiveWorkspace workspace) {
        return new OutputArtifactContext(
            workspace.agentId(),
            target.jobId(),
            target.jobAssignmentId(),
            target.jobRunId(),
            workspace.projectId(),
            workspace.workspaceId(),
            runType(target.kind())
        );
    }

    private String runType(OutputDirectoryKind kind) {
        return switch (kind) {
            case TASK -> "TASK_RUN";
            case WORKFLOW -> "WORKFLOW_RUN";
            case JOB -> "JOB_RUN";
        };
    }

    private String requireSegment(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        PlainPathSegmentValidator.requirePlainSegment(value, label);
        return value.trim();
    }

    private record WorkAreaPaths(Path ownerRoot, Path executionRoot, Path outputRoot, boolean routed) {
    }
}
