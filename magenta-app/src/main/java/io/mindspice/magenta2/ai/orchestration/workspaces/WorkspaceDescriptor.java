package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Trusted internal workspace identity carried across filesystem, explorer,
 * metadata, execution, tool, and output boundaries.
 *
 * <p>Construction is package-controlled. Only {@link WorkspaceDirectoryService}
 * may create instances, and it must validate the key, enforce entity/declaration
 * existence policy, and resolve the root through {@link WorkspacePathLayout}.</p>
 */
public final class WorkspaceDescriptor {
    private final WorkspaceKind kind;
    private final String key;
    private final String identity;
    private final Path root;

    public WorkspaceDescriptor(WorkspaceKind kind, String key, Path root) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.key = Objects.requireNonNull(key, "key");
        this.identity = kind.token() + "/" + key;
        Path normalizedRoot = Objects.requireNonNull(root, "root").normalize();
        if (!normalizedRoot.isAbsolute()) {
            throw new IllegalArgumentException("Workspace root must be absolute");
        }
        this.root = normalizedRoot;
    }

    public WorkspaceKind kind() {
        return kind;
    }

    /** The validated decoded key (agent id, chat conversation id, or user name). */
    public String key() {
        return key;
    }

    /** Canonical identity: {@code <token>/<key>}. Never client-supplied. */
    public String identity() {
        return identity;
    }

    /** Normalized absolute trusted root beneath the real data root. */
    public Path root() {
        return root;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WorkspaceDescriptor that)) return false;
        return kind == that.kind && key.equals(that.key) && root.equals(that.root);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, key, root);
    }

    @Override
    public String toString() {
        return "WorkspaceDescriptor[" + identity + " -> " + root + "]";
    }
}
