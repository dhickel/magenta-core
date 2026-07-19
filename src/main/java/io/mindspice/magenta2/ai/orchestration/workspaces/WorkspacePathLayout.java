package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;

/**
 * Sole structural path authority for the workspace tree.
 *
 * <pre>{@code
 * <dataRoot>/
 *   workspace/
 *     agent/<id>/
 *     chat/<id>/
 *     user/<name>/
 * }</pre>
 *
 * <p>Only {@link #WORKSPACE} and {@link #OUTPUTS} are public structural names.
 * Root factories produce relative paths beneath the configured data root.
 * No legacy alias, migration, project, job, run, scratch, work, or temp helpers remain.
 */
public final class WorkspacePathLayout {
    public static final String WORKSPACE = "workspace";
    public static final String OUTPUTS = "outputs";

    private WorkspacePathLayout() {
    }

    /** {@code workspace/agent/<id>} */
    public static Path agentRoot(String agentId) {
        return Path.of(WORKSPACE, WorkspaceKind.AGENT.token(), segment(agentId, "agentId"));
    }

    /** {@code workspace/chat/<id>} */
    public static Path chatRoot(String conversationId) {
        return Path.of(WORKSPACE, WorkspaceKind.CHAT.token(), segment(conversationId, "conversationId"));
    }

    /** {@code workspace/user/<name>} */
    public static Path userRoot(String name) {
        return Path.of(WORKSPACE, WorkspaceKind.USER.token(), segment(name, "userName"));
    }

    private static String segment(String value, String label) {
        return PlainPathSegmentValidator.requirePlainSegment(value, label);
    }
}
