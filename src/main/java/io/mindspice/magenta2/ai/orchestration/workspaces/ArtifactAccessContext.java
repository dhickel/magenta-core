package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.springframework.util.StringUtils;

/**
 * Trusted, narrow artifact-access scope derived from the request/resource
 * context before any data is loaded. The authorization predicate receives
 * a {@link PublicationRecord} and must return {@code true} for the record
 * to be visible.
 *
 * <p>Services never accept a raw workspace string or post-filter materialized
 * unrestricted results. Every scan, count, snippet, row, lineage edge,
 * metadata field, and byte-open must pass through an access context first.</p>
 */
public record ArtifactAccessContext(
        String workspaceKind,
        String workspaceKey,
        String taskRunId,
        Function<PublicationRecord, Boolean> recordPredicate) {

    private static final Set<String> VALID_KINDS = Set.of("agent", "chat", "user");

    public ArtifactAccessContext {
        Objects.requireNonNull(workspaceKind, "workspaceKind");
        Objects.requireNonNull(workspaceKey, "workspaceKey");
        String lower = workspaceKind.toLowerCase(Locale.ROOT);
        if (!VALID_KINDS.contains(lower)) {
            throw new IllegalArgumentException("Unknown workspace kind: " + workspaceKind);
        }
        workspaceKind = lower;
        if (recordPredicate == null) {
            recordPredicate = r -> true;
        }
    }

    /** Accepts only records in the exact workspace and, when constrained, the exact task run. */
    public boolean accepts(PublicationRecord record) {
        if (record == null) return false;
        if (!workspaceKind.equals(record.workspaceKind())) return false;
        if (!workspaceKey.equals(record.workspaceKey())) return false;
        if (StringUtils.hasText(taskRunId) && !taskRunId.equals(record.taskRunId())) return false;
        return recordPredicate.apply(record);
    }

    /**
     * Returns a SQL fragment that authorizes workspace-kind, workspace-key,
     * and optional task-run-id before any rows are materialized.
     */
    public String authorizationFragment() {
        if (StringUtils.hasText(taskRunId)) {
            return "op.workspace_kind = ? and op.workspace_key = ? and op.task_run_id = ?";
        }
        return "op.workspace_kind = ? and op.workspace_key = ?";
    }

    /** Returns the ordered bind parameters for {@link #authorizationFragment()}. */
    public Object[] authorizationParams() {
        if (StringUtils.hasText(taskRunId)) {
            return new Object[]{workspaceKind, workspaceKey, taskRunId};
        }
        return new Object[]{workspaceKind, workspaceKey};
    }

    /** Factory scoped to an exact workspace. */
    public static ArtifactAccessContext forWorkspace(String workspaceKind, String workspaceKey) {
        return new ArtifactAccessContext(workspaceKind, workspaceKey, null, null);
    }

    /** Factory scoped to a workspace plus task-run. */
    public static ArtifactAccessContext forTaskRun(String workspaceKind, String workspaceKey, String taskRunId) {
        return new ArtifactAccessContext(workspaceKind, workspaceKey, taskRunId, null);
    }

    /** Factory with a custom record-level predicate on top of workspace scope. */
    public static ArtifactAccessContext withPredicate(String workspaceKind, String workspaceKey,
                                                       Function<PublicationRecord, Boolean> predicate) {
        return new ArtifactAccessContext(workspaceKind, workspaceKey, null, predicate);
    }
}
