package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;

/** Sole structural grammar authority for Magenta workspace paths. */
public final class WorkspacePathLayout {
    public static final String WORKSPACE = "workspace";
    public static final String OUTPUTS = "outputs";
    public static final String TASKS = "tasks";
    public static final String SKILLS = ".skills";
    public static final String STAGING = "staging";
    public static final String HOME = "home";
    public static final String SCRIPTS = "scripts";
    public static final String APPS = "apps";
    public static final String REPOS = "repos";
    public static final String PROJECTS = "projects";

    private static final int MAX_COMPONENT_BYTES = 255;
    private static final Pattern TASK_PATH_KEY = Pattern.compile(
        "v1-(task|workflow|job)-[0-9a-f]{64}");

    private WorkspacePathLayout() {
    }

    public static Path agentRoot(String agentId) {
        return Path.of(WORKSPACE, WorkspaceKind.AGENT.token(), segment(agentId, "agentId"));
    }

    public static Path chatRoot(String conversationId) {
        return Path.of(WORKSPACE, WorkspaceKind.CHAT.token(), segment(conversationId, "conversationId"));
    }

    public static Path userRoot(String name) {
        return Path.of(WORKSPACE, WorkspaceKind.USER.token(), segment(name, "userName"));
    }

    // ── AGENT canonical personal area factories ──

    public static Path agentHomeDir(String agentId) {
        return agentRoot(agentId).resolve(HOME);
    }

    public static Path agentScriptsDir(String agentId) {
        return agentRoot(agentId).resolve(SCRIPTS);
    }

    public static Path agentAppsDir(String agentId) {
        return agentRoot(agentId).resolve(APPS);
    }

    public static Path agentReposDir(String agentId) {
        return agentRoot(agentId).resolve(REPOS);
    }

    public static Path agentProjectsDir(String agentId) {
        return agentRoot(agentId).resolve(PROJECTS);
    }

    /** Workspace-relative path to an agent's materialization directory. */
    public static Path agentMaterializationDir(String agentId, String keySerialized) {
        return agentProjectsDir(agentId).resolve(validateComponent(keySerialized, "materializationKey"));
    }

    /** Workspace-relative reserved task root. */
    public static Path tasksRoot() {
        return Path.of(TASKS);
    }

    /** Workspace-relative reserved skill root. */
    public static Path skillsRoot() {
        return Path.of(SKILLS);
    }

    public static Path tasksDir(TaskPathKey taskPathKey) {
        Objects.requireNonNull(taskPathKey, "taskPathKey");
        validateComponent(taskPathKey.serialized(), "taskPathKey");
        return Path.of(TASKS, taskPathKey.serialized());
    }

    public static Path taskRunDir(TaskPathKey taskPathKey, String taskRunId) {
        return tasksDir(taskPathKey).resolve(validateComponent(taskRunId, "taskRunId"));
    }

    public static Path taskRunOutputsDir(TaskPathKey taskPathKey, String taskRunId) {
        return taskRunDir(taskPathKey, taskRunId).resolve(OUTPUTS);
    }

    public static Path taskRunStagingDir(TaskPathKey taskPathKey, String taskRunId) {
        return taskRunDir(taskPathKey, taskRunId).resolve(STAGING);
    }

    static String validateRunId(String taskRunId) {
        return validateComponent(taskRunId, "taskRunId");
    }

    static String validateTaskPathKeySegment(String value) {
        String component = validateComponent(value, "taskPathKey");
        if (!TASK_PATH_KEY.matcher(component).matches()) {
            throw new IllegalArgumentException("taskPathKey is not canonical");
        }
        return component;
    }

    private static String segment(String value, String label) {
        String segment = PlainPathSegmentValidator.requirePlainSegment(value, label);
        requireComponentByteLength(segment, label);
        return segment;
    }

    private static String validateComponent(String value, String label) {
        String component = PlainPathSegmentValidator.requirePlainSegment(value, label);
        String lower = component.toLowerCase(Locale.ROOT);
        if (OUTPUTS.equals(lower) || TASKS.equals(lower) || SKILLS.equals(lower)
                || STAGING.equals(lower) || WORKSPACE.equals(lower)
                || HOME.equals(lower) || SCRIPTS.equals(lower) || APPS.equals(lower)
                || REPOS.equals(lower) || PROJECTS.equals(lower)) {
            throw new IllegalArgumentException(label + " must not use reserved name: " + value);
        }
        requireComponentByteLength(component, label);
        return component;
    }

    private static void requireComponentByteLength(String value, String label) {
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_COMPONENT_BYTES) {
            throw new IllegalArgumentException(label + " exceeds " + MAX_COMPONENT_BYTES + " UTF-8 bytes");
        }
    }
}
