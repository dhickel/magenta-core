package io.mindspice.magenta2.ai.orchestration.workspaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class WorkspacePathLayoutTaskRunTest {
    private final TaskPathKey key = TaskPathKey.from(
            new TaskIdentity(TaskSourceKind.TASK, "definition-1"));

    @Test
    void reservedWorkspaceRelativeRootsAndCanonicalChildren() {
        assertEquals(Path.of("tasks"), WorkspacePathLayout.tasksRoot());
        assertEquals(Path.of(".skills"), WorkspacePathLayout.skillsRoot());
        assertEquals(Path.of("tasks", key.serialized()), WorkspacePathLayout.tasksDir(key));
        assertEquals(Path.of("tasks", key.serialized(), "run-1"),
                WorkspacePathLayout.taskRunDir(key, "run-1"));
        assertEquals(Path.of("tasks", key.serialized(), "run-1", "outputs"),
                WorkspacePathLayout.taskRunOutputsDir(key, "run-1"));
        assertEquals(Path.of("tasks", key.serialized(), "run-1", "staging"),
                WorkspacePathLayout.taskRunStagingDir(key, "run-1"));
    }

    @Test
    void runComponentsRejectTraversalAbsoluteSeparatorsReservedAndUtf8Overflow() {
        for (String invalid : new String[] {"", " ", ".", "..", "../x", "x/y", "x\\y",
                "/absolute", "C:\\absolute", "tasks", "OUTPUTS", "staging", ".skills", "workspace"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> WorkspacePathLayout.taskRunDir(key, invalid), invalid);
        }
        assertThrows(IllegalArgumentException.class,
                () -> WorkspacePathLayout.taskRunDir(key, "é".repeat(128)));
        assertEquals("a..b", WorkspacePathLayout.taskRunDir(key, "a..b").getFileName().toString());
        assertEquals("x".repeat(255),
                WorkspacePathLayout.taskRunDir(key, "x".repeat(255)).getFileName().toString());
    }

    @Test
    void existingWorkspaceFactoriesRemainClosed() {
        assertEquals(Path.of("workspace", "agent", "a"), WorkspacePathLayout.agentRoot("a"));
        assertEquals(Path.of("workspace", "chat", "c"), WorkspacePathLayout.chatRoot("c"));
        assertEquals(Path.of("workspace", "user", "u"), WorkspacePathLayout.userRoot("u"));
        assertEquals(3, WorkspaceKind.values().length);
    }
}
