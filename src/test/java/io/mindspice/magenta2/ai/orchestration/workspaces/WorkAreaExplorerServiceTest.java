package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import io.mindspice.magenta2.ai.config.user.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class WorkAreaExplorerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void listsPreviewsSavesAndCreatesDirectoriesInsideWorkArea() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        context.explorer().createDirectory(home.id(), "notes");
        context.explorer().createMarkdownFile(home.id(), "notes", "todo.md");
        context.explorer().saveText(home.id(), "notes/todo.md", "hello\n");
        context.explorer().ensureTag("research", "Research");
        context.explorer().addLabel(home.id(), "notes", "research");
        context.explorer().addLabel(home.id(), "notes/todo.md", "note");

        WorkAreaExplorerService.DirectoryListing listing = context.explorer().list(home.id(), "notes");
        WorkAreaExplorerService.Entry detail = context.explorer().inspect(home.id(), "notes/todo.md");
        WorkAreaExplorerService.FilePreview preview = context.explorer().preview(home.id(), "notes/todo.md");

        assertThat(listing.entries()).extracting(WorkAreaExplorerService.Entry::path).contains("notes/todo.md");
        assertThat(context.explorer().inspect(home.id(), "notes").tags())
            .extracting(WorkspaceFileLabel::slug)
            .containsExactly("research");
        assertThat(detail.fileType()).isEqualTo("Markdown");
        assertThat(detail.sizeBytes()).isEqualTo(6);
        assertThat(detail.sizeLabel()).isEqualTo("6 B");
        assertThat(detail.viewerKind()).isEqualTo(WorkAreaExplorerService.ViewerKind.MARKDOWN);
        assertThat(detail.canView()).isTrue();
        assertThat(detail.canRename()).isTrue();
        assertThat(detail.canDelete()).isTrue();
        assertThat(detail.canCopy()).isTrue();
        assertThat(detail.canMove()).isTrue();
        assertThat(detail.canTag()).isTrue();
        assertThat(detail.createdAt()).isNotNull();
        assertThat(detail.modifiedAt()).isNotNull();
        assertThat(detail.tags()).extracting(WorkspaceFileLabel::slug).containsExactly("note");
        assertThat(preview.text()).isTrue();
        assertThat(preview.content()).isEqualTo("hello\n");
        assertThat(Files.readString(tempDir.resolve("data/workspace/agent-1/home/notes/todo.md")))
            .isEqualTo("hello\n");
    }

    @Test
    void createsRenamesMovesCopiesAndDeletesWithMetadataAndActionLogs() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        context.explorer().createDirectory(home.id(), "notes");
        context.explorer().createDirectory(home.id(), "archive");
        context.explorer().createMarkdownFile(home.id(), "notes", "todo.md");
        context.explorer().saveText(home.id(), "notes/todo.md", "hello\n");
        context.explorer().ensureTag("project-alpha", "Project Alpha");
        context.explorer().addLabel(home.id(), "notes", "project-alpha");
        context.explorer().addLabel(home.id(), "notes/todo.md", "note");

        context.explorer().rename(home.id(), "notes", "renamed-notes");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/renamed-notes"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/renamed-notes/todo.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        context.explorer().move(home.id(), "renamed-notes", ".", "notes");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/notes"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");

        context.explorer().rename(home.id(), "notes/todo.md", "renamed.md");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/notes/renamed.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        context.explorer().copy(home.id(), "notes", "archive", "notes-copy");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/archive/notes-copy"))
            .extracting(a -> a.label().slug())
            .containsExactly("project-alpha");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/archive/notes-copy/renamed.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        context.explorer().copy(home.id(), "notes/renamed.md", "archive", "copied.md");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/archive/copied.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        context.explorer().move(home.id(), "archive/copied.md", "notes", "moved.md");
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/notes/moved.md"))
            .extracting(a -> a.label().slug())
            .containsExactly("note");

        WorkAreaExplorerService.DeletePreflight intent =
            context.explorer().deletePreflight(home.id(), "notes/moved.md", WorkAreaExplorerService.DeleteStep.INTENT);
        assertThat(intent.executable()).isFalse();
        assertThat(intent.requiredStep()).isEqualTo(WorkAreaExplorerService.DeleteStep.FILE_CONFIRM);

        context.explorer().delete(home.id(), "notes/moved.md", WorkAreaExplorerService.DeleteStep.FILE_CONFIRM);
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/notes/moved.md")).isEmpty();

        context.explorer().delete(home.id(), "archive/notes-copy", WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/archive/notes-copy")).isEmpty();
        assertThat(context.metadataRepository().labelsForPath(home.workspaceId(), "home/archive/notes-copy/renamed.md")).isEmpty();

        assertThat(context.actionLogRepository().recentForWorkspace(home.workspaceId(), 20))
            .extracting(WorkspaceFileActionRecord::actionType)
            .contains(
                WorkspaceFileActionType.CREATE_FOLDER,
                WorkspaceFileActionType.CREATE_MARKDOWN_FILE,
                WorkspaceFileActionType.SAVE_MARKDOWN,
                WorkspaceFileActionType.TAG_ADD,
                WorkspaceFileActionType.RENAME,
                WorkspaceFileActionType.COPY,
                WorkspaceFileActionType.MOVE,
                WorkspaceFileActionType.DELETE_DIRECTORY,
                WorkspaceFileActionType.DELETE_FILE
            );
    }

    @Test
    void rejectsMoveIntoDescendantAndCollisions() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        context.explorer().createDirectory(home.id(), "parent");
        context.explorer().createDirectory(home.id(), "parent/child");
        context.explorer().createTextFile(home.id(), "parent", "a.txt");
        context.explorer().createTextFile(home.id(), "parent", "b.txt");

        assertThatThrownBy(() -> context.explorer().move(home.id(), "parent", "parent/child", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("descendant");
        assertThatThrownBy(() -> context.explorer().copy(home.id(), "parent", "parent/child", "copy"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("descendant");
        assertThatThrownBy(() -> context.explorer().rename(home.id(), "parent/a.txt", "b.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target already exists");
        assertThatThrownBy(() -> context.explorer().copy(home.id(), "parent/a.txt", "parent", "b.txt"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target already exists");
    }

    @Test
    void renameMoveCopyAndDeleteRejectNestedSymlinks() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path parent = Files.createDirectories(tempDir.resolve("data/workspace/agent-1/home/parent"));
        Files.createDirectories(tempDir.resolve("data/workspace/agent-1/home/archive"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        try {
            Files.createSymbolicLink(parent.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> context.explorer().rename(home.id(), "parent", "renamed"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> context.explorer().move(home.id(), "parent", "archive", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> context.explorer().copy(home.id(), "parent", "archive", "copy"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
        assertThatThrownBy(() -> context.explorer().delete(home.id(), "parent", WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
    }

    @Test
    void createDirectoryRejectsSymlinkAncestorBeforeExternalMutation() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path root = tempDir.resolve("data/workspace/agent-1/home");
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThatThrownBy(() -> context.explorer().createDirectory(home.id(), "escape/new/leaf"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("symbolic links");
        assertThat(outside.resolve("new")).doesNotExist();
    }

    @Test
    void preservesCrLfAndStripsUtf8BomOnSave() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path file = Files.createDirectories(tempDir.resolve("data/workspace/agent-1/home/notes"))
            .resolve("crlf.txt");
        Files.writeString(file, "one\r\ntwo\r\n", java.nio.charset.StandardCharsets.UTF_8);

        context.explorer().saveText(home.id(), "notes/crlf.txt", "\uFEFFalpha\nbeta\n");

        assertThat(Files.readString(file)).isEqualTo("alpha\r\nbeta\r\n");
    }

    @Test
    void previewsNormalSizeLargeTextAndMarkdownWithUtf8Validation() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path notes = Files.createDirectories(tempDir.resolve("data/workspace/agent-1/home/notes"));
        String largeMarkdown = "# Heading\n" + "body\n".repeat(70_000);
        Files.writeString(notes.resolve("large.md"), largeMarkdown, StandardCharsets.UTF_8);
        byte[] invalid = new byte[300 * 1024];
        Arrays.fill(invalid, (byte) 'a');
        invalid[invalid.length - 1] = (byte) 0xFF;
        Files.write(notes.resolve("invalid.txt"), invalid);

        WorkAreaExplorerService.FilePreview markdown = context.explorer().preview(home.id(), "notes/large.md");
        WorkAreaExplorerService.FilePreview invalidPreview = context.explorer().preview(home.id(), "notes/invalid.txt");

        assertThat(markdown.text()).isTrue();
        assertThat(markdown.kind()).isEqualTo("markdown");
        assertThat(markdown.requiresWarning()).isFalse();
        assertThat(markdown.content()).isEqualTo(largeMarkdown);
        assertThat(invalidPreview.text()).isFalse();
        assertThat(invalidPreview.kind()).isEqualTo("invalid_utf8");
    }

    @Test
    void saveTextRequiresExistingFile() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);

        assertThatThrownBy(() -> context.explorer().saveText(home.id(), "missing/new.txt", "hello"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsTraversalBinaryTextSaveAndHomeDelete() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);

        assertThatThrownBy(() -> context.explorer().list(home.id(), "../outside"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("escapes Work Area");
        assertThatThrownBy(() -> context.explorer().list(home.id(), "C:\\Windows\\system32"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("absolute paths");
        Files.writeString(tempDir.resolve("data/workspace/agent-1/home/image.png"), "not image");
        assertThatThrownBy(() -> context.explorer().saveText(home.id(), "image.png", "not image"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not safe for text editing");
        assertThatThrownBy(() -> context.explorer().deleteRecursive(home.id(), ".", "home"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Work Area root is protected");
    }

    @Test
    void recursiveDeleteRequiresConfirmationAndRejectsSymlinkEscape() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        Path deleteMe = Files.createDirectories(tempDir.resolve("data/workspace/agent-1/home/delete-me"));
        Files.writeString(deleteMe.resolve("note.txt"), "x");

        assertThatThrownBy(() -> context.explorer().deleteRecursive(home.id(), "delete-me", "wrong"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confirmation");
        WorkAreaExplorerService.DeleteResult result = context.explorer()
            .deleteRecursive(home.id(), "delete-me", "delete-me");
        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(Files.exists(deleteMe)).isFalse();

        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = tempDir.resolve("data/workspace/agent-1/home/link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }
        assertThatThrownBy(() -> context.explorer().preview(home.id(), "link"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recursiveDeleteProtectsActiveWorkAreaTargets() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        context.explorer().createDirectory(home.id(), "parent/active");
        context.jdbc().update("""
                insert into work_areas (
                    id, owner_type, owner_id, workspace_id, root_relative_path, area_relative_path,
                    display_name, system_flag, home_flag, active_flag, metadata_json, created_at, updated_at
                )
                values (?, ?, ?, ?, ?, ?, ?, 0, 0, 1, '{}', ?, ?)
                """,
            "legacy-active",
            home.ownerType().name(),
            home.ownerId(),
            home.workspaceId(),
            home.rootRelativePath(),
            "home/parent/active",
            "Active",
            java.time.Instant.now().toString(),
            java.time.Instant.now().toString()
        );
        context.jdbc().execute("""
            create table work_assignments (
                selected_work_area_id text,
                output_work_area_id text,
                status text
            )
            """);
        context.jdbc().update(
            "insert into work_assignments(selected_work_area_id, status) values (?, 'QUEUED')",
            "legacy-active"
        );

        assertThatThrownBy(() -> context.explorer().deleteRecursive(home.id(), "parent", "parent"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("active Work Area paths are protected");
        assertThatThrownBy(() -> context.explorer().deleteRecursive("legacy-active", ".", "active"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Work Area root is protected");
    }

    @Test
    void directoryDeleteUsesTwoStepModalSemantics() throws Exception {
        TestContext context = context();
        WorkArea home = context.workAreaService().ensureHome(WorkspaceOwnerType.AGENT, "agent-1", null);
        context.explorer().createDirectory(home.id(), "delete-me");
        context.explorer().createTextFile(home.id(), "delete-me", "note.txt");

        WorkAreaExplorerService.DeletePreflight intent =
            context.explorer().deletePreflight(home.id(), "delete-me", WorkAreaExplorerService.DeleteStep.INTENT);

        assertThat(intent.directory()).isTrue();
        assertThat(intent.executable()).isFalse();
        assertThat(intent.requiredStep()).isEqualTo(WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThatThrownBy(() -> context.explorer().delete(home.id(), "delete-me", WorkAreaExplorerService.DeleteStep.INTENT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confirmation step");

        WorkAreaExplorerService.DeleteResult result =
            context.explorer().delete(home.id(), "delete-me", WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThat(result.deletedCount()).isEqualTo(2);
    }

    private TestContext context() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource("jdbc:sqlite::memory:?foreign_keys=true", true));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(jdbc);
        WorkAreaRepository workAreaRepository = new WorkAreaRepository(jdbc);
        WorkspaceFileActionLogRepository actionLogRepository = new WorkspaceFileActionLogRepository(jdbc);
        WorkspaceFileMetadataRepository metadataRepository = new WorkspaceFileMetadataRepository(jdbc);
        WorkspaceFileMetadataService metadataService =
            new WorkspaceFileMetadataService(metadataRepository, actionLogRepository);
        Path dataRoot = Files.createDirectories(tempDir.resolve("data"));
        AiConfig aiConfig = new AiConfig(null, null, null, null, null, 10, dataRoot, null, Map.of(), Map.of());
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(dataRoot);
        WorkspaceService workspaceService = new WorkspaceService(
            workspaceRepository,
            aiConfig,
            new RootRelativePathService(directoryService)
        );
        WorkAreaService workAreaService = new WorkAreaService(workAreaRepository, workspaceService, directoryService);
        return new TestContext(
            jdbc,
            workAreaService,
            new WorkAreaExplorerService(workAreaService, metadataService, actionLogRepository),
            metadataRepository,
            actionLogRepository
        );
    }

    private record TestContext(
        JdbcTemplate jdbc,
        WorkAreaService workAreaService,
        WorkAreaExplorerService explorer,
        WorkspaceFileMetadataRepository metadataRepository,
        WorkspaceFileActionLogRepository actionLogRepository
    ) {
    }
}
