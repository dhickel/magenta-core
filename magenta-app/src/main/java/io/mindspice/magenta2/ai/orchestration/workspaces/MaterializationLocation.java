package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;

/**
 * Trusted workspace value for a resolved materialization location.
 *
 * <p>Construction is package-private. Only {@link WorkspaceDirectoryService}
 * may create instances, and it must recompute the canonical path from the
 * resolved AGENT descriptor, key, and captured generation. No service
 * reconstructs this from a {@code Path} or persisted string.
 *
 * <p>Carries only the materialization identity, captured generation, a
 * verified data-root-relative assertion, and the confined absolute path.
 */
public final class MaterializationLocation {
    private final WorkspaceDescriptor agentDescriptor;
    private final MaterializationKey materializationKey;
    private final int generation;
    private final String rootRelativePath;
    private final Path confinedPath;

    MaterializationLocation(WorkspaceDescriptor agentDescriptor,
                            MaterializationKey materializationKey,
                            int generation,
                            String rootRelativePath,
                            Path confinedPath) {
        this.agentDescriptor = Objects.requireNonNull(agentDescriptor, "agentDescriptor");
        this.materializationKey = Objects.requireNonNull(materializationKey, "materializationKey");
        if (generation < 1) {
            throw new IllegalArgumentException("generation must be >= 1");
        }
        this.generation = generation;
        this.rootRelativePath = Objects.requireNonNull(rootRelativePath, "rootRelativePath");
        this.confinedPath = Objects.requireNonNull(confinedPath, "confinedPath");
        if (agentDescriptor.kind() != WorkspaceKind.AGENT) {
            throw new IllegalArgumentException("MaterializationLocation requires an AGENT descriptor");
        }
        if (!confinedPath.isAbsolute() || !confinedPath.startsWith(agentDescriptor.root())) {
            throw new IllegalArgumentException("confinedPath must be underneath the agent root");
        }
    }

    public WorkspaceDescriptor agentDescriptor() {
        return agentDescriptor;
    }

    public MaterializationKey materializationKey() {
        return materializationKey;
    }

    public int generation() {
        return generation;
    }

    /** Data-root-relative path (e.g. {@code workspace/agent/.../projects/v1-PROJECT-.../}). */
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
        if (!(o instanceof MaterializationLocation that)) return false;
        return generation == that.generation
                && materializationKey.equals(that.materializationKey)
                && rootRelativePath.equals(that.rootRelativePath)
                && confinedPath.equals(that.confinedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(materializationKey, generation, rootRelativePath, confinedPath);
    }

    @Override
    public String toString() {
        return "MaterializationLocation[" + materializationKey.serialized()
                + " gen=" + generation + " -> " + rootRelativePath + "]";
    }
}
