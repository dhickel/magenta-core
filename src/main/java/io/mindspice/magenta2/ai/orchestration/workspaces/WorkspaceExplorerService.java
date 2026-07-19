package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Descriptor-first owner of confined workspace file exploration and mutation. */
@Service
public class WorkspaceExplorerService {
    static final long NORMAL_TEXT_BYTES = 10L * 1024 * 1024;
    static final long NORMAL_MARKDOWN_BYTES = 5L * 1024 * 1024;
    static final long HARD_EDIT_BYTES = 25L * 1024 * 1024;
    private static final int ROW_UTF8_PROBE_BYTES = 64 * 1024;

    private final WorkspaceFileMetadataService metadataService;
    private final WorkspaceFileActionLogRepository actionLogRepository;

    @Autowired
    public WorkspaceExplorerService(
        WorkspaceFileMetadataService metadataService,
        WorkspaceFileActionLogRepository actionLogRepository
    ) {
        this.metadataService = metadataService;
        this.actionLogRepository = actionLogRepository;
    }

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
                return new DirectoryListing(".", List.of());
            }
            throw new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "path does not exist: " + path.value());
        }
        return list(descriptor, descriptor.root(), path);
    }

    public FilePreview preview(WorkspaceDescriptor descriptor, RelativePath path) {
        return preview(descriptor.root(), path);
    }

    public DownloadContent download(WorkspaceDescriptor descriptor, RelativePath path) {
        return downloadContent(descriptor, descriptor.root(), path);
    }

    public FilePreview saveText(WorkspaceDescriptor descriptor, RelativePath path, String content) {
        FilePreview saved = saveText(descriptor, descriptor.root(), path, content);
        logAction(descriptor, "markdown".equals(saved.kind()) ? WorkspaceFileActionType.SAVE_MARKDOWN : WorkspaceFileActionType.SAVE_TEXT,
            path.value(), null, "SUCCEEDED", "{\"bytes\":" + saved.size() + "}");
        return saved;
    }

    public Entry createDirectory(WorkspaceDescriptor descriptor, RelativePath path) {
        requireNonReserved(path);
        requireCreatableParent(descriptor, path);
        ensureDescriptorRootForCreate(descriptor);
        Entry created = createDirectoryRoot(descriptor.root(), path);
        logAction(descriptor, WorkspaceFileActionType.CREATE_FOLDER, created.path(), null, "SUCCEEDED", "{}");
        return decorate(descriptor, created);
    }

    public Entry createTextFile(WorkspaceDescriptor descriptor, RelativePath parent, String fileName) {
        requireNonReserved(parent);
        requireFileName(fileName, ".txt", "text");
        requireCreatableParent(descriptor, new RelativePath(childValue(parent, fileName.trim())));
        ensureDescriptorRootForCreate(descriptor);
        Entry created = createTextFileRoot(descriptor.root(), parent, fileName);
        logAction(descriptor, WorkspaceFileActionType.CREATE_TEXT_FILE, created.path(), null, "SUCCEEDED", "{}");
        return decorate(descriptor, created);
    }

    public Entry createMarkdownFile(WorkspaceDescriptor descriptor, RelativePath parent, String fileName) {
        requireNonReserved(parent);
        requireFileName(fileName, ".md", "markdown");
        requireCreatableParent(descriptor, new RelativePath(childValue(parent, fileName.trim())));
        ensureDescriptorRootForCreate(descriptor);
        Entry created = createMarkdownFileRoot(descriptor.root(), parent, fileName);
        logAction(descriptor, WorkspaceFileActionType.CREATE_MARKDOWN_FILE, created.path(), null, "SUCCEEDED", "{}");
        return decorate(descriptor, created);
    }

    @Transactional
    public Entry rename(WorkspaceDescriptor descriptor, RelativePath source, String newName) {
        Entry renamed = renameRoot(descriptor.root(), source, newName);
        metadataService.onMove(descriptor, source.value(), renamed.path());
        logAction(descriptor, WorkspaceFileActionType.RENAME, source.value(), renamed.path(), "SUCCEEDED", "{}");
        return decorate(descriptor, renamed);
    }

    @Transactional
    public Entry move(WorkspaceDescriptor descriptor, RelativePath source, RelativePath destination, String newName) {
        Entry moved = moveRoot(descriptor.root(), source, destination, newName);
        metadataService.onMove(descriptor, source.value(), moved.path());
        logAction(descriptor, WorkspaceFileActionType.MOVE, source.value(), moved.path(), "SUCCEEDED", "{}");
        return decorate(descriptor, moved);
    }

    @Transactional
    public Entry copy(WorkspaceDescriptor descriptor, RelativePath source, RelativePath destination, String newName) {
        if (".".equals(source.value())) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "workspace root cannot be copied");
        }
        Entry copied = copyRoot(descriptor.root(), source, destination, newName);
        metadataService.onCopy(descriptor, source.value(), copied.path());
        logAction(descriptor, WorkspaceFileActionType.COPY, source.value(), copied.path(), "SUCCEEDED", "{}");
        return decorate(descriptor, copied);
    }

    public DeletePreflight deletePreflight(WorkspaceDescriptor descriptor, RelativePath path, DeleteStep step) {
        return deletePreflightRoot(descriptor.root(), path, step);
    }

    @Transactional
    public DeleteResult delete(WorkspaceDescriptor descriptor, RelativePath path, DeleteStep step) {
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
    }

    public Entry inspect(WorkspaceDescriptor descriptor, RelativePath path) {
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
        Path dir = resolveExisting(root, relativePath);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is not a directory");
        }
        try (var stream = Files.list(dir)) {
            List<Entry> entries = stream.map(path -> decorate(descriptor, entry(root, path)))
                .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name))
                .toList();
            return new DirectoryListing(relative(root, dir), entries);
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
        Path dir = resolveExisting(root, relativePath);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path is not a directory");
        }
        try (var stream = Files.list(dir)) {
            List<Entry> entries = stream.map(path -> entry(root, path))
                .sorted(Comparator.comparing(Entry::directory).reversed().thenComparing(Entry::name))
                .toList();
            return new DirectoryListing(relative(root, dir), entries);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to list directory", exception);
        }
    }

    private FilePreview previewRoot(Path root, RelativePath relativePath) {
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
            Files.write(file, applyLineEnding(normalizedContent, lineEnding).getBytes(StandardCharsets.UTF_8));
            return preview(root, relativePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to save text file", exception);
        }
    }

    private Entry createDirectoryRoot(Path root, RelativePath relativePath) {
        requireNonReserved(relativePath);
        Path dir = resolveForWrite(root, relativePath);
        ensureMutable(root, dir);
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new WorkspaceFileException(WorkspaceFileException.Reason.CONFLICT, "target already exists");
            }
            return entry(root, Files.createDirectory(dir).toRealPath(LinkOption.NOFOLLOW_LINKS));
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
            movePath(source, target);
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
            movePath(source, target);
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
            copyPath(source, target);
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
                Files.delete(path);
            }
            return new DeleteResult(relative(root, target), paths.size());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete path", exception);
        }
    }

    private Entry inspectRoot(Path root, RelativePath relativePath) {
        return entry(root, resolveExisting(root, relativePath));
    }

    /* ── Private helpers ── */

    private Entry decorate(WorkspaceDescriptor descriptor, Entry entry) {
        if (metadataService == null) return entry;
        List<WorkspaceFileLabel> tags = metadataService.labelsForPath(descriptor, entry.path()).stream()
            .map(WorkspaceFileLabelAssignment::label).toList();
        return new Entry(entry.name(), entry.path(), entry.directory(), entry.regularFile(), entry.size(),
            entry.createdAt(), entry.modifiedAt(), entry.fileType(), entry.sizeLabel(), entry.viewerKind(),
            tags, entry.canView(), entry.canRename(), entry.canDelete(), entry.canCopy(), entry.canMove(), entry.canTag());
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
            Files.createDirectories(descriptor.root());
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
            Files.writeString(file, "", StandardCharsets.UTF_8);
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
            boolean mutable = isMutableRelative(relative);
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
        if (!isMutableRelative(relative)) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "workspace root and outputs are protected");
        }
    }

    private void requireNonReserved(RelativePath path) {
        if ("outputs".equals(path.value()) || path.value().startsWith("outputs/")) {
            throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "outputs are protected");
        }
    }

    private boolean isMutableRelative(String relative) {
        return !".".equals(relative) && !"outputs".equals(relative) && !relative.startsWith("outputs/");
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

    private void movePath(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source, target);
        }
    }

    private void copyPath(Path source, Path target) throws IOException {
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path copyTarget = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(copyTarget);
                else Files.copy(path, copyTarget, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private List<Path> validatedDeletePaths(Path root, Path target) throws IOException {
        try (var walk = Files.walk(target)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                ensureConfined(root, path.toRealPath(LinkOption.NOFOLLOW_LINKS));
                if (Files.isSymbolicLink(path)) throw new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "symbolic links are not deleted");
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
