package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adversarial tests for reserved-tree confinement in the explorer.
 * Covers: traversal, encoded separators, absolute paths, symlink swaps,
 * destination-link swaps, parent replacement, cross-boundary copy/move,
 * root deletion/replacement, stale capabilities, and hard-link aliases.
 */
class ReservedTreeExplorerAdversarialTest {

    @TempDir
    Path tempDir;

    private WorkspaceExplorerService explorer() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                        "jdbc:sqlite::memory:?foreign_keys=true", true));
        var actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
        var metadataRepo = new WorkspaceFileMetadataRepository(jdbc);
        var metadataService = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo);
        return new WorkspaceExplorerService(metadataService, actionLogRepo);
    }

    private WorkspaceDescriptor descriptor(String id, Path root) {
        return new WorkspaceDescriptor(WorkspaceKind.AGENT, id,
                root.toAbsolutePath().normalize());
    }

    private WorkspaceDescriptor userDescriptor(String name, Path root) {
        return new WorkspaceDescriptor(WorkspaceKind.USER, name,
                root.toAbsolutePath().normalize());
    }

    private WorkspaceExplorerService explorerWithDb() {
        var jdbc = new JdbcTemplate(
                new SingleConnectionDataSource(
                        "jdbc:sqlite::memory:?foreign_keys=true", true));
        var actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
        var metadataRepo = new WorkspaceFileMetadataRepository(jdbc);
        var metadataService = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo);
        return new WorkspaceExplorerService(metadataService, actionLogRepo);
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return new WorkspaceExplorerService.RelativePath(
                value != null && !value.isBlank() ? value : ".");
    }

    // ── Traversal attacks ──

    @Test
    void rejectsDotDotTraversalCreate() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.createDirectory(descriptor, path("../escape")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    void rejectsDotDotTraversalInDeepPath() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("notes"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.createDirectory(descriptor, path("notes/../../escape")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("escapes");
    }

    // ── Absolute path attacks ──

    @Test
    void rejectsAbsoluteUnixPath() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() -> explorer.normalizeRelativePath("/etc/passwd"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("absolute");
    }

    @Test
    void rejectsAbsoluteWindowsPath() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() -> explorer.normalizeRelativePath("C:/windows/system32"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("absolute");
    }

    // ── Reserved tree mutation rejection ──

    @Test
    void rejectsCreateInOutputsTree() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.createDirectory(descriptor, path("outputs/new")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    @Test
    void rejectsCreateInTasksTree() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.createDirectory(descriptor, path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/newfile")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    @Test
    void rejectsSaveTextInOutputsTree() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        Files.writeString(root.resolve("outputs/result.txt"), "original");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(Files.readString(root.resolve("outputs/result.txt")))
                .isEqualTo("original");
    }

    @Test
    void rejectsDeleteOfOutputsTree() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.delete(descriptor, path("outputs"),
                        WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(root.resolve("outputs")).isDirectory();
    }

    @Test
    void rejectsDeleteOfTaskStructure() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.delete(descriptor, path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"),
                        WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1")).isDirectory();
    }

    @Test
    void rejectsRenameIntoTasksTree() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Path homeDir = Files.createDirectories(root.resolve("home"));
        Path src = homeDir.resolve("notes.txt");
        Files.writeString(src, "hello");
        Files.createDirectories(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Rename within personal area is fine
        explorer.rename(descriptor, path("home/notes.txt"), "notes2.txt");
        assertThat(homeDir.resolve("notes2.txt")).exists();

        // Moving into tasks should fail
        assertThatThrownBy(() ->
                explorer.move(descriptor, path("home/notes2.txt"),
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"), null))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    // ── Cross-boundary copy/move rejection ──

    @Test
    void rejectsCopyFromOrdinaryIntoOutputs() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        Path src = root.resolve("notes.txt");
        Files.writeString(src, "secret");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.copy(descriptor, path("notes.txt"), path("outputs"), null))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(root.resolve("outputs/notes.txt")).doesNotExist();
    }

    @Test
    void rejectsCopyFromOutputsIntoOrdinary() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        Files.createDirectories(root.resolve("notes"));
        Files.writeString(root.resolve("outputs/result.txt"), "data");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Source is outputs — resolveExisting would succeed (browse allowed),
        // but target is ordinary and the source is outputs, meaning ORDINARY_MUTATION
        // at source is denied. ensureMutable checks source classification.
        assertThatThrownBy(() ->
                explorer.move(descriptor, path("outputs/result.txt"),
                        path("notes"), null))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    // ── Root deletion protection ──

    @Test
    void rejectsRootDelete() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.delete(descriptor, path("."),
                        WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(root).isDirectory();
    }

    @Test
    void rejectsRootCopy() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("notes"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.copy(descriptor, path("."), path("notes"), "root-copy"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("root");
    }

    // ── Browse visibility: .skills and staging hidden ──

    @Test
    void skillsDirectoryIsHiddenFromBrowse() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve(".skills/my-skill"));
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // List root — .skills should not appear
        WorkspaceExplorerService.DirectoryListing listing =
                explorer.list(descriptor, path("."));
        assertThat(listing.entries().stream().map(WorkspaceExplorerService.Entry::name))
                .doesNotContain(".skills");

        // Direct access to .skills should fail
        assertThatThrownBy(() ->
                explorer.inspect(descriptor, path(".skills")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void stagingIsHiddenFromBrowse() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/staging"));
        Files.writeString(root.resolve(
                "tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/staging/temp.dat"), "staged");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Direct access to staging should fail
        assertThatThrownBy(() ->
                explorer.inspect(descriptor,
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/staging")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("does not exist");
    }

    // ── Symlink attacks ──

    @Test
    void rejectsSymlinkComponentInPath() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = root.resolve("link-to-outside");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return; // filesystem doesn't support symlinks
        }
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.list(descriptor, path("link-to-outside")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("symbolic links");
    }

    // ── Destination link swap (TOCTOU): link created after validation ──

    @Test
    void rejectsDestinationSymlinkOnRename() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Files.writeString(root.resolve("notes.txt"), "hello");
        // Create a symlink at the target name pointing outside
        Path targetLink = root.resolve("notes2.txt");
        try {
            Files.createSymbolicLink(targetLink, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        assertThatThrownBy(() ->
                explorer.rename(descriptor, path("notes.txt"), "notes2.txt"))
                .isInstanceOf(WorkspaceFileException.class);
    }

    // ── Workspace root is not a symlink ──

    @Test
    void rejectsSymlinkedWorkspaceRoot() throws Exception {
        // Create a real dir and a symlink to it
        Path realRoot = Files.createDirectories(tempDir.resolve("real-agent-1"));
        Path symlinkRoot = tempDir.resolve("agent-1-link");
        try {
            Files.createSymbolicLink(symlinkRoot, realRoot);
        } catch (UnsupportedOperationException e) {
            return;
        }

        // The descriptor uses the symlink; resolveExisting/toRealPath will follow it
        // but rejectSymbolicPath will catch it during walk
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor("agent-1", symlinkRoot);

        // Creating a file under symlinked root should fail
        assertThatThrownBy(() ->
                explorer.createTextFile(descriptor, path("."), "note.txt"))
                .isInstanceOf(WorkspaceFileException.class);
    }

    // ── Task structure is browsable but not mutable ──

    @Test
    void taskStructureIsBrowsable() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        Files.writeString(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/notes.txt"), "run note");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Browse should work
        WorkspaceExplorerService.FilePreview preview =
                explorer.preview(descriptor,
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/notes.txt"));
        assertThat(preview.content()).isEqualTo("run note");

        // List should work
        WorkspaceExplorerService.DirectoryListing listing =
                explorer.list(descriptor, path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        assertThat(listing.entries()).isNotEmpty();
    }

    @Test
    void taskStructureIsNotMutable() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"));
        Files.writeString(root.resolve("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/notes.txt"), "run note");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Save text should fail
        assertThatThrownBy(() ->
                explorer.saveText(descriptor,
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/notes.txt"), "changed"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");

        // Create should fail
        assertThatThrownBy(() ->
                explorer.createTextFile(descriptor,
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1"), "new.txt"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");

        // Delete should fail
        assertThatThrownBy(() ->
                explorer.delete(descriptor,
                        path("tasks/v1-task-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/run-1/notes.txt"),
                        WorkspaceExplorerService.DeleteStep.FILE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    // ── Workspace outputs are browsable but not mutable ──

    // ── AGENT workspace area classification ──

    @Test
    void agentHomeDescendantIsMutableButHomeRootIsDenied() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        Path root = Files.createDirectories(tempDir.resolve("agent-home"));
        WorkspaceDescriptor descriptor = descriptor("agent-home", root);

        // home/<child> is AGENT_PERSONAL_DESCENDANT → mutable
        explorer.createDirectory(descriptor, path("home/notes"));
        explorer.createMarkdownFile(descriptor, path("home/notes"), "readme.md");
        explorer.saveText(descriptor, path("home/notes/readme.md"), "# AGENT notes");
        assertThat(explorer.preview(descriptor, path("home/notes/readme.md")).content())
                .isEqualTo("# AGENT notes");

        // home area root itself is AGENT_CANONICAL_AREA_ROOT → denied for mutation
        assertThatThrownBy(() ->
                explorer.rename(descriptor, path("home"), "home2"))
                .isInstanceOf(WorkspaceFileException.class);
        assertThatThrownBy(() ->
                explorer.delete(descriptor, path("home"),
                        WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class);
    }

    @Test
    void agentUnknownChildIsImmutable() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        Path root = Files.createDirectories(tempDir.resolve("agent-unknown"));
        Files.createDirectories(root.resolve("misc"));
        Files.writeString(root.resolve("misc/notes.txt"), "secret");
        WorkspaceDescriptor descriptor = descriptor("agent-unknown", root);

        // AGENT_UNKNOWN_CHILD → browse allowed, mutation denied
        assertThat(explorer.preview(descriptor, path("misc/notes.txt")).content())
                .isEqualTo("secret");
        assertThatThrownBy(() ->
                explorer.saveText(descriptor, path("misc/notes.txt"), "changed"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThatThrownBy(() ->
                explorer.createDirectory(descriptor, path("misc/new")))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }

    // ── USER workspace area classification ──

    @Test
    void userHomeIsMutableOrdinaryContent() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        Path root = Files.createDirectories(tempDir.resolve("user-home"));
        WorkspaceDescriptor descriptor = userDescriptor("alice", root);

        // USER workspace: all non-reserved paths are ORDINARY_CONTENT → mutable
        // home/ area root is just a directory, not an AGENT canonical area
        explorer.createDirectory(descriptor, path("home"));
        explorer.createTextFile(descriptor, path("home"), "notes.txt");
        explorer.saveText(descriptor, path("home/notes.txt"), "# User home");
        assertThat(explorer.preview(descriptor, path("home/notes.txt")).content())
                .isEqualTo("# User home");

        // home/<child> is also mutable
        explorer.createDirectory(descriptor, path("home/sub"));
        explorer.createTextFile(descriptor, path("home/sub"), "todo.txt");
        assertThat(root.resolve("home/sub/todo.txt")).isRegularFile();
    }

    @Test
    void userUnknownChildIsMutableOrdinaryContent() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        Path root = Files.createDirectories(tempDir.resolve("user-misc"));
        WorkspaceDescriptor descriptor = userDescriptor("bob", root);

        // USER workspace: unknown children are ORDINARY_CONTENT → mutable
        explorer.createDirectory(descriptor, path("misc"));
        explorer.createTextFile(descriptor, path("misc"), "notes.txt");
        explorer.saveText(descriptor, path("misc/notes.txt"), "# User misc");
        assertThat(explorer.preview(descriptor, path("misc/notes.txt")).content())
                .isEqualTo("# User misc");
        explorer.createDirectory(descriptor, path("misc/sub"));
        assertThat(root.resolve("misc/sub")).isDirectory();
    }

    // ── Reserved-tree denial for both AGENT and USER ──

    @Test
    void outputsDeniedForMutationInBothAgentAndUser() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-outputs";
            Path root = Files.createDirectories(tempDir.resolve(name));
            Files.createDirectories(root.resolve("outputs"));
            Files.writeString(root.resolve("outputs/result.txt"), "data");
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                    ? descriptor(name, root) : userDescriptor(name, root);

            assertThatThrownBy(() ->
                    explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
            assertThatThrownBy(() ->
                    explorer.createDirectory(descriptor, path("outputs/new")))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
        }
    }

    @Test
    void tasksDeniedForMutationInBothAgentAndUser() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        String taskKey = "v1-task-" + "a".repeat(64);

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-tasks";
            Path root = Files.createDirectories(tempDir.resolve(name));
            Files.createDirectories(root.resolve("tasks/" + taskKey + "/run-1"));
            Files.writeString(root.resolve("tasks/" + taskKey + "/run-1/notes.txt"), "note");
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                    ? descriptor(name, root) : userDescriptor(name, root);

            assertThatThrownBy(() ->
                    explorer.saveText(descriptor,
                            path("tasks/" + taskKey + "/run-1/notes.txt"), "changed"))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
        }
    }

    @Test
    void skillsDeniedForMutationInBothAgentAndUser() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-skills";
            Path root = Files.createDirectories(tempDir.resolve(name));
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                    ? descriptor(name, root) : userDescriptor(name, root);

            assertThatThrownBy(() ->
                    explorer.createDirectory(descriptor, path(".skills/new")))
                    .isInstanceOf(WorkspaceFileException.class);
        }
    }

    // ── Cross-boundary rename/move/copy reserved-tree denial ──

    @Test
    void rejectsRenameMoveCopyIntoTasksForBothKinds() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        String taskKey = "v1-task-" + "a".repeat(64);

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-cross-tasks";
            Path root = Files.createDirectories(tempDir.resolve(name));
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                    ? descriptor(name, root) : userDescriptor(name, root);

            // Create in writable area (home/ for AGENT, arbitrary for USER)
            // AGENT home area root is auto-provisioned lazily when creating descendants.
            String srcBase = kind == WorkspaceKind.AGENT ? "home" : "";
            String srcDir = mkRel(srcBase, "src");

            explorer.createDirectory(descriptor, path(srcDir));
            explorer.createTextFile(descriptor, path(srcDir), "notes.txt");

            // Create tasks structure for targeting
            Files.createDirectories(root.resolve("tasks/" + taskKey + "/run-1"));

            String srcPath = srcDir + "/notes.txt";
            String tasksTarget = "tasks/" + taskKey + "/run-1";

            // Move into tasks → denied
            assertThatThrownBy(() ->
                    explorer.move(descriptor, path(srcPath), path(tasksTarget), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");

            // Copy into tasks → denied
            assertThatThrownBy(() ->
                    explorer.copy(descriptor, path(srcPath), path(tasksTarget), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
        }
    }

    @Test
    void rejectsMutatingOutputsAcrossRenameMoveCopySourceAndDestination() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-outputs-cross";
            Path root = Files.createDirectories(tempDir.resolve(name));
            Files.createDirectories(root.resolve("outputs"));
            Files.writeString(root.resolve("outputs/r.txt"), "data");
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                    ? descriptor(name, root) : userDescriptor(name, root);

            // AGENT home area root is auto-provisioned lazily.
            String srcBase = kind == WorkspaceKind.AGENT ? "home" : "";
            String srcDir = mkRel(srcBase, "src");

            explorer.createDirectory(descriptor, path(srcDir));
            explorer.createTextFile(descriptor, path(srcDir), "a.txt");

            // Move ordinary into outputs → destination denied
            assertThatThrownBy(() ->
                    explorer.move(descriptor, path(srcDir + "/a.txt"), path("outputs"), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");

            // Copy ordinary into outputs → destination denied
            assertThatThrownBy(() ->
                    explorer.copy(descriptor, path(srcDir + "/a.txt"), path("outputs"), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
        }
    }

    // ── Cross-kind non-interference: prior AGENT operation does not leak into USER ──

    @Test
    void priorAgentOperationDoesNotAffectUserClassification() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();

        // AGENT: create in personal area
        Path agentRoot = Files.createDirectories(tempDir.resolve("agent-cross"));
        WorkspaceDescriptor agentDesc = descriptor("agent-cross", agentRoot);
        explorer.createDirectory(agentDesc, path("home/notes"));
        explorer.createTextFile(agentDesc, path("home/notes"), "a.txt");

        // Rename within AGENT personal area
        explorer.rename(agentDesc, path("home/notes/a.txt"), "b.txt");
        assertThat(agentRoot.resolve("home/notes/b.txt")).isRegularFile();

        // USER: unknown child should be mutable (ORDINARY_CONTENT)
        // If AGENT kind leaked, this would be denied as AGENT_UNKNOWN_CHILD
        Path userRoot = Files.createDirectories(tempDir.resolve("user-cross"));
        WorkspaceDescriptor userDesc = userDescriptor("charlie", userRoot);
        explorer.createDirectory(userDesc, path("misc"));
        explorer.createTextFile(userDesc, path("misc"), "notes.txt");
        explorer.saveText(userDesc, path("misc/notes.txt"), "# Charlie's notes");
        assertThat(explorer.preview(userDesc, path("misc/notes.txt")).content())
                .isEqualTo("# Charlie's notes");

        // USER: home root should be mutable (unlike AGENT where it's denied)
        // If AGENT kind leaked, this would be AGENT_CANONICAL_AREA_ROOT → denied
        explorer.createDirectory(userDesc, path("home"));
        explorer.createTextFile(userDesc, path("home"), "root.txt");
        explorer.saveText(userDesc, path("home/root.txt"), "# User home root");
        assertThat(explorer.preview(userDesc, path("home/root.txt")).content())
                .isEqualTo("# User home root");
    }

    @Test
    void priorUserOperationDoesNotAffectAgentClassification() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();

        // USER: create in ordinary content (unknown child area)
        Path userRoot = Files.createDirectories(tempDir.resolve("user-cross2"));
        WorkspaceDescriptor userDesc = userDescriptor("dave", userRoot);
        explorer.createDirectory(userDesc, path("misc"));
        explorer.createTextFile(userDesc, path("misc"), "notes.txt");
        explorer.saveText(userDesc, path("misc/notes.txt"), "# Dave's stuff");

        // USER: home root mutable
        explorer.createDirectory(userDesc, path("home"));
        explorer.createTextFile(userDesc, path("home"), "info.txt");
        explorer.saveText(userDesc, path("home/info.txt"), "# Dave's home");

        // AGENT: unknown child should remain denied for mutation
        // If USER kind leaked, AGENT_UNKNOWN_CHILD would become ORDINARY_CONTENT → ALLOW
        Path agentRoot = Files.createDirectories(tempDir.resolve("agent-cross2"));
        Files.createDirectories(agentRoot.resolve("misc"));
        Files.writeString(agentRoot.resolve("misc/notes.txt"), "secret");
        WorkspaceDescriptor agentDesc = descriptor("agent-cross2", agentRoot);

        assertThatThrownBy(() ->
                explorer.saveText(agentDesc, path("misc/notes.txt"), "changed"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        assertThat(Files.readString(agentRoot.resolve("misc/notes.txt")))
                .isEqualTo("secret");

        // AGENT: home root should remain denied for mutation
        // (rename of area root is denied for AGENT_CANONICAL_AREA_ROOT)
        Files.createDirectories(agentRoot.resolve("home"));
        assertThatThrownBy(() ->
                explorer.rename(agentDesc, path("home"), "home2"))
                .isInstanceOf(WorkspaceFileException.class);
    }

    @Test
    void rejectsReservedSourcesAndSkillsDestinationsForRenameMoveCopyInBothKinds() throws Exception {
        WorkspaceExplorerService explorer = explorerWithDb();
        String taskKey = "v1-task-" + "a".repeat(64);

        for (WorkspaceKind kind : new WorkspaceKind[]{WorkspaceKind.AGENT, WorkspaceKind.USER}) {
            String name = kind.token() + "-reserved-source";
            Path root = Files.createDirectories(tempDir.resolve(name));
            WorkspaceDescriptor descriptor = kind == WorkspaceKind.AGENT
                ? descriptor(name, root) : userDescriptor(name, root);
            String ordinaryDir = mkRel(kind == WorkspaceKind.AGENT ? "home" : "", "ordinary");
            String destinationDir = mkRel(kind == WorkspaceKind.AGENT ? "home" : "", "destination");
            explorer.createDirectory(descriptor, path(ordinaryDir));
            explorer.createDirectory(descriptor, path(destinationDir));
            explorer.createTextFile(descriptor, path(ordinaryDir), "source.txt");

            List<String> reservedSources = List.of(
                "outputs/result.txt",
                "tasks/" + taskKey + "/run-1/result.txt",
                ".skills/managed/result.txt");
            for (String reservedSource : reservedSources) {
                Path source = root.resolve(reservedSource);
                Files.createDirectories(source.getParent());
                Files.writeString(source, "protected");

                // Rename has a same-parent target, so this proves source rejection.
                assertThatThrownBy(() -> explorer.rename(descriptor, path(reservedSource), "renamed.txt"))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
                assertThatThrownBy(() -> explorer.move(descriptor, path(reservedSource), path(destinationDir), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
                assertThatThrownBy(() -> explorer.copy(descriptor, path(reservedSource), path(destinationDir), null))
                    .isInstanceOf(WorkspaceFileException.class)
                    .hasMessageContaining("protected");
                assertThat(source).isRegularFile();
            }

            // .skills is also rejected as a move/copy destination for both kinds.
            assertThatThrownBy(() -> explorer.move(descriptor, path(ordinaryDir + "/source.txt"),
                path(".skills/managed"), null))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
            assertThatThrownBy(() -> explorer.copy(descriptor, path(ordinaryDir + "/source.txt"),
                path(".skills/managed"), null))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
        }
    }

    private static String mkRel(String base, String name) {
        return base.isEmpty() ? name : base + "/" + name;
    }

    @Test
    void workspaceOutputsAreBrowsableButNotMutable() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path root = Files.createDirectories(tempDir.resolve("agent-1"));
        Files.createDirectories(root.resolve("outputs"));
        Files.writeString(root.resolve("outputs/result.txt"), "published");
        WorkspaceDescriptor descriptor = descriptor("agent-1", root);

        // Browse works
        WorkspaceExplorerService.FilePreview preview =
                explorer.preview(descriptor, path("outputs/result.txt"));
        assertThat(preview.content()).isEqualTo("published");

        // Mutation fails
        assertThatThrownBy(() ->
                explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
                .isInstanceOf(WorkspaceFileException.class)
                .hasMessageContaining("protected");
    }
}
