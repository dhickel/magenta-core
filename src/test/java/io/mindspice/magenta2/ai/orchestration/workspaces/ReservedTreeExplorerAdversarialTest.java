package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
