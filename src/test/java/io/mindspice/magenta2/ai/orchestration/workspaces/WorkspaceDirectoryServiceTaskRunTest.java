package io.mindspice.magenta2.ai.orchestration.workspaces;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceDirectoryServiceTaskRunTest {
    @TempDir Path dataRoot;
    private WorkspaceDirectoryService service;
    private WorkspaceDescriptor lazyAgent;
    private TaskIdentity identity;
    private TaskPathKey key;

    @BeforeEach
    void setUp() throws IOException {
        service = WorkspaceDirectoryServiceTestSupport.directoryService(
                dataRoot,
                WorkspaceDirectoryServiceTestSupport.agentProfileRepositoryWith("agent-1"),
                WorkspaceDirectoryServiceTestSupport.chatExistenceRepositoryWith("chat-1"));
        lazyAgent = service.resolveForRead(WorkspaceKind.AGENT, "agent-1");
        identity = new TaskIdentity(TaskSourceKind.TASK, "plan-definition-1");
        key = TaskPathKey.from(identity);
    }

    @Test
    void freshAgentReadsAreLazyForWorkspaceTasksOutputsStagingAndSkills() {
        TaskRunLocation run = service.resolveTaskRunForRead(lazyAgent, identity, key, "run-1");
        RunOutputLocation output = service.resolveRunOutput(run);
        Path skills = service.resolveSkillsRoot(lazyAgent);

        assertFalse(Files.exists(lazyAgent.root()));
        assertFalse(Files.exists(run.confinedPath()));
        assertFalse(Files.exists(output.confinedPath()));
        assertFalse(Files.exists(skills));
        assertFalse(Files.exists(lazyAgent.root().resolve("tasks")));
        assertFalse(Files.exists(lazyAgent.root().resolve(".skills")));
    }

    @Test
    void agentAndUserProvisionOnlyCanonicalRunHierarchy() {
        TaskRunLocation agentRun = service.provisionTaskRun(lazyAgent, identity, key, "run-1");
        WorkspaceDescriptor user = service.createUserWorkspace("bob");
        TaskIdentity workflow = new TaskIdentity(TaskSourceKind.WORKFLOW, "workflow-1");
        TaskPathKey workflowKey = TaskPathKey.from(workflow);
        TaskRunLocation userRun = service.provisionTaskRun(user, workflow, workflowKey, "workflow-run-1");

        assertEquals(dataRoot.resolve("workspace/agent/agent-1/tasks/" + key + "/run-1").normalize(),
                agentRun.confinedPath());
        assertEquals("workspace/agent/agent-1/tasks/" + key + "/run-1", agentRun.rootRelativePath());
        assertTrue(Files.isDirectory(agentRun.confinedPath()));
        assertTrue(Files.isDirectory(userRun.confinedPath()));
        assertFalse(Files.exists(agentRun.confinedPath().resolve("outputs")));
        assertFalse(Files.exists(agentRun.confinedPath().resolve("staging")));
        assertEquals(agentRun, service.provisionTaskRun(lazyAgent, identity, key, "run-1"));
    }

    @Test
    void outputLocationIsDataRootRelativeAndServiceConstructed() {
        TaskRunLocation run = service.provisionTaskRun(lazyAgent, identity, key, "run-1");
        RunOutputLocation output = service.resolveRunOutput(run);
        assertEquals(run.rootRelativePath() + "/outputs", output.rootRelativePath());
        assertEquals(run.confinedPath().resolve("outputs"), output.confinedPath());
        assertFalse(Files.exists(output.confinedPath()));
        assertTrue(Modifier.isPublic(TaskRunLocation.class.getModifiers()));
        for (var constructor : TaskRunLocation.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
        for (var constructor : RunOutputLocation.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
        for (var constructor : TaskRunWriteCapability.class.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()));
        }
    }

    @Test
    void keyMismatchChatAndMalformedIdsFailClosed() {
        TaskPathKey wrong = TaskPathKey.from(new TaskIdentity(TaskSourceKind.TASK, "other"));
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTaskRunForRead(lazyAgent, identity, wrong, "run-1"));
        WorkspaceDescriptor chat = service.resolveForRead(WorkspaceKind.CHAT, "chat-1");
        IllegalArgumentException chatFailure = assertThrows(IllegalArgumentException.class,
                () -> service.resolveTaskRunForRead(chat, identity, key, "run-1"));
        assertTrue(chatFailure.getMessage().contains("not task-selectable"));
        for (String invalid : new String[] {"", "../x", "x/y", "/x", "outputs"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolveTaskRunForRead(lazyAgent, identity, key, invalid));
        }
    }

    @Test
    void existingLinkComponentsAndParentReplacementFailClosed() throws IOException {
        Files.createDirectories(lazyAgent.root());
        Path outside = Files.createDirectory(dataRoot.resolve("outside"));
        Files.createSymbolicLink(lazyAgent.root().resolve("tasks"), outside);
        assertThrows(IllegalArgumentException.class,
                () -> service.resolveTaskRunForRead(lazyAgent, identity, key, "run-1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.provisionTaskRun(lazyAgent, identity, key, "run-1"));
    }

    @Test
    void rootRelocationChangesOnlyRuntimePath() throws IOException {
        TaskRunLocation first = service.resolveTaskRunForRead(lazyAgent, identity, key, "run-1");
        Path relocatedRoot = dataRoot.resolveSibling(dataRoot.getFileName() + "-relocated");
        try {
            WorkspaceDirectoryService relocated = WorkspaceDirectoryServiceTestSupport.directoryService(
                    relocatedRoot,
                    WorkspaceDirectoryServiceTestSupport.agentProfileRepositoryWith("agent-1"),
                    WorkspaceDirectoryServiceTestSupport.chatExistenceRepository(false));
            WorkspaceDescriptor descriptor = relocated.resolveForRead(WorkspaceKind.AGENT, "agent-1");
            TaskRunLocation second = relocated.resolveTaskRunForRead(descriptor, identity, key, "run-1");
            assertEquals(first, second);
            assertEquals(first.rootRelativePath(), second.rootRelativePath());
            assertNotEquals(first.confinedPath(), second.confinedPath());
        } finally {
            if (Files.exists(relocatedRoot)) {
                try (var paths = Files.walk(relocatedRoot)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }
}
