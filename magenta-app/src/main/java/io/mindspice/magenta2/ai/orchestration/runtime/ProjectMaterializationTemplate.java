package io.mindspice.magenta2.ai.orchestration.runtime;

import java.time.Instant;
import java.util.Objects;

/**
 * Append-only project-owned materialization template revision.
 *
 * <p>Every edit creates a new immutable revision with a monotonically
 * increasing revision number. The {@code directoriesSnapshot} is a
 * JSON array of normalized relative directory paths that define the
 * canonical layout of a materialization generation. Templates are
 * never mutated in place — callers read a revision and allocate a
 * generation that snapshots it permanently.
 */
public record ProjectMaterializationTemplate(
        String id,
        String projectId,
        int revision,
        String directoriesSnapshot,
        Instant createdAt) {

    public ProjectMaterializationTemplate {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(projectId, "projectId");
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be >= 1");
        }
        Objects.requireNonNull(directoriesSnapshot, "directoriesSnapshot");
        if (directoriesSnapshot.isBlank()) {
            throw new IllegalArgumentException("directoriesSnapshot must not be blank");
        }
        ProjectMaterializationTemplateService.parseValidatedDirectories(directoriesSnapshot);
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
