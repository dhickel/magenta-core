package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Converts Magenta-owned filesystem paths to data-root-relative storage values
 * and resolves those persisted values back under the current data root.
 */
@Service
public class RootRelativePathService {
    private final Path dataRoot;

    public RootRelativePathService(WorkspaceDirectoryService workspaceDirectories) {
        if (workspaceDirectories == null || workspaceDirectories.dataRoot() == null) {
            throw new IllegalArgumentException("workspaceDirectories with dataRoot is required");
        }
        this.dataRoot = workspaceDirectories.dataRoot().toAbsolutePath().normalize();
    }

    public String store(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path is required");
        }
        Path normalized = path.isAbsolute()
            ? path.normalize()
            : dataRoot.resolve(path).normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Path escapes data root: " + path);
        }
        Path relative = dataRoot.relativize(normalized);
        String stored = relative.toString().replace('\\', '/');
        return stored.isBlank() ? "." : stored;
    }

    public Path resolve(String storedPath) {
        Path resolved = resolveWithoutExistence(storedPath);
        if (!resolved.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Stored path escapes current data root: " + storedPath);
        }
        return resolved;
    }

    public Path resolveExistingFile(String storedPath) {
        Path real = resolveExisting(storedPath);
        if (!Files.isRegularFile(real)) {
            throw new IllegalArgumentException("Stored path is not an existing regular file: " + storedPath);
        }
        return real;
    }

    public Path resolveExistingDirectory(String storedPath) {
        Path real = resolveExisting(storedPath);
        if (!Files.isDirectory(real)) {
            throw new IllegalArgumentException("Stored path is not an existing directory: " + storedPath);
        }
        return real;
    }

    public String display(String storedPath) {
        return resolve(storedPath).toString();
    }

    private Path resolveExisting(String storedPath) {
        Path resolved = resolve(storedPath);
        try {
            Path real = resolved.toRealPath();
            if (!real.startsWith(dataRoot)) {
                throw new IllegalArgumentException("Stored path resolves outside current data root: " + storedPath);
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException("Stored path does not exist under current data root: " + storedPath, e);
        }
    }

    private Path resolveWithoutExistence(String storedPath) {
        if (!StringUtils.hasText(storedPath)) {
            throw new IllegalArgumentException("storedPath is required");
        }
        String normalizedSeparators = storedPath.replace('\\', '/');
        Path raw = Path.of(normalizedSeparators);
        if (raw.isAbsolute()) {
            Path normalized = raw.normalize();
            if (!normalized.startsWith(dataRoot)) {
                throw new IllegalArgumentException(
                    "Stored absolute path is stale or outside current data root: " + storedPath);
            }
            return normalized;
        }
        rejectTraversal(raw, storedPath);
        return dataRoot.resolve(raw).normalize();
    }

    private void rejectTraversal(Path path, String storedPath) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("Stored path escapes current data root: " + storedPath);
            }
        }
    }
}
