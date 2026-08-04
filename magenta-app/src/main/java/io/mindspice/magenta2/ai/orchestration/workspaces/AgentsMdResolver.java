package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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
 *
 * <p>Every candidate layer is validated: links, non-regular files, escape,
 * invalid UTF-8, and unsafe active-path ancestry are terminal. Per-layer
 * and aggregate byte limits apply with path-free truncation markers.
 */
@Service
public class AgentsMdResolver {
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final int MAX_LAYER_BYTES = 16384;   // 16 KB per layer
    private static final int MAX_AGGREGATE_BYTES = 65536; // 64 KB total
    private static final int MAX_PROMPT_CHARS = 8192;    // per-layer prompt truncation

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
        WorkspaceDescriptor descriptor = context.workspaceDescriptor();
        if (descriptor == null) {
            return Optional.empty();
        }
        return Optional.of(resolveForDescriptor(descriptor, activePath));
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

    private List<AgentsMdLayer> collectLayers(Path realBoundRoot, Path lookupDirectory) throws IOException {
        List<Path> candidateDirectories = directoriesFromRoot(realBoundRoot, lookupDirectory);
        List<AgentsMdLayer> layers = new ArrayList<>();
        int aggregateBytes = 0;
        int rank = 0;
        for (Path directory : candidateDirectories) {
            Path agentsPath = directory.resolve(AGENTS_FILE);
            if (!Files.exists(agentsPath, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            // Reject links and non-regular files
            if (Files.isSymbolicLink(agentsPath)) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md is a symbolic link and is not allowed");
            }
            if (!Files.isRegularFile(agentsPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md is not a regular file");
            }

            // Confinement check via real path
            Path realAgentsPath;
            try {
                realAgentsPath = agentsPath.toRealPath();
            } catch (IOException e) {
                throw new AgentsMdIntegrityException(
                        "Failed to resolve AGENTS.md real path", e);
            }
            if (!realAgentsPath.startsWith(realBoundRoot)) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md escapes bound root");
            }

            // Read bounded bytes
            long fileSize;
            try {
                fileSize = Files.size(realAgentsPath);
            } catch (IOException e) {
                throw new AgentsMdIntegrityException(
                        "Failed to read AGENTS.md size", e);
            }
            if (fileSize > MAX_LAYER_BYTES) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md exceeds per-layer limit of " + MAX_LAYER_BYTES + " bytes");
            }

            byte[] bytes;
            try {
                bytes = Files.readAllBytes(realAgentsPath);
            } catch (IOException e) {
                throw new AgentsMdIntegrityException(
                        "Failed to read AGENTS.md", e);
            }

            // Validate UTF-8
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md is not valid UTF-8");
            }

            // Check aggregate limit
            aggregateBytes += bytes.length;
            if (aggregateBytes > MAX_AGGREGATE_BYTES) {
                throw new AgentsMdIntegrityException(
                        "AGENTS.md layers exceed aggregate limit of "
                                + MAX_AGGREGATE_BYTES + " bytes");
            }

            // Truncate for prompt injection with path-free marker
            String promptContent = content;
            boolean truncated = false;
            if (promptContent.length() > MAX_PROMPT_CHARS) {
                promptContent = promptContent.substring(0, MAX_PROMPT_CHARS);
                truncated = true;
            }

            String relativeDirectory = normalizeRelative(realBoundRoot.relativize(directory));
            layers.add(new AgentsMdLayer(realAgentsPath, relativeDirectory,
                    promptContent, rank, truncated));
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

    private String normalizeRelative(Path relativePath) {
        String normalized = relativePath.toString().replace('\\', '/');
        return ".".equals(normalized) ? "" : normalized;
    }

    // ── Integrity exception ──

    /**
     * Terminal exception for AGENTS.md integrity or I/O failures.
     *
     * <p>Only an absent optional file resolves to empty. Any discovered-file
     * link, non-regular file, escape, invalid UTF-8, size-limit, or I/O
     * failure is terminal.
     */
    public static final class AgentsMdIntegrityException extends RuntimeException {
        public AgentsMdIntegrityException(String message) {
            super(message);
        }

        public AgentsMdIntegrityException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
