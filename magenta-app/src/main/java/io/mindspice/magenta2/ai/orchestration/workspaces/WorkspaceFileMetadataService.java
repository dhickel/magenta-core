package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.List;

import org.springframework.stereotype.Service;

@Service
public class WorkspaceFileMetadataService {
    private final WorkspaceFileMetadataRepository repository;
    private final WorkspaceFileActionLogRepository actionLogRepository;

    public WorkspaceFileMetadataService(
        WorkspaceFileMetadataRepository repository,
        WorkspaceFileActionLogRepository actionLogRepository
    ) {
        this.repository = repository;
        this.actionLogRepository = actionLogRepository;
    }

    public WorkspaceFileLabel ensureTag(String labelSlug, String displayName) {
        return repository.ensureLabel(labelSlug, displayName, false);
    }

    public WorkspaceFileLabelAssignment addLabel(WorkspaceDescriptor descriptor, String fileRelativePath, String labelSlug) {
        WorkspaceFileLabelAssignment assignment = repository.addLabel(descriptor.identity(), fileRelativePath, labelSlug);
        actionLogRepository.record(
            descriptor,
            WorkspaceFileActionType.TAG_ADD,
            fileRelativePath,
            null,
            "SUCCEEDED",
            "{\"label\":\"" + assignment.label().slug() + "\"}"
        );
        return assignment;
    }

    public int removeLabel(WorkspaceDescriptor descriptor, String fileRelativePath, String labelSlug) {
        int removed = repository.removeLabel(descriptor.identity(), fileRelativePath, labelSlug);
        actionLogRepository.record(
            descriptor,
            WorkspaceFileActionType.TAG_REMOVE,
            fileRelativePath,
            null,
            "SUCCEEDED",
            "{\"label\":\"" + labelSlug + "\",\"removed\":" + removed + "}"
        );
        return removed;
    }

    public List<WorkspaceFileLabelAssignment> labelsForPath(WorkspaceDescriptor descriptor, String fileRelativePath) {
        return repository.labelsForPath(descriptor.identity(), fileRelativePath);
    }

    public void onMove(WorkspaceDescriptor descriptor, String sourceRelativePath, String targetRelativePath) {
        repository.moveSubtree(descriptor.identity(), sourceRelativePath, targetRelativePath);
    }

    public void onCopy(WorkspaceDescriptor descriptor, String sourceRelativePath, String targetRelativePath) {
        repository.copySubtree(descriptor.identity(), sourceRelativePath, targetRelativePath);
    }

    public void onDelete(WorkspaceDescriptor descriptor, String sourceRelativePath) {
        repository.deleteSubtree(descriptor.identity(), sourceRelativePath);
    }
}
