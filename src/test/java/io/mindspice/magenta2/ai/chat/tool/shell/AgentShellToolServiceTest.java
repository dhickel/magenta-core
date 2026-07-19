package io.mindspice.magenta2.ai.chat.tool.shell;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspacePathLayout;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentShellToolServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearContext() {
        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void runsAllowedCommandLine() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        AgentShellToolService.ShellExecResult result = service.exec("printf hello", ".", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello");
        assertThat(result.stderr()).isEmpty();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.workingDirectory()).isEqualTo(".");
    }

    @Test
    void parsesQuotedCommandLineArgs() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        AgentShellToolService.ShellExecResult result = service.exec("printf \"hello world\"", ".", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("hello world");
        assertThat(result.args()).containsExactly("hello world");
    }

    @Test
    void wildcardDoesNotAllowCommandsByDefault() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("*"));

        assertThatThrownBy(() -> service.exec("printf wild", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void unsafeWildcardOverrideAllowsAnyNonWrapperCommandLine() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("*"), true);

        AgentShellToolService.ShellExecResult result = service.exec("printf wild", ".", 5);

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("wild");
    }

    @Test
    void rejectsCommandOutsideAllowlist() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("pwd", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not allowed");
    }

    @Test
    void rejectsExecutablePath() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("bash"));

        assertThatThrownBy(() -> service.exec("/bin/bash -lc pwd", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("bare executable");
    }

    @Test
    void rejectsShellWrapperEvenWhenExplicitlyAllowed() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("bash"));

        assertThatThrownBy(() -> service.exec("bash -lc pwd", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shell wrapper");
    }

    @Test
    void unsafeWildcardStillRejectsShellWrappers() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("*"), true);

        assertThatThrownBy(() -> service.exec("sh -c pwd", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("shell wrapper");
    }

    @Test
    void rejectsUnterminatedQuote() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf \"bad", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unterminated quote");
    }

    @Test
    void rejectsWorkingDirectoryTraversalOutsideRoot() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf bad", "..", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void rejectsSymlinkWorkingDirectoryEscape() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        try {
            Files.createSymbolicLink(tempDir.resolve("escape"), outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            return;
        }

        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf bad", "escape", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void rejectsAbsolutePathArguments() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf /etc/passwd", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute filesystem paths");
    }

    @Test
    void rejectsEmbeddedAbsolutePathArguments() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf \"open('/etc/passwd')\"", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute filesystem paths");
    }

    @Test
    void rejectsParentTraversalArguments() throws IOException {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        assertThatThrownBy(() -> service.exec("printf ../secret", ".", 5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("parent-directory traversal");
    }

    @Test
    void reportsFailedExitCode() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("false"));

        AgentShellToolService.ShellExecResult result = service.exec("false", ".", 5);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void reportsTimeout() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("sleep"));

        AgentShellToolService.ShellExecResult result = service.exec("sleep 2", ".", 1);

        assertThat(result.exitCode()).isNull();
        assertThat(result.timedOut()).isTrue();
    }

    @Test
    void interruptedExecutionCleansUpAndThrowsInterruptedException() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("sleep"));
        java.util.concurrent.atomic.AtomicReference<Throwable> exception = new java.util.concurrent.atomic.AtomicReference<>();

        Thread executor = new Thread(() -> {
            try {
                service.exec("sleep 30", ".", 30);
            } catch (InterruptedException e) {
                exception.set(e);
            } catch (Exception e) {
                exception.set(e);
            }
        }, "shell-executor");

        executor.start();
        Thread.sleep(500); // let sleep start
        executor.interrupt();
        executor.join(5_000); // should complete promptly after cleanup

        assertThat(executor.isAlive()).as("Executor must finish after interruption").isFalse();
        assertThat(exception.get()).isInstanceOf(InterruptedException.class);
    }

    @Test
    void timeoutStillDestroysProcess() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("sleep"));

        AgentShellToolService.ShellExecResult result = service.exec("sleep 10", ".", 1);

        assertThat(result.exitCode()).isNull();
        assertThat(result.timedOut()).isTrue();
    }

    // ════════════════════════════════════════════════════════════════
    //  Phase 02: Workspace-backed host execution
    // ════════════════════════════════════════════════════════════════

    @Test
    void hostExecutionWhenNoOrchestrationContext() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        AgentShellToolService.ShellExecResult result = service.exec("printf host-test", ".", 5);

        assertThat(result.executionType()).isEqualTo("bash");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("host-test");
    }

    @Test
    void executesInAgentWorkspaceWhenOrchestrationContextHasAgentId() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "", 5);

            assertThat(result.executionType()).isEqualTo("bash");
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).endsWith("workspace/agent-1");
            assertThat(result.workingDirectory()).isEqualTo("workspace");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void resolvesWorkspaceAliasInAgentContext() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "workspace", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).endsWith("workspace/agent-1");
            assertThat(result.workingDirectory()).isEqualTo("workspace");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void resolvesOutputsAliasInAgentContext() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));
        Files.createDirectories(tempDir.resolve("workspace/agent-1/outputs"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "outputs", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).endsWith("workspace/agent-1/outputs");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void rejectsLegacyScratchAliasInAgentContext() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            assertThatThrownBy(() -> service.exec("pwd", WorkspacePathLayout.LEGACY_SCRATCH, 5))
                .isInstanceOf(IOException.class);
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void executesInActiveAssignmentWorkspaceWhenHostPathIsPresent() throws Exception {
        Path runWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-1"));
        Path outputDir = Files.createDirectories(runWorkspace.resolve("outputs"));
        Files.createDirectories(runWorkspace.resolve("nested"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            runWorkspace.toString(),
            outputDir.toString()));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "nested", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).endsWith("workspace/agent-1/runs/run-1/nested");
            assertThat(result.workingDirectory()).isEqualTo("workspace/nested");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void resolvesRunOutputAliasWhenHostOutputPathIsPresent() throws Exception {
        Path runWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            runWorkspace.toString(),
            runWorkspace.toString()));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "outputs", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).endsWith("workspace/agent-1/runs/run-1");
            assertThat(result.workingDirectory()).isEqualTo("outputs");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void activeTaskContextAliasesDurableWorkspaceRunTempAndCurrentOutputs() throws Exception {
        Path durableWorkspace = Files.createDirectories(tempDir.resolve("projects/project-1/workspace"));
        Path workDir = Files.createDirectories(durableWorkspace.resolve("work"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "TestAgent", null, null, null, "project-1",
            null, "workspace-1", "workspace-1", "TASK_RUN", null,
            durableWorkspace.toString(), durableWorkspace.toString(),
            durableWorkspace.toString(), durableWorkspace.toString(), null));

        try {
            assertThat(service.exec("pwd", "workspace", 5).stdout().trim())
                .isEqualTo(durableWorkspace.toRealPath().toString());
            assertThat(service.exec("pwd", "work", 5).stdout().trim())
                .isEqualTo(workDir.toRealPath().toString());
            assertThat(service.exec("pwd", "run", 5).stdout().trim())
                .isEqualTo(durableWorkspace.toRealPath().toString());
            assertThat(service.exec("pwd", "outputs", 5).stdout().trim())
                .isEqualTo(durableWorkspace.toRealPath().toString());
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void recordsActiveRuntimePathFromConfinedWorkingDirectory() throws Exception {
        Path durableWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1"));
        Path nested = Files.createDirectories(durableWorkspace.resolve("nested"));
        Path outputDir = Files.createDirectories(durableWorkspace.resolve("runs/run-1/outputs"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext(
            "agent-1", "TestAgent", null, null, null, null,
            null, "workspace-1", "workspace-1", "TASK_RUN", null,
            durableWorkspace.toString(), outputDir.toString(),
            durableWorkspace.resolve("runs/run-1").toString(), durableWorkspace.toString(), null));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "workspace/nested", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).isEqualTo(nested.toRealPath().toString());
            assertThat(OrchestrationTaskContextHolder.current().activeRuntimePath())
                .isEqualTo("workspace/nested");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void resolvesOnlyCurrentProjectScopeInAssignmentContext() throws Exception {
        Path runWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-1"));
        Path outputDir = Files.createDirectories(runWorkspace.resolve("outputs"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            "project-1",
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            runWorkspace.toString(),
            outputDir.toString()));

        try {
            AgentShellToolService.ShellExecResult result = service.exec("pwd", "projects/project-1", 5);

            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout().trim()).isEqualTo(runWorkspace.toRealPath().toString());
            assertThat(result.workingDirectory()).isEqualTo("projects/project-1");
            assertThatThrownBy(() -> service.exec("pwd", "projects/project-2", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not linked");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void rejectsAbsoluteWorkingDirectoryInAgentContext() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            assertThatThrownBy(() -> service.exec("pwd", "/etc", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Absolute working directory not allowed in agent context");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void rejectsTraversalPathInAgentContext() throws Exception {
        Files.createDirectories(tempDir.resolve("workspace/agent-1"));

        AiConfig aiConfig = new AiConfig(null, null, null, null, null, null, tempDir, null, null, null);
        WorkspaceDirectoryService dirService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("pwd"), dirService);

        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null));

        try {
            assertThatThrownBy(() -> service.exec("pwd", "..", 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes agent workspace");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void provenanceUsesFilesystemRuntimeExecutionType() throws Exception {
        AgentShellToolService service = new AgentShellToolService(tempDir, List.of("printf"));

        AgentShellToolService.ShellExecResult result = service.exec("printf clean", ".", 5);

        assertThat(result.executionType()).isEqualTo("bash");
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("clean");
    }
}
