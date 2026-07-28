package io.mindspice.magenta2.ai.skills;

import java.util.Objects;
import java.util.regex.Pattern;

import io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey;

/**
 * Trusted skill authority bound to a captured materialization id and
 * generation. Replaces the retired generic project-root authority so materialization-scoped
 * skill discovery and approval carry exact owner, generation, kind, and
 * root-relative identity.
 *
 * <p>This is a trusted value, never a persisted or caller-supplied path.
 * No skill service may recover authority by parsing a project id, workspace
 * identity string, filename, display name, URL, or {@code Path}.
 *
 * <p>Project id is retained only for assignment specificity and attribution;
 * it grants no filesystem or skill authority.
 */
public record MaterializationSkillAuthority(
        String agentId,
        MaterializationKey materializationKey,
        int capturedGeneration,
        String projectId) {

    private static final Pattern IDENTIFIER = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,254})");

    public MaterializationSkillAuthority {
        requireIdentifier(agentId, "agentId");
        Objects.requireNonNull(materializationKey, "materializationKey");
        if (capturedGeneration < 1) {
            throw new IllegalArgumentException("capturedGeneration must be >= 1");
        }
        requireIdentifier(projectId, "projectId");

        // Key must agree with the agent and project
        if (!agentId.equals(materializationKey.agentId())) {
            throw new IllegalArgumentException(
                    "Materialization key does not belong to the agent");
        }
        if (!"project".equals(materializationKey.sourceKind())) {
            throw new IllegalArgumentException(
                    "MaterializationSkillAuthority requires a project materialization");
        }
        if (!projectId.equals(materializationKey.sourceId())) {
            throw new IllegalArgumentException(
                    "Project id does not match the materialization key source");
        }
    }

    /**
     * The materialization kind token (currently always {@code PROJECT}).
     */
    public String kindToken() {
        return materializationKey.kindToken();
    }

    /**
     * The serialized materialization key for idempotent identity comparison.
     */
    public String serializedKey() {
        return materializationKey.serialized();
    }

    private static void requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!IDENTIFIER.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(label + " is not a canonical identifier");
        }
    }
}
