package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
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
 * <h3>Phase 02 trusted roots</h3>
 * <ul>
 *   <li>Agent: {@code workspace/agent/<id>}</li>
 *   <li>Chat:  {@code workspace/chat/<id>}</li>
 *   <li>User:  {@code workspace/user/<name>}</li>
 * </ul>
 */
@Service
public class WorkspaceDirectoryService {
    private final Path dataRoot;
    private final AgentProfileRepository agentProfileRepository;
    private final ChatConversationExistenceRepository chatConversationExistenceRepository;

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
    }

    // ── Phase 02 descriptor resolution ──

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
            Files.createDirectory(absoluteRoot);
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

    private void validateDescriptor(WorkspaceDescriptor descriptor) {
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
        // Verify every existing ancestor is a non-symlink directory
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

    private Path confined(String relativePath) {
        return confined(Path.of(relativePath));
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

    // ── Descriptor-relative path helper for chat files ──

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

    // ── Legacy methods preserved until Phase 06 ──

    /**
     * Agent execution root (legacy path). Creates the directory if missing.
     * No starter file is generated.
     * @deprecated New code should use {@link #resolveForRead} or {@link #resolveForWrite}
     * with {@link WorkspaceKind#AGENT}.
     */
    @Deprecated
    public Path agentWorkspace(String agentId) {
        requireId(agentId, "agentId");
        Path workspaceDir = confined(WorkspacePathLayout.agentWorkspaceRoot(agentId));
        return ensureDir(workspaceDir);
    }

    /**
     * @deprecated Replaced by {@link #agentWorkspace(String)}. Retained only
     * for warm data roots that still reference the pre-workspace layout.
     */
    @Deprecated
    public Path agentHome(String agentId) {
        requireId(agentId, "agentId");
        return ensureDir(confined(WorkspacePathLayout.legacyAgentHome(agentId)));
    }

    public Path agentWorkspaceRoot(String agentId) {
        return agentWorkspace(agentId);
    }

    public Path agentWorkspaceOutputs(String agentId) {
        requireId(agentId, "agentId");
        return ensureDir(confined(WorkspacePathLayout.agentFinalOutputs(agentId)));
    }

    public Path agentProjectLinks(String agentId) {
        requireId(agentId, "agentId");
        return ensureDir(confined(WorkspacePathLayout.agentProjectLinks(agentId)));
    }

    public Path agentScratch(String agentId) {
        requireId(agentId, "agentId");
        return ensureDir(confined(WorkspacePathLayout.agentWorkspaceRoot(agentId).resolve(WorkspacePathLayout.LEGACY_SCRATCH)));
    }

    public Path workDir(Path workspaceRoot) {
        return ensureDir(confinedWorkspaceChild(workspaceRoot, WorkspacePathLayout.WORK));
    }

    public Path outputsDir(Path workspaceRoot) {
        return ensureDir(confinedWorkspaceChild(workspaceRoot, WorkspacePathLayout.OUTPUTS));
    }

    public Path runsDir(Path workspaceRoot) {
        return ensureDir(confinedWorkspaceChild(workspaceRoot, WorkspacePathLayout.RUNS));
    }

    public Path scratchDir(Path workspaceRoot) {
        return ensureDir(confinedWorkspaceChild(workspaceRoot, WorkspacePathLayout.LEGACY_SCRATCH));
    }

    public Path taskOutput(Path workspaceRoot, String taskId, String runId) {
        requireId(taskId, "taskId");
        requireId(runId, "runId");
        return ensureDir(confinedWorkspaceChild(outputsDir(workspaceRoot), "tasks", taskId, runId));
    }

    public Path workflowOutput(Path workspaceRoot, String workflowId, String runId) {
        requireId(workflowId, "workflowId");
        requireId(runId, "runId");
        return ensureDir(confinedWorkspaceChild(outputsDir(workspaceRoot), "workflows", workflowId, runId));
    }

    public Path jobAssignmentOutput(Path workspaceRoot, String jobAssignmentId, String runId) {
        requireId(jobAssignmentId, "jobAssignmentId");
        requireId(runId, "runId");
        return ensureDir(confinedWorkspaceChild(outputsDir(workspaceRoot), "jobs", jobAssignmentId, runId));
    }

    public Path jobAssignmentWorkspace(Path workspaceRoot, String jobAssignmentId) {
        requireId(jobAssignmentId, "jobAssignmentId");
        return ensureDir(confinedWorkspaceChild(workspaceRoot, "jobs", jobAssignmentId));
    }

    /**
     * @deprecated Replaced by {@link #agentWorkspaceOutputs(String)}.
     * Retained only for warm data roots that still reference the legacy output layout.
     */
    @Deprecated
    public Path agentOutputRoot(String agentId) {
        requireId(agentId, "agentId");
        return ensureDir(confined(WorkspacePathLayout.legacyAgentOutputs(agentId)));
    }

    /**
     * Output directory for an agent's run. Never deleted after terminal state.
     * Layout: data/workspace/{agentId}/outputs/{slug}-{runId}/
     */
    public Path agentOutput(String agentId, String planSlug, String runId) {
        requireId(agentId, "agentId");
        requireId(runId, "runId");
        String slug = StringUtils.hasText(planSlug) ? sanitize(planSlug) : "run";
        return ensureDir(confined(WorkspacePathLayout.agentFinalOutputs(agentId).resolve(slug + "-" + runId)));
    }

    /**
     * Migrates legacy {@code agents/<id>/home} and {@code agents/<id>/outputs}
     * into the workspace tree. Safe to call repeatedly — only performs work
     * when legacy directories exist and their workspace counterparts do not.
     */
    public void migrateLegacyAgentDirs(String agentId) {
        requireId(agentId, "agentId");
        Path workspaceDir = confined(WorkspacePathLayout.agentWorkspaceRoot(agentId));
        if (Files.exists(workspaceDir)) {
            return; // already migrated
        }
        Path legacyWorkspace = confined(WorkspacePathLayout.legacyAgentWorkspaceRoot(agentId));
        Path legacyHome = confined(WorkspacePathLayout.legacyAgentHome(agentId));
        Path legacyOutputs = confined(WorkspacePathLayout.legacyAgentOutputs(agentId));
        try {
            if (Files.isDirectory(legacyWorkspace)) {
                Files.createDirectories(workspaceDir.getParent());
                Files.move(legacyWorkspace, workspaceDir);
                return;
            }
            if (Files.isDirectory(legacyHome)) {
                Files.createDirectories(workspaceDir.getParent());
                Files.move(legacyHome, workspaceDir);
            }
            if (Files.isDirectory(legacyOutputs)) {
                Path wsOutputs = workspaceDir.resolve(WorkspacePathLayout.OUTPUTS);
                if (!Files.exists(wsOutputs)) {
                    Files.createDirectories(wsOutputs.getParent());
                    Files.move(legacyOutputs, wsOutputs);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to migrate legacy agent directories for " + agentId, e);
        }
    }

    // ── Task / Workflow temp ──

    /**
     * Temporary workspace for a single task run. Deleted after terminal state.
     * Layout: data/runtime/task-runs/{runId}
     */
    public Path taskTemp(String runId) {
        requireId(runId, "runId");
        return ensureDir(confined(WorkspacePathLayout.legacyTaskRun(runId)));
    }

    /**
     * Returns the path for a task temp directory without creating it.
     * Use for cleanup or path computation when the directory may not exist.
     */
    public String taskTempPath(String runId) {
        requireId(runId, "runId");
        return confined(WorkspacePathLayout.legacyTaskRun(runId)).toString();
    }

    /**
     * Temporary workspace for a workflow run. Persists between workflow steps,
     * deleted after terminal workflow completion.
     * Layout: data/runtime/workflow-runs/{runId}
     */
    public Path workflowTemp(String runId) {
        requireId(runId, "runId");
        return ensureDir(confined(WorkspacePathLayout.legacyWorkflowRun(runId)));
    }

    /**
     * Persistent file directory for an ordinary chat conversation.
     * @deprecated Use {@link #resolveForRead} or {@link #resolveForWrite} with
     * {@link WorkspaceKind#CHAT} for new code. Retained for legacy callers.
     */
    @Deprecated
    public Path chatFiles(String conversationId) {
        requireId(conversationId, "conversationId");
        return ensureDir(confined(WorkspacePathLayout.chatFiles(conversationId)));
    }

    // ── Job ──

    public Path jobWorkspace(String jobId) {
        requireId(jobId, "jobId");
        throw new IllegalStateException(
            "Job-owned workspace directories are retired; bind jobs to an agent, project, or Work Area instead.");
    }

    public Path jobOutput(String jobId, String planSlug, String runId) {
        requireId(jobId, "jobId");
        requireId(runId, "runId");
        throw new IllegalStateException(
            "Job-owned output directories are retired; route job outputs through the bound workspace instead.");
    }

    // ── Project ──

    public Path projectWorkspace(String projectId) {
        requireId(projectId, "projectId");
        return ensureDir(confined(WorkspacePathLayout.projectRoot(projectId)));
    }

    public Path projectWorkspaceRoot(String projectId) {
        return projectWorkspace(projectId);
    }

    /**
     * Materializes the current project workspace under an assignment temp
     * workspace as {@code projects/<projectId>}. The returned path is the
     * visible assignment-relative link; callers should remove it before
     * releasing the corresponding project workspace lease.
     */
    public Path materializeAssignmentProjectLink(String assignmentWorkspacePath, String projectId) {
        requireId(projectId, "projectId");
        try {
            Path assignmentWorkspace = existingConfinedDirectory(assignmentWorkspacePath, "assignment workspace");
            Path projectsDir = ensureDir(confinedChild(assignmentWorkspace, WorkspacePathLayout.PROJECTS));
            Path link = confinedChild(projectsDir, projectId);
            Path target = projectWorkspace(projectId).toRealPath();
            removeExistingProjectLink(link, target);
            try {
                Files.createSymbolicLink(link, target);
            } catch (UnsupportedOperationException | FileSystemException e) {
                throw new IllegalStateException(
                    "Project workspace links require filesystem symlink support: " + link, e);
            }
            return link;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize project workspace link for " + projectId, e);
        }
    }

    public Path requireAssignmentProjectLinkTarget(String assignmentWorkspacePath, String projectId) {
        requireId(projectId, "projectId");
        try {
            Path assignmentWorkspace = existingConfinedDirectory(assignmentWorkspacePath, "assignment workspace");
            Path projectsDir = confinedChild(assignmentWorkspace, WorkspacePathLayout.PROJECTS);
            Path link = confinedChild(projectsDir, projectId);
            if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("Project workspace is not materialized for this assignment: " + projectId);
            }
            Path expectedTarget = projectWorkspace(projectId).toRealPath();
            Path actualTarget = link.toRealPath();
            if (!actualTarget.equals(expectedTarget)) {
                throw new IllegalArgumentException("Project workspace link target does not match leased project: " + projectId);
            }
            return actualTarget;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to resolve project workspace link for " + projectId, e);
        }
    }

    public void removeAssignmentProjectLink(String assignmentWorkspacePath, String projectId) {
        requireId(projectId, "projectId");
        if (!StringUtils.hasText(assignmentWorkspacePath)) {
            return;
        }
        Path assignmentWorkspace = Path.of(assignmentWorkspacePath).toAbsolutePath().normalize();
        if (!assignmentWorkspace.startsWith(dataRoot) || !Files.exists(assignmentWorkspace, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Path projectsDir = confinedChild(assignmentWorkspace, WorkspacePathLayout.PROJECTS);
            Path link = confinedChild(projectsDir, projectId);
            if (Files.isSymbolicLink(link)) {
                Files.deleteIfExists(link);
            } else if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Refusing to remove non-link project path: " + link);
            }
            try {
                Files.deleteIfExists(projectsDir);
            } catch (IOException ignored) {
                // Directory is not empty; leave other assignment-local project links alone.
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to remove project workspace link for " + projectId, e);
        }
    }

    // ── Input file resolution ──

    /**
     * Resolves a relative or workspace-absolute input path to a real file system
     * path. The input path must be confined under dataRoot. If the path is
     * absolute, it is normalized and checked. If relative, it is resolved
     * against the given base workspace directory.
     */
    public Path resolveInputPath(String baseWorkspaceDir, String inputPath) {
        if (!StringUtils.hasText(inputPath)) {
            throw new IllegalArgumentException("inputPath is required");
        }
        Path path = Path.of(inputPath);
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path.normalize();
        } else {
            resolved = confined(baseWorkspaceDir).resolve(path).normalize();
        }
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Input path escapes data root: " + inputPath);
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Input file does not exist: " + resolved);
        }
        return resolved;
    }

    // ── Cleanup ──

    /**
     * Deletes a temp directory recursively. Never deletes output directories.
     * Safe — rejects paths that are neither legacy {@code data/runtime/}
     * directories nor run-local {@code data/workspace/<agent>/runs/<runId>}
     * staging roots.
     */
    public void deleteTempDir(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        Path normalized = dir.toRealPath();
        Path runtimeDir = dataRoot.resolve(WorkspacePathLayout.LEGACY_RUNTIME);
        boolean legacyRuntimeTemp = Files.exists(runtimeDir) && normalized.startsWith(runtimeDir.toRealPath());
        boolean runLocalStaging = isRunLocalStagingRoot(normalized);
        if (!legacyRuntimeTemp && !runLocalStaging) {
            throw new IllegalArgumentException(
                "Refusing to delete non-temp directory: " + normalized);
        }
        try (var stream = Files.walk(normalized)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // best effort
                    }
                });
        }
    }

    private boolean isRunLocalStagingRoot(Path normalized) {
        if (!normalized.startsWith(dataRoot)) {
            return false;
        }
        if (WorkspacePathLayout.OUTPUTS.equals(fileName(normalized))) {
            return false;
        }
        Path parent = normalized.getParent();
        return parent != null
            && WorkspacePathLayout.RUNS.equals(fileName(parent))
            && parent.getParent() != null
            && parent.getParent().getParent() != null
            && WorkspacePathLayout.WORKSPACE.equals(fileName(parent.getParent().getParent()));
    }

    private String fileName(Path path) {
        return path == null || path.getFileName() == null ? null : path.getFileName().toString();
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
            Files.createDirectory(path);
        } catch (FileAlreadyExistsException ignored) {
            // A concurrent creator won the race; verify its result below.
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

    private Path existingConfinedDirectory(String path, String label) throws IOException {
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path raw = Path.of(path);
        Path normalized = raw.isAbsolute() ? raw.normalize() : dataRoot.resolve(raw).normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new IllegalArgumentException(label + " escapes data root");
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(dataRoot)) {
            throw new IllegalArgumentException(label + " escapes data root");
        }
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " is not a directory");
        }
        return real;
    }

    private Path confinedWorkspaceChild(Path workspaceRoot, String first, String... more) {
        requireId(first, "workspaceChild");
        Path root = confinedWorkspaceRoot(workspaceRoot);
        Path resolved = root.resolve(first);
        for (String segment : more) {
            requireId(segment, "workspaceChild");
            resolved = resolved.resolve(segment);
        }
        resolved = resolved.normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Workspace child path escapes workspace root");
        }
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Workspace child path escapes data root");
        }
        return resolved;
    }

    private Path confinedWorkspaceRoot(Path workspaceRoot) {
        if (workspaceRoot == null) {
            throw new IllegalArgumentException("workspaceRoot is required");
        }
        Path normalized = workspaceRoot.isAbsolute()
            ? workspaceRoot.normalize()
            : dataRoot.resolve(workspaceRoot).normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new IllegalArgumentException("workspaceRoot escapes data root");
        }
        try {
            Path realRoot = Files.createDirectories(normalized).toRealPath();
            if (!realRoot.startsWith(dataRoot)) {
                throw new IllegalArgumentException("workspaceRoot escapes data root");
            }
            return realRoot;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + normalized, e);
        }
    }

    private void removeExistingProjectLink(Path link, Path expectedTarget) throws IOException {
        if (!Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isSymbolicLink(link)) {
            throw new IllegalStateException("Refusing to replace non-link project path: " + link);
        }
        Path actualTarget = link.toRealPath();
        if (!actualTarget.equals(expectedTarget)) {
            throw new IllegalStateException("Refusing to replace project link with unexpected target: " + link);
        }
        Files.delete(link);
    }

    private Path ensureDir(Path path) {
        try {
            return Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create directory: " + path, e);
        }
    }

    private void requireId(String value, String label) {
        PlainPathSegmentValidator.requirePlainSegment(value, label);
    }

    private String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").replaceAll("_+", "_");
    }

}
