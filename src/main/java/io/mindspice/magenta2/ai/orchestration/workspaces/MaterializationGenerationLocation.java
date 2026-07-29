package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Trusted workspace value for a resolved V2 generation location.
 *
 * <p>Construction is package-private. Only {@link WorkspaceDirectoryService}
 * may create instances, and it must recompute the canonical path from the
 * resolved AGENT descriptor, project key, and generation number.
 *
 * <p>Carries the generation identity, a verified data-root-relative
 * assertion, and the confined absolute path.
 */
public final class MaterializationGenerationLocation {
    private final WorkspaceDescriptor agentDescriptor;
    private final String projectKey;
    private final int generation;
    private final String rootRelativePath;
    private final Path confinedPath;

    MaterializationGenerationLocation(WorkspaceDescriptor agentDescriptor,
                                       String projectKey,
                                       int generation,
                                       String rootRelativePath,
                                       Path confinedPath) {
        this.agentDescriptor = Objects.requireNonNull(agentDescriptor, "agentDescriptor");
        this.projectKey = Objects.requireNonNull(projectKey, "projectKey");
        if (projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1");
        }
        this.generation = generation;
        this.rootRelativePath = Objects.requireNonNull(rootRelativePath, "rootRelativePath");
        this.confinedPath = Objects.requireNonNull(confinedPath, "confinedPath");
        if (agentDescriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException(
                    "MaterializationGenerationLocation requires an AGENT descriptor");
        }
        if (!confinedPath.isAbsolute() || !confinedPath.startsWith(agentDescriptor.root())) {
            throw new IllegalArgumentException("confinedPath must be underneath the agent root");
        }
    }

    public WorkspaceDescriptor agentDescriptor() {
        return agentDescriptor;
    }

    public String projectKey() {
        return projectKey;
    }

    public int generation() {
        return generation;
    }

    /** Data-root-relative path (e.g. {@code workspace/agent/.../materializations/v2/<key>/gen-<N>/}). */
    public String rootRelativePath() {
        return rootRelativePath;
    }

    /** Confined absolute path for filesystem operations. */
    public Path confinedPath() {
        return confinedPath;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MaterializationGenerationLocation that)) return false;
        return generation == that.generation
                && projectKey.equals(that.projectKey)
                && rootRelativePath.equals(that.rootRelativePath)
                && confinedPath.equals(that.confinedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(projectKey, generation, rootRelativePath, confinedPath);
    }

    @Override
    public String toString() {
        return "MaterializationGenerationLocation[" + projectKey
                + " gen=" + generation + " -> " + rootRelativePath + "]";
    }
}
