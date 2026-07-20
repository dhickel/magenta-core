package io.mindspice.magenta2.ai.skills;

import java.util.Objects;
import java.util.regex.Pattern;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;

/**
 * Trusted resolution context assembled from run, assignment, workflow, job,
 * project-membership, agent, or chat/session metadata. Never derived from
 * paths, filenames, display names, or current process state.
 */
public record SkillResolutionContext(
    /** Effective workspace descriptor (AGENT or USER, never CHAT). */
    WorkspaceDescriptor effectiveWorkspace,

    /** Physically distinct bound-agent workspace, if different from effective. */
    WorkspaceDescriptor boundAgentWorkspace,

    /** Agent id for assignment resolution. */
    String agentId,

    /** Project id for assignment resolution and project-scope discovery. */
    String projectId,

    /** Authoritative project root descriptor, only when proven. */
    ProjectRootAuthority projectRootAuthority,

    /** Job id for assignment specificity. */
    String jobId,

    /** Task run id for assignment specificity. */
    String taskId,

    /** Workflow run id for assignment specificity. */
    String workflowId,

    /** Chat session / conversation id for assignment specificity. */
    String chatSessionId
) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._:-]{0,254})");

    public SkillResolutionContext {
        requireIdentifier(agentId, "agentId");
        requireOptionalIdentifier(projectId, "projectId");
        requireOptionalIdentifier(jobId, "jobId");
        requireOptionalIdentifier(taskId, "taskId");
        requireOptionalIdentifier(workflowId, "workflowId");
        requireOptionalIdentifier(chatSessionId, "chatSessionId");
        if (effectiveWorkspace != null && effectiveWorkspace.kind() == io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.CHAT) {
            throw new IllegalArgumentException("CHAT workspaces are not permitted in resolution context");
        }
        if (boundAgentWorkspace != null
                && boundAgentWorkspace.kind() != io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.AGENT) {
            throw new IllegalArgumentException("boundAgentWorkspace must be AGENT");
        }
        if (projectRootAuthority != null && !Objects.equals(projectId, projectRootAuthority.projectId())) {
            throw new IllegalArgumentException("project root authority must match projectId");
        }
    }

    private static void requireOptionalIdentifier(String value, String label) {
        if (value != null) requireIdentifier(value, label);
    }

    private static void requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!IDENTIFIER.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(label + " is not a canonical identifier");
        }
    }

    /**
     * Record for a proven authoritative project root. Discovery is disabled
     * without one.
     */
    public record ProjectRootAuthority(
        String projectId,
        WorkspaceDescriptor descriptor
    ) {
        public ProjectRootAuthority {
            requireIdentifier(projectId, "projectId");
            Objects.requireNonNull(descriptor, "descriptor");
            if (descriptor.kind() == io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.CHAT) {
                throw new IllegalArgumentException("project root authority cannot use CHAT");
            }
        }
    }
}
