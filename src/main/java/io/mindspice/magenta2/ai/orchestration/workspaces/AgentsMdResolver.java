package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Resolves AGENTS.md layers confined to a bound root and active path.
 *
 * <p>Magenta keeps all ancestor layers from the bound root to the active path
 * and uses closest-wins only for conflicts.
 */
@Service
public class AgentsMdResolver {
    private static final String AGENTS_FILE = "AGENTS.md";

    public AgentsMdResolution resolve(Path boundRoot, Path activePath) throws IOException {
        Path realBoundRoot = requireExistingDirectory(boundRoot, "bound root");
        Path resolvedActivePath = resolveConfinedPath(realBoundRoot, activePath, "active path");
        Path lookupDirectory = lookupDirectoryFor(resolvedActivePath);
        List<AgentsMdLayer> layers = collectLayers(realBoundRoot, lookupDirectory);
        return new AgentsMdResolution(realBoundRoot, resolvedActivePath, layers);
    }

    /**
     * Runtime binding helper for model-backed execution contexts.
     *
     * <p>Returns empty when the execution context has no filesystem root binding.
     */
    public Optional<AgentsMdResolution> resolveForContext(
        OrchestrationTaskContext context,
        String activePath
    ) throws IOException {
        if (context == null) {
            return Optional.empty();
        }
        String boundRootText = boundRootText(context);
        if (!StringUtils.hasText(boundRootText)) {
            return Optional.empty();
        }
        Path boundRoot = requireExistingDirectory(Path.of(boundRootText), "bound root");
        Path resolvedActivePath = resolveContextActivePath(context, activePath);
        return Optional.of(resolve(boundRoot, resolvedActivePath));
    }

    private Path resolveContextActivePath(
        OrchestrationTaskContext context,
        String activePath
    ) throws IOException {
        Path workspaceRoot = requireExistingDirectory(Path.of(requiredText(contextWorkspacePath(context),
            "active durable workspace path")), "active durable workspace");
        String requested = StringUtils.hasText(activePath) ? activePath.trim() : WorkspacePathLayout.WORKSPACE;
        String normalized = normalizeRequest(requested);
        if (isAliasRoot(normalized, WorkspacePathLayout.WORKSPACE)) {
            return workspaceRoot;
        }
        if (normalized.startsWith(WorkspacePathLayout.WORKSPACE + "/")) {
            return resolveScoped(workspaceRoot, normalized.substring((WorkspacePathLayout.WORKSPACE + "/").length()),
                "active durable workspace");
        }
        if (WorkspacePathLayout.ROOT_ALIAS.equals(normalized) || normalized.startsWith(WorkspacePathLayout.ROOT_ALIAS + "/")) {
            Path ownerRoot = requireExistingDirectory(
                Path.of(requiredText(contextRootPath(context), "active owner root path")),
                "active owner root");
            String remainder = WorkspacePathLayout.ROOT_ALIAS.equals(normalized)
                ? ""
                : normalized.substring((WorkspacePathLayout.ROOT_ALIAS + "/").length());
            return resolveScoped(ownerRoot, remainder, "active owner root");
        }
        if (WorkspacePathLayout.OUTPUTS.equals(normalized) || normalized.startsWith(WorkspacePathLayout.OUTPUTS + "/")) {
            Path outputRoot = requireExistingDirectory(Path.of(requiredText(context.hostOutputPath(),
                "active output path")), "active output");
            String remainder = WorkspacePathLayout.OUTPUTS.equals(normalized)
                ? ""
                : normalized.substring((WorkspacePathLayout.OUTPUTS + "/").length());
            return resolveScoped(outputRoot, remainder, "active output");
        }
        if (WorkspacePathLayout.RUN_ALIAS.equals(normalized) || normalized.startsWith(WorkspacePathLayout.RUN_ALIAS + "/")) {
            Path runRoot = requireExistingDirectory(Path.of(requiredText(contextRunPath(context),
                "active run path")), "active run");
            String remainder = WorkspacePathLayout.RUN_ALIAS.equals(normalized)
                ? ""
                : normalized.substring((WorkspacePathLayout.RUN_ALIAS + "/").length());
            return resolveScoped(runRoot, remainder, "active run");
        }
        if (normalized.startsWith(WorkspacePathLayout.PROJECTS + "/")) {
            String requiredProjectPrefix = WorkspacePathLayout.PROJECTS + "/" + requiredText(context.projectId(), "projectId");
            if (!normalized.equals(requiredProjectPrefix) && !normalized.startsWith(requiredProjectPrefix + "/")) {
                throw new IllegalArgumentException("Project path is not bound to this execution context: " + activePath);
            }
            Path ownerRoot = requireExistingDirectory(
                Path.of(requiredText(contextRootPath(context), "active owner root path")),
                "active owner root");
            return resolveScoped(ownerRoot, normalized, "active owner root");
        }
        return resolveScoped(workspaceRoot, normalized, "active durable workspace");
    }

    private List<AgentsMdLayer> collectLayers(Path realBoundRoot, Path lookupDirectory) throws IOException {
        List<Path> candidateDirectories = directoriesFromRoot(realBoundRoot, lookupDirectory);
        List<AgentsMdLayer> layers = new ArrayList<>();
        int rank = 0;
        for (Path directory : candidateDirectories) {
            Path agentsPath = directory.resolve(AGENTS_FILE);
            if (!Files.isRegularFile(agentsPath, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path realAgentsPath = agentsPath.toRealPath();
            if (!realAgentsPath.startsWith(realBoundRoot)) {
                throw new IllegalArgumentException("AGENTS.md escapes bound root: " + agentsPath);
            }
            String relativeDirectory = normalizeRelative(realBoundRoot.relativize(directory));
            String content = Files.readString(realAgentsPath);
            layers.add(new AgentsMdLayer(realAgentsPath, relativeDirectory, content, rank));
            rank++;
        }
        return List.copyOf(layers);
    }

    private List<Path> directoriesFromRoot(Path root, Path targetDirectory) {
        List<Path> reversed = new ArrayList<>();
        Path current = targetDirectory;
        while (current != null && current.startsWith(root)) {
            reversed.add(current);
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        List<Path> ordered = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return ordered;
    }

    private Path lookupDirectoryFor(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                return path.toRealPath();
            }
            Path parent = path.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("active path has no parent directory");
            }
            return parent.toRealPath();
        }
        Path parent = nearestExistingParent(path);
        return parent.toRealPath();
    }

    private Path resolveConfinedPath(Path root, Path candidate, String label) throws IOException {
        if (candidate == null) {
            return root;
        }
        Path normalized = candidate.isAbsolute() ? candidate.normalize() : root.resolve(candidate).normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes bound root");
        }
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Path real = normalized.toRealPath();
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException(label + " escapes bound root");
            }
            return real;
        }
        Path existingParent = nearestExistingParent(normalized);
        Path parentReal = existingParent.toRealPath();
        if (!parentReal.startsWith(root)) {
            throw new IllegalArgumentException(label + " escapes bound root");
        }
        return normalized;
    }

    private Path resolveScoped(Path scopeRoot, String relativePath, String label) throws IOException {
        rejectUnsafeRelativePath(relativePath, label);
        Path resolved = scopeRoot.resolve(relativePath).normalize();
        return resolveConfinedPath(scopeRoot, resolved, label);
    }

    private Path requireExistingDirectory(Path path, String label) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        Path real = path.toRealPath();
        if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(label + " is not a directory");
        }
        return real;
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

    private String contextWorkspacePath(OrchestrationTaskContext context) {
        return context.hostDurableWorkspacePath();
    }

    private String contextRunPath(OrchestrationTaskContext context) {
        return StringUtils.hasText(context.hostRunPath())
            ? context.hostRunPath()
            : context.hostDurableWorkspacePath();
    }

    private String contextRootPath(OrchestrationTaskContext context) {
        return StringUtils.hasText(context.hostRootPath())
            ? context.hostRootPath()
            : contextWorkspacePath(context);
    }

    private String boundRootText(OrchestrationTaskContext context) {
        if (StringUtils.hasText(context.hostRootPath())) {
            return context.hostRootPath();
        }
        return context.hostDurableWorkspacePath();
    }

    private String normalizeRequest(String requested) {
        String normalized = requested.replace('\\', '/').trim();
        if (normalized.startsWith("/")) {
            throw new IllegalArgumentException("Absolute paths are not allowed for AGENTS.md resolution: " + requested);
        }
        if (normalized.contains("//")) {
            throw new IllegalArgumentException("AGENTS.md active path is invalid: " + requested);
        }
        return normalized;
    }

    private boolean isAliasRoot(String normalized, String alias) {
        return normalized.isEmpty() || ".".equals(normalized) || alias.equals(normalized);
    }

    private void rejectUnsafeRelativePath(String relativePath, String label) {
        String normalized = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (normalized.equals("..")
            || normalized.startsWith("../")
            || normalized.endsWith("/..")
            || normalized.contains("/../")) {
            throw new IllegalArgumentException(label + " path escapes its scope");
        }
    }

    private String requiredText(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value;
    }

    private String normalizeRelative(Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        return ".".equals(normalized) ? "" : normalized;
    }
}
