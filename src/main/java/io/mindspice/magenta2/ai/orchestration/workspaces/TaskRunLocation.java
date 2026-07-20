package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;
import java.util.Objects;

/** Trusted canonical task-run child location within an AGENT or USER workspace. */
public final class TaskRunLocation {
    private final WorkspaceKind workspaceKind;
    private final String workspaceKey;
    private final TaskIdentity taskIdentity;
    private final TaskPathKey taskPathKey;
    private final String taskRunId;
    private final String rootRelativePath;
    private final Path confinedPath;

    TaskRunLocation(WorkspaceKind workspaceKind, String workspaceKey,
                    TaskIdentity taskIdentity, TaskPathKey taskPathKey,
                    String taskRunId, String rootRelativePath, Path confinedPath) {
        this.workspaceKind = Objects.requireNonNull(workspaceKind);
        this.workspaceKey = Objects.requireNonNull(workspaceKey);
        this.taskIdentity = Objects.requireNonNull(taskIdentity);
        this.taskPathKey = Objects.requireNonNull(taskPathKey);
        this.taskRunId = Objects.requireNonNull(taskRunId);
        this.rootRelativePath = Objects.requireNonNull(rootRelativePath);
        this.confinedPath = Objects.requireNonNull(confinedPath);
    }

    public WorkspaceKind workspaceKind() { return workspaceKind; }
    public String workspaceKey() { return workspaceKey; }
    public String workspaceIdentity() { return workspaceKind.token() + "/" + workspaceKey; }
    public TaskIdentity taskIdentity() { return taskIdentity; }
    public TaskPathKey taskPathKey() { return taskPathKey; }
    public String taskRunId() { return taskRunId; }
    public String rootRelativePath() { return rootRelativePath; }
    public Path confinedPath() { return confinedPath; }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof TaskRunLocation that
                && workspaceKind == that.workspaceKind
                && workspaceKey.equals(that.workspaceKey)
                && taskIdentity.equals(that.taskIdentity)
                && taskPathKey.equals(that.taskPathKey)
                && taskRunId.equals(that.taskRunId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workspaceKind, workspaceKey, taskIdentity, taskPathKey, taskRunId);
    }

    @Override
    public String toString() {
        return "TaskRunLocation[" + workspaceIdentity() + ":" + rootRelativePath + "]";
    }
}
