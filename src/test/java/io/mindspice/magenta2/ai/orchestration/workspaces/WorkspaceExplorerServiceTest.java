package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceExplorerServiceTest {
    @TempDir
    Path tempDir;

    private final WorkspaceExplorerService explorer = new WorkspaceExplorerService();

    @Test
    void descriptorBoundaryReadsMissingAgentRootAsEmptyAndCreatesLazily() {
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", tempDir.resolve("agent-1"));

        assertThat(explorer.list(descriptor, path(".")).entries()).isEmpty();
        assertThat(descriptor.root()).doesNotExist();

        explorer.createDirectory(descriptor, path("notes"));
        explorer.createMarkdownFile(descriptor, path("notes"), "readme.md");
        explorer.saveText(descriptor, path("notes/readme.md"), "# Hello\n");

        assertThat(descriptor.root()).isDirectory();
        assertThat(explorer.preview(descriptor, path("notes/readme.md")).content()).isEqualTo("# Hello\n");
        assertThat(explorer.inspect(descriptor, path("notes/readme.md")).viewerKind())
            .isEqualTo(WorkspaceExplorerService.ViewerKind.MARKDOWN);
    }

    @Test
    void missingUserRootIsNotImplicitlyCreated() {
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.USER, "declared", tempDir.resolve("declared"));

        assertThatThrownBy(() -> explorer.createTextFile(descriptor, path("."), "note.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
        assertThat(descriptor.root()).doesNotExist();
    }

    @Test
    void rejectsTraversalRootAndReservedOutputsMutationsButAllowsOutputsReads() throws Exception {
        WorkspaceDescriptor missing = descriptor(WorkspaceKind.AGENT, "missing", tempDir.resolve("missing"));
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("outputs/new")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outputs");
        assertThat(missing.root()).doesNotExist();

        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Files.createDirectories(descriptor.root().resolve("outputs"));
        Files.writeString(descriptor.root().resolve("outputs/result.txt"), "result");

        assertThat(explorer.preview(descriptor, path("outputs/result.txt")).content()).isEqualTo("result");
        assertThatThrownBy(() -> explorer.normalizeRelativePath("../outside"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escapes workspace");
        assertThatThrownBy(() -> explorer.createDirectory(descriptor, path("outputs/new")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.delete(descriptor, path("."), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("protected");
        assertThat(Files.readString(descriptor.root().resolve("outputs/result.txt"))).isEqualTo("result");
    }

    @Test
    void rejectsSymlinkComponentsAndSymbolicRecursiveTrees() throws Exception {
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path tree = Files.createDirectories(descriptor.root().resolve("tree"));
        try {
            Files.createSymbolicLink(tree.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> explorer.list(descriptor, path("tree/escape")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("tree"), path("."), "tree-copy"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbolic links");
        assertThat(descriptor.root().resolve("tree-copy")).doesNotExist();
    }

    @Test
    void enforcesPreviewEditClassificationAndDownloadLimits() throws Exception {
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Path markdown = descriptor.root().resolve("large.md");
        try (var file = new java.io.RandomAccessFile(markdown.toFile(), "rw")) {
            file.setLength(6L * 1024 * 1024);
        }
        WorkspaceExplorerService.FilePreview warning = explorer.preview(descriptor, path("large.md"));
        assertThat(warning.requiresWarning()).isTrue();
        assertThat(warning.content()).isNull();

        Path download = descriptor.root().resolve("download.txt");
        try (var file = new java.io.RandomAccessFile(download.toFile(), "rw")) {
            file.setLength(11L * 1024 * 1024);
        }
        assertThatThrownBy(() -> explorer.download(descriptor, path("download.txt")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");

        Path edit = descriptor.root().resolve("edit.txt");
        try (var file = new java.io.RandomAccessFile(edit.toFile(), "rw")) {
            file.setLength(26L * 1024 * 1024);
        }
        assertThat(explorer.preview(descriptor, path("edit.txt")).kind()).isEqualTo("too_large");
    }

    @Test
    void handlesCollisionsDescendantsAndRecursiveDeleteConfirmation() throws Exception {
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createDirectory(descriptor, path("parent/child"));
        explorer.createTextFile(descriptor, path("parent"), "a.txt");
        explorer.createTextFile(descriptor, path("parent"), "b.txt");

        assertThatThrownBy(() -> explorer.rename(descriptor, path("parent/a.txt"), "b.txt"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> explorer.move(descriptor, path("parent"), path("parent/child"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("descendant");

        WorkspaceExplorerService.DeletePreflight preflight = explorer.deletePreflight(
            descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.INTENT);
        assertThat(preflight.executable()).isFalse();
        assertThat(preflight.requiredStep()).isEqualTo(WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.delete(descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.INTENT))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("confirmation");
        assertThat(explorer.delete(descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM).deletedCount())
            .isEqualTo(4);
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return explorer.normalizeRelativePath(value);
    }

    private WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root.toAbsolutePath().normalize());
    }
}
