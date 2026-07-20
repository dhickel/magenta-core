package io.mindspice.magenta2.ai.chat.tool.file;

import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.runtime.TaskRunLifecycleService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ConfinedPathMutationService;
import io.mindspice.magenta2.ai.orchestration.workspaces.ReservedWorkspacePathPolicy;
import io.mindspice.magenta2.ai.orchestration.workspaces.TaskRunLocation;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspacePathLayout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentFileToolService {
    private static final int DEFAULT_MAX_ENTRIES = 200;
    private static final int MAX_ENTRIES = 1_000;
    private static final int DEFAULT_READ_LINES = 200;
    private static final int MAX_READ_LINES = 400;
    private static final int DEFAULT_MAX_MATCHES = 50;
    private static final int MAX_MATCHES = 100;
    private static final int MAX_CONTEXT_LINES = 5;
    private static final int HASH_LENGTH = 12;
    private static final int MAX_DISPLAY_LINE_CHARS = 2_000;

    // Maximum file size for full-buffer operations (replace). Files larger than
    // this are rejected with a clear message directing the caller to streaming tools.
    private static final long MAX_FULL_BUFFER_BYTES =
        Long.parseLong(System.getProperty("magenta.file.maxFullBufferBytes", Long.toString(10_485_760))); // 10 MB

    private final Path root;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final TaskRunLifecycleService taskRunLifecycleService;
    private final ConfinedPathMutationService mutations;

    public AgentFileToolService(
        AiConfig aiConfig,
        WorkspaceDirectoryService workspaceDirectoryService
    ) throws IOException {
        this(aiConfig, workspaceDirectoryService, null);
    }

    @Autowired
    public AgentFileToolService(
        AiConfig aiConfig,
        @Autowired(required = false) WorkspaceDirectoryService workspaceDirectoryService,
        @Autowired(required = false) TaskRunLifecycleService taskRunLifecycleService
    ) throws IOException {
        if (aiConfig == null || aiConfig.dataRoot() == null) {
            throw new IllegalArgumentException("AI config dataRoot is required for file tools");
        }
        if (!Files.isDirectory(aiConfig.dataRoot())) {
            throw new IllegalArgumentException("AI config dataRoot must be an existing directory: " + aiConfig.dataRoot());
        }
        this.root = aiConfig.dataRoot().toRealPath();
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.taskRunLifecycleService = taskRunLifecycleService;
        this.mutations = new ConfinedPathMutationService();
    }

    public AgentFileToolService(AiConfig aiConfig) throws IOException {
        this(aiConfig, null);
    }

    AgentFileToolService(Path root) throws IOException {
        this(root, null);
    }

    AgentFileToolService(Path root, WorkspaceDirectoryService workspaceDirectoryService) throws IOException {
        this.root = root.toRealPath();
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.taskRunLifecycleService = null;
        this.mutations = new ConfinedPathMutationService();
    }

    public FileListResult list(String path, boolean recursive, Integer maxEntries) throws IOException {
        return list(path, recursive, maxEntries, null);
    }

    public FileListResult list(String path, boolean recursive, Integer maxEntries, String glob) throws IOException {
        ResolvedFilePath target = resolveExisting(path);
        recordActiveRuntimePath(target);
        if (Files.isRegularFile(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            return new FileListResult(
                displayPath(target),
                matchesGlob(target.path(), target.scope(), glob) ? List.of(fileEntry(target.path(), target.scope())) : List.of(),
                false
            );
        }
        int limit = clamp(maxEntries, DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES);
        List<FileEntry> entries = new ArrayList<>();
        List<Path> discovered;
        if (recursive) {
            discovered = browsableTree(target);
        } else {
            try (Stream<Path> stream = Files.list(target.path())) {
                discovered = stream.filter(item -> isBrowsableDiscovered(item, target.scope())).toList();
            }
        }
        List<Path> paths = discovered.stream()
            .filter(item -> !item.equals(target.path()))
            .filter(item -> matchesGlob(item, target.scope(), glob))
            .sorted(Comparator.comparing(item -> displayPath(item, target.scope())))
            .limit(limit + 1L)
            .toList();
        for (Path item : paths.stream().limit(limit).toList()) {
            entries.add(fileEntry(item, target.scope()));
        }
        return new FileListResult(displayPath(target), entries, paths.size() > limit);
    }

    private boolean matchesGlob(Path path, FileScope scope, String glob) {
        if (!StringUtils.hasText(glob)) {
            return true;
        }
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + glob.trim());
        Path scopedRelative = relativePath(path, scope);
        return matcher.matches(scopedRelative) || matcher.matches(Path.of(displayPath(path, scope)));
    }

    public FileReadResult read(String path, Integer startLine, Integer maxLines) throws IOException {
        ResolvedFilePath target = resolveTextFile(path);
        recordActiveRuntimePath(target);
        int limit = clamp(maxLines, DEFAULT_READ_LINES, 1, MAX_READ_LINES);
        int firstLine = Math.max(1, startLine == null ? 1 : startLine);
        List<String> formattedLines = new ArrayList<>();
        int totalLines = 0;
        try (BufferedReader reader = Files.newBufferedReader(target.path(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                totalLines++;
                if (totalLines >= firstLine && formattedLines.size() < limit) {
                    formattedLines.add(formatLine(totalLines, line));
                }
            }
        }
        Integer nextStartLine = firstLine + formattedLines.size() <= totalLines
            ? firstLine + formattedLines.size()
            : null;
        int endLine = formattedLines.isEmpty() ? firstLine - 1 : firstLine + formattedLines.size() - 1;
        return new FileReadResult(displayPath(target), totalLines, firstLine, endLine, nextStartLine, formattedLines);
    }

    public FileSearchResult search(
        String path,
        String query,
        boolean regex,
        boolean caseSensitive,
        Integer contextLines,
        Integer maxMatches
    ) throws IOException {
        if (!StringUtils.hasText(query)) {
            throw new IllegalArgumentException("query is required");
        }
        ResolvedFilePath target = resolveExisting(path);
        int context = clamp(contextLines, 0, 0, MAX_CONTEXT_LINES);
        int limit = clamp(maxMatches, DEFAULT_MAX_MATCHES, 1, MAX_MATCHES);
        Pattern pattern = compilePattern(query, regex, caseSensitive);
        recordActiveRuntimePath(target);
        List<Path> files = searchableFiles(target);
        List<SearchMatch> matches = new ArrayList<>();
        boolean truncated = false;
        for (Path file : files) {
            if (matches.size() >= limit) {
                truncated = true;
                break;
            }
            try {
                truncated = searchFile(file, target.scope(), pattern, context, limit, matches);
            } catch (IOException | RuntimeException ignored) {
                continue;
            }
            if (truncated) {
                break;
            }
        }
        return new FileSearchResult(matches, truncated);
    }

    private boolean searchFile(
        Path file,
        FileScope scope,
        Pattern pattern,
        int context,
        int limit,
        List<SearchMatch> matches
    ) throws IOException {
        Deque<String> before = new ArrayDeque<>();
        List<PendingSearchMatch> pending = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                addAfterContext(pending, lineNumber, line);
                flushReadyPending(pending, matches);
                if (matches.size() >= limit) {
                    return true;
                }
                if (pattern.matcher(line).find()) {
                    pending.add(new PendingSearchMatch(
                        displayPath(file, scope),
                        lineNumber,
                        hashLine(line),
                        displayLine(line),
                        List.copyOf(before),
                        context
                    ));
                    flushReadyPending(pending, matches);
                    if (matches.size() >= limit) {
                        return true;
                    }
                }
                before.addLast(formatLine(lineNumber, line));
                while (before.size() > context) {
                    before.removeFirst();
                }
            }
        }
        for (PendingSearchMatch match : pending) {
            matches.add(match.toSearchMatch());
            if (matches.size() >= limit) {
                return true;
            }
        }
        return false;
    }

    private void addAfterContext(List<PendingSearchMatch> pending, int lineNumber, String line) {
        for (PendingSearchMatch match : pending) {
            if (match.lineNumber() != lineNumber && !match.isReady()) {
                match.addAfter(formatLine(lineNumber, line));
            }
        }
    }

    private void flushReadyPending(List<PendingSearchMatch> pending, List<SearchMatch> matches) {
        pending.removeIf(match -> {
            if (!match.isReady()) {
                return false;
            }
            matches.add(match.toSearchMatch());
            return true;
        });
    }

    public FileWriteResult write(String path, String content, boolean overwrite) throws IOException {
        ResolvedFilePath target = resolveForWrite(path);
        requireWritablePath(target);
        recordActiveRuntimePath(target);
        boolean existed = Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS);
        if (existed && !overwrite) {
            throw new IllegalArgumentException("file already exists: " + displayPath(target));
        }
        if (existed && !Files.isRegularFile(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("target is not a regular file: " + displayPath(target));
        }
        String text = content == null ? "" : content;
        requireCurrentWriterForMutation();
        mutations.write(target.scope().root(), relativePath(target.path(), target.scope()).toString(),
            text.getBytes(StandardCharsets.UTF_8),
            existed ? ConfinedPathMutationService.WriteMode.OVERWRITE
                    : ConfinedPathMutationService.WriteMode.CREATE_NEW,
            true);
        return new FileWriteResult(displayPath(target), text.getBytes(StandardCharsets.UTF_8).length, !existed);
    }

    public FileAppendResult append(String path, String content, boolean create) throws IOException {
        ResolvedFilePath target = create ? resolveForWrite(path) : resolveTextFile(path);
        requireWritablePath(target);
        recordActiveRuntimePath(target);
        boolean existed = Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS);
        if (existed && !Files.isRegularFile(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("target is not a regular file: " + displayPath(target));
        }
        if (!existed && !create) {
            throw new IllegalArgumentException("file does not exist: " + displayPath(target));
        }
        String text = content == null ? "" : content;
        requireCurrentWriterForMutation();
        mutations.write(target.scope().root(), relativePath(target.path(), target.scope()).toString(),
            text.getBytes(StandardCharsets.UTF_8),
            existed ? ConfinedPathMutationService.WriteMode.APPEND
                    : ConfinedPathMutationService.WriteMode.CREATE_NEW,
            true);
        return new FileAppendResult(displayPath(target), text.getBytes(StandardCharsets.UTF_8).length, !existed);
    }

    public FileReplaceResult replace(String path, String startAnchor, String endAnchor, String replacement) throws IOException {
        ResolvedFilePath target = resolveTextFile(path);
        requireWritablePath(target);
        recordActiveRuntimePath(target);
        long fileSize = Files.size(target.path());
        if (fileSize > MAX_FULL_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                "File is too large for file_replace (" + fileSize + " bytes exceeds "
                + MAX_FULL_BUFFER_BYTES + " byte limit). To edit large files, use file_read "
                + "to inspect content, then use file_write to write the desired content."
            );
        }
        LineAnchor start = parseAnchor(startAnchor, "startAnchor");
        LineAnchor end = StringUtils.hasText(endAnchor) ? parseAnchor(endAnchor, "endAnchor") : start;
        if (end.lineNumber() < start.lineNumber()) {
            throw new IllegalArgumentException("endAnchor must not be before startAnchor");
        }

        String original = Files.readString(target.path(), StandardCharsets.UTF_8);
        List<String> lines = splitLines(original);
        validateAnchor(start, lines, "startAnchor");
        validateAnchor(end, lines, "endAnchor");

        List<String> replacementLines = splitLines(replacement == null ? "" : replacement);
        List<String> updated = new ArrayList<>();
        updated.addAll(lines.subList(0, start.lineNumber() - 1));
        updated.addAll(replacementLines);
        updated.addAll(lines.subList(end.lineNumber(), lines.size()));

        String lineSeparator = detectLineSeparator(original);
        boolean finalNewline = endsWithNewline(original);
        String updatedText = String.join(lineSeparator, updated);
        if (finalNewline && !updatedText.isEmpty()) {
            updatedText += lineSeparator;
        }
        requireCurrentWriterForMutation();
        mutations.write(target.scope().root(), relativePath(target.path(), target.scope()).toString(),
            updatedText.getBytes(StandardCharsets.UTF_8),
            ConfinedPathMutationService.WriteMode.OVERWRITE, false);
        return new FileReplaceResult(
            displayPath(target),
            start.lineNumber(),
            end.lineNumber(),
            end.lineNumber() - start.lineNumber() + 1,
            replacementLines.size()
        );
    }

    private void requireCurrentWriterForMutation() {
        OrchestrationTaskContext context = OrchestrationTaskContextHolder.current();
        if (context == null || context.taskRunLocation() == null) return;
        if (taskRunLifecycleService == null || context.writerCapability() == null) {
            throw new TaskRunLifecycleService.StaleWriterException(
                "Task-run file mutation requires a current writer capability");
        }
        taskRunLifecycleService.requireCurrentWriter(
            context.taskRunLocation(), context.writerCapability());
    }

    private ResolvedFilePath resolveExisting(String path) throws IOException {
        ResolvedFilePath resolved = resolveNormalized(path);
        if (!Files.exists(resolved.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path does not exist: " + displayPath(resolved));
        }
        Path real = resolved.path().toRealPath();
        if (!real.startsWith(resolved.scope().root())) {
            throw new IllegalArgumentException("path escapes " + resolved.scope().label());
        }
        ResolvedFilePath result = new ResolvedFilePath(real, resolved.scope());
        requireBrowsablePath(result);
        return result;
    }

    private ResolvedFilePath resolveTextFile(String path) throws IOException {
        ResolvedFilePath resolved = resolveExisting(path);
        if (!Files.isRegularFile(resolved.path(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is not a regular file: " + displayPath(resolved));
        }
        return resolved;
    }

    private ResolvedFilePath resolveForWrite(String path) throws IOException {
        ResolvedFilePath resolved = resolveNormalized(path);
        if (Files.exists(resolved.path(), LinkOption.NOFOLLOW_LINKS)) {
            Path real = resolved.path().toRealPath();
            if (!real.startsWith(resolved.scope().root())) {
                throw new IllegalArgumentException("path escapes " + resolved.scope().label());
            }
            return new ResolvedFilePath(real, resolved.scope());
        }
        Path parent = resolved.path().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("path must have a parent directory");
        }
        Path existingParent = nearestExistingParent(parent);
        Path parentReal = existingParent.toRealPath();
        if (!parentReal.startsWith(resolved.scope().root())) {
            throw new IllegalArgumentException("path escapes " + resolved.scope().label());
        }
        return resolved;
    }

    private ResolvedFilePath resolveNormalized(String path) throws IOException {
        FileScope scope = resolveScope(path);
        Path resolved = scope.relativePath().isEmpty()
            ? scope.root()
            : scope.root().resolve(scope.relativePath()).normalize();
        if (!resolved.startsWith(scope.root())) {
            throw new IllegalArgumentException("path escapes " + scope.label());
        }
        return new ResolvedFilePath(resolved, scope);
    }

    private FileScope resolveScope(String path) throws IOException {
        String requested = StringUtils.hasText(path) ? path : ".";
        OrchestrationTaskContext ctx = OrchestrationTaskContextHolder.current();
        if (ctx != null && ctx.hasContext()) {
            return resolveContextScope(ctx, requested);
        }
        Path input = Path.of(requested);
        Path resolved = input.isAbsolute() ? input.normalize() : root.resolve(input).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes data root");
        }
        Path relative = root.relativize(resolved);
        return new FileScope(root, "", "data root", relative.toString());
    }

    private FileScope resolveContextScope(OrchestrationTaskContext ctx, String requested) throws IOException {
        String normalized = normalizeContextRequest(requested);
        if (ctx.taskRunLocation() != null) {
            Path runRoot = ctx.taskRunLocation().confinedPath().toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!runRoot.startsWith(root) || !Files.isDirectory(runRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("trusted task-run location escapes data root");
            }
            return directWorkspaceScope(runRoot, normalized, requested, "task run workspace");
        }
        if (StringUtils.hasText(ctx.hostDurableWorkspacePath())) {
            return resolveAssignmentScope(ctx, normalized, requested);
        }
        if (ctx.hasAgentContext()) {
            return resolveAgentScope(ctx, normalized, requested);
        }
        throw new IllegalStateException("File tools require an active assignment workspace");
    }

    private FileScope resolveAssignmentScope(OrchestrationTaskContext ctx, String normalized, String requested)
        throws IOException {
        Path workspaceRoot = activeScopeRoot(ctx.hostDurableWorkspacePath(), "active workspace");
        return directWorkspaceScope(workspaceRoot, normalized, requested, "active workspace");
    }

    private FileScope resolveAgentScope(OrchestrationTaskContext ctx, String normalized, String requested)
        throws IOException {
        if (workspaceDirectoryService == null) {
            throw new IllegalStateException("Workspace directory service is not available");
        }
        Path workspaceRoot = workspaceDirectoryService.resolveForWrite(
            io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.AGENT,
            ctx.agentId()
        ).root().toRealPath();
        return directWorkspaceScope(workspaceRoot, normalized, requested, "agent workspace");
    }

    private FileScope directWorkspaceScope(Path workspaceRoot, String normalized, String requested, String label) {
        String relative = normalized.isEmpty() || ".".equals(normalized) ? "" : normalized;
        rejectUnsafeRelativePath(relative, "path escapes " + label + ": " + requested);
        return new FileScope(workspaceRoot, "", label, relative);
    }

    private String normalizeContextRequest(String requested) {
        String normalized = requested.trim().replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Absolute file paths are not allowed in active task context: " + requested);
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("path escapes active workspace: " + requested);
        }
        return normalized;
    }

    private void rejectUnsafeRelativePath(String relativePath, String message) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (normalized.equals("..")
            || normalized.startsWith("../")
            || normalized.endsWith("/..")
            || normalized.contains("/../")) {
            throw new IllegalArgumentException(message);
        }
    }

    private Path activeScopeRoot(String path, String label) throws IOException {
        if (!StringUtils.hasText(path)) {
            throw new IllegalStateException("File tools require an " + label);
        }
        Path real = Path.of(path).toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes data root");
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " is not a directory");
        }
        return real;
    }

    // ── Phase 03 policy-based path protection ──

    /**
     * Require that the resolved target is writable under the current
     * context. Classifies via {@link ReservedWorkspacePathPolicy} using a
     * workspace-relative or scope-relative path.
     *
     * <p>With a {@link TaskRunLocation}: requires
     * {@code RUN_WRITER_MUTATION} (only run working content is writable).
     * Without a task run (agent/workspace scope): requires
     * {@code ORDINARY_MUTATION} (outputs, tasks, .skills are protected).
     */
    private void requireWritablePath(ResolvedFilePath target) {
        OrchestrationTaskContext ctx = OrchestrationTaskContextHolder.current();
        String workspaceRelative = ctx != null && ctx.hasContext()
                ? workspaceRelative(target, ctx)
                : workspaceRelativeFromDataRoot(target);
        if (workspaceRelative == null) return;
        ReservedWorkspacePathPolicy.Classification c =
                ReservedWorkspacePathPolicy.classify(workspaceRelative);
        ReservedWorkspacePathPolicy.Operation requiredOp =
                ctx != null && ctx.taskRunLocation() != null
                ? ReservedWorkspacePathPolicy.Operation.RUN_WRITER_MUTATION
                : ReservedWorkspacePathPolicy.Operation.ORDINARY_MUTATION;
        if (ReservedWorkspacePathPolicy.decide(c, requiredOp)
                != ReservedWorkspacePathPolicy.Decision.ALLOW) {
            throw new IllegalArgumentException(
                    "path is not writable in current context: " + displayPath(target));
        }
    }

    /**
     * Require that the resolved target is browsable. Hidden content
     * ({@code .skills}, staging) is rejected. Applies only when a context
     * is active.
     */
    private void requireBrowsablePath(ResolvedFilePath target) {
        OrchestrationTaskContext ctx = OrchestrationTaskContextHolder.current();
        String workspaceRelative = ctx != null && ctx.hasContext()
                ? workspaceRelative(target, ctx)
                : workspaceRelativeFromDataRoot(target);
        if (workspaceRelative == null) return;
        ReservedWorkspacePathPolicy.Classification c =
                ReservedWorkspacePathPolicy.classify(workspaceRelative);
        if (ReservedWorkspacePathPolicy.decide(c,
                ReservedWorkspacePathPolicy.Operation.BROWSE)
                != ReservedWorkspacePathPolicy.Decision.ALLOW) {
            throw new IllegalArgumentException("path is not accessible: " + displayPath(target));
        }
    }

    /** Classify every recursive discovery; hidden .skills and staging entries are skipped. */
    private boolean isBrowsableDiscovered(Path path, FileScope scope) {
        try {
            requireBrowsablePath(new ResolvedFilePath(path, scope));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Compute a workspace-relative path for policy classification.
     * When a {@link TaskRunLocation} is available, prepends its
     * {@code rootRelativePath}. Otherwise falls back to scope-relative.
     */
    private String workspaceRelative(ResolvedFilePath target,
                                     OrchestrationTaskContext ctx) {
        if (ctx != null && ctx.taskRunLocation() != null) {
            String prefix = ctx.taskRunLocation().rootRelativePath();
            Path runRoot = ctx.taskRunLocation().confinedPath();
            java.nio.file.Path resolved = target.path().toAbsolutePath().normalize();
            String relative = runRoot.relativize(resolved).toString().replace('\\', '/');
            if (relative.isEmpty() || ".".equals(relative)) return prefix;
            return prefix + "/" + relative;
        }
        return workspaceRelativeOrScopeRelative(target);
    }

    private String workspaceRelativeOrScopeRelative(ResolvedFilePath target) {
        return target.scope().root()
                .relativize(target.path().toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    /**
     * Recognize canonical workspace roots even in legacy no-context data-root
     * scope so that fallback chat tooling cannot bypass reserved-tree policy.
     */
    private String workspaceRelativeFromDataRoot(ResolvedFilePath target) {
        Path relative = root.relativize(target.path().toAbsolutePath().normalize());
        if (relative.getNameCount() < 3
                || !WorkspacePathLayout.WORKSPACE.equals(relative.getName(0).toString())) {
            return null;
        }
        try {
            WorkspaceKind.fromToken(relative.getName(1).toString());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid workspace path");
        }
        String value = relative.getNameCount() == 3
                ? "." : relative.subpath(3, relative.getNameCount()).toString().replace('\\', '/');
        return value.isBlank() ? "." : value;
    }

    private Path nearestExistingParent(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalArgumentException("path has no existing parent");
        }
        return current;
    }

    private List<Path> searchableFiles(ResolvedFilePath target) throws IOException {
        if (Files.isRegularFile(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            return List.of(target.path());
        }
        if (!Files.isDirectory(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        return browsableTree(target).stream()
            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            .sorted(Comparator.comparing(path -> displayPath(path, target.scope())))
            .toList();
    }

    private List<Path> browsableTree(ResolvedFilePath target) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(target.path(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (!directory.equals(target.path()) && !isBrowsableDiscovered(directory, target.scope())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                paths.add(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (isBrowsableDiscovered(file, target.scope())) paths.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return paths;
    }

    private Pattern compilePattern(String query, boolean regex, boolean caseSensitive) {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return regex ? Pattern.compile(query, flags) : Pattern.compile(Pattern.quote(query), flags);
        } catch (PatternSyntaxException exception) {
            throw new IllegalArgumentException("invalid regex query: " + exception.getMessage(), exception);
        }
    }

    private void validateAnchor(LineAnchor anchor, List<String> lines, String name) {
        if (anchor.lineNumber() < 1 || anchor.lineNumber() > lines.size()) {
            throw new IllegalArgumentException(name + " line is out of range");
        }
        String actualHash = hashLine(lines.get(anchor.lineNumber() - 1));
        if (!actualHash.equals(anchor.hash())) {
            throw new IllegalArgumentException(name + " hash does not match current file content");
        }
    }

    private LineAnchor parseAnchor(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " is required");
        }
        String[] parts = value.trim().split(":", 2);
        if (parts.length != 2 || !StringUtils.hasText(parts[0]) || !StringUtils.hasText(parts[1])) {
            throw new IllegalArgumentException(name + " must use lineNumber:hash format");
        }
        try {
            return new LineAnchor(Integer.parseInt(parts[0]), parts[1].toLowerCase(Locale.ROOT));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " line number is invalid", exception);
        }
    }

    private String formatLine(int lineNumber, String content) {
        return lineNumber + ":" + hashLine(content) + "|" + displayLine(content);
    }

    private String displayLine(String content) {
        if (content == null || content.length() <= MAX_DISPLAY_LINE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_DISPLAY_LINE_CHARS).trim()
            + " ... [line truncated; use narrower search terms or read nearby chunks]";
    }

    private String hashLine(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private List<String> splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        return List.of(normalized.split("\n", -1));
    }

    private String detectLineSeparator(String content) {
        if (content != null && content.contains("\r\n")) {
            return "\r\n";
        }
        return "\n";
    }

    private boolean endsWithNewline(String content) {
        return content != null && (content.endsWith("\n") || content.endsWith("\r"));
    }

    private int clamp(Integer value, int defaultValue, int min, int max) {
        int actual = value == null ? defaultValue : value;
        return Math.min(max, Math.max(min, actual));
    }

    private Path relativePath(Path path, FileScope scope) {
        return scope.root().relativize(path.toAbsolutePath().normalize());
    }

    private String displayPath(ResolvedFilePath path) {
        return displayPath(path.path(), path.scope());
    }

    private void recordActiveRuntimePath(ResolvedFilePath path) {
        OrchestrationTaskContextHolder.recordActiveRuntimePath(displayPath(path));
    }

    private String displayPath(Path path, FileScope scope) {
        Path relative = relativePath(path, scope);
        String rel = relative.toString();
        if (!StringUtils.hasText(scope.displayPrefix())) {
            return rel.isEmpty() ? "." : rel;
        }
        return rel.isEmpty() ? scope.displayPrefix() : scope.displayPrefix() + "/" + rel;
    }

    private FileEntry fileEntry(Path path, FileScope scope) throws IOException {
        return new FileEntry(
            displayPath(path, scope),
            Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file",
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : null
        );
    }

    private record LineAnchor(int lineNumber, String hash) {
    }

    private record FileScope(Path root, String displayPrefix, String label, String relativePath) {
    }

    private record ResolvedFilePath(Path path, FileScope scope) {
    }

    private static final class PendingSearchMatch {
        private final String path;
        private final int lineNumber;
        private final String hash;
        private final String line;
        private final List<String> before;
        private final int context;
        private final List<String> after = new ArrayList<>();

        private PendingSearchMatch(
            String path,
            int lineNumber,
            String hash,
            String line,
            List<String> before,
            int context
        ) {
            this.path = path;
            this.lineNumber = lineNumber;
            this.hash = hash;
            this.line = line;
            this.before = before;
            this.context = context;
        }

        private int lineNumber() {
            return lineNumber;
        }

        private void addAfter(String formattedLine) {
            if (!isReady()) {
                after.add(formattedLine);
            }
        }

        private boolean isReady() {
            return after.size() >= context;
        }

        private SearchMatch toSearchMatch() {
            return new SearchMatch(path, lineNumber, hash, line, before, List.copyOf(after));
        }
    }

    public record FileEntry(String path, String type, Long size) {
    }

    public record FileListResult(String path, List<FileEntry> entries, boolean truncated) {
    }

    public record FileReadResult(
        String path,
        int totalLines,
        int startLine,
        int endLine,
        Integer nextStartLine,
        List<String> lines
    ) {
    }

    public record SearchMatch(
        String path,
        int lineNumber,
        String hash,
        String line,
        List<String> before,
        List<String> after
    ) {
    }

    public record FileSearchResult(List<SearchMatch> matches, boolean truncated) {
    }

    public record FileWriteResult(String path, int bytesWritten, boolean created) {
    }

    public record FileAppendResult(String path, int bytesAppended, boolean created) {
    }

    public record FileReplaceResult(String path, int startLine, int endLine, int replacedLines, int newLines) {
    }
}
