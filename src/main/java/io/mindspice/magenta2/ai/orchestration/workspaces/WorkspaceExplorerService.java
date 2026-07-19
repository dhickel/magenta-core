package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Descriptor-first owner of confined workspace file exploration and mutation. */
@Service
public class WorkspaceExplorerService {
    static final long NORMAL_TEXT_BYTES = 10L * 1024 * 1024;
    static final long NORMAL_MARKDOWN_BYTES = 5L * 1024 * 1024;
    static final long HARD_EDIT_BYTES = 25L * 1024 * 1024;
    private static final int ROW_UTF8_PROBE_BYTES = 64 * 1024;

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
        if (text.matches("^[A-Za-z]:/.*")) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        Path path = Path.of(text).normalize();
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("path escapes workspace");
            }
        }
        String normalized = path.toString().replace('\\', '/');
        return new RelativePath(normalized.isBlank() ? "." : normalized);
    }

    public DirectoryListing list(WorkspaceDescriptor workspace, RelativePath path) {
        if (!Files.exists(workspace.root(), LinkOption.NOFOLLOW_LINKS)) {
            if (".".equals(path.value())) {
                return new DirectoryListing(".", List.of());
            }
            throw new IllegalArgumentException("path does not exist: " + path.value());
        }
        return list(workspace.root(), path);
    }

    public FilePreview preview(WorkspaceDescriptor workspace, RelativePath path) {
        return preview(workspace.root(), path);
    }

    public DownloadContent download(WorkspaceDescriptor workspace, RelativePath path) {
        return downloadContent(workspace.root(), path);
    }

    DownloadContent downloadContent(Path root, RelativePath path) {
        Path file = download(root, path);
        try {
            long size = Files.size(file);
            if (size > NORMAL_TEXT_BYTES) {
                throw new IllegalArgumentException("file is too large to download");
            }
            return new DownloadContent(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to download file", exception);
        }
    }

    public FilePreview saveText(WorkspaceDescriptor workspace, RelativePath path, String content) {
        return saveText(workspace.root(), path, content);
    }

    public Entry createDirectory(WorkspaceDescriptor workspace, RelativePath path) {
        requireNonReserved(path);
        ensureDescriptorRootForCreate(workspace);
        return createDirectory(workspace.root(), path);
    }

    public Entry createTextFile(WorkspaceDescriptor workspace, RelativePath parent, String fileName) {
        requireNonReserved(parent);
        ensureDescriptorRootForCreate(workspace);
        return createTextFile(workspace.root(), parent, fileName);
    }

    public Entry createMarkdownFile(WorkspaceDescriptor workspace, RelativePath parent, String fileName) {
        requireNonReserved(parent);
        ensureDescriptorRootForCreate(workspace);
        return createMarkdownFile(workspace.root(), parent, fileName);
    }

    public Entry rename(WorkspaceDescriptor workspace, RelativePath source, String newName) {
        return rename(workspace.root(), source, newName);
    }

    public Entry move(WorkspaceDescriptor workspace, RelativePath source, RelativePath destination, String newName) {
        return move(workspace.root(), source, destination, newName);
    }

    public Entry copy(WorkspaceDescriptor workspace, RelativePath source, RelativePath destination, String newName) {
        return copy(workspace.root(), source, destination, newName);
    }

    public DeletePreflight deletePreflight(WorkspaceDescriptor workspace, RelativePath path, DeleteStep step) {
        return deletePreflight(workspace.root(), path, step);
    }

    public DeleteResult delete(WorkspaceDescriptor workspace, RelativePath path, DeleteStep step) {
        return delete(workspace.root(), path, step);
    }

    public Entry inspect(WorkspaceDescriptor workspace, RelativePath path) {
        return inspect(workspace.root(), path);
    }

    /* Package-private root bridge used only by the temporary Work Area adapter. */

    DirectoryListing list(Path root, RelativePath relativePath) {
        Path dir = resolveExisting(root, relativePath);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is not a directory");
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

    FilePreview preview(Path root, RelativePath relativePath) {
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

    Path download(Path root, RelativePath relativePath) {
        Path file = resolveExisting(root, relativePath);
        requireRegularFile(file);
        return file;
    }

    FilePreview saveText(Path root, RelativePath relativePath, String content) {
        Path file = resolveExisting(root, relativePath);
        ensureMutable(root, file);
        if (!isSafeTextPath(file)) {
            throw new IllegalArgumentException("file type is not safe for text editing");
        }
        String normalizedContent = stripBom(content == null ? "" : content);
        if (normalizedContent.getBytes(StandardCharsets.UTF_8).length > HARD_EDIT_BYTES) {
            throw new IllegalArgumentException("text file is too large");
        }
        try {
            requireRegularFile(file);
            if (readUtf8(file) == null) {
                throw new IllegalArgumentException("file is not valid UTF-8");
            }
            String lineEnding = dominantLineEnding(file);
            Files.write(file, applyLineEnding(normalizedContent, lineEnding).getBytes(StandardCharsets.UTF_8));
            return preview(root, relativePath);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to save text file", exception);
        }
    }

    Entry createDirectory(Path root, RelativePath relativePath) {
        requireNonReserved(relativePath);
        Path dir = resolveForWrite(root, relativePath);
        ensureMutable(root, dir);
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("target already exists");
            }
            return entry(root, Files.createDirectory(dir).toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create directory", exception);
        }
    }

    Entry createTextFile(Path root, RelativePath parent, String fileName) {
        requirePlainName(fileName);
        String name = fileName.trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
            throw new IllegalArgumentException("text file name must end with .txt");
        }
        return createEmptyFile(root, parent, name);
    }

    Entry createMarkdownFile(Path root, RelativePath parent, String fileName) {
        requirePlainName(fileName);
        String name = fileName.trim();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("markdown file name must end with .md");
        }
        return createEmptyFile(root, parent, name);
    }

    Entry rename(Path root, RelativePath relativePath, String newName) {
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

    Entry move(Path root, RelativePath sourcePath, RelativePath destinationPath, String newName) {
        Path source = resolveExisting(root, sourcePath);
        ensureMutable(root, source);
        Path destination = resolveExisting(root, destinationPath);
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("destination is not a directory");
        }
        String targetName = StringUtils.hasText(newName) ? newName.trim() : source.getFileName().toString();
        requirePlainName(targetName);
        Path target = destination.resolve(targetName).normalize();
        ensureConfined(root, target);
        ensureMutable(root, target);
        rejectCollision(target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
            throw new IllegalArgumentException("directory cannot be moved into itself or a descendant");
        }
        try {
            rejectSymbolicTree(root, source);
            movePath(source, target);
            return entry(root, target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to move path", exception);
        }
    }

    Entry copy(Path root, RelativePath sourcePath, RelativePath destinationPath, String newName) {
        Path source = resolveExisting(root, sourcePath);
        Path destination = resolveExisting(root, destinationPath);
        if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("destination is not a directory");
        }
        String targetName = StringUtils.hasText(newName) ? newName.trim() : source.getFileName().toString();
        requirePlainName(targetName);
        Path target = destination.resolve(targetName).normalize();
        ensureConfined(root, target);
        ensureMutable(root, target);
        rejectCollision(target);
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) && target.startsWith(source)) {
            throw new IllegalArgumentException("directory cannot be copied into itself or a descendant");
        }
        try {
            rejectSymbolicTree(root, source);
            copyPath(source, target);
            return entry(root, target.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException exception) {
            throw new IllegalStateException("failed to copy path", exception);
        }
    }

    DeletePreflight deletePreflight(Path root, RelativePath relativePath, DeleteStep step) {
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

    DeleteResult delete(Path root, RelativePath relativePath, DeleteStep step) {
        DeletePreflight preflight = deletePreflight(root, relativePath, step);
        if (!preflight.executable()) {
            throw new IllegalArgumentException("delete confirmation step is required: " + preflight.requiredStep());
        }
        Path target = resolveExisting(root, relativePath);
        try {
            List<Path> paths = validatedDeletePaths(root, target);
            for (Path path : paths) {
                Files.delete(path);
            }
            return new DeleteResult(preflight.path(), paths.size());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to delete path", exception);
        }
    }

    Entry inspect(Path root, RelativePath relativePath) {
        return entry(root, resolveExisting(root, relativePath));
    }

    private void ensureDescriptorRootForCreate(WorkspaceDescriptor workspace) {
        if (Files.exists(workspace.root(), LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (workspace.kind() == WorkspaceKind.USER) {
            throw new IllegalArgumentException("user workspace does not exist");
        }
        try {
            Files.createDirectories(workspace.root());
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create workspace root", exception);
        }
    }

    private Entry createEmptyFile(Path root, RelativePath parentPath, String fileName) {
        Path parent = resolveExisting(root, parentPath);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("parent path is not a directory");
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
            throw new IllegalArgumentException("path does not exist: " + relativePath.value(), exception);
        }
    }

    private Path resolveForWrite(Path root, RelativePath relativePath) {
        Path resolved = resolve(root, relativePath);
        Path parent = resolved.getParent() == null ? root : resolved.getParent();
        try {
            ensureWritableParent(root, parent);
            ensureConfined(root, parent.toRealPath(LinkOption.NOFOLLOW_LINKS));
            if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("symbolic links are not editable");
            }
            return resolved;
        } catch (IOException exception) {
            throw new IllegalArgumentException("path cannot be created: " + relativePath.value(), exception);
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
            throw new IllegalArgumentException("workspace root and outputs are protected");
        }
    }

    private void requireNonReserved(RelativePath path) {
        if ("outputs".equals(path.value()) || path.value().startsWith("outputs/")) {
            throw new IllegalArgumentException("outputs are protected");
        }
    }

    private boolean isMutableRelative(String relative) {
        return !".".equals(relative) && !"outputs".equals(relative) && !relative.startsWith("outputs/");
    }

    private void ensureConfined(Path root, Path path) {
        if (!path.normalize().startsWith(root.normalize())) {
            throw new IllegalArgumentException("path escapes workspace");
        }
    }

    private void rejectCollision(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("target already exists");
        }
    }

    private void ensureWritableParent(Path root, Path parent) throws IOException {
        ensureConfined(root, parent);
        Path current = root;
        for (Path segment : root.relativize(parent.normalize())) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("symbolic links are not allowed");
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("parent path is not a directory");
            } else {
                Files.createDirectory(current);
            }
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
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("symbolic links are not deleted");
            }
            return paths;
        }
    }

    private void rejectSymbolicTree(Path root, Path source) throws IOException {
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                ensureConfined(root, path.toRealPath(LinkOption.NOFOLLOW_LINKS));
                if (Files.isSymbolicLink(path)) throw new IllegalArgumentException("symbolic links are not allowed");
            }
        }
    }

    private void rejectSymbolicPath(Path root, Path path) throws IOException {
        ensureConfined(root, path);
        Path current = root;
        for (Path segment : root.relativize(path.normalize())) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("symbolic links are not allowed");
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
            throw new IllegalArgumentException("newName must be a plain path segment");
        }
    }

    private void requireRegularFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("path is not a regular file");
    }

    private String readUtf8(Path file) throws IOException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
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
        if (text == null) throw new IllegalArgumentException("file is not valid UTF-8");
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

    private String relative(Path root, Path path) {
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
