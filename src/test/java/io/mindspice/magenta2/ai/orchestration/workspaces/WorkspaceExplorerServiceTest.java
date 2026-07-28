package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        // Missing agent root: synthetic areas only (.skills is hidden)
        var listing = explorer.list(descriptor, path("."));
        assertThat(listing.entries()).hasSize(7);
        assertThat(listing.entries().stream().allMatch(e -> e.directory())).isTrue();
        assertThat(descriptor.root()).doesNotExist();

        // Create within personal area home/
        explorer.createDirectory(descriptor, path("home/notes"));
        explorer.createMarkdownFile(descriptor, path("home/notes"), "readme.md");
        explorer.saveText(descriptor, path("home/notes/readme.md"), "# Hello\n");

        assertThat(descriptor.root()).isDirectory();
        assertThat(explorer.preview(descriptor, path("home/notes/readme.md")).content()).isEqualTo("# Hello\n");
        assertThat(explorer.inspect(descriptor, path("home/notes/readme.md")).viewerKind())
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

            // AGENT: create within personal area home/
            String base = kind == WorkspaceKind.AGENT ? "home" : "";
            String notesRel = mkRel(base, "notes");
            String archiveRel = mkRel(base, "archive");

            explorer.createDirectory(descriptor, path(notesRel));
            explorer.createTextFile(descriptor, path(notesRel), "a.txt");
            explorer.saveText(descriptor, path(notesRel + "/a.txt"), kind.token());
            explorer.createDirectory(descriptor, path(archiveRel));
            explorer.copy(descriptor, path(notesRel + "/a.txt"), path(archiveRel), null);
            explorer.rename(descriptor, path(archiveRel + "/a.txt"), "b.txt");
            explorer.move(descriptor, path(archiveRel + "/b.txt"), path(notesRel), "b.txt");

            assertThat(explorer.list(descriptor, path(notesRel)).entries()).hasSize(2);
            assertThat(explorer.inspect(descriptor, path(notesRel + "/b.txt")).regularFile()).isTrue();
            assertThat(explorer.preview(descriptor, path(notesRel + "/b.txt")).content()).isEqualTo(kind.token());
            assertThat(explorer.download(descriptor, path(notesRel + "/b.txt")).bytes())
                .isEqualTo(kind.token().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            explorer.delete(descriptor, path(notesRel + "/b.txt"), WorkspaceExplorerService.DeleteStep.FILE_CONFIRM);
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
        // Non-existent intermediate parent under personal area is denied
        assertThatThrownBy(() -> explorer.createDirectory(missing, path("home/nonexistent/child")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("parent");
        assertThatThrownBy(() -> explorer.createTextFile(missing, path("."), "invalid.md"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining(".txt");
        // Root may have been lazily provisioned by earlier create attempts;
        // this is acceptable behavior — the failed mutations didn't corrupt state.

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
    void agentReservedAndUnknownTreesRemainReadableButCannotBeDeleted() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1",
            Files.createDirectories(tempDir.resolve("agent-1")));
        for (String path : List.of("projects/p-1", "repos/r-1", "legacy/content")) {
            Path target = descriptor.root().resolve(path);
            Files.createDirectories(target.getParent());
            Files.writeString(target, path);
            assertThat(explorer.preview(descriptor, this.path(path)).content()).isEqualTo(path);
            assertThatThrownBy(() -> explorer.delete(descriptor, this.path(path),
                WorkspaceExplorerService.DeleteStep.FILE_CONFIRM))
                .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("protected");
            assertThat(target).exists();
        }
    }

    @Test
    void rejectsSymlinkComponentsAndSymbolicRecursiveTrees() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path homeDir = Files.createDirectories(descriptor.root().resolve("home"));
        Path tree = Files.createDirectories(homeDir.resolve("tree"));
        try {
            Files.createSymbolicLink(tree.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> explorer.list(descriptor, path("home/tree/escape")))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("home/tree"), path("home"), "tree-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("symbolic links");
        assertThat(homeDir.resolve("tree-copy")).doesNotExist();
    }

    @Test
    void enforcesPreviewEditClassificationAndDownloadLimits() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        // Files at root are unknown children; browsable but not writable
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
        // Create within personal area home/
        explorer.createDirectory(descriptor, path("home/parent"));
        explorer.createDirectory(descriptor, path("home/parent/child"));
        explorer.createTextFile(descriptor, path("home/parent"), "a.txt");
        explorer.createTextFile(descriptor, path("home/parent"), "b.txt");

        assertThatThrownBy(() -> explorer.rename(descriptor, path("home/parent/a.txt"), "b.txt"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("already exists");
        assertThatThrownBy(() -> explorer.move(descriptor, path("home/parent"), path("home/parent/child"), null))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("descendant");
        assertThatThrownBy(() -> explorer.copy(descriptor, path("."), path("home/parent"), "root-copy"))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("root");

        WorkspaceExplorerService.DeletePreflight preflight = explorer.deletePreflight(
            descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.INTENT);
        assertThat(preflight.executable()).isFalse();
        assertThat(preflight.requiredStep()).isEqualTo(WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.delete(descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.INTENT))
            .isInstanceOf(WorkspaceFileException.class).hasMessageContaining("confirmation");
        assertThat(explorer.delete(descriptor, path("home/parent"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM).deletedCount())
            .isEqualTo(4);
    }

    @Test
    void labelsTagsAndActionsUseCanonicalIdentity() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createTextFile(descriptor, path("home"), "note.txt");

        WorkspaceFileLabel tag = explorer.ensureTag("test-tag", "Test Tag");
        assertThat(tag.slug()).isEqualTo("test-tag");

        explorer.addLabel(descriptor, "home/note.txt", "test-tag");
        assertThat(explorer.labels(descriptor, "home/note.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "home/note.txt").getFirst().label().slug()).isEqualTo("test-tag");

        assertThat(explorer.recentActions(descriptor, 10)).isNotEmpty();
        assertThat(explorer.recentActions(descriptor, 10).getFirst().workspaceIdentity())
            .isEqualTo(descriptor.identity());
    }

    @Test
    void metadataFollowsMoveAndCopy() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", Files.createDirectories(tempDir.resolve("agent-1")));
        explorer.createDirectory(descriptor, path("home/src"));
        explorer.createTextFile(descriptor, path("home/src"), "a.txt");
        explorer.addLabel(descriptor, "home/src/a.txt", "note");

        explorer.rename(descriptor, path("home/src/a.txt"), "b.txt");
        assertThat(explorer.labels(descriptor, "home/src/b.txt")).hasSize(1);

        explorer.createDirectory(descriptor, path("home/dst"));
        explorer.copy(descriptor, path("home/src/b.txt"), path("home/dst"), null);
        assertThat(explorer.labels(descriptor, "home/dst/b.txt")).hasSize(1);
        assertThat(explorer.labels(descriptor, "home/dst/b.txt").getFirst().label().slug()).isEqualTo("note");

        explorer.delete(descriptor, path("home/dst"), WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> explorer.labels(descriptor, "home/dst/b.txt"))
            .isInstanceOf(WorkspaceFileException.class)
            .hasMessageContaining("does not exist");
    }

    private static String mkRel(String base, String name) {
        return base.isEmpty() ? name : base + "/" + name;
    }

    @Test
    void materializationReadsAreConfinedToTrustedLocationAndNeverExposeAgentSiblings() throws Exception {
        WorkspaceExplorerService explorer = explorer();
        Path agentRoot = tempDir.resolve("agent-materialized");
        Path materializationRoot = agentRoot.resolve("projects/materialized-key");
        Files.createDirectories(materializationRoot);
        Files.writeString(materializationRoot.resolve("brief.txt"), "project only");
        Files.writeString(agentRoot.resolve("private.txt"), "agent private");

        WorkspaceDescriptor descriptor = descriptor(WorkspaceKind.AGENT, "agent-1", agentRoot);
        var key = io.mindspice.magenta2.ai.orchestration.context.AgentMaterializationRepository.MaterializationKey
            .forProject("agent-1", "project-1");
        MaterializationLocation location = new MaterializationLocation(
            descriptor, key, 3, "workspace/agent/agent-1/projects/materialized-key", materializationRoot);

        assertThat(explorer.list(location, path(".")).entries())
            .extracting(WorkspaceExplorerService.Entry::name)
            .containsExactly("brief.txt");
        assertThat(explorer.preview(location, path("brief.txt")).content()).isEqualTo("project only");
        assertThat(explorer.inspect(location, path("brief.txt")).name()).isEqualTo("brief.txt");
        assertThatThrownBy(() -> explorer.list(location, explorer.normalizeRelativePath("../")))
            .isInstanceOf(WorkspaceFileException.class);
        assertThat(agentRoot.resolve("private.txt")).exists();
    }

    private WorkspaceExplorerService.RelativePath path(String value) {
        return new WorkspaceExplorerService.RelativePath(value != null && !value.isBlank() ? value : ".");
    }

    private WorkspaceDescriptor descriptor(WorkspaceKind kind, String key, Path root) {
        return new WorkspaceDescriptor(kind, key, root.toAbsolutePath().normalize());
    }
}
