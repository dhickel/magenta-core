package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Transitional Work Area identity/metadata adapter. All reusable file IO,
 * normalization, confinement, limits, and mutation behavior is delegated to
 * {@link WorkspaceExplorerService}. Removed in Phase 04.
 */
@Service
public class WorkAreaExplorerService {
    private final WorkAreaService workAreaService;
    private final WorkspaceExplorerService explorer;
    private final WorkspaceFileMetadataService metadataService;
    private final WorkspaceFileActionLogRepository actionLogRepository;

    @Autowired
    public WorkAreaExplorerService(WorkAreaService workAreaService, WorkspaceExplorerService explorer,
                                   WorkspaceFileMetadataService metadataService,
                                   WorkspaceFileActionLogRepository actionLogRepository) {
        this.workAreaService = workAreaService;
        this.explorer = explorer;
        this.metadataService = metadataService;
        this.actionLogRepository = actionLogRepository;
    }

    public WorkAreaExplorerService(WorkAreaService workAreaService) {
        this(workAreaService, new WorkspaceExplorerService(), null, null);
    }

    public DirectoryListing list(String workAreaId, String relativePath) {
        return list(workAreaService.get(workAreaId), relativePath);
    }

    public DirectoryListing listOwnerRoot(WorkspaceOwnerType ownerType, String ownerId, String displayName, String relativePath) {
        return list(workAreaService.ownerRootDescriptor(ownerType, ownerId, displayName), relativePath);
    }

    private DirectoryListing list(WorkArea area, String relativePath) {
        WorkspaceExplorerService.DirectoryListing listing = explorer.list(root(area), path(relativePath));
        return new DirectoryListing(area, listing.path(), listing.entries().stream().map(entry -> adapt(area, entry)).toList());
    }

    public FilePreview preview(String workAreaId, String relativePath) {
        return preview(workAreaService.get(workAreaId), relativePath);
    }

    public FilePreview previewOwnerRoot(WorkspaceOwnerType ownerType, String ownerId, String displayName, String relativePath) {
        return preview(workAreaService.ownerRootDescriptor(ownerType, ownerId, displayName), relativePath);
    }

    private FilePreview preview(WorkArea area, String relativePath) {
        return adapt(explorer.preview(root(area), path(relativePath)));
    }

    public DownloadContent download(String workAreaId, String relativePath) {
        WorkArea area = workAreaService.get(workAreaId);
        WorkspaceExplorerService.DownloadContent content = explorer.downloadContent(root(area), path(relativePath));
        return new DownloadContent(content.fileName(), content.bytes());
    }

    @Transactional
    public FilePreview saveText(String workAreaId, String relativePath, String content) {
        return saveText(workAreaService.get(workAreaId), relativePath, content);
    }

    @Transactional
    public FilePreview saveTextOwnerRoot(WorkspaceOwnerType ownerType, String ownerId, String displayName,
                                         String relativePath, String content) {
        return saveText(workAreaService.ownerRootDescriptor(ownerType, ownerId, displayName), relativePath, content);
    }

    private FilePreview saveText(WorkArea area, String relativePath, String content) {
        WorkspaceExplorerService.RelativePath source = path(relativePath);
        WorkspaceExplorerService.FilePreview saved = explorer.saveText(root(area), source, content);
        log(area, saved.kind().equals("markdown") ? WorkspaceFileActionType.SAVE_MARKDOWN : WorkspaceFileActionType.SAVE_TEXT,
            rootRelative(area, saved.path()), null, "SUCCEEDED", "{\"bytes\":" + saved.size() + "}");
        return adapt(saved);
    }

    @Transactional
    public Entry createDirectory(String workAreaId, String relativePath) {
        return createDirectory(workAreaService.get(workAreaId), relativePath);
    }

    @Transactional
    public Entry createDirectoryOwnerRoot(WorkspaceOwnerType ownerType, String ownerId, String displayName, String relativePath) {
        return createDirectory(workAreaService.ownerRootDescriptor(ownerType, ownerId, displayName), relativePath);
    }

    private Entry createDirectory(WorkArea area, String relativePath) {
        WorkspaceExplorerService.Entry created = explorer.createDirectory(root(area), path(relativePath));
        log(area, WorkspaceFileActionType.CREATE_FOLDER, rootRelative(area, created.path()), null, "SUCCEEDED", "{}");
        return adapt(area, created);
    }

    @Transactional
    public Entry createTextFile(String workAreaId, String parentRelativePath, String fileName) {
        return createFile(workAreaService.get(workAreaId), parentRelativePath, fileName, false);
    }

    @Transactional
    public Entry createTextFileOwnerRoot(WorkspaceOwnerType ownerType, String ownerId, String displayName,
                                         String parentRelativePath, String fileName) {
        return createFile(workAreaService.ownerRootDescriptor(ownerType, ownerId, displayName), parentRelativePath, fileName, false);
    }

    @Transactional
    public Entry createMarkdownFile(String workAreaId, String parentRelativePath, String fileName) {
        return createFile(workAreaService.get(workAreaId), parentRelativePath, fileName, true);
    }

    private Entry createFile(WorkArea area, String parentRelativePath, String fileName, boolean markdown) {
        WorkspaceExplorerService.Entry created = markdown
            ? explorer.createMarkdownFile(root(area), path(parentRelativePath), fileName)
            : explorer.createTextFile(root(area), path(parentRelativePath), fileName);
        log(area, markdown ? WorkspaceFileActionType.CREATE_MARKDOWN_FILE : WorkspaceFileActionType.CREATE_TEXT_FILE,
            rootRelative(area, created.path()), null, "SUCCEEDED", "{}");
        return adapt(area, created);
    }

    @Transactional
    public Entry rename(String workAreaId, String relativePath, String newName) {
        WorkArea area = workAreaService.get(workAreaId);
        protectLegacyWorkAreas(area, relativePath);
        WorkspaceExplorerService.Entry renamed = explorer.rename(root(area), path(relativePath), newName);
        String source = rootRelative(area, path(relativePath).value());
        String target = rootRelative(area, renamed.path());
        metadataMove(area, source, target);
        log(area, WorkspaceFileActionType.RENAME, source, target, "SUCCEEDED", "{}");
        return adapt(area, renamed);
    }

    @Transactional
    public Entry move(String workAreaId, String sourceRelativePath, String destinationDirectoryRelativePath, String newName) {
        WorkArea area = workAreaService.get(workAreaId);
        protectLegacyWorkAreas(area, sourceRelativePath);
        WorkspaceExplorerService.Entry moved = explorer.move(root(area), path(sourceRelativePath), path(destinationDirectoryRelativePath), newName);
        String source = rootRelative(area, path(sourceRelativePath).value());
        String target = rootRelative(area, moved.path());
        metadataMove(area, source, target);
        log(area, WorkspaceFileActionType.MOVE, source, target, "SUCCEEDED", "{}");
        return adapt(area, moved);
    }

    @Transactional
    public Entry copy(String workAreaId, String sourceRelativePath, String destinationDirectoryRelativePath, String newName) {
        WorkArea area = workAreaService.get(workAreaId);
        WorkspaceExplorerService.Entry copied = explorer.copy(root(area), path(sourceRelativePath), path(destinationDirectoryRelativePath), newName);
        String source = rootRelative(area, path(sourceRelativePath).value());
        String target = rootRelative(area, copied.path());
        metadataCopy(area, source, target);
        log(area, WorkspaceFileActionType.COPY, source, target, "SUCCEEDED", "{}");
        return adapt(area, copied);
    }

    public DeletePreflight deletePreflight(String workAreaId, String relativePath, DeleteStep step) {
        WorkArea area = workAreaService.get(workAreaId);
        protectLegacyWorkAreas(area, relativePath);
        return adapt(explorer.deletePreflight(root(area), path(relativePath), adapt(step)));
    }

    @Transactional
    public DeleteResult delete(String workAreaId, String relativePath, DeleteStep step) {
        WorkArea area = workAreaService.get(workAreaId);
        protectLegacyWorkAreas(area, relativePath);
        WorkspaceExplorerService.DeleteResult deleted = explorer.delete(root(area), path(relativePath), adapt(step));
        String source = rootRelative(area, deleted.path());
        metadataDelete(area, source);
        log(area, deletePreflightType(step, relativePath, area), source, null, "SUCCEEDED",
            "{\"deletedCount\":" + deleted.deletedCount() + "}");
        return new DeleteResult(deleted.path(), deleted.deletedCount());
    }

    private WorkspaceFileActionType deletePreflightType(DeleteStep step, String relativePath, WorkArea area) {
        // Confirmation step is an identity-safe indication of directory versus file.
        return step == DeleteStep.DIRECTORY_RECURSIVE_CONFIRM
            ? WorkspaceFileActionType.DELETE_DIRECTORY : WorkspaceFileActionType.DELETE_FILE;
    }

    @Transactional
    public DeleteResult deleteRecursive(String workAreaId, String relativePath, String confirmation) {
        WorkArea area = workAreaService.get(workAreaId);
        String normalized = path(relativePath).value();
        protectLegacyWorkAreas(area, normalized);
        String expected = ".".equals(normalized)
            ? root(area).getFileName().toString()
            : normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!expected.equals(confirmation)) {
            throw new IllegalArgumentException("typed confirmation must match path name");
        }
        DeletePreflight preflight = deletePreflight(workAreaId, normalized, DeleteStep.INTENT);
        return delete(workAreaId, normalized, preflight.requiredStep());
    }

    public WorkArea mark(String workAreaId, String relativePath, String displayName) {
        WorkArea area = workAreaService.get(workAreaId);
        return workAreaService.markDirectory(area.ownerType(), area.ownerId(), join(area.areaRelativePath(), path(relativePath).value()), displayName);
    }

    public WorkspaceFileLabelAssignment addLabel(String workAreaId, String relativePath, String labelSlug) {
        WorkArea area = workAreaService.get(workAreaId);
        WorkspaceExplorerService.Entry entry = explorer.inspect(root(area), path(relativePath));
        requireMetadata();
        return metadataService.addLabel(area, rootRelative(area, entry.path()), labelSlug);
    }

    public WorkspaceFileLabel ensureTag(String labelSlug, String displayName) {
        requireMetadata();
        return metadataService.ensureTag(labelSlug, displayName);
    }

    public int removeLabel(String workAreaId, String relativePath, String labelSlug) {
        WorkArea area = workAreaService.get(workAreaId);
        WorkspaceExplorerService.Entry entry = explorer.inspect(root(area), path(relativePath));
        requireMetadata();
        return metadataService.removeLabel(area, rootRelative(area, entry.path()), labelSlug);
    }

    public List<WorkspaceFileLabelAssignment> labels(String workAreaId, String relativePath) {
        WorkArea area = workAreaService.get(workAreaId);
        WorkspaceExplorerService.Entry entry = explorer.inspect(root(area), path(relativePath));
        requireMetadata();
        return metadataService.labelsForPath(area.workspaceId(), rootRelative(area, entry.path()));
    }

    public List<WorkspaceFileActionRecord> recentActions(String workAreaId, int limit) {
        WorkArea area = workAreaService.get(workAreaId);
        if (actionLogRepository == null) throw new IllegalStateException("workspace file action log repository is not available");
        return actionLogRepository.recentForWorkspace(area.workspaceId(), Math.max(1, limit));
    }

    public Entry inspect(String workAreaId, String relativePath) {
        WorkArea area = workAreaService.get(workAreaId);
        return adapt(area, explorer.inspect(root(area), path(relativePath)));
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return explorer.normalizeRelativePath(value);
    }

    private Path root(WorkArea area) {
        return workAreaService.resolve(area);
    }

    private Entry adapt(WorkArea area, WorkspaceExplorerService.Entry entry) {
        List<WorkspaceFileLabel> tags = metadataService == null ? List.of()
            : metadataService.labelsForPath(area.workspaceId(), rootRelative(area, entry.path())).stream()
                .map(WorkspaceFileLabelAssignment::label).toList();
        return new Entry(entry.name(), entry.path(), entry.directory(), entry.regularFile(), entry.size(), entry.createdAt(),
            entry.modifiedAt(), entry.fileType(), entry.sizeLabel(), ViewerKind.valueOf(entry.viewerKind().name()), tags,
            entry.canView(), entry.canRename(), entry.canDelete(), entry.canCopy(), entry.canMove(), entry.canTag());
    }

    private FilePreview adapt(WorkspaceExplorerService.FilePreview preview) {
        return new FilePreview(preview.path(), preview.size(), preview.text(), preview.content(), preview.requiresWarning(), preview.kind());
    }

    private DeletePreflight adapt(WorkspaceExplorerService.DeletePreflight preflight) {
        return new DeletePreflight(preflight.path(), preflight.directory(), preflight.candidateCount(),
            DeleteStep.valueOf(preflight.requiredStep().name()), preflight.executable());
    }

    private WorkspaceExplorerService.DeleteStep adapt(DeleteStep step) {
        return step == null ? null : WorkspaceExplorerService.DeleteStep.valueOf(step.name());
    }

    private void protectLegacyWorkAreas(WorkArea area, String relativePath) {
        WorkspaceExplorerService.RelativePath normalized = path(relativePath);
        if (".".equals(normalized.value())) {
            throw new IllegalArgumentException("Work Area root is protected");
        }
        Path target = root(area).resolve(normalized.value()).normalize();
        for (WorkArea candidate : workAreaService.list(area.ownerType(), area.ownerId(), false)) {
            Path candidateRoot = workAreaService.resolve(candidate);
            if (target.equals(candidateRoot) || candidateRoot.startsWith(target)) {
                if (candidate.system() || candidate.home()) throw new IllegalArgumentException("Home/system Work Areas are protected");
                if (!candidate.id().equals(area.id())) throw new IllegalArgumentException("active Work Area paths are protected");
                if (workAreaService.hasActiveWorkReferences(candidate.id())) {
                    throw new IllegalArgumentException("Work Area is active in queued or running work: " + candidate.id());
                }
            }
        }
    }

    private String rootRelative(WorkArea area, String withinArea) {
        if (!StringUtils.hasText(area.areaRelativePath()) || ".".equals(area.areaRelativePath())) return withinArea;
        if (!StringUtils.hasText(withinArea) || ".".equals(withinArea)) return area.areaRelativePath();
        return join(area.areaRelativePath(), withinArea);
    }

    private void requireMetadata() {
        if (metadataService == null) throw new IllegalStateException("workspace file metadata service is not available");
    }

    private void metadataMove(WorkArea area, String source, String target) { if (metadataService != null) metadataService.onMove(area, source, target); }
    private void metadataCopy(WorkArea area, String source, String target) { if (metadataService != null) metadataService.onCopy(area, source, target); }
    private void metadataDelete(WorkArea area, String source) { if (metadataService != null) metadataService.onDelete(area, source); }

    private void log(WorkArea area, WorkspaceFileActionType type, String source, String target, String result, String payload) {
        if (actionLogRepository != null) actionLogRepository.record(area, type, source, target, result, payload);
    }

    private static String join(String base, String relative) {
        if (!StringUtils.hasText(relative) || ".".equals(relative)) return base;
        return base + "/" + relative;
    }

    public record DirectoryListing(WorkArea workArea, String path, List<Entry> entries) { }
    public record Entry(String name, String path, boolean directory, boolean regularFile, long size, Instant createdAt,
                        Instant modifiedAt, String fileType, String sizeLabel, ViewerKind viewerKind,
                        List<WorkspaceFileLabel> tags, boolean canView, boolean canRename, boolean canDelete,
                        boolean canCopy, boolean canMove, boolean canTag) {
        public Entry { tags = tags == null ? List.of() : List.copyOf(tags); }
        public Entry(String name, String path, boolean directory, boolean regularFile, long size, Instant modifiedAt) {
            this(name, path, directory, regularFile, size, null, modifiedAt, directory ? "Folder" : "File",
                directory ? "-" : size + " B", ViewerKind.UNSUPPORTED, List.of(), regularFile, true, true, true, true, true);
        }
        public long sizeBytes() { return size; }
    }
    public enum ViewerKind { TEXT, MARKDOWN, IMAGE, UNSUPPORTED, TOO_LARGE, INVALID_UTF8 }
    public record FilePreview(String path, long size, boolean text, String content, boolean requiresWarning, String kind) {
        public FilePreview(String path, long size, boolean text, String content) {
            this(path, size, text, content, false, text ? "text" : "unsupported");
        }
    }
    public record DownloadContent(String fileName, byte[] bytes) {
        public DownloadContent { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record DeleteResult(String path, int deletedCount) { }
    public record DeletePreflight(String path, boolean directory, int candidateCount, DeleteStep requiredStep, boolean executable) { }
    public enum DeleteStep { INTENT, FILE_CONFIRM, DIRECTORY_RECURSIVE_CONFIRM }
}
