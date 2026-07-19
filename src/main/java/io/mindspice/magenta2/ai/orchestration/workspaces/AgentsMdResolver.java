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
 * <p>Walks ancestors from the bound root to the confined target directory,
 * collecting user-authored AGENTS.md files in root-to-leaf order.
 * No starter file is generated and no alias escapes the root.
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
        Path resolvedActivePath = resolveContextActivePath(context, boundRoot, activePath);
        return Optional.of(resolve(boundRoot, resolvedActivePath));
    }

    /**
     * Resolve using a descriptor as the bound root. The active path is
     * resolved relative to the descriptor root.
     */
    public AgentsMdResolution resolveForDescriptor(
        WorkspaceDescriptor descriptor,
        String relativePath
    ) throws IOException {
        if (descriptor == null) {
            throw new IllegalArgumentException("descriptor is required");
        }
        Path boundRoot = requireExistingDirectory(descriptor.root(), "descriptor root");
        String requested = StringUtils.hasText(relativePath) ? relativePath.trim() : ".";
        if (requested.startsWith("/")) {
            throw new IllegalArgumentException("Absolute paths are not allowed for AGENTS.md resolution");
        }
        Path active = ".".equals(requested) ? boundRoot : boundRoot.resolve(requested).normalize();
        return resolve(boundRoot, active);
    }

    private Path resolveContextActivePath(
        OrchestrationTaskContext context,
        Path boundRoot,
        String activePath
    ) throws IOException {
        String requested = StringUtils.hasText(activePath) ? activePath.trim() : ".";
        if (requested.startsWith("/")) {
            throw new IllegalArgumentException("Absolute paths are not allowed for AGENTS.md resolution: " + activePath);
        }
        Path active = ".".equals(requested) ? boundRoot : boundRoot.resolve(requested).normalize();
        return resolveConfinedPath(boundRoot, active, "active path");
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

    private String boundRootText(OrchestrationTaskContext context) {
        return context.hostDurableWorkspacePath();
    }

    private String normalizeRelative(Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        return ".".equals(normalized) ? "" : normalized;
    }
}
