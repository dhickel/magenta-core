package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Path;

import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;

public final class WorkspacePathLayout {
    public static final String WORKSPACE = "workspace";
    public static final String AGENTS = "agents";
    public static final String PROJECTS = "projects";
    public static final String CHATS = "chats";
    public static final String HOME = "home";
    public static final String WORKAREAS = "workareas";
    public static final String WORK = "work";
    public static final String OUTPUTS = "outputs";
    public static final String RUNS = "runs";
    public static final String ROOT_ALIAS = "root";
    public static final String RUN_ALIAS = "run";
    public static final String FILES = "files";

    public static final String LEGACY_RUNTIME = "runtime";
    public static final String LEGACY_TASK_RUNS = "task-runs";
    public static final String LEGACY_WORKFLOW_RUNS = "workflow-runs";
    public static final String LEGACY_JOBS = "jobs";
    public static final String LEGACY_SCRATCH = "scratch";

    private WorkspacePathLayout() {
    }

    // ── Phase 02 trusted three-kind factories ──

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

    // ── Legacy factories retained until Phase 06 ──

    public static Path agentWorkspaceRoot(String agentWorkspaceId) {
        return Path.of(WORKSPACE, segment(agentWorkspaceId, "agentWorkspaceId"));
    }

    public static Path agentHome(String agentWorkspaceId) {
        return agentWorkspaceRoot(agentWorkspaceId).resolve(HOME);
    }

    public static Path workArea(String agentWorkspaceId, String workAreaId) {
        return agentWorkspaceRoot(agentWorkspaceId)
            .resolve(WORKAREAS)
            .resolve(segment(workAreaId, "workAreaId"));
    }

    public static Path workAreaRelative(String workAreaId) {
        return Path.of(WORKAREAS, segment(workAreaId, "workAreaId"));
    }

    public static Path runRoot(String agentWorkspaceId, String runId) {
        return agentWorkspaceRoot(agentWorkspaceId)
            .resolve(RUNS)
            .resolve(segment(runId, "runId"));
    }

    public static Path runOutputs(String agentWorkspaceId, String runId) {
        return runRoot(agentWorkspaceId, runId).resolve(OUTPUTS);
    }

    public static Path agentFinalOutputs(String agentWorkspaceId) {
        return agentWorkspaceRoot(agentWorkspaceId).resolve(OUTPUTS);
    }

    public static Path agentWork(String agentWorkspaceId) {
        return agentWorkspaceRoot(agentWorkspaceId).resolve(WORK);
    }

    public static Path agentProjectLinks(String agentWorkspaceId) {
        return agentWorkspaceRoot(agentWorkspaceId).resolve(PROJECTS);
    }

    public static Path chatFiles(String conversationId) {
        return Path.of(CHATS, segment(conversationId, "conversationId"), FILES);
    }

    public static Path projectRoot(String projectId) {
        return Path.of(PROJECTS, segment(projectId, "projectId"));
    }

    public static Path agentMetadataRoot(String agentId) {
        return Path.of(AGENTS, segment(agentId, "agentId"));
    }

    public static Path legacyAgentHome(String agentId) {
        return agentMetadataRoot(agentId).resolve(HOME);
    }

    public static Path legacyAgentOutputs(String agentId) {
        return agentMetadataRoot(agentId).resolve(OUTPUTS);
    }

    public static Path legacyAgentWorkspaceRoot(String agentId) {
        return agentMetadataRoot(agentId).resolve(WORKSPACE);
    }

    public static Path legacyTaskRun(String runId) {
        return Path.of(LEGACY_RUNTIME, LEGACY_TASK_RUNS, segment(runId, "runId"));
    }

    public static Path legacyWorkflowRun(String runId) {
        return Path.of(LEGACY_RUNTIME, LEGACY_WORKFLOW_RUNS, segment(runId, "runId"));
    }

    public static Path legacyJobWorkspace(String jobId) {
        return Path.of(LEGACY_JOBS, segment(jobId, "jobId"), WORKSPACE);
    }

    public static Path legacyJobOutputRoot(String jobId) {
        return Path.of(LEGACY_JOBS, segment(jobId, "jobId"), OUTPUTS);
    }

    public static String relativeString(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private static String segment(String value, String label) {
        return PlainPathSegmentValidator.requirePlainSegment(value, label);
    }
}
