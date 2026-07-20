package io.mindspice.magenta2.ai.chat.tool.file;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContext;
import io.mindspice.magenta2.ai.orchestration.runtime.OrchestrationTaskContextHolder;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class AgentFileToolServiceTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTaskContext() {
        OrchestrationTaskContextHolder.clear();
    }

    @Test
    void rejectsTraversalOutsideRoot() throws IOException {
        AgentFileToolService service = service();
        Files.writeString(tempDir.resolveSibling("outside.txt"), "do not read");

        assertThatThrownBy(() -> service.read("../outside.txt", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void rejectsSymlinkEscapeOutsideRoot() throws IOException {
        Path outside = Files.createDirectories(tempDir.resolveSibling(tempDir.getFileName() + "-outside"));
        Files.writeString(outside.resolve("secret.txt"), "do not read");
        try {
            Files.createSymbolicLink(tempDir.resolve("escape"), outside);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            return;
        }

        AgentFileToolService service = service();

        assertThatThrownBy(() -> service.read("escape/secret.txt", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes data root");
    }

    @Test
    void readsFileInChunksWithStableLineAnchors() throws IOException {
        Files.writeString(tempDir.resolve("chunked.txt"), generatedLines(25));
        AgentFileToolService service = service();

        AgentFileToolService.FileReadResult firstChunk = service.read("chunked.txt", 6, 10);
        AgentFileToolService.FileReadResult finalChunk = service.read("chunked.txt", 21, 10);

        assertThat(firstChunk.totalLines()).isEqualTo(25);
        assertThat(firstChunk.startLine()).isEqualTo(6);
        assertThat(firstChunk.endLine()).isEqualTo(15);
        assertThat(firstChunk.nextStartLine()).isEqualTo(16);
        assertThat(firstChunk.lines()).hasSize(10);
        assertThat(firstChunk.lines().getFirst()).contains("|fixture line 006 ");
        assertThat(firstChunk.lines().getFirst()).matches("6:[0-9a-f]{12}\\|fixture line 006 .*");

        assertThat(finalChunk.startLine()).isEqualTo(21);
        assertThat(finalChunk.endLine()).isEqualTo(25);
        assertThat(finalChunk.nextStartLine()).isNull();
        assertThat(finalChunk.lines()).hasSize(5);
    }

    @Test
    void readsEmptyFileAsEmptyChunk() throws IOException {
        Files.writeString(tempDir.resolve("empty.txt"), "");
        AgentFileToolService.FileReadResult result = service().read("empty.txt", 1, 10);

        assertThat(result.totalLines()).isZero();
        assertThat(result.startLine()).isEqualTo(1);
        assertThat(result.endLine()).isZero();
        assertThat(result.nextStartLine()).isNull();
        assertThat(result.lines()).isEmpty();
    }

    @Test
    void readsLargeFilesInRequestedChunks() throws IOException {
        Files.writeString(tempDir.resolve("large.txt"), "x".repeat(1_000_001));

        AgentFileToolService.FileReadResult result = service().read("large.txt", 1, 1);

        assertThat(result.totalLines()).isEqualTo(1);
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().getFirst()).contains("[line truncated");
        assertThat(result.lines().getFirst().length()).isLessThan(2_200);
        assertThat(result.nextStartLine()).isNull();
    }

    @Test
    void listsSingleFileMetadata() throws IOException {
        Files.writeString(tempDir.resolve("single.txt"), "abc");

        AgentFileToolService.FileListResult result = service().list("single.txt", false, 10);

        assertThat(result.path()).isEqualTo("single.txt");
        assertThat(result.truncated()).isFalse();
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().path()).isEqualTo("single.txt");
        assertThat(result.entries().getFirst().type()).isEqualTo("file");
        assertThat(result.entries().getFirst().size()).isEqualTo(3);
    }

    @Test
    void filtersListEntriesWithGlobAfterDiscovery() throws IOException {
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.createDirectories(tempDir.resolve("notes"));
        Files.writeString(tempDir.resolve("src/main/App.java"), "class App {}\n");
        Files.writeString(tempDir.resolve("src/main/App.md"), "# App\n");
        Files.writeString(tempDir.resolve("notes/todo.md"), "# Todo\n");

        AgentFileToolService.FileListResult result = service().list(".", true, 10, "**/*.md");

        assertThat(result.truncated()).isFalse();
        assertThat(result.entries())
            .extracting(AgentFileToolService.FileEntry::path)
            .containsExactly("notes/todo.md", "src/main/App.md");
    }

    @Test
    void filtersSingleFileListWithGlob() throws IOException {
        Files.writeString(tempDir.resolve("single.txt"), "abc");

        AgentFileToolService.FileListResult result = service().list("single.txt", false, 10, "*.md");

        assertThat(result.entries()).isEmpty();
    }

    @Test
    void searchesPlainTextCaseInsensitiveWithContext() throws IOException {
        Files.createDirectories(tempDir.resolve("notes"));
        Files.writeString(
            tempDir.resolve("notes/a.txt"),
            "before one\nNeedle alpha\nafter one\nplain line\nneedle beta\n"
        );
        Files.writeString(tempDir.resolve("notes/b.txt"), "nothing\nNEEDLE gamma\n");

        AgentFileToolService.FileSearchResult result = service().search("notes", "needle", false, false, 1, 10);

        assertThat(result.truncated()).isFalse();
        assertThat(result.matches()).hasSize(3);
        AgentFileToolService.SearchMatch first = result.matches().getFirst();
        assertThat(first.path()).isEqualTo("notes/a.txt");
        assertThat(first.lineNumber()).isEqualTo(2);
        assertThat(first.hash()).matches("[0-9a-f]{12}");
        assertThat(first.line()).isEqualTo("Needle alpha");
        assertThat(first.before()).hasSize(1);
        assertThat(first.before().getFirst()).matches("1:[0-9a-f]{12}\\|before one");
        assertThat(first.after()).hasSize(1);
        assertThat(first.after().getFirst()).matches("3:[0-9a-f]{12}\\|after one");
    }

    @Test
    void searchesRegexCaseSensitiveAndTruncates() throws IOException {
        Files.writeString(tempDir.resolve("matches.txt"), "ID-001\nid-002\nID-003\nID-004\n");

        AgentFileToolService.FileSearchResult result = service().search("matches.txt", "ID-\\d+", true, true, 0, 2);

        assertThat(result.truncated()).isTrue();
        assertThat(result.matches())
            .extracting(AgentFileToolService.SearchMatch::line)
            .containsExactly("ID-001", "ID-003");
    }

    @Test
    void searchesLargeFilesAndReportsMatchLineNumbers() throws IOException {
        Files.writeString(
            tempDir.resolve("large-search.txt"),
            "x".repeat(1_000_001) + "\nneedle line\ntrailing context\n"
        );

        AgentFileToolService.FileSearchResult result = service().search("large-search.txt", "needle", false, false, 1, 10);

        assertThat(result.truncated()).isFalse();
        assertThat(result.matches()).hasSize(1);
        AgentFileToolService.SearchMatch match = result.matches().getFirst();
        assertThat(match.lineNumber()).isEqualTo(2);
        assertThat(match.line()).isEqualTo("needle line");
        assertThat(match.before()).hasSize(1);
        assertThat(match.after()).hasSize(1);
        assertThat(match.after().getFirst()).contains("trailing context");
    }

    @Test
    void replacesSingleAnchoredLineWithRepeatedContentAroundIt() throws IOException {
        Files.writeString(tempDir.resolve("edit.txt"), "same\nmiddle\nsame\n");
        AgentFileToolService service = service();
        List<String> lines = service.read("edit.txt", 1, 10).lines();
        String middleAnchor = anchor(lines.get(1));

        AgentFileToolService.FileReplaceResult result = service.replace("edit.txt", middleAnchor, null, "changed");

        assertThat(result.replacedLines()).isEqualTo(1);
        assertThat(result.newLines()).isEqualTo(1);
        assertThat(Files.readString(tempDir.resolve("edit.txt"))).isEqualTo("same\nchanged\nsame\n");
    }

    @Test
    void replacesAnchoredLineRange() throws IOException {
        Files.writeString(tempDir.resolve("range.txt"), "one\ntwo\nthree\nfour\n");
        AgentFileToolService service = service();
        List<String> lines = service.read("range.txt", 1, 10).lines();

        service.replace("range.txt", anchor(lines.get(1)), anchor(lines.get(2)), "TWO\nTHREE");

        assertThat(Files.readString(tempDir.resolve("range.txt"))).isEqualTo("one\nTWO\nTHREE\nfour\n");
    }

    @Test
    void rejectsStaleAnchorWithoutChangingFile() throws IOException {
        Path file = tempDir.resolve("stale.txt");
        Files.writeString(file, "old\nkeep\n");
        AgentFileToolService service = service();
        String oldAnchor = anchor(service.read("stale.txt", 1, 10).lines().getFirst());
        Files.writeString(file, "new\nkeep\n");

        assertThatThrownBy(() -> service.replace("stale.txt", oldAnchor, null, "changed"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("hash does not match");
        assertThat(Files.readString(file)).isEqualTo("new\nkeep\n");
    }

    @Test
    void rejectsReversedAnchors() throws IOException {
        Files.writeString(tempDir.resolve("reverse.txt"), "one\ntwo\nthree\n");
        AgentFileToolService service = service();
        List<String> lines = service.read("reverse.txt", 1, 10).lines();

        assertThatThrownBy(() -> service.replace("reverse.txt", anchor(lines.get(2)), anchor(lines.get(0)), "bad"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("endAnchor must not be before startAnchor");
    }

    @Test
    void writesNewFileAndHonorsOverwriteFlag() throws IOException {
        AgentFileToolService service = service();

        AgentFileToolService.FileWriteResult created = service.write("new/file.txt", "content", false);

        assertThat(created.created()).isTrue();
        assertThat(created.bytesWritten()).isEqualTo(7);
        assertThat(Files.readString(tempDir.resolve("new/file.txt"))).isEqualTo("content");
        assertThatThrownBy(() -> service.write("new/file.txt", "other", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void appendsToExistingFileWithoutRewritingPriorContent() throws IOException {
        Files.writeString(tempDir.resolve("notes.md"), "first\n");
        AgentFileToolService service = service();

        AgentFileToolService.FileAppendResult result = service.append("notes.md", "second\n", false);

        assertThat(result.created()).isFalse();
        assertThat(result.bytesAppended()).isEqualTo(7);
        assertThat(Files.readString(tempDir.resolve("notes.md"))).isEqualTo("first\nsecond\n");
    }

    @Test
    void appendsCanCreateFileWhenRequested() throws IOException {
        AgentFileToolService service = service();

        AgentFileToolService.FileAppendResult result = service.append("new/log.txt", "entry\n", true);

        assertThat(result.created()).isTrue();
        assertThat(result.bytesAppended()).isEqualTo(6);
        assertThat(Files.readString(tempDir.resolve("new/log.txt"))).isEqualTo("entry\n");
        assertThatThrownBy(() -> service.append("missing.txt", "entry\n", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("path does not exist");
    }

    @Test
    void activeAssignmentContextUsesRunWorkspaceAndNotDataRootFallback() throws Exception {
        Path runWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-1"));
        Path outputDir = Files.createDirectories(runWorkspace.resolve("outputs"));
        Files.writeString(runWorkspace.resolve("notes.txt"), "workspace note\n");
        Path unrelatedRuntime = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-2"));
        Files.writeString(unrelatedRuntime.resolve("secret.txt"), "other run\n");

        AgentFileToolService service = serviceWithWorkspaceDirectory();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            runWorkspace.toString(),
            null, null, null, null, null));

        AgentFileToolService.FileReadResult result = service.read("notes.txt", 1, 10);

        assertThat(result.path()).isEqualTo("notes.txt");
        assertThat(result.lines().getFirst()).endsWith("|workspace note");
        assertThatThrownBy(() -> service.read("runs/run-2/secret.txt", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runs/run-2/secret.txt");
    }

    @Test
    void activeAssignmentContextRejectsReservedOutputsButAllowsOrdinaryDirectoryNames() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path ordinaryDirectory = Files.createDirectories(workspace.resolve("workspace"));
        Files.writeString(ordinaryDirectory.resolve("notes.txt"), "ordinary directory\n");

        AgentFileToolService service = serviceWithWorkspaceDirectory();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            workspace.toString(),
            null, null, null, null, null));

        assertThatThrownBy(() -> service.write("outputs/result.txt", "done\n", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reserved for output publication");
        assertThat(service.read("workspace/notes.txt", 1, 10).lines().getFirst())
            .endsWith("|ordinary directory");
    }

    @Test
    void activeTaskContextTreatsFormerAliasNamesAsDirectWorkspacePaths() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        for (String directory : List.of("workspace", "root", "work", "run", "projects")) {
            Path child = Files.createDirectories(workspace.resolve(directory));
            Files.writeString(child.resolve("notes.txt"), directory + " directory\n");
        }
        Path outputs = Files.createDirectories(workspace.resolve("outputs"));
        Files.writeString(outputs.resolve("output.txt"), "published output\n");

        AgentFileToolService service = serviceWithWorkspaceDirectory();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            "project-1",
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            workspace.toString(),
            null, null, null, null, null));
        try {
            for (String directory : List.of("workspace", "root", "work", "run", "projects")) {
                assertThat(service.read(directory + "/notes.txt", 1, 10).lines().getFirst())
                    .endsWith("|" + directory + " directory");
            }
            assertThat(service.read("outputs/output.txt", 1, 10).lines().getFirst()).endsWith("|published output");
        } finally {
            OrchestrationTaskContextHolder.clear();
        }
    }

    @Test
    void recordsActiveRuntimePathFromConfinedFileTarget() throws Exception {
        Path durableWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1"));
        Path outputDir = Files.createDirectories(durableWorkspace.resolve("runs/run-1/outputs"));
        Files.createDirectories(durableWorkspace.resolve("a"));
        Files.createDirectories(durableWorkspace.resolve("b"));
        Files.writeString(durableWorkspace.resolve("a/file.txt"), "a\n");
        Files.writeString(durableWorkspace.resolve("b/file.txt"), "b\n");

        AgentFileToolService service = serviceWithWorkspaceDirectory();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            null,
            "workspace-1",
            "workspace-1",
            "TASK_RUN",
            null,
            durableWorkspace.toString(),
            null, null, null, null, null));

        service.read("a/file.txt", 1, 10);
        assertThat(OrchestrationTaskContextHolder.current().activeRuntimePath())
            .isEqualTo("a/file.txt");

        service.read("b/file.txt", 1, 10);
        assertThat(OrchestrationTaskContextHolder.current().activeRuntimePath())
            .isEqualTo("b/file.txt");
    }

    @Test
    void noContextKeepsLegacyDataRootFallback() throws Exception {
        Path otherAgent = Files.createDirectories(tempDir.resolve("agents/agent-2/workspace"));
        Files.writeString(otherAgent.resolve("legacy.txt"), "legacy fallback\n");

        AgentFileToolService.FileReadResult result = serviceWithWorkspaceDirectory()
            .read("agents/agent-2/workspace/legacy.txt", 1, 10);

        assertThat(result.path()).isEqualTo("agents/agent-2/workspace/legacy.txt");
        assertThat(result.lines().getFirst()).endsWith("|legacy fallback");
    }

    @Test
    void activeAssignmentContextAllowsProjectNamedDirectoryInsideSelectedWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace/agent/agent-1"));
        Path projectDirectory = Files.createDirectories(workspace.resolve("projects/project-1"));
        WorkspaceDirectoryService dirService = workspaceDirectoryService();
        Files.writeString(projectDirectory.resolve("shared.txt"), "ordinary project directory\n");

        AgentFileToolService service = new AgentFileToolService(tempDir, dirService);
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            "project-1",
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            workspace.toString(),
            null, null, null, null, null));

        assertThat(service.read("projects/project-1/shared.txt", 1, 10).lines().getFirst())
            .endsWith("|ordinary project directory");
    }

    @Test
    void activeAgentContextUsesAgentWorkspaceWhenNoRunHostPathExists() throws Exception {
        WorkspaceDirectoryService dirService = workspaceDirectoryService();
        Path agentWorkspace = dirService.resolveForWrite(
                io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind.AGENT, "agent-1").root();
        Files.writeString(agentWorkspace.resolve("profile.txt"), "agent workspace\n");

        AgentFileToolService service = new AgentFileToolService(tempDir, dirService);
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            null,
            null, null, null, null, null));

        AgentFileToolService.FileReadResult result = service.read("profile.txt", 1, 10);

        assertThat(result.path()).isEqualTo("profile.txt");
        assertThat(result.lines().getFirst()).endsWith("|agent workspace");
    }

    @Test
    void activeContextRejectsTraversalAbsolutePathsAndSymlinkEscapes() throws Exception {
        Path runWorkspace = Files.createDirectories(tempDir.resolve("workspace/agent-1/runs/run-1"));
        Path outputDir = Files.createDirectories(runWorkspace.resolve("outputs"));
        Path outsideScope = Files.createDirectories(tempDir.resolve("agents/agent-2/workspace"));
        Files.writeString(outsideScope.resolve("secret.txt"), "do not read\n");
        try {
            Files.createSymbolicLink(runWorkspace.resolve("escape"), outsideScope);
        } catch (UnsupportedOperationException | FileSystemException exception) {
            return;
        }

        AgentFileToolService service = serviceWithWorkspaceDirectory();
        OrchestrationTaskContextHolder.set(new OrchestrationTaskContext("agent-1",
            "TestAgent",
            null,
            null,
            null,
            null,
            "agent",
            "agent-1",
            "agent/agent-1",
            "TASK_RUN",
            null,
            runWorkspace.toString(),
            null, null, null, null, null));

        assertThatThrownBy(() -> service.read("../run-2/secret.txt", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes active workspace");
        assertThatThrownBy(() -> service.read(runWorkspace.resolve("missing.txt").toString(), 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Absolute file paths are not allowed");
        assertThatThrownBy(() -> service.read("escape/secret.txt", 1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes active workspace");
        assertThatThrownBy(() -> service.write("escape/new.txt", "bad", false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes active workspace");
    }

    private AgentFileToolService service() throws IOException {
        return new AgentFileToolService(tempDir);
    }

    private AgentFileToolService serviceWithWorkspaceDirectory() throws IOException {
        return new AgentFileToolService(tempDir, workspaceDirectoryService());
    }

    private WorkspaceDirectoryService workspaceDirectoryService() throws IOException {
        return WorkspaceDirectoryServiceTestSupport.directoryService(tempDir);
    }

    private String generatedLines(int count) {
        return IntStream.rangeClosed(1, count)
            .mapToObj(i -> "fixture line %03d lorem ipsum token %04d".formatted(i, i * 37))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("") + "\n";
    }

    private String anchor(String formattedLine) {
        return formattedLine.substring(0, formattedLine.indexOf('|'));
    }
}
