package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Descriptor-first owner of confined workspace file exploration and mutation.
 *
 * <p>Phase 03 routes every browse and mutation operation through
 * {@link ReservedWorkspacePathPolicy} to protect reserved trees (outputs,
 * tasks, .skills, staging). Ordinary mutation is denied inside any reserved
 * subtree. Hidden content (.skills, publication staging) is invisible to
 * browse operations.
 */
@Service
public class WorkspaceExplorerService {
    static final long NORMAL_TEXT_BYTES = 10L * 1024 * 1024;
    static final long NORMAL_MARKDOWN_BYTES = 5L * 1024 * 1024;
    static final long HARD_EDIT_BYTES = 25L * 1024 * 1024;
    private static final int ROW_UTF8_PROBE_BYTES = 64 * 1024;

    private final WorkspaceFileMetadataService metadataService;
    private final WorkspaceFileActionLogRepository actionLogRepository;
    private final WorkspaceDirectoryService directoryService;
    private final ConfinedPathMutationService mutations;
    private final ThreadLocal<WorkspaceKind> heldMutationKind = new ThreadLocal<>();

    public WorkspaceExplorerService(
        WorkspaceFileMetadataService metadataService,
        WorkspaceFileActionLogRepository actionLogRepository
    ) {
        this(metadataService, actionLogRepository, null, new ConfinedPathMutationService());
    }

    @Autowired
    public WorkspaceExplorerService(
        WorkspaceFileMetadataService metadataService,
        WorkspaceFileActionLogRepository actionLogRepository,
        WorkspaceDirectoryService directoryService
    ) {
        this(metadataService, actionLogRepository, directoryService, new ConfinedPathMutationService());
    }

    WorkspaceExplorerService(
        WorkspaceFileMetadataService metadataService,
        WorkspaceFileActionLogRepository actionLogRepository,
        WorkspaceDirectoryService directoryService,
        ConfinedPathMutationService mutations
    ) {
        this.metadataService = metadataService;
        this.actionLogRepository = actionLogRepository;
        this.directoryService = directoryService;
        this.mutations = mutations.withAdditionalHook((primitive, root, relative) ->
            reauthorizeHeldMutation(relative));
    }

    private static final AgentWorkspaceArea[] AGENT_AREAS = AgentWorkspaceArea.values();

    /** A normalized workspace-relative path kept distinct from workspace identity. */
    public record RelativePath(String value) {
        public RelativePath {
            if (!StringUtils.hasText(value)) {
                value = ".";
            }
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public RelativePath normalizeRelativePath(String value) {
        String text = StringUtils.hasText(value) ? value.trim().replace('\\', '/') : ".";
        if (text.indexOf('\0') >= 0 || text.matches("^[A-Za-z]:/.*") || text.startsWith("/")) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "absolute paths are not allowed");
        }
        for (String segment : text.split("/", -1)) {
            if ("..".equals(segment)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path escapes workspace");
            }
        }
        try {
            Path path = Path.of(text).normalize();
            if (path.isAbsolute()) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "absolute paths are not allowed");
            }
            String normalized = path.toString().replace('\\', '/');
            return new RelativePath(normalized.isBlank() ? "." : normalized);
        } catch (InvalidPathException exception) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is invalid", exception);
        }
    }

    /* ── Descriptor-first public API ── */

    public DirectoryListing list(WorkspaceDescriptor descriptor, RelativePath path) {
        if (!Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS)) {
            if (".".equals(path.value())) {
                if (descriptor.kind() == WorkspaceKind.AGENT) {
                    return new DirectoryListing(".", syntheticAgentAreaEntries());
                }
                return new DirectoryListing(".", List.of());
            }
            // For AGENT canonical areas that don't exist yet, show empty
            if (descriptor.kind() == WorkspaceKind.AGENT && isAgentCanonicalArea(path.value())) {
                return new DirectoryListing(path.value(), List.of());
            }
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "path does not exist: " + path.value());
        }
        return list(descriptor, descriptor.root(), path);
    }

    public FilePreview preview(WorkspaceDescriptor descriptor, RelativePath path) {
        requireBrowsable(descriptor.kind(), path.value());
        return preview(descriptor.root(), path);
    }

    /**
     * Read-only listing rooted at an exact, generation-verified project materialization.
     * The trusted location is resolved by the materialization authority; this service
     * neither derives identity from its path nor creates missing content.
     */
    public DirectoryListing list(MaterializationLocation location, RelativePath path) {
        Objects.requireNonNull(location, "location");
        return listRoot(location.confinedPath(), path);
    }

    /** Read-only preview confined to an exact materialization root. */
    public FilePreview preview(MaterializationLocation location, RelativePath path) {
        Objects.requireNonNull(location, "location");
        return previewRoot(location.confinedPath(), path);
    }

    /** Read-only inspection confined to an exact materialization root. */
    public Entry inspect(MaterializationLocation location, RelativePath path) {
        Objects.requireNonNull(location, "location");
        return inspectRoot(location.confinedPath(), path);
    }

    public DownloadContent download(WorkspaceDescriptor descriptor, RelativePath path) {
        requireBrowsable(descriptor.kind(), path.value());
        return downloadContent(descriptor, descriptor.root(), path);
    }

    public FilePreview saveText(WorkspaceDescriptor descriptor, RelativePath path, String content) {
        return withHeldMutationKind(descriptor, () -> {
            requireOrdinaryMutationAllowed(descriptor.kind(), path.value());
            ensureDescriptorRootForCreate(descriptor);
            ensurePersonalAreaProvisioned(descriptor, path.value());
            FilePreview saved = saveText(descriptor, descriptor.root(), path, content);
            logAction(descriptor, "markdown".equals(saved.kind()) ? WorkspaceFileActionType.SAVE_MARKDOWN : WorkspaceFileActionType.SAVE_TEXT,
                path.value(), null, "SUCCEEDED", "{\"bytes\":" + saved.size() + "}");
            return saved;
        });
    }

    public Entry createDirectory(WorkspaceDescriptor descriptor, RelativePath path) {
        return withHeldMutationKind(descriptor, () -> {
            requireOrdinaryMutationAllowed(descriptor.kind(), path.value());
            ensureDescriptorRootForCreate(descriptor);
            ensurePersonalAreaProvisioned(descriptor, path.value());
            requireCreatableParent(descriptor, path);
            Entry created = createDirectoryRoot(descriptor.root(), path);
            logAction(descriptor, WorkspaceFileActionType.CREATE_FOLDER, created.path(), null, "SUCCEEDED", "{}");
            return decorate(descriptor, created);
        });
    }

    public Entry createTextFile(WorkspaceDescriptor descriptor, RelativePath parent, String fileName) {
        return withHeldMutationKind(descriptor, () -> {
            String childPath = childValue(parent, fileName.trim());
            requireFileName(fileName, ".txt", "text");
            requireOrdinaryMutationAllowed(descriptor.kind(), childPath);
            ensureDescriptorRootForCreate(descriptor);
            ensurePersonalAreaProvisioned(descriptor, childPath);
            requireCreatableParent(descriptor, new RelativePath(childPath));
            Entry created = createTextFileRoot(descriptor.root(), parent, fileName);
            logAction(descriptor, WorkspaceFileActionType.CREATE_TEXT_FILE, created.path(), null, "SUCCEEDED", "{}");
            return decorate(descriptor, created);
        });
    }

    public Entry createMarkdownFile(WorkspaceDescriptor descriptor, RelativePath parent, String fileName) {
        return withHeldMutationKind(descriptor, () -> {
            String childPath = childValue(parent, fileName.trim());
            requireFileName(fileName, ".md", "markdown");
            requireOrdinaryMutationAllowed(descriptor.kind(), childPath);
            ensureDescriptorRootForCreate(descriptor);
            ensurePersonalAreaProvisioned(descriptor, childPath);
            requireCreatableParent(descriptor, new RelativePath(childPath));
            Entry created = createMarkdownFileRoot(descriptor.root(), parent, fileName);
            logAction(descriptor, WorkspaceFileActionType.CREATE_MARKDOWN_FILE, created.path(), null, "SUCCEEDED", "{}");
            return decorate(descriptor, created);
        });
    }

    @Transactional
    public Entry rename(WorkspaceDescriptor descriptor, RelativePath source, String newName) {
        return withHeldMutationKind(descriptor, () -> {
            String targetRel = computeRenameTarget(source.value(), newName);
            reauthorizeSourceAndTarget(descriptor, source.value(), targetRel);
            Entry renamed = renameRoot(descriptor.root(), source, newName);
            metadataService.onMove(descriptor, source.value(), renamed.path());
            logAction(descriptor, WorkspaceFileActionType.RENAME, source.value(), renamed.path(), "SUCCEEDED", "{}");
            return decorate(descriptor, renamed);
        });
    }

    @Transactional
    public Entry move(WorkspaceDescriptor descriptor, RelativePath source, RelativePath destination, String newName) {
        return withHeldMutationKind(descriptor, () -> {
            String targetName = StringUtils.hasText(newName) ? newName.trim() : Path.of(source.value()).getFileName().toString();
            String destPath = ".".equals(destination.value()) ? targetName : destination.value() + "/" + targetName;
            reauthorizeSourceAndTarget(descriptor, source.value(), destPath);
            Entry moved = moveRoot(descriptor.root(), source, destination, newName);
            metadataService.onMove(descriptor, source.value(), moved.path());
            logAction(descriptor, WorkspaceFileActionType.MOVE, source.value(), moved.path(), "SUCCEEDED", "{}");
            return decorate(descriptor, moved);
        });
    }

    @Transactional
    public Entry copy(WorkspaceDescriptor descriptor, RelativePath source, RelativePath destination, String newName) {
        return withHeldMutationKind(descriptor, () -> {
            if (".".equals(source.value())) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "workspace root cannot be copied");
            }
            String targetName = StringUtils.hasText(newName) ? newName.trim() : Path.of(source.value()).getFileName().toString();
            String destPath = ".".equals(destination.value()) ? targetName : destination.value() + "/" + targetName;
            reauthorizeSourceAndTarget(descriptor, source.value(), destPath);
            Entry copied = copyRoot(descriptor.root(), source, destination, newName);
            metadataService.onCopy(descriptor, source.value(), copied.path());
            logAction(descriptor, WorkspaceFileActionType.COPY, source.value(), copied.path(), "SUCCEEDED", "{}");
            return decorate(descriptor, copied);
        });
    }

    public DeletePreflight deletePreflight(WorkspaceDescriptor descriptor, RelativePath path, DeleteStep step) {
        requireOrdinaryMutationAllowed(descriptor.kind(), path.value());
        return deletePreflightRoot(descriptor.root(), path, step);
    }

    @Transactional
    public DeleteResult delete(WorkspaceDescriptor descriptor, RelativePath path, DeleteStep step) {
        return withHeldMutationKind(descriptor, () -> {
            requireOrdinaryMutationAllowed(descriptor.kind(), path.value());
            DeletePreflight preflight = deletePreflightRoot(descriptor.root(), path, step);
            if (!preflight.executable()) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                    "delete confirmation step is required: " + preflight.requiredStep());
            }
            DeleteResult result = deleteRoot(descriptor.root(), path, step);
            metadataService.onDelete(descriptor, path.value());
            WorkspaceFileActionType actionType = step == DeleteStep.DIRECTORY_RECURSIVE_CONFIRM
                ? WorkspaceFileActionType.DELETE_DIRECTORY : WorkspaceFileActionType.DELETE_FILE;
            logAction(descriptor, actionType, path.value(), null, "SUCCEEDED",
                "{\"deletedCount\":" + result.deletedCount() + "}");
            return result;
        });
    }

    public Entry inspect(WorkspaceDescriptor descriptor, RelativePath path) {
        requireBrowsable(descriptor.kind(), path.value());
        if (!Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS) && ".".equals(path.value())) {
            return virtualRootEntry();
        }
        return decorate(descriptor, inspectRoot(descriptor.root(), path));
    }

    /* ── Labels and tags ── */

    public WorkspaceFileLabelAssignment addLabel(WorkspaceDescriptor descriptor, String fileRelativePath, String labelSlug) {
        RelativePath path = normalizeRelativePath(fileRelativePath);
        inspect(descriptor, path);
        return metadataService.addLabel(descriptor, path.value(), labelSlug);
    }

    public WorkspaceFileLabel ensureTag(String labelSlug, String displayName) {
        return metadataService.ensureTag(labelSlug, displayName);
    }

    public int removeLabel(WorkspaceDescriptor descriptor, String fileRelativePath, String labelSlug) {
        RelativePath path = normalizeRelativePath(fileRelativePath);
        inspect(descriptor, path);
        return metadataService.removeLabel(descriptor, path.value(), labelSlug);
    }

    public List<WorkspaceFileLabelAssignment> labels(WorkspaceDescriptor descriptor, String fileRelativePath) {
        RelativePath path = normalizeRelativePath(fileRelativePath);
        inspect(descriptor, path);
        return metadataService.labelsForPath(descriptor, path.value());
    }

    public List<WorkspaceFileActionRecord> recentActions(WorkspaceDescriptor descriptor, int limit) {
        return actionLogRepository.recentForWorkspace(descriptor.identity(), Math.max(1, limit));
    }

    /* ── Package-private root bridges (transitional; not public API) ── */

    DirectoryListing list(Path root, RelativePath relativePath) {
        return listRoot(root, relativePath);
    }

    FilePreview preview(Path root, RelativePath relativePath) {
        return previewRoot(root, relativePath);
    }

    FilePreview saveText(Path root, RelativePath relativePath, String content) {
        return saveTextRoot(root, relativePath, content);
    }

    Entry createTextFile(Path root, RelativePath parent, String fileName) {
        return createTextFileRoot(root, parent, fileName);
    }

    Entry createDirectory(Path root, RelativePath relativePath) {
        return createDirectoryRoot(root, relativePath);
    }

    /* ── Internal descriptor-based bridges ── */

    DirectoryListing list(WorkspaceDescriptor descriptor, Path root, RelativePath relativePath) {
        requireBrowsable(descriptor.kind(), relativePath.value());
        Path dir = resolveExisting(root, relativePath);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is not a directory");
        }
        try (var stream = Files.list(dir)) {
            List<Entry> physical = stream
                .filter(path -> isBrowsable(relative(root, path)))
                .map(path -> decorate(descriptor, entry(root, path)))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

            // Synthesize absent AGENT canonical area entries at root
            if (descriptor.kind() == WorkspaceKind.AGENT && ".".equals(relativePath.value())) {
                for (AgentWorkspaceArea area : AGENT_AREAS) {
                    if (!isAgentAreaBrowsable(area)) continue;
                    boolean present = physical.stream()
                        .anyMatch(e -> area.directoryName().equals(e.name()));
                    if (!present) {
                        physical.add(syntheticAreaEntry(area));
                    }
                }
            }

            physical.sort(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name));
            return new DirectoryListing(relative(root, dir), List.copyOf(physical));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to list directory", exception);
        }
    }

    DownloadContent downloadContent(WorkspaceDescriptor descriptor, Path root, RelativePath path) {
        return downloadContentRoot(root, path);
    }

    private DownloadContent downloadContentRoot(Path root, RelativePath path) {
        Path file = downloadRoot(root, path);
        try {
            long size = Files.size(file);
            if (size > NORMAL_TEXT_BYTES) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "file is too large to download");
            }
            return new DownloadContent(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to download file", exception);
        }
    }

    FilePreview saveText(WorkspaceDescriptor descriptor, Path root, RelativePath relativePath, String content) {
        return saveTextRoot(root, relativePath, content);
    }

    /* ── Internal filesystem operations ── */

    private DirectoryListing listRoot(Path root, RelativePath relativePath) {
        requireBrowsable(relativePath.value());
        Path dir = resolveExisting(root, relativePath);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is not a directory");
        }
        try (var stream = Files.list(dir)) {
            List<Entry> entries = stream
                .filter(path -> isBrowsable(relative(root, path)))
                .map(path -> entry(root, path))
                .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name))
                .toList();
            return new DirectoryListing(relative(root, dir), entries);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to list directory", exception);
        }
    }

    private FilePreview previewRoot(Path root, RelativePath relativePath) {
        requireBrowsable(relativePath.value());
        Path file = resolveExisting(root, relativePath);
        requireRegularFile(file);
        try {
            long size = Files.size(file);
            String relative = relative(root, file);
            if (isImagePath(file)) {
                return new FilePreview(relative, size, false, null, false, "image");
            }
            if (!isSafeTextPath(file)) {
                return new FilePreview(relative, size, false, null, false, "unsupported");
            }
            if (size > HARD_EDIT_BYTES) {
                return new FilePreview(relative, size, false, null, false, "too_large");
            }
            boolean warning = size > (isMarkdownPath(file) ? NORMAL_MARKDOWN_BYTES : NORMAL_TEXT_BYTES);
            String kind = isMarkdownPath(file) ? "markdown" : "text";
            if (warning) {
                return new FilePreview(relative, size, true, null, true, kind);
            }
            String text = readUtf8(file);
            return text == null
                ? new FilePreview(relative, size, false, null, false, "invalid_utf8")
                : new FilePreview(relative, size, true, stripBom(text), false, kind);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to preview file", exception);
        }
    }

    private Path downloadRoot(Path root, RelativePath relativePath) {
        requireBrowsable(relativePath.value());
        Path file = resolveExisting(root, relativePath);
        requireRegularFile(file);
        return file;
    }

    private FilePreview saveTextRoot(Path root, RelativePath relativePath, String content) {
        Path file = resolveExisting(root, relativePath);
        ensureMutable(root, file);
        if (!isSafeTextPath(file)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "file type is not safe for text editing");
        }
        String normalizedContent = stripBom(content == null ? "" : content);
        if (normalizedContent.getBytes(StandardCharsets.UTF_8).length > HARD_EDIT_BYTES) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "text file is too large");
        }
        try {
            requireRegularFile(file);
            if (readUtf8(file) == null) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "file is not valid UTF-8");
            }
            String lineEnding = dominantLineEnding(file);
            mutations.write(root, relativePath.value(),
                applyLineEnding(normalizedContent, lineEnding).getBytes(StandardCharsets.UTF_8),
                ConfinedPathMutationService.WriteMode.OVERWRITE, false);
            return preview(root, relativePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to save text file", exception);
        }
    }

    private Entry createDirectoryRoot(Path root, RelativePath relativePath) {
        requireOrdinaryMutationAllowed(relativePath.value());
        Path dir = resolveForWrite(root, relativePath);
        ensureMutable(root, dir);
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.CONFLICT, "target already exists");
            }
            mutations.createDirectory(root, relativePath.value());
            return entry(root, dir.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create directory", exception);
        }
    }

    private Entry createTextFileRoot(Path root, RelativePath parent, String fileName) {
        requireFileName(fileName, ".txt", "text");
        return createEmptyFile(root, parent, fileName.trim());
    }

    private Entry createMarkdownFileRoot(Path root, RelativePath parent, String fileName) {
        requireFileName(fileName, ".md", "markdown");
        return createEmptyFile(root, parent, fileName.trim());
    }

    private Entry renameRoot(Path root, RelativePath relativePath, String newName) {
        Path source = resolveExisting(root, relativePath);
        ensureMutable(root, source);
        requirePlainName(newName);
        Path target = source.getParent().resolve(newName.trim()).normalize();
        ensureConfined(root, target);
        ensureMutable(root, target);
        rejectCollision(target);
        try {
            rejectSymbolicTree(root, source);
            mutations.move(root, relative(root, source), relative(root, target));
            return entry(root, target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to rename path", exception);
        }
    }

    private Entry moveRoot(Path root, RelativePath sourcePath, RelativePath destinationPath, String newName) {
        Path source = resolveExisting(root, sourcePath);
        ensureMutable(root, source);
        Path destination = resolveExisting(root, destinationPath);
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "destination is not a directory");
        }
        String targetName = StringUtils.hasText(newName) ? newName.trim() : source.getFileName().toString();
        requirePlainName(targetName);
        Path target = destination.resolve(targetName).normalize();
        ensureConfined(root, target);
        ensureMutable(root, target);
        rejectCollision(target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "directory cannot be moved into itself or a descendant");
        }
        try {
            rejectSymbolicTree(root, source);
            mutations.move(root, relative(root, source), relative(root, target));
            return entry(root, target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to move path", exception);
        }
    }

    private Entry copyRoot(Path root, RelativePath sourcePath, RelativePath destinationPath, String newName) {
        Path source = resolveExisting(root, sourcePath);
        Path destination = resolveExisting(root, destinationPath);
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "destination is not a directory");
        }
        String targetName = StringUtils.hasText(newName) ? newName.trim() : source.getFileName().toString();
        requirePlainName(targetName);
        Path target = destination.resolve(targetName).normalize();
        ensureConfined(root, target);
        ensureMutable(root, target);
        rejectCollision(target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "directory cannot be copied into itself or a descendant");
        }
        try {
            rejectSymbolicTree(root, source);
            copyPath(root, source, target);
            return entry(root, target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to copy path", exception);
        }
    }

    private DeletePreflight deletePreflightRoot(Path root, RelativePath relativePath, DeleteStep step) {
        Path target = resolveExisting(root, relativePath);
        ensureMutable(root, target);
        try {
            List<Path> paths = validatedDeletePaths(root, target);
            boolean directory = Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS);
            DeleteStep required = directory ? DeleteStep.DIRECTORY_RECURSIVE_CONFIRM : DeleteStep.FILE_CONFIRM;
            return new DeletePreflight(relative(root, target), directory, paths.size(), required, step == required);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to preflight delete", exception);
        }
    }

    private DeleteResult deleteRoot(Path root, RelativePath relativePath, DeleteStep step) {
        Path target = resolveExisting(root, relativePath);
        try {
            List<Path> paths = validatedDeletePaths(root, target);
            for (Path path : paths) {
                mutations.delete(root, relative(root, path), Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
            }
            return new DeleteResult(relative(root, target), paths.size());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete path", exception);
        }
    }

    private Entry inspectRoot(Path root, RelativePath relativePath) {
        requireBrowsable(relativePath.value());
        return entry(root, resolveExisting(root, relativePath));
    }

    /* ── Private helpers ── */

    private Entry decorate(WorkspaceDescriptor descriptor, Entry entry) {
        List<WorkspaceFileLabel> tags = metadataService == null ? List.of()
            : metadataService.labelsForPath(descriptor, entry.path()).stream()
                .map(WorkspaceFileLabelAssignment::label).toList();
        boolean mutable = isMutableByOrdinaryRoute(descriptor.kind(), entry.path());
        return new Entry(entry.name(), entry.path(), entry.directory(), entry.regularFile(), entry.size(),
            entry.createdAt(), entry.modifiedAt(), entry.fileType(), entry.sizeLabel(), entry.viewerKind(),
            tags, entry.canView(), mutable, mutable, mutable, mutable, entry.canTag());
    }

    private void logAction(WorkspaceDescriptor descriptor, WorkspaceFileActionType type,
                           String source, String target, String result, String payload) {
        if (actionLogRepository != null) {
            actionLogRepository.record(descriptor, type, source, target, result, payload);
        }
    }

    private Entry virtualRootEntry() {
        return new Entry(".", ".", true, false, 0, null, null, "Folder", "-", ViewerKind.UNSUPPORTED,
            List.of(), false, false, false, false, false, true);
    }

    // ── AGENT synthetic-area support ──

    /** Check whether a workspace-relative path (single segment) names a canonical agent area. */
    private boolean isAgentCanonicalArea(String relativePath) {
        if (relativePath.indexOf('/') >= 0) return false;
        return AgentWorkspaceArea.fromDirectoryName(relativePath).isPresent();
    }

    /** Produce synthetic entries for every canonical agent area (used when root is absent). */
    private List<Entry> syntheticAgentAreaEntries() {
        List<Entry> entries = new ArrayList<>(AGENT_AREAS.length);
        for (AgentWorkspaceArea area : AGENT_AREAS) {
            // Skip areas that are not browsable (e.g. .skills)
            if (!isAgentAreaBrowsable(area)) continue;
            entries.add(syntheticAreaEntry(area));
        }
        return entries;
    }

    /** Check whether a canonical agent area should appear in listings. */
    private boolean isAgentAreaBrowsable(AgentWorkspaceArea area) {
        var c = ReservedWorkspacePathPolicy.classify(
                WorkspaceKind.AGENT, area.directoryName());
        return ReservedWorkspacePathPolicy.decide(c,
                ReservedWorkspacePathPolicy.Operation.BROWSE)
                == ReservedWorkspacePathPolicy.Decision.ALLOW;
    }

    /** Build a single synthetic (non-existent) directory entry for a canonical area. */
    private Entry syntheticAreaEntry(AgentWorkspaceArea area) {
        String name = area.directoryName();
        boolean personal = area.isPersonal();
        return new Entry(name, name, true, false, 0, null, null,
            "Folder", "-", ViewerKind.UNSUPPORTED,
            List.of(), false,
            false, // canRename
            false, // canDelete
            false, // canCopy
            false, // canMove
            false  // synthetic entries never expose metadata mutations
        );
    }

    /**
     * When writing to an AGENT personal descendant whose area root does not
     * yet exist, lazily provision only the canonical area chain.
     */
    private void ensurePersonalAreaProvisioned(WorkspaceDescriptor descriptor, String relativePath) {
        if (descriptor.kind() != WorkspaceKind.AGENT) return;
        if (!Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS)) return;
        String firstSegment = relativePath.split("/", -1)[0];
        var area = AgentWorkspaceArea.fromDirectoryName(firstSegment);
        if (area.isEmpty() || area.get().isReserved()) return;
        Path areaDir = descriptor.root().resolve(firstSegment);
        if (!Files.exists(areaDir, LinkOption.NOFOLLOW_LINKS)) {
            if (directoryService != null) {
                directoryService.provisionPersonalArea(descriptor, area.get());
            } else {
                try {
                    new ConfinedPathMutationService().createDirectory(descriptor.root(), firstSegment);
                } catch (IOException e) {
                    throw new IllegalStateException(
                        "Failed to provision personal area: " + firstSegment, e);
                }
            }
        }
    }

    private void requireCreatableParent(WorkspaceDescriptor descriptor, RelativePath target) {
        if (".".equals(target.value())) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "workspace root cannot be created");
        }
        Path parent = descriptor.root().resolve(target.value()).normalize().getParent();
        if (parent == null || !parent.startsWith(descriptor.root())) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path escapes workspace");
        }
        if (!Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS)) {
            if (!parent.equals(descriptor.root())) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "parent directory does not exist");
            }
            return;
        }
        try {
            rejectSymbolicPath(descriptor.root(), parent);
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "parent directory does not exist");
            }
            ensureConfined(descriptor.root(), parent.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "parent directory does not exist", exception);
        }
    }

    private String childValue(RelativePath parent, String name) {
        return ".".equals(parent.value()) ? name : parent.value() + "/" + name;
    }

    private void requireFileName(String fileName, String extension, String kind) {
        requirePlainName(fileName);
        if (!fileName.trim().toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                kind + " file name must end with " + extension);
        }
    }

    private void ensureDescriptorRootForCreate(WorkspaceDescriptor descriptor) {
        if (Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (descriptor.kind() == WorkspaceKind.USER) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "user workspace does not exist");
        }
        try {
            if (directoryService != null) {
                WorkspaceDescriptor provisioned = directoryService.resolveForWrite(
                    descriptor.kind(), descriptor.key());
                if (!provisioned.root().equals(descriptor.root())) {
                    throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                        "workspace descriptor root mismatch");
                }
                return;
            }
            Path parent = descriptor.root().getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND,
                    "workspace parent does not exist");
            }
            new ConfinedPathMutationService().createDirectory(parent, descriptor.root().getFileName().toString());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create workspace root", exception);
        }
    }

    private Entry createEmptyFile(Path root, RelativePath parentPath, String fileName) {
        Path parent = resolveExisting(root, parentPath);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "parent path is not a directory");
        }
        Path file = parent.resolve(fileName).normalize();
        ensureConfined(root, file);
        ensureMutable(root, file);
        rejectCollision(file);
        try {
            mutations.write(root, relative(root, file), new byte[0],
                ConfinedPathMutationService.WriteMode.CREATE_NEW, false);
            return entry(root, file.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create file", exception);
        }
    }

    private Entry entry(Path root, Path path) {
        try {
            boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
            boolean regular = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
            long size = regular ? Files.size(path) : 0;
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String relative = relative(root, path);
            ViewerKind viewer = viewerKind(path, directory, regular, size);
            boolean mutable = isMutableByOrdinaryRoute(relative);
            return new Entry(path.getFileName() == null ? "." : path.getFileName().toString(), relative, directory, regular,
                size, createdAt(attributes.creationTime()), attributes.lastModifiedTime().toInstant(),
                fileType(path, directory, regular), sizeLabel(size, directory), viewer, List.of(),
                regular && (viewer == ViewerKind.TEXT || viewer == ViewerKind.MARKDOWN || viewer == ViewerKind.IMAGE),
                mutable, mutable, mutable, mutable, true);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to inspect path", exception);
        }
    }

    private Path resolveExisting(Path root, RelativePath relativePath) {
        Path resolved = resolve(root, relativePath);
        try {
            rejectSymbolicPath(root, resolved);
            Path real = resolved.toRealPath(LinkOption.NOFOLLOW_LINKS);
            ensureConfined(root, real);
            return real;
        } catch (IOException exception) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND,
                "path does not exist: " + relativePath.value(), exception);
        }
    }

    private Path resolveForWrite(Path root, RelativePath relativePath) {
        Path resolved = resolve(root, relativePath);
        Path parent = resolved.getParent() == null ? root : resolved.getParent();
        try {
            ensureWritableParent(root, parent);
            ensureConfined(root, parent.toRealPath(LinkOption.NOFOLLOW_LINKS));
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "symbolic links are not editable");
            }
            return resolved;
        } catch (IOException exception) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                "path cannot be created: " + relativePath.value(), exception);
        }
    }

    private Path resolve(Path root, RelativePath relativePath) {
        Path resolved = root.resolve(relativePath.value()).normalize();
        ensureConfined(root, resolved);
        return resolved;
    }

    private void ensureMutable(Path root, Path target) {
        ensureConfined(root, target);
        String relative = relative(root, target);
        if (!isMutableByOrdinaryRoute(relative)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                    "workspace root, outputs, task structure, skills, and staging are protected");
        }
    }

    // ── Phase 03 policy-based protection ──

    /**
     * Require that the path is classifiable for ordinary browse without
     * accessing hidden content. Hidden: {@code .skills}, {@code staging},
     * control markers.
     */
    private void requireBrowsable(String relativePath) {
        requireBrowsable(null, relativePath);
    }

    /** Kind-aware browse authorization. */
    private void requireBrowsable(WorkspaceKind kind, String relativePath) {
        ReservedWorkspacePathPolicy.Classification c;
        try {
            c = kind != null
                ? ReservedWorkspacePathPolicy.classify(kind, relativePath)
                : ReservedWorkspacePathPolicy.classify(relativePath);
        } catch (IllegalArgumentException e) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                    "path is invalid", e);
        }
        ReservedWorkspacePathPolicy.Decision d = ReservedWorkspacePathPolicy.decide(
                c, ReservedWorkspacePathPolicy.Operation.BROWSE);
        if (d != ReservedWorkspacePathPolicy.Decision.ALLOW) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND,
                    "path does not exist: " + relativePath);
        }
    }

    /**
     * Require that the path is mutable by ordinary explorer routes.
     * Ordinary mutation is allowed only on {@code ORDINARY_CONTENT}
     * or {@code AGENT_PERSONAL_DESCENDANT}.
     */
    private void requireOrdinaryMutationAllowed(String relativePath) {
        requireOrdinaryMutationAllowed(null, relativePath);
    }

    private void requireOrdinaryMutationAllowed(WorkspaceKind kind, String relativePath) {
        ReservedWorkspacePathPolicy.Classification c;
        try {
            c = kind != null
                ? ReservedWorkspacePathPolicy.classify(kind, relativePath)
                : ReservedWorkspacePathPolicy.classify(relativePath);
        } catch (IllegalArgumentException e) {
            String message = relativePath.contains("..") ? "path escapes workspace" : "path is protected";
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, message, e);
        }
        ReservedWorkspacePathPolicy.Decision d = ReservedWorkspacePathPolicy.decide(
                c, ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION);
        if (d != ReservedWorkspacePathPolicy.Decision.ALLOW) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                    protectedTreeMessage(c));
        }
    }

    /**
     * Install the descriptor kind for one operation and remove it on every
     * exit path. The body receives the held kind; validation helpers are pure.
     */
    private <T> T withHeldMutationKind(WorkspaceDescriptor descriptor, Supplier<T> body) {
        WorkspaceKind kind = Objects.requireNonNull(descriptor, "descriptor").kind();
        Objects.requireNonNull(body, "body");
        heldMutationKind.set(Objects.requireNonNull(kind, "workspace kind"));
        try { return body.get(); }
        finally { heldMutationKind.remove(); }
    }

    /** Reauthorize the exact held relative target immediately before its primitive. */
    private void reauthorizeHeldMutation(String relativePath) {
        WorkspaceKind kind = heldMutationKind.get();
        if (kind == null) {
            return; // Package-private root bridges are not descriptor production routes.
        }
        requireOrdinaryMutationAllowed(kind, relativePath);
    }

    /** Compute the target relative path for a rename (same parent, new leaf). */
    private String computeRenameTarget(String sourceRel, String newName) {
        int lastSep = sourceRel.lastIndexOf('/');
        if (lastSep < 0) return newName;
        return sourceRel.substring(0, lastSep + 1) + newName;
    }

    /** Reauthorize both source and destination for moves, copies, renames. */
    private void reauthorizeSourceAndTarget(WorkspaceDescriptor descriptor,
                                            String sourceRel, String targetRel) {
        WorkspaceKind kind = descriptor.kind();
        // Source: check existent path is mutable
        if (".".equals(sourceRel)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                "workspace root cannot be moved or copied");
        }
        try {
            var srcClass = ReservedWorkspacePathPolicy.classify(kind, sourceRel);
            var dstClass = ReservedWorkspacePathPolicy.classify(kind, targetRel);
            if (ReservedWorkspacePathPolicy.decide(srcClass,
                    ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION)
                    != ReservedWorkspacePathPolicy.Decision.ALLOW
                    || ReservedWorkspacePathPolicy.decide(dstClass,
                    ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION)
                    != ReservedWorkspacePathPolicy.Decision.ALLOW) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                    "source or destination path is protected");
            }
        } catch (IllegalArgumentException exception) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID,
                "source or destination path is protected", exception);
        }
    }

    /**
     * Check whether browse should be permitted for a relative path.
     * Hidden trees (skills, staging) are denied. Unsafe paths are denied.
     */
    private boolean isBrowsable(String relative) {
        if (".".equals(relative)) return true;
        try {
            ReservedWorkspacePathPolicy.Classification c =
                    ReservedWorkspacePathPolicy.classify(relative);
            return ReservedWorkspacePathPolicy.decide(c,
                    ReservedWorkspacePathPolicy.Operation.BROWSE)
                    == ReservedWorkspacePathPolicy.Decision.ALLOW;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Check whether an ordinary mutation is allowed on a relative path. */
    private boolean isMutableByOrdinaryRoute(String relative) {
        return isMutableByOrdinaryRoute(null, relative);
    }

    private boolean isMutableByOrdinaryRoute(WorkspaceKind kind, String relative) {
        try {
            ReservedWorkspacePathPolicy.Classification c = kind == null
                ? ReservedWorkspacePathPolicy.classify(relative)
                : ReservedWorkspacePathPolicy.classify(kind, relative);
            return ReservedWorkspacePathPolicy.decide(c,
                    ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION)
                    == ReservedWorkspacePathPolicy.Decision.ALLOW;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String protectedTreeMessage(
            ReservedWorkspacePathPolicy.Classification classification) {
        return switch (classification) {
            case WORKSPACE_OUTPUTS -> "outputs are protected";
            case TASKS_ROOT, TASK_KEY_ROOT, RUN_ROOT, RUN_WORKING_CONTENT,
                 RUN_OUTPUTS -> "task structure is protected";
            case PUBLICATION_STAGING -> "publication staging is protected";
            case SKILLS -> "skills are protected";
            default -> "path is protected";
        };
    }

    private void ensureConfined(Path root, Path path) {
        if (!path.normalize().startsWith(root.normalize())) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path escapes workspace");
        }
    }

    private void rejectCollision(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.CONFLICT, "target already exists");
        }
    }

    private void ensureWritableParent(Path root, Path parent) throws IOException {
        ensureConfined(root, parent);
        rejectSymbolicPath(root, parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "parent directory does not exist");
        }
    }

    private void copyPath(Path root, Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            mutations.write(root, relative(root, target), mutations.read(root, relative(root, source)),
                ConfinedPathMutationService.WriteMode.CREATE_NEW, false);
            return;
        }
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path copyTarget = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    mutations.createDirectory(root, relative(root, copyTarget));
                } else {
                    mutations.write(root, relative(root, copyTarget),
                        mutations.read(root, relative(root, path)),
                        ConfinedPathMutationService.WriteMode.CREATE_NEW, false);
                }
            }
        }
    }

    private List<Path> validatedDeletePaths(Path root, Path target) throws IOException {
        try (var walk = Files.walk(target)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                ensureConfined(root, path.toRealPath(LinkOption.NOFOLLOW_LINKS));
                if (Files.isSymbolicLink(path)) throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "symbolic links are not deleted");
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) mutations.requireSingleLink(path);
            }
            return paths;
        }
    }

    private void rejectSymbolicTree(Path root, Path source) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                ensureConfined(root, path.toRealPath(LinkOption.NOFOLLOW_LINKS));
                if (Files.isSymbolicLink(path)) throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "symbolic links are not allowed");
            }
        }
    }

    private void rejectSymbolicPath(Path root, Path path) throws IOException {
        ensureConfined(root, path);
        Path current = root;
        for (Path segment : root.relativize(path.normalize())) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "symbolic links are not allowed");
            }
        }
    }

    private boolean isSafeTextPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".json") || name.endsWith(".yaml")
            || name.endsWith(".yml") || name.endsWith(".csv") || name.endsWith(".log") || name.endsWith(".xml")
            || name.endsWith(".html") || name.endsWith(".css") || name.endsWith(".js") || !name.contains(".");
    }

    private boolean isMarkdownPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") || name.endsWith(".markdown");
    }

    private boolean isImagePath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".webp");
    }

    private void requirePlainName(String value) {
        if (!StringUtils.hasText(value) || value.contains("/") || value.contains("\\") || value.equals(".") || value.equals("..")) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "newName must be a plain path segment");
        }
    }

    private void requireRegularFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is not a regular file");
    }

    private String readUtf8(Path file) throws IOException {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private boolean appearsUtf8(Path path) throws IOException {
        int limit = (int) Math.min(Files.size(path), ROW_UTF8_PROBE_BYTES);
        if (limit <= 0) return true;
        byte[] bytes = new byte[limit];
        try (var input = Files.newInputStream(path)) {
            int read = input.read(bytes);
            if (read <= 0) return true;
            int decodeLength = read == ROW_UTF8_PROBE_BYTES && read > 4 ? read - 4 : read;
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes, 0, decodeLength));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private String stripBom(String content) {
        return content != null && !content.isEmpty() && content.charAt(0) == '\uFEFF' ? content.substring(1) : content;
    }

    private String dominantLineEnding(Path file) throws IOException {
        String text = readUtf8(file);
        if (text == null) throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "file is not valid UTF-8");
        int crlf = text.split("\\r\\n", -1).length - 1;
        int lf = text.split("(?<!\\r)\\n", -1).length - 1;
        return crlf > lf ? "\r\n" : "\n";
    }

    private String applyLineEnding(String content, String lineEnding) {
        return "\n".equals(lineEnding) ? content : content.replace("\r\n", "\n").replace("\r", "\n").replace("\n", lineEnding);
    }

    private ViewerKind viewerKind(Path path, boolean directory, boolean regular, long size) throws IOException {
        if (directory || !regular) return ViewerKind.UNSUPPORTED;
        if (isImagePath(path)) return ViewerKind.IMAGE;
        if (!isSafeTextPath(path)) return ViewerKind.UNSUPPORTED;
        if (size > HARD_EDIT_BYTES) return ViewerKind.TOO_LARGE;
        if (!appearsUtf8(path)) return ViewerKind.INVALID_UTF8;
        return isMarkdownPath(path) ? ViewerKind.MARKDOWN : ViewerKind.TEXT;
    }

    private String fileType(Path path, boolean directory, boolean regular) {
        if (directory) return "Folder";
        if (!regular) return "Unknown";
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isMarkdownPath(path)) return "Markdown";
        if (isImagePath(path)) return "Image";
        if (name.endsWith(".txt") || name.endsWith(".log") || !name.contains(".")) return "Text";
        if (name.endsWith(".json")) return "JSON";
        if (name.endsWith(".yaml") || name.endsWith(".yml")) return "YAML";
        if (name.endsWith(".csv")) return "CSV";
        if (name.endsWith(".xml")) return "XML";
        if (name.endsWith(".html")) return "HTML";
        if (name.endsWith(".css")) return "CSS";
        if (name.endsWith(".js")) return "JavaScript";
        return "Binary";
    }

    private String sizeLabel(long size, boolean directory) {
        if (directory) return "-";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
        return String.format(Locale.ROOT, "%.1f GB", size / (1024.0 * 1024.0 * 1024.0));
    }

    private Instant createdAt(FileTime time) {
        return time == null || time.toMillis() <= 0 ? null : time.toInstant();
    }

    String relative(Path root, Path path) {
        String value = root.relativize(path.normalize()).toString().replace('\\', '/');
        return value.isBlank() ? "." : value;
    }

    public record DirectoryListing(String path, List<Entry> entries) {
        public DirectoryListing { entries = List.copyOf(entries); }
    }

    public record Entry(String name, String path, boolean directory, boolean regularFile, long size, Instant createdAt,
                        Instant modifiedAt, String fileType, String sizeLabel, ViewerKind viewerKind,
                        List<WorkspaceFileLabel> tags, boolean canView, boolean canRename, boolean canDelete,
                        boolean canCopy, boolean canMove, boolean canTag) {
        public Entry { tags = tags == null ? List.of() : List.copyOf(tags); }
        public long sizeBytes() { return size; }
    }

    public enum ViewerKind { TEXT, MARKDOWN, IMAGE, UNSUPPORTED, TOO_LARGE, INVALID_UTF8 }

    public record FilePreview(String path, long size, boolean text, String content, boolean requiresWarning, String kind) { }
    public record DownloadContent(String fileName, byte[] bytes) {
        public DownloadContent { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public record DeleteResult(String path, int deletedCount) { }
    public record DeletePreflight(String path, boolean directory, int candidateCount, DeleteStep requiredStep, boolean executable) { }
    public enum DeleteStep { INTENT, FILE_CONFIRM, DIRECTORY_RECURSIVE_CONFIRM }
}
