package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileRepository;
import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Constructs trusted {@link WorkspaceDescriptor} instances and manages
 * filesystem paths for all workspace types. Every path is resolved
 * relative to the configured {@code dataRoot} and confined so it cannot
 * escape via {@code ..} traversal.
 *
 * <h3>Trusted roots</h3>
 * <ul>
 *   <li>Agent: {@code workspace/agent/<id>}</li>
 *   <li>Chat:  {@code workspace/chat/<id>}</li>
 *   <li>User:  {@code workspace/user/<name>}</li>
 * </ul>
 *
 * <p>This is the sole descriptor constructor. No caller reconstructs identity
 * from a {@code Path}, accepts a raw root, or hand-concatenates structural paths.
 */
@Service
public class WorkspaceDirectoryService {
    private final Path dataRoot;
    private final AgentProfileRepository agentProfileRepository;
    private final ChatConversationExistenceRepository chatConversationExistenceRepository;
    private final ConfinedPathMutationService mutations;

    public WorkspaceDirectoryService(
            AiConfig aiConfig,
            AgentProfileRepository agentProfileRepository,
            ChatConversationExistenceRepository chatConversationExistenceRepository) throws IOException {
        if (aiConfig == null || aiConfig.dataRoot() == null) {
            throw new IllegalArgumentException("AI config dataRoot is required");
        }
        this.dataRoot = Files.createDirectories(aiConfig.dataRoot()).toRealPath();
        this.agentProfileRepository = Objects.requireNonNull(
                agentProfileRepository, "agentProfileRepository");
        this.chatConversationExistenceRepository = Objects.requireNonNull(
                chatConversationExistenceRepository, "chatConversationExistenceRepository");
        this.mutations = new ConfinedPathMutationService();
    }

    // ── Descriptor resolution ──

    /**
     * Resolve a descriptor for reading. Agent/chat identity is validated
     * against repositories, but missing physical roots are not created.
     * Missing user roots are {@link WorkspaceResolutionException.Reason#NOT_FOUND}.
     */
    public WorkspaceDescriptor resolveForRead(WorkspaceKind kind, String key) {
        requireKey(key, kind);
        return switch (kind) {
            case AGENT -> resolveAgent(key, false);
            case CHAT -> resolveChat(key, false);
            case USER -> resolveUser(key, false);
        };
    }

    /**
     * Resolve a descriptor for writing. Validates identity and creates a
     * missing agent or chat root without scaffolding. User roots must already
     * exist; missing user is {@link WorkspaceResolutionException.Reason#NOT_FOUND}.
     */
    public WorkspaceDescriptor resolveForWrite(WorkspaceKind kind, String key) {
        requireKey(key, kind);
        return switch (kind) {
            case AGENT -> resolveAgent(key, true);
            case CHAT -> resolveChat(key, true);
            case USER -> resolveUser(key, true);
        };
    }

    /**
     * Explicitly create a user workspace. The parent structural directories
     * are created as needed, then the final root is created with
     * {@link Files#createDirectory} so an existing root or race is
     * {@link WorkspaceResolutionException.Reason#CONFLICT}.
     */
    public WorkspaceDescriptor createUserWorkspace(String name) {
        requireKey(name, WorkspaceKind.USER);
        Path relativeRoot = WorkspacePathLayout.userRoot(name);
        Path absoluteRoot = confined(relativeRoot);
        try {
            ensureDirectoryChain(absoluteRoot.getParent());
            verifyOrAbsent(absoluteRoot);
            mutations.createDirectory(dataRoot, dataRootRelative(absoluteRoot));
        } catch (FileAlreadyExistsException e) {
            throw new WorkspaceResolutionException(
                    WorkspaceResolutionException.Reason.CONFLICT,
                    "User workspace already exists: " + name);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create user workspace: " + name, e);
        }
        return descriptor(WorkspaceKind.USER, name, verifyRoot(absoluteRoot));
    }

    /**
     * List existing user workspaces. Sorted by name; absent parent returns
     * immutable empty. Only direct non-symlink directories are returned;
     * files, symlinks, nested entries, and malformed names are ignored.
     */
    public List<WorkspaceDescriptor> listUserWorkspaces() {
        Path userParent = confined(Path.of(
                WorkspacePathLayout.WORKSPACE, WorkspaceKind.USER.token()));
        if (!Files.exists(userParent, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        verifyRoot(userParent);
        return safeList(userParent).stream()
                .map(this::listedUserDescriptor)
                .filter(d -> d != null)
                .sorted(Comparator.comparing(WorkspaceDescriptor::key))
                .toList();
    }

    /**
     * Publication-only: lazily ensure the resolved descriptor root exists
     * (agent/chat only), require user declaration, then create and return
     * {@code <root>/outputs}.
     */
    public Path outputs(WorkspaceDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        validateDescriptor(descriptor);
        WorkspaceDescriptor current = switch (descriptor.kind()) {
            case AGENT, CHAT -> resolveForWrite(descriptor.kind(), descriptor.key());
            case USER -> resolveForRead(WorkspaceKind.USER, descriptor.key());
        };
        Path outputsDir = confinedChild(current.root(), WorkspacePathLayout.OUTPUTS);
        return ensureDirectory(outputsDir);
    }

    public Path dataRoot() {
        return dataRoot;
    }

    // ── Trusted task-run child locations ──

    /** Resolve a canonical task-run path without creating any directory. */
    public TaskRunLocation resolveTaskRunForRead(
            WorkspaceDescriptor descriptor,
            TaskIdentity taskIdentity,
            TaskPathKey expectedTaskPathKey,
            String taskRunId) {
        validateTaskCapable(descriptor);
        TaskPathKey canonicalKey = validateTaskIdentity(taskIdentity, expectedTaskPathKey);
        WorkspacePathLayout.validateRunId(taskRunId);
        Path confined = confinedRelativePath(descriptor.root(),
                WorkspacePathLayout.taskRunDir(canonicalKey, taskRunId));
        verifyOrAbsent(confined);
        return taskRunLocation(descriptor, taskIdentity, canonicalKey, taskRunId, confined);
    }

    /**
     * Provision only the canonical run hierarchy. Run-local outputs and staging
     * remain lazy and are not created by this Phase 01 operation.
     */
    public TaskRunLocation provisionTaskRun(
            WorkspaceDescriptor descriptor,
            TaskIdentity taskIdentity,
            TaskPathKey expectedTaskPathKey,
            String taskRunId) {
        validateTaskCapable(descriptor);
        TaskPathKey canonicalKey = validateTaskIdentity(taskIdentity, expectedTaskPathKey);
        WorkspacePathLayout.validateRunId(taskRunId);
        Path confined = confinedRelativePath(descriptor.root(),
                WorkspacePathLayout.taskRunDir(canonicalKey, taskRunId));
        Path verified = ensureDirectoryChain(confined);
        return taskRunLocation(descriptor, taskIdentity, canonicalKey, taskRunId, verified);
    }

    /** Resolve the lazy run-local output location without creating it. */
    public RunOutputLocation resolveRunOutput(TaskRunLocation runLocation) {
        Objects.requireNonNull(runLocation, "runLocation");
        WorkspaceDescriptor descriptor = descriptor(
                runLocation.workspaceKind(), runLocation.workspaceKey(),
                confined(switch (runLocation.workspaceKind()) {
                    case AGENT -> WorkspacePathLayout.agentRoot(runLocation.workspaceKey());
                    case USER -> WorkspacePathLayout.userRoot(runLocation.workspaceKey());
                    case CHAT -> throw new IllegalArgumentException(
                            "CHAT workspaces are not task-selectable");
                }));
        validateTaskCapable(descriptor);
        TaskRunLocation canonicalRun = resolveTaskRunForRead(
                descriptor, runLocation.taskIdentity(), runLocation.taskPathKey(),
                runLocation.taskRunId());
        if (!canonicalRun.equals(runLocation)
                || !canonicalRun.rootRelativePath().equals(runLocation.rootRelativePath())
                || !canonicalRun.confinedPath().equals(runLocation.confinedPath())) {
            throw new IllegalArgumentException("Task-run location is not canonical for its identity");
        }
        Path confined = canonicalRun.confinedPath().resolve(WorkspacePathLayout.OUTPUTS).normalize();
        verifyOrAbsent(confined);
        return new RunOutputLocation(canonicalRun,
                canonicalRun.rootRelativePath() + "/" + WorkspacePathLayout.OUTPUTS,
                confined);
    }

    /** Resolve a workspace-local reserved skill root without creating it. */
    public Path resolveSkillsRoot(WorkspaceDescriptor descriptor) {
        validateTaskCapable(descriptor);
        Path confined = confinedRelativePath(descriptor.root(), WorkspacePathLayout.skillsRoot());
        return verifyOrAbsent(confined);
    }

    private void validateTaskCapable(WorkspaceDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        validateDescriptor(descriptor);
        if (descriptor.kind() == WorkspaceKind.CHAT) {
            throw new IllegalArgumentException("CHAT workspaces are not task-selectable");
        }
    }

    private TaskPathKey validateTaskIdentity(
            TaskIdentity taskIdentity, TaskPathKey expectedTaskPathKey) {
        Objects.requireNonNull(taskIdentity, "taskIdentity");
        Objects.requireNonNull(expectedTaskPathKey, "expectedTaskPathKey");
        TaskPathKey canonical = TaskPathKey.from(taskIdentity);
        if (!canonical.equals(expectedTaskPathKey)) {
            throw new IllegalArgumentException("taskPathKey does not match the canonical task identity");
        }
        return canonical;
    }

    private TaskRunLocation taskRunLocation(
            WorkspaceDescriptor descriptor, TaskIdentity identity, TaskPathKey key,
            String taskRunId, Path confined) {
        return new TaskRunLocation(descriptor.kind(), descriptor.key(), identity, key,
                taskRunId, dataRootRelative(confined), confined);
    }

    private Path confinedRelativePath(Path root, Path relative) {
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Child path must be workspace-relative");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || !resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Path escapes workspace root");
        }
        return resolved;
    }

    private String dataRootRelative(Path confined) {
        return dataRoot.relativize(confined).normalize().toString().replace('\\', '/');
    }

    // ── Canonical agent hard-delete helper ──

    /** Deletes only the canonical agent root and never follows symbolic links. */
    public void deleteAgentRoot(String agentId) {
        requireKey(agentId, WorkspaceKind.AGENT);
        Path root = confined(WorkspacePathLayout.agentRoot(agentId));
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Canonical agent root must not be a symbolic link: " + root);
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                mutations.delete(dataRoot, dataRootRelative(path),
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete canonical agent workspace: " + agentId, e);
        }
    }

    // ── Descriptor-relative path helper ──

    /**
     * Resolve a confined, non-traversing path relative to a descriptor root.
     * Rejects blank, absolute, traversal, backslash-escaped, and symlink
     * component paths.
     */
    public Path confinedRelative(WorkspaceDescriptor descriptor, String relativePath) {
        Objects.requireNonNull(descriptor, "descriptor");
        validateDescriptor(descriptor);
        if (!StringUtils.hasText(relativePath) || relativePath.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("relative path is required and must use forward separators");
        }
        Path relative = Path.of(relativePath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Path must be relative to the workspace root");
        }
        for (Path segment : relative) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("Path escapes workspace root");
            }
        }
        Path root = verifyRoot(descriptor.root());
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace root");
        }
        Path current = root;
        for (Path segment : root.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalArgumentException("Symlinks are not allowed: " + current);
                }
                try {
                    if (!current.toRealPath().startsWith(root)) {
                        throw new IllegalArgumentException("Path escapes workspace root");
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to resolve path: " + current, e);
                }
            }
        }
        return resolved;
    }

    // ── Descriptor helpers ──

    private WorkspaceDescriptor resolveAgent(String agentId, boolean create) {
        if (!agentProfileRepository.existsById(agentId)) {
            throw new WorkspaceResolutionException(
                    WorkspaceResolutionException.Reason.NOT_FOUND,
                    "Agent not found: " + agentId);
        }
        Path relativeRoot = WorkspacePathLayout.agentRoot(agentId);
        Path absoluteRoot = confined(relativeRoot);
        if (create && !Files.exists(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectoryChain(absoluteRoot);
        }
        Path verified = create ? verifyRoot(absoluteRoot)
                : verifyOrAbsent(absoluteRoot);
        return descriptor(WorkspaceKind.AGENT, agentId, verified);
    }

    private WorkspaceDescriptor resolveChat(String conversationId, boolean create) {
        if (!chatConversationExistenceRepository.existsByConversationId(conversationId)) {
            throw new WorkspaceResolutionException(
                    WorkspaceResolutionException.Reason.NOT_FOUND,
                    "Chat conversation not found: " + conversationId);
        }
        Path relativeRoot = WorkspacePathLayout.chatRoot(conversationId);
        Path absoluteRoot = confined(relativeRoot);
        if (create && !Files.exists(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            ensureDirectoryChain(absoluteRoot);
        }
        Path verified = create ? verifyRoot(absoluteRoot)
                : verifyOrAbsent(absoluteRoot);
        return descriptor(WorkspaceKind.CHAT, conversationId, verified);
    }

    private WorkspaceDescriptor resolveUser(String name, boolean create) {
        Path relativeRoot = WorkspacePathLayout.userRoot(name);
        Path absoluteRoot = confined(relativeRoot);
        if (!Files.isDirectory(absoluteRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceResolutionException(
                    WorkspaceResolutionException.Reason.NOT_FOUND,
                    "User workspace not found: " + name);
        }
        Path real = verifyRoot(absoluteRoot);
        return descriptor(WorkspaceKind.USER, name, real);
    }

    void validateDescriptor(WorkspaceDescriptor descriptor) {
        requireKey(descriptor.key(), descriptor.kind());
        Path expected = confined(switch (descriptor.kind()) {
            case AGENT -> WorkspacePathLayout.agentRoot(descriptor.key());
            case CHAT -> WorkspacePathLayout.chatRoot(descriptor.key());
            case USER -> WorkspacePathLayout.userRoot(descriptor.key());
        });
        if (!descriptor.root().equals(expected)) {
            throw new IllegalArgumentException("Descriptor root does not match its identity");
        }
    }

    private WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root);
    }

    private void requireKey(String key, WorkspaceKind kind) {
        Objects.requireNonNull(kind, "kind");
        String label = switch (kind) {
            case AGENT -> "agentId";
            case CHAT -> "conversationId";
            case USER -> "userName";
        };
        PlainPathSegmentValidator.requirePlainSegment(key, label);
    }

    // ── Confinement ──

    /**
     * Verify a root exists and is a confined non-symlink directory.
     */
    private Path verifyRoot(Path absoluteRoot) {
        Path normalized = verifyOrAbsent(absoluteRoot);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Workspace root does not exist: " + normalized);
        }
        try {
            Path real = normalized.toRealPath();
            if (!real.startsWith(dataRoot) || !Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Workspace root is not a confined directory: " + normalized);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve workspace root: " + normalized, e);
        }
    }

    /**
     * Verify lexical confinement when root may be absent. Every existing
     * ancestor is checked with NOFOLLOW_LINKS.
     */
    private Path verifyOrAbsent(Path absoluteRoot) {
        Path normalized = absoluteRoot.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Workspace root escapes data root: " + normalized);
        }
        Path ancestor = dataRoot;
        for (Path segment : dataRoot.relativize(normalized)) {
            ancestor = ancestor.resolve(segment);
            if (Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(ancestor)
                        || !Files.isDirectory(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException(
                            "Workspace path component is not a direct directory: " + ancestor);
                }
                try {
                    Path real = ancestor.toRealPath();
                    if (!real.startsWith(dataRoot)) {
                        throw new IllegalArgumentException("Ancestor escapes data root: " + real);
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException(
                            "Failed to resolve ancestor: " + ancestor, e);
                }
            }
        }
        return normalized;
    }

    private Path confined(Path relative) {
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("Workspace path must be relative to data root");
        }
        Path resolved = dataRoot.resolve(relative).normalize();
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Workspace path escapes data root: " + relative);
        }
        return resolved;
    }

    private Path confinedChild(Path parent, String child) {
        Path resolved = parent.resolve(child).normalize();
        if (!resolved.startsWith(parent)) {
            throw new IllegalArgumentException("Workspace child path escapes parent: " + child);
        }
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Workspace child path escapes data root: " + child);
        }
        return resolved;
    }

    // ── Internal helpers ──

    private WorkspaceDescriptor listedUserDescriptor(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        String name = path.getFileName().toString();
        try {
            PlainPathSegmentValidator.requirePlainSegment(name, "userName");
            return descriptor(WorkspaceKind.USER, name, verifyRoot(path));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private List<Path> safeList(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list user workspaces", e);
        }
    }

    private Path ensureDirectory(Path path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return verifyRoot(path);
        }
        try {
            mutations.createDirectory(dataRoot, dataRootRelative(path));
        } catch (FileAlreadyExistsException ignored) {
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + path, e);
        }
        return verifyRoot(path);
    }

    private Path ensureDirectoryChain(Path target) {
        Path normalized = verifyOrAbsent(target);
        Path current = dataRoot;
        for (Path segment : dataRoot.relativize(normalized)) {
            current = current.resolve(segment);
            ensureDirectory(current);
        }
        return verifyRoot(normalized);
    }
}
