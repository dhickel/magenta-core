package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceExplorerServiceTest {
    @TempDir
    Path tempDir;

    private WorkspaceExplorerService explorer() {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceFileActionLogRepository actionLogRepo = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceFileMetadataRepository metadataRepo = new WorkspaceFileMetadataRepository(jdbc);
        WorkspaceFileMetadataService metadataService = new WorkspaceFileMetadataService(metadataRepo, actionLogRepo);
        return new WorkspaceExplorerService(metadataService, actionLogRepo);
    }

    @Test
    void descriptorBoundaryReadsMissingAgentRootAsEmptyAndCreatesLazily() {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", tempDir.resolve("agent-1"));

        assertThat(explorer.list(descriptor, path(".")).entries()).isEmpty();
        assertThat(explorer.inspect(descriptor, path(".")).directory()).isTrue();
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
    void fullOperationBoundaryWorksForAgentChatAndDeclaredUserDescriptors() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        for (WorkspaceKind kind : WorkspaceKind.values()) {
            Path root = tempDir.resolve(kind.token());
            if (kind == WorkspaceKind.USER) {
                Files.createDirectories(root);
            }
            WorkspaceDescriptor descriptor = descriptor(kind, kind.token() + "-1", root);

            explorer.createDirectory(descriptor, path("notes"));
            explorer.createTextFile(descriptor, path("notes"), "a.txt");
            explorer.saveText(descriptor, path("notes/a.txt"), kind.token());
            explorer.createDirectory(descriptor, path("archive"));
            explorer.copy(descriptor, path("notes/a.txt"), path("archive"), null);
            explorer.rename(descriptor, path("archive/a.txt"), "b.txt");
            explorer.move(descriptor, path("archive/b.txt"), path("notes"), "b.txt");

            assertThat(explorer.list(descriptor, path("notes")).entries()).hasSize(2);
            assertThat(explorer.inspect(descriptor, path("notes/b.txt")).regularFile()).isTrue();
            assertThat(explorer.preview(descriptor, path("notes/b.txt")).content()).isEqualTo(kind.token());
            assertThat(explorer.download(descriptor, path("notes/b.txt")).bytes())
                .isEqualTo(kind.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            explorer.delete(descriptor, path("notes/b.txt"), WorkspaceExplorerService.DeleteStep.FILE_CONFIRM);
            assertThat(explorer.recentActions(descriptor, 20)).isNotEmpty();
        }
    }

    @Test
    void missingUserRootIsNotImplicitlyCreated() {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.USER, "declared", tempDir.resolve("declared"));

        assertThatThrownBy(() -> explorer.createTextFile(descriptor, path("."), "note.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("does not exist");
        assertThat(descriptor.root()).doesNotExist();
    }

    @Test
    void rejectsTraversalRootAndReservedOutputsMutationsButAllowsOutputsReads() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor missing = descriptor(WorkspaceKind.AGENT, "missing", tempDir.resolve("missing"));
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("missing/child")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("parent");
        assertThatThrownBy(() -> explorer.createTextFile(missing, path("."), "invalid.md"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining(".txt");
        assertThat(missing.root()).doesNotExist();

        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Files.createDirectories(descriptor.root().resolve("outputs"));
        Files.writeString(descriptor.root().resolve("outputs/result.txt"), "result");

        assertThat(explorer.preview(descriptor, path("outputs/result.txt")).content()).isEqualTo("result");
        assertThatThrownBy(() -> explorer.normalizeRelativePath("../outside"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("escapes workspace");
        assertThatThrownBy(() -> explorer.createDirectory(descriptor, path("outputs/new")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.saveText(descriptor, path("outputs/result.txt"), "changed"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("outputs");
        assertThatThrownBy(() -> explorer.delete(descriptor, path("."), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");
        assertThat(Files.readString(descriptor.root().resolve("outputs/result.txt"))).isEqualTo("result");
    }

    @Test
    void rejectsSymlinkComponentsAndSymbolicRecursiveTrees() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path tree = Files.createDirectories(descriptor.root().resolve("tree"));
        try {
            Files.createSymbolicLink(tree.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> explorer.list(descriptor, path("tree/escape")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("tree"), path("."), "tree-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThat(descriptor.root().resolve("tree-copy")).doesNotExist();
    }

    @Test
    void enforcesPreviewEditClassificationAndDownloadLimits() throws Exception {
        WorkspaceExplorerService explorer = explorer();
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
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("too large");

        Path edit = descriptor.root().resolve("edit.txt");
        try (var file = new java.io.RandomAccessFile(edit.toFile(), "rw")) {
            file.setLength(26L * 1024 * 1024);
        }
        assertThat(explorer.preview(descriptor, path("edit.txt")).kind()).isEqualTo("too_large");
    }

    @Test
    void handlesCollisionsDescendantsAndRecursiveDeleteConfirmation() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createDirectory(descriptor, path("parent"));
        explorer.createDirectory(descriptor, path("parent/child"));
        explorer.createTextFile(descriptor, path("parent"), "a.txt");
        explorer.createTextFile(descriptor, path("parent"), "b.txt");

        assertThatThrownBy(() -> explorer.rename(descriptor, path("parent/a.txt"), "b.txt"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> explorer.move(descriptor, path("parent"), path("parent/child"), null))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("descendant");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("."), path("parent"), "root-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("root");

        WorkspaceExplorerService.DeletePreflight preflight = explorer.deletePreflight(
            descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.INTENT);
        assertThat(preflight.executable()).isFalse();
        assertThat(preflight.requiredStep()).isEqualTo(WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.delete(descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.INTENT))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("confirmation");
        assertThat(explorer.delete(descriptor, path("parent"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM).deletedCount())
            .isEqualTo(4);
    }

    @Test
    void labelsTagsAndActionsUseCanonicalIdentity() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createTextFile(descriptor, path("."), "note.txt");

        WorkspaceFileLabel tag = explorer.ensureTag("test-tag", "Test Tag");
        assertThat(tag.slug()).isEqualTo("test-tag");

        explorer.addLabel(descriptor, "note.txt", "test-tag");
        assertThat(explorer.labels(descriptor, "note.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "note.txt").getFirst().label().slug()).isEqualTo("test-tag");

        assertThat(explorer.recentActions(descriptor, 10)).isNotEmpty();
        assertThat(explorer.recentActions(descriptor, 10).getFirst().workspaceIdentity())
            .isEqualTo(descriptor.identity());
    }

    @Test
    void metadataFollowsMoveAndCopy() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createDirectory(descriptor, path("src"));
        explorer.createTextFile(descriptor, path("src"), "a.txt");
        explorer.addLabel(descriptor, "src/a.txt", "note");

        explorer.rename(descriptor, path("src/a.txt"), "b.txt");
        assertThat(explorer.labels(descriptor, "src/b.txt")).hasSize(1);

        explorer.createDirectory(descriptor, path("dst"));
        explorer.copy(descriptor, path("src/b.txt"), path("dst"), null);
        assertThat(explorer.labels(descriptor, "dst/b.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "dst/b.txt").getFirst().label().slug()).isEqualTo("note");

        explorer.delete(descriptor, path("dst"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.labels(descriptor, "dst/b.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("does not exist");
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return new WorkspaceExplorerService.RelativePath(value != null && !value.isBlank() ? value : ".");
    }

    private WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root.toAbsolutePath().normalize());
    }
}
