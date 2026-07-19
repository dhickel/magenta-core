package io.mindspice.magenta2.api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.config.user.AiConfig;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfile;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileStatus;
import io.mindspice.magenta2.ai.orchestration.agents.AgentProfileService;
import io.mindspice.magenta2.ai.orchestration.runtime.AssignmentService;
import io.mindspice.magenta2.ai.orchestration.runtime.JobDefinition;
import io.mindspice.magenta2.ai.orchestration.runtime.JobService;
import io.mindspice.magenta2.ai.orchestration.runtime.WorkAssignment;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessage;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessageToType;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxMessageType;
import io.mindspice.magenta2.ai.orchestration.workflow.InboxService;
import io.mindspice.magenta2.ai.orchestration.workspaces.OutputArtifactService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RootRelativePathService;
import io.mindspice.magenta2.ai.orchestration.workspaces.RunOutputArtifact;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionLogRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileMetadataRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileMetadataService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceRepository;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceService;
import io.mindspice.magenta2.avatar.AvatarEvent;
import io.mindspice.magenta2.avatar.AvatarRepository;
import io.mindspice.magenta2.avatar.AvatarSchemaInitializer;
import io.mindspice.magenta2.avatar.AvatarService;
import io.mindspice.magenta2.avatar.AvatarTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.server.ResponseStatusException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;

class AvatarDashboardControllerTest {
    @TempDir
    Path tempDir;

    private AvatarService avatarService;
    private WorkAreaService workAreaService;
    private WorkAreaExplorerService workAreaExplorerService;
    private AvatarDashboardController controller;

    @BeforeEach
    void setUp() throws IOException {
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true",
            true
        );
        new AvatarSchemaInitializer(dataSource).initialize();
        avatarService = new AvatarService(new AvatarRepository(new JdbcTemplate(dataSource), new ObjectMapper()));
        avatarService.appendEvent(new AvatarEvent(
            "alert-1",
            "alert.manual",
            Map.of("body", "Check inbox"),
            Instant.parse("2026-05-22T10:00:00Z")
        ));
        JdbcTemplate runtimeJdbc = new JdbcTemplate(new SingleConnectionDataSource(
            "jdbc:sqlite::memory:?foreign_keys=true",
            true
        ));
        WorkspaceRepository workspaceRepository = new WorkspaceRepository(runtimeJdbc);
        WorkAreaRepository workAreaRepository = new WorkAreaRepository(runtimeJdbc);
        WorkspaceFileActionLogRepository actionLogRepository = new WorkspaceFileActionLogRepository(runtimeJdbc);
        WorkspaceFileMetadataRepository metadataRepository = new WorkspaceFileMetadataRepository(runtimeJdbc);
        WorkspaceFileMetadataService metadataService =
            new WorkspaceFileMetadataService(metadataRepository, actionLogRepository);
        AiConfig runtimeConfig = new AiConfig(
            null,
            null,
            null,
            10,
            tempDir.resolve("data"),
            Map.of(),
            Map.of()
        );
        WorkspaceDirectoryService directoryService = WorkspaceDirectoryServiceTestSupport.directoryService(tempDir.resolve("data"));
        WorkspaceService workspaceService = new WorkspaceService(
            workspaceRepository,
            runtimeConfig,
            new RootRelativePathService(directoryService)
        );
        workAreaService = new WorkAreaService(workAreaRepository, workspaceService, directoryService);
        workAreaExplorerService = new WorkAreaExplorerService(workAreaService, metadataService, actionLogRepository);
        controller = new AvatarDashboardController(
            avatarService,
            new StubChatService(),
            new StubOutputArtifactService(tempDir),
            new StubAgentProfileService(),
            new StubJobService(),
            new EmptyAssignmentProvider(),
            new FixedProvider<>(workAreaService),
            new FixedProvider<>(workAreaExplorerService),
            new StubInboxService()
        );
    }

    @Test
    void avatarShellRendersCompactChatWidgetRootsAndScopedAssets() {
        String html = controller.avatar(false);

        assertThat(html).contains("/css/avatar-dashboard.css?v=4");
        assertThat(html).contains("/js/avatar-chat.js?v=3");
        assertThat(html).contains("/js/avatar-layout-edit.js?v=1");
        assertThat(html).contains("/js/avatar-shell.js?v=6");
        assertThat(html).doesNotContain("/js/chat-client.js");
        assertThat(html).contains("id=\"content-area\" class=\"avatar-content-area\"");
        assertThat(html).contains("id=\"avatar-chat\"");
        assertThat(html).contains("data-avatar-chat=\"true\"");
        assertThat(html).contains("data-avatar-chat-corner-resizer=\"true\"");
        assertThat(html).doesNotContain("data-avatar-chat-resizer=\"true\"");
        assertThat(html).contains("data-avatar-shell=\"true\"");
        int railIndex = html.indexOf("class=\"avatar-shell-rail\"");
        int mainIndex = html.indexOf("class=\"avatar-shell-main\"");
        assertThat(railIndex).isGreaterThan(-1);
        assertThat(mainIndex).isGreaterThan(railIndex);
        assertThat(html).contains("id=\"avatar-tab-panel\"");
        assertThat(html).contains("data-avatar-tab=\"dashboard\"");
        assertThat(html).contains("data-avatar-tab=\"queue\"");
        assertThat(html).contains("data-avatar-tab=\"history\"");
        assertThat(html).contains("data-avatar-tab=\"profile\"");
        assertThat(html).contains("data-avatar-tab=\"outputs\"");
        assertThat(html).contains("data-avatar-tab=\"work-areas\"");
        assertPrimaryTopNav(html);
        assertThat(html).doesNotContain("Organizer");
        assertThat(html).doesNotContain("Refresh Widgets");
        for (AvatarDashboardComponents.WidgetDefinition widget : AvatarDashboardComponents.WIDGETS) {
            assertThat(html).contains("id=\"avatar-widget-" + widget.key() + "\"");
        }

        String editHtml = controller.avatar(true);
        assertThat(editHtml).contains("avatar-widget-grid-editing");
        assertThat(editHtml).contains("Dashboard edit mode");
        assertThat(editHtml).contains("avatar-icon-link");

        controller.addLayoutRow();
        controller.addLayoutWidget(avatarService.dashboardRows().getFirst().id(), "todos", 4);
        String editRowsHtml = controller.avatar(true);
        assertThat(editRowsHtml).contains("editable-row-wrapper");
        assertThat(editRowsHtml).contains("add-module-section");
        assertThat(editRowsHtml).contains("insert-row-section");
        assertThat(editRowsHtml).contains("avatar-row-decoration");
        assertThat(editRowsHtml).contains("avatar-widget-corner-controls");
        assertThat(editRowsHtml).contains("avatar-chat-status");
        assertThat(editRowsHtml).contains("/width-picker");
        assertThat(editRowsHtml).doesNotContain("Refresh Widgets");
        assertThat(editRowsHtml).doesNotContain("avatar-widget-decoration");
    }

    private static void assertPrimaryTopNav(String html) {
        int home = html.indexOf("<a href=\"/\" class=\"navbar-item\">Home</a>");
        int dashboard = html.indexOf("<a href=\"/dashboard\" class=\"navbar-item\">Dashboard</a>");
        int chat = html.indexOf("<a href=\"/chat\" class=\"navbar-item\">Chat</a>");

        assertThat(home).isGreaterThanOrEqualTo(0);
        assertThat(dashboard).isGreaterThan(home);
        assertThat(chat).isGreaterThan(dashboard);
        assertThat(html).doesNotContain("<a href=\"/avatar\" class=\"navbar-item\">Avatar</a>");
    }

    @Test
    void widgetFragmentsReturnStableTargets() {
        String grid = controller.widgets(false);
        String editGrid = controller.widgets(true);
        String todos = controller.widget("todos");

        assertThat(grid).contains("id=\"avatar-widget-grid\"");
        assertThat(editGrid).contains("avatar-widget-grid-editing");
        assertThat(todos).contains("id=\"avatar-widget-todos\"");
        assertThat(todos).contains("hx-post=\"/avatar/_todos\"");
        assertThat(controller.widgetDetail("todos")).contains("avatar-widget-detail-modal");
        assertThatThrownBy(() -> controller.widget("unknown"))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void avatarTabRoutesNormalizeEditModeOutsideDashboard() {
        String queuePage = controller.avatar("queue", true);
        assertThat(queuePage).contains("data-avatar-active-tab=\"queue\"");
        assertThat(queuePage).contains("data-avatar-tab-panel=\"queue\"");
        assertThat(queuePage).doesNotContain("avatar-widget-grid-editing");
        assertThat(queuePage).doesNotContain("Dashboard edit mode");

        String queueFragment = controller.avatarTabPanel("queue", true);
        assertThat(queueFragment).contains("id=\"avatar-tab-panel\"");
        assertThat(queueFragment).contains("avatar-tab-panel-queue");
        assertThat(queueFragment).contains("id=\"avatar-shell-tabs-wrap\"");
        assertThat(queueFragment).contains("hx-swap-oob=\"true\"");
        assertThat(queueFragment).contains("data-avatar-tab=\"queue\"");
        assertThat(queueFragment).doesNotContain("avatar-widget-grid-editing");

        String dashboardFragment = controller.avatarTabPanel("dashboard", true);
        assertThat(dashboardFragment).contains("avatar-widget-grid-editing");
        assertThat(dashboardFragment).contains("id=\"avatar-tab-panel\"");
    }

    @Test
    void workAreaWidgetAndExplorerFragmentsBrowseAndEditFiles() {
        workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");

        String files = controller.widget("files");
        assertThat(files).contains("Work Areas");
        assertThat(files).contains("/avatar/_work-areas/");
        assertThat(files).contains("id=\"avatar-workarea-surface\"");
        assertThat(files).contains("hx-target=\"#avatar-workarea-surface\"");
        assertThat(files).doesNotContain("/explorer\" hx-target=\"#avatar-edit-container\"");
        assertThat(controller.workAreaPlaceholder()).contains("Select a Work Area");

        String workAreaId = workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false).getFirst().id();
        String explorer = controller.workAreaExplorer(workAreaId, ".", null);
        assertThat(explorer).contains("id=\"avatar-workarea-explorer-shell\"");
        assertThat(explorer).contains("hx-get=\"/avatar/_work-areas/placeholder\"");
        assertThat(explorer).contains("hx-target=\"#avatar-workarea-surface\"");
        assertThat(explorer).contains("id=\"avatar-workarea-list-region\"");
        assertThat(explorer).contains("id=\"avatar-workarea-inspector\"");
        assertThat(explorer).contains("id=\"avatar-workarea-modal\"");
        assertThat(explorer).contains("workspace-explorer-toolbar");
        assertThat(explorer).contains(">Back</button>");
        assertThat(explorer).contains(">Forward</button>");
        assertThat(explorer).contains(">Refresh</button>");
        assertThat(explorer).contains(">New Folder</button>");
        assertThat(explorer).contains(">New Text</button>");
        assertThat(explorer).contains(">New Markdown</button>");
        assertThat(explorer).contains("workspace-explorer-pathbar");
        assertThat(explorer).contains("<th>Name</th>");
        assertThat(explorer).contains("<th>File Type</th>");
        assertThat(explorer).contains("<th>Size</th>");
        assertThat(explorer).contains("<th>Created</th>");
        assertThat(explorer).contains("<th>Last Modified</th>");
        assertThat(explorer).contains("<th>Tags</th>");
        assertThat(explorer).contains("<th>Actions</th>");
        assertThat(explorer).doesNotContain("file-explorer-cards");
        assertThat(explorer).doesNotContain("file-explorer-entry");

        String created = controller.createWorkAreaDirectory(workAreaId, ".", "notes");
        assertThat(created).contains("notes");

        String newFileEditor = controller.createWorkAreaTextFile(workAreaId, "notes", "todo.md", "markdown");
        assertThat(newFileEditor).contains("textarea");
        assertThat(newFileEditor).contains("id=\"avatar-workarea-list-region\"");
        assertThat(newFileEditor).contains("hx-swap-oob=\"true\"");

        String savedPreview = controller.saveWorkAreaText(workAreaId, "notes/todo.md", "**hello**\n");
        assertThat(savedPreview).contains("id=\"avatar-workarea-list-region\"");
        assertThat(savedPreview).contains("hx-swap-oob=\"true\"");
        assertThat(savedPreview).contains("id=\"avatar-workarea-inspector\"");
        assertThat(savedPreview).contains("class=\"avatar-modal\"");
        assertThat(savedPreview).doesNotContain("id=\"avatar-workarea-modal\"><div class=\"avatar-modal\"");
        assertThat(savedPreview).contains("avatar-tab-active\">Rendered");
        assertThat(savedPreview).contains("data-viewer-kind=\"markdown\"");
        assertThat(savedPreview).contains("data-active-tab=\"rendered\"");
        assertThat(savedPreview).contains("<strong>hello</strong>");

        String preview = controller.workAreaPreview(workAreaId, "notes/todo.md");
        assertThat(preview).contains("id=\"avatar-workarea-preview\"");
        assertThat(preview).contains("Rendered");

        String viewer = controller.workAreaViewer(workAreaId, "notes/todo.md");
        assertThat(viewer).contains("class=\"avatar-modal\"");
        assertThat(viewer).doesNotContain("id=\"avatar-workarea-modal\"");
        assertThat(viewer).doesNotContain("id=\"avatar-workarea-preview\"");

        String editor = controller.workAreaTextEditor(workAreaId, "notes/todo.md");
        assertThat(editor).contains("textarea");
        assertThat(editor).contains("hx-put=\"/avatar/_work-areas/" + workAreaId + "/text?path=notes%2Ftodo.md\"");

        String marked = controller.markNestedWorkArea(workAreaId, "notes", "Notes");
        assertThat(marked).contains("notes");
        assertThat(workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false))
            .anySatisfy(workArea -> assertThat(workArea.displayName()).isEqualTo("Notes"));
    }

    @Test
    void workAreaExplorerFragmentsExposeStableRoutesTargetsAndOperationForms() throws Exception {
        workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false).getFirst().id();
        workAreaExplorerService.createDirectory(workAreaId, "notes");
        workAreaExplorerService.createTextFile(workAreaId, "notes", "todo.txt");
        workAreaExplorerService.saveText(workAreaId, "notes/todo.txt", "hello");
        workAreaExplorerService.ensureTag("note", "Note");
        workAreaExplorerService.ensureTag("work-area", "Work Area");
        workAreaExplorerService.ensureTag("project-alpha", "Project Alpha");
        workAreaExplorerService.ensureTag("review", "Review");
        workAreaExplorerService.addLabel(workAreaId, "notes/todo.txt", "note");
        workAreaExplorerService.addLabel(workAreaId, "notes/todo.txt", "work-area");
        workAreaExplorerService.addLabel(workAreaId, "notes/todo.txt", "project-alpha");
        workAreaExplorerService.addLabel(workAreaId, "notes/todo.txt", "review");

        String shell = controller.workAreaExplorer(workAreaId, "notes", "notes/todo.txt");
        assertThat(shell).contains("id=\"avatar-workarea-explorer-shell\"");
        assertThat(shell).contains("id=\"avatar-workarea-list-region\"");
        assertThat(shell).contains("id=\"avatar-workarea-inspector\"");
        assertThat(shell).contains("id=\"avatar-workarea-modal\"");
        assertThat(shell).contains("avatar-workarea-explorer-layout");
        assertThat(shell).contains("workspace-explorer-table-region");
        assertThat(shell).contains("file-explorer-inspector-pane");
        assertThat(shell).contains("data-workarea-path=\"notes/todo.txt\"");
        assertThat(shell).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/explorer?path=notes&selected=notes%2Ftodo.txt\"");
        assertThat(shell).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/viewer?path=notes%2Ftodo.txt\"");
        assertThat(shell).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/rename?path=notes%2Ftodo.txt\"");
        assertThat(shell).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/delete?path=notes%2Ftodo.txt\"");
        assertThat(shell).contains("+1");
        assertThat(shell).doesNotContain("file-explorer-cards");
        assertThat(shell).doesNotContain("file-explorer-entry");

        String list = controller.workAreaExplorerList(workAreaId, "notes", "notes/todo.txt");
        assertThat(list).contains("id=\"avatar-workarea-list-region\"");
        assertThat(list).contains("<th>Name</th>");
        assertThat(list).contains("<th>File Type</th>");
        assertThat(list).contains("<th>Size</th>");
        assertThat(list).contains("<th>Created</th>");
        assertThat(list).contains("<th>Last Modified</th>");
        assertThat(list).contains("<th>Tags</th>");
        assertThat(list).contains("<th>Actions</th>");
        assertThat(list).contains("selected");
        assertThat(list).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/viewer?path=notes%2Ftodo.txt\"");
        assertThat(list).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/rename?path=notes%2Ftodo.txt\"");
        assertThat(list).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/delete?path=notes%2Ftodo.txt\"");
        assertThat(list).doesNotContain("file-explorer-cards");

        String inspect = controller.workAreaInspector(workAreaId, "notes/todo.txt");
        assertThat(inspect).contains("id=\"avatar-workarea-inspector\"");
        assertThat(inspect).contains("file-explorer-inspector-pane");
        assertThat(inspect).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/tags\"");
        assertThat(inspect).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/viewer?path=notes%2Ftodo.txt\"");
        assertThat(inspect).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/rename?path=notes%2Ftodo.txt\"");
        assertThat(inspect).contains("hx-get=\"/avatar/_work-areas/" + workAreaId + "/modal/delete?path=notes%2Ftodo.txt\"");
        assertThat(inspect).contains("file-operation-stack");
        assertThat(inspect).contains("<details class=\"file-operation-group\" open>");
        assertThat(inspect).contains("data-file-action=\"copy\"");
        assertThat(inspect).contains("aria-label=\"Copy destination directory\"");
        assertThat(inspect).contains("required");
        assertThat(inspect).contains("data-file-action-submit=\"copy\"");
        assertThat(inspect).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/action/copy\"");
        assertThat(inspect).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/action/move\"");

        String rename = controller.workAreaActionModal(workAreaId, "rename", "notes/todo.txt");
        assertThat(rename).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/rename\"");
        assertThat(rename).contains("hx-target=\"#avatar-workarea-modal\"");

        String copy = controller.workAreaActionModal(workAreaId, "copy", "notes/todo.txt");
        assertThat(copy).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/action/copy\"");
        assertThat(copy).contains("name=\"destination\"");
        assertThat(copy).contains("aria-label=\"Copy destination directory\"");
        assertThat(copy).contains("required");

        String move = controller.workAreaActionModal(workAreaId, "move", "notes/todo.txt");
        assertThat(move).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/action/move\"");

        String delete = controller.workAreaActionModal(workAreaId, "delete", "notes/todo.txt");
        assertThat(delete).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/delete\"");
        assertThat(delete).contains("name=\"step\" value=\"FILE_CONFIRM\"");

        String tag = controller.workAreaActionModal(workAreaId, "tag", "notes/todo.txt");
        assertThat(tag).contains("hx-post=\"/avatar/_work-areas/" + workAreaId + "/files/tags\"");
    }

    @Test
    void workAreaMutationsReturnOobRefreshesForListInspectorAndModal() throws Exception {
        workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false).getFirst().id();
        workAreaExplorerService.createDirectory(workAreaId, "notes");
        workAreaExplorerService.createDirectory(workAreaId, "archive");
        workAreaExplorerService.createDirectory(workAreaId, "notes/dest");
        workAreaExplorerService.createTextFile(workAreaId, "notes", "todo.txt");

        String renamed = controller.renameWorkAreaPath(workAreaId, "notes/todo.txt", "renamed.txt");
        assertOobRefresh(renamed);
        assertThat(renamed).contains("renamed.txt");

        String copied = controller.copyMoveWorkAreaPath(workAreaId, "copy", "notes/renamed.txt", "archive", "copy.txt");
        assertOobRefresh(copied);
        assertThat(copied).contains("copy.txt");

        String moved = controller.copyMoveWorkAreaPath(workAreaId, "move", "archive/copy.txt", "notes", "moved.txt");
        assertOobRefresh(moved);
        assertThat(moved).contains("moved.txt");

        String copiedToSiblingDestination = controller.copyMoveWorkAreaPath(
            workAreaId,
            "copy",
            "notes/renamed.txt",
            "dest",
            "sibling-copy.txt"
        );
        assertOobRefresh(copiedToSiblingDestination);
        assertThat(copiedToSiblingDestination).contains("sibling-copy.txt");
        assertThat(workAreaExplorerService.inspect(workAreaId, "notes/dest/sibling-copy.txt").regularFile()).isTrue();

        String missingDestination = controller.copyMoveWorkAreaPath(workAreaId, "copy", "notes/renamed.txt", "", "bad.txt");
        assertThat(missingDestination).contains("File action failed");
        assertThat(missingDestination).contains("destination directory is required");

        String deleted = controller.deleteWorkAreaPathStep(workAreaId, "notes/moved.txt", "FILE_CONFIRM");
        assertOobRefresh(deleted);
        assertThat(deleted).contains("Deleted notes/moved.txt");
    }

    @Test
    void workAreaViewerRejectsUnsupportedFilesAndTextSaveErrorsAreVisible() throws Exception {
        var home = workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = home.id();
        Path root = workAreaService.resolve(home);
        Files.write(root.resolve("blob.bin"), new byte[] {0, 1, 2, 3});

        String unsupported = controller.workAreaViewer(workAreaId, "blob.bin");
        assertThat(unsupported).contains("class=\"avatar-modal\"");
        assertThat(unsupported).doesNotContain("id=\"avatar-workarea-modal\"");
        assertThat(unsupported).contains("Viewer unavailable for this file type or size.");

        String save = controller.saveWorkAreaText(workAreaId, "blob.bin", "oops");
        assertThat(save).contains("class=\"avatar-modal\"");
        assertThat(save).doesNotContain("id=\"avatar-workarea-modal\"");
        assertThat(save).contains("Save failed");
        assertThat(save).contains("not safe for text editing");
    }

    @Test
    void workAreaViewerSupportsMarkdownTextImageAndFriendlyMarkdownFailure() throws Exception {
        var home = workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = home.id();
        Path root = workAreaService.resolve(home);
        Files.writeString(root.resolve("note.md"), "# Heading\n\nbody");
        Files.writeString(root.resolve("plain.txt"), "raw text");
        Files.write(root.resolve("pic.png"), new byte[] {1, 2, 3, 4});

        String markdown = controller.workAreaViewer(workAreaId, "note.md");
        assertThat(markdown).contains("class=\"avatar-modal\"");
        assertThat(markdown).doesNotContain("id=\"avatar-workarea-modal\"");
        assertThat(markdown).contains("data-viewer-kind=\"markdown\"");
        assertThat(markdown).contains("data-active-tab=\"rendered\"");
        assertThat(markdown).contains("avatar-tab-active\">Rendered");
        assertThat(markdown).contains(">Text</button>");
        assertThat(markdown).contains("<h1>Heading</h1>");

        String markdownText = controller.workAreaTextViewer(workAreaId, "note.md", "text");
        assertThat(markdownText).contains("textarea");
        assertThat(markdownText).contains("data-viewer-kind=\"markdown\"");
        assertThat(markdownText).contains("data-active-tab=\"text\"");
        assertThat(markdownText).contains(">Rendered</button>");
        assertThat(markdownText).contains("avatar-tab-active\">Text");

        String plainText = controller.workAreaViewer(workAreaId, "plain.txt");
        assertThat(plainText).contains("textarea");
        assertThat(plainText).contains("data-viewer-kind=\"text\"");
        assertThat(plainText).contains("data-active-tab=\"text\"");
        assertThat(plainText).contains("avatar-tab-active\">Text");
        assertThat(plainText).doesNotContain(">Rendered</button>");

        String image = controller.workAreaViewer(workAreaId, "pic.png");
        assertThat(image).contains("avatar-workarea-image-frame");
        assertThat(image).contains("<img class=\"avatar-workarea-image\"");
        assertThat(image).contains("/api/work-areas/" + workAreaId + "/files/view?path=pic.png");
        assertThat(image).contains("/api/work-areas/" + workAreaId + "/files/download?path=pic.png");

        String failedMarkdown = WorkAreaExplorerFragments.renderedMarkdownForTest(
            "# broken",
            content -> {
                throw new RuntimeException("forced");
            }
        );
        assertThat(failedMarkdown).contains("avatar-workarea-render-fallback");
        assertThat(failedMarkdown).contains("# broken");
        assertThat(failedMarkdown).contains("avatar-workarea-render-error");
        assertThat(failedMarkdown).contains("Raw text is still available");
    }

    @Test
    void workAreaTagRoutesCreateAssignAndRemoveCustomTags() throws Exception {
        workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false).getFirst().id();
        workAreaExplorerService.createDirectory(workAreaId, "notes");

        String created = controller.createWorkAreaTag(workAreaId, "project-alpha", "Project Alpha");
        assertThat(created).contains("Tag is ready to assign: project-alpha");

        String added = controller.addWorkAreaTag(workAreaId, "notes", "project-alpha");
        assertOobRefresh(added);
        assertThat(added).contains("id=\"avatar-workarea-inspector\"");
        assertThat(added).contains("project-alpha");
        assertThat(added).contains("hx-delete=\"/avatar/_work-areas/" + workAreaId + "/files/tags?path=notes&amp;label=project-alpha\"");

        String removed = controller.removeWorkAreaTag(workAreaId, "notes", "project-alpha");
        assertOobRefresh(removed);
        assertThat(removed).contains("Tag removed");
        assertThat(removed).contains("No tags");
    }

    @Test
    void workAreaFragmentValidationErrorsReturnVisibleFragments() {
        workAreaService.ensureHome(WorkspaceOwnerType.AGENT, "agent-1", "Home");
        String workAreaId = workAreaService.list(WorkspaceOwnerType.AGENT, "agent-1", false).getFirst().id();

        assertThat(controller.workAreaExplorerList(workAreaId, "../escape", null))
            .contains("id=\"avatar-workarea-list-region\"")
            .contains("path escapes Work Area");
        assertThat(controller.workAreaInspector(workAreaId, "../escape"))
            .contains("id=\"avatar-workarea-inspector\"")
            .contains("path escapes Work Area");
        assertThat(controller.workAreaActionModal(workAreaId, "delete", "../escape"))
            .contains("class=\"avatar-modal\"")
            .doesNotContain("id=\"avatar-workarea-modal\"")
            .contains("Action unavailable");
    }

    private void assertOobRefresh(String html) {
        assertThat(html).contains("id=\"avatar-workarea-modal\" hx-swap-oob=\"true\"");
        assertThat(html).contains("id=\"avatar-workarea-list-region\" class=\"workspace-explorer-table-region\" hx-swap-oob=\"true\"");
        assertThat(html).contains("id=\"avatar-workarea-inspector\" class=\"file-explorer-inspector-pane\" hx-swap-oob=\"true\"");
    }

    @Test
    void rowLayoutEditorAddsMovesResizesAndRemovesWidgets() {
        String emptyEditor = controller.edit(false);
        assertThat(emptyEditor).isEmpty();

        String afterRow = controller.addLayoutRow();
        String rowId = avatarService.dashboardRows().getFirst().id();
        assertThat(afterRow).contains("hx-swap-oob=\"true\"");
        assertThat(afterRow).contains("/avatar/_layout/rows/" + rowId + "/catalog");
        assertThat(afterRow).contains("avatar-empty-row-insert");
        assertThat(afterRow).doesNotContain("/avatar/_layout/rows/" + rowId + "/insert-after");

        String catalog = controller.widgetCatalog(rowId);
        assertThat(catalog).contains("Add Widget");
        assertThat(catalog).contains("daily-tasks");
        assertThat(catalog).contains("avatar-modal avatar-widget-picker");
        assertThat(catalog).contains("avatar-widget-picker-modal");

        String inserted = controller.insertLayoutRowAfter(rowId);
        assertThat(inserted).contains("hx-swap-oob=\"true\"");
        assertThat(inserted).contains("avatar-widget-catalog");
        assertThat(inserted).contains("avatar-empty-row-insert");
        assertThat(avatarService.dashboardRows()).hasSize(2);

        assertThatThrownBy(() -> controller.addLayoutWidget(rowId, "unknown", 4))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        controller.addLayoutWidget(rowId, "todos", 4);
        controller.addLayoutWidget(rowId, "notes", 4);
        String todosId = avatarService.dashboardRows().getFirst().widgets().stream()
            .filter(widget -> widget.widgetKey().equals("todos"))
            .findFirst()
            .orElseThrow()
            .id();
        String notesId = avatarService.dashboardRows().getFirst().widgets().stream()
            .filter(widget -> widget.widgetKey().equals("notes"))
            .findFirst()
            .orElseThrow()
            .id();

        String widthPicker = controller.widgetWidthPicker(todosId);
        assertThat(widthPicker).contains("Widget width");
        assertThat(widthPicker).contains("data-avatar-width-picker");
        assertThat(widthPicker).contains("1/12");
        assertThat(widthPicker).contains("12/12");

        String resized = controller.resizeLayoutWidget(todosId, 5);
        assertThat(resized).contains("id=\"avatar-widget-todos\"");
        assertThat(avatarService.dashboardRows().getFirst().widgets()).anySatisfy(widget -> {
            assertThat(widget.widgetKey()).isEqualTo("todos");
            assertThat(widget.columnWidth()).isEqualTo(5);
        });

        controller.cycleLayoutWidgetWidth(todosId);
        assertThat(avatarService.dashboardRows().getFirst().widgets()).anySatisfy(widget -> {
            assertThat(widget.widgetKey()).isEqualTo("todos");
            assertThat(widget.columnWidth()).isEqualTo(3);
        });

        controller.moveLayoutWidget(notesId, "left");
        assertThat(avatarService.dashboardRows().getFirst().widgets())
            .extracting(widget -> widget.widgetKey())
            .containsExactly("notes", "todos");

        controller.removeLayoutWidget(notesId);
        assertThat(avatarService.dashboardRows().getFirst().widgets())
            .extracting(widget -> widget.widgetKey())
            .containsExactly("todos");

        assertThatThrownBy(() -> controller.removeLayoutRow(rowId))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(error -> ((ResponseStatusException) error).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);

        controller.removeLayoutWidget(todosId);
        assertThat(controller.removeLayoutRow(rowId)).contains("avatar-widget-grid-editing");

        controller.addLayoutRow();
        controller.addLayoutRow();
        String secondRowId = avatarService.dashboardRows().get(1).id();
        controller.moveLayoutRow(secondRowId, "up");
        assertThat(avatarService.dashboardRows().getFirst().id()).isEqualTo(secondRowId);
    }

    @Test
    void organizerEndpointsMutateAvatarServicesAndReturnWidgets() {
        String todoHtml = controller.createTodo("Pay bills", "checking", "HIGH");
        assertThat(todoHtml).contains("Pay bills");
        assertThat(avatarService.todos()).singleElement()
            .satisfies(todo -> assertThat(todo.priority().name()).isEqualTo("HIGH"));

        String dailyHtml = controller.createDailyTask("Review day", null);
        assertThat(dailyHtml).contains("Review day");
        assertThat(avatarService.dailyTasks(LocalDate.now())).singleElement()
            .satisfies(task -> assertThat(task.status()).isEqualTo(AvatarTaskStatus.PLANNED));

        String notesHtml = controller.createNote("Garden", "Water seedlings");
        assertThat(notesHtml).contains("Garden");
        assertThat(avatarService.notes(false)).singleElement()
            .satisfies(note -> assertThat(note.body()).contains("Water seedlings"));

        String organizer = controller.organizer("planner");
        assertThat(organizer).contains("Planner");
        assertThat(organizer).contains("/avatar/_planner-tasks");

        String plannerHtml = controller.createPlannerTask(
            "Review projects",
            "Look for blocked work",
            "HIGH",
            "",
            "",
            "DAILY",
            1,
            LocalDate.now().toString(),
            LocalDate.now().plusDays(2).toString(),
            "09:00",
            "",
            null,
            "",
            "project-1",
            "",
            "",
            ""
        );
        assertThat(plannerHtml).contains("Review projects");
        String taskId = avatarService.plannerTasks().getFirst().id();
        String subtodoHtml = controller.createPlannerSubtodo(taskId, "Check queue");
        assertThat(subtodoHtml).contains("Check queue");
        assertThat(controller.organizer("calendar")).contains("Planner projection");
    }

    @Test
    void outputPreviewUsesArtifactService() {
        String html = controller.outputPreview("artifact-1");

        assertThat(html).contains("summary");
        assertThat(html).contains("hello output");
        assertThat(html).contains("/api/outputs/artifact-1/download");
    }

    @Test
    void alertDismissAppendsInternalAvatarEventOnly() {
        String html = controller.dismissAlert("alert-1");

        assertThat(html).contains("id=\"avatar-widget-alerts\"");
        assertThat(avatarService.events()).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo("alert.dismissed");
            assertThat(event.payload()).containsEntry("eventId", "alert-1");
        });
    }

    private static class StubChatService extends ChatService {
        StubChatService() {
            super(null, null, null, null, null);
        }

        @Override
        public String defaultModel() {
            return "qwen3";
        }
    }

    private static class StubOutputArtifactService extends OutputArtifactService {
        StubOutputArtifactService(Path tempDir) throws IOException {
            super(null, WorkspaceDirectoryServiceTestSupport.directoryService(tempDir), new ObjectMapper());
        }

        @Override
        public List<RunOutputArtifact> query(String runId, String planId, String artifactType, Integer limit) {
            return List.of(artifact());
        }

        @Override
        public RunOutputArtifact getArtifact(String artifactId) {
            return artifact();
        }

        @Override
        public String loadContent(String artifactId, long maxBytes) {
            return "hello output";
        }

        private RunOutputArtifact artifact() {
            return new RunOutputArtifact(
                "artifact-1",
                "run-1",
                "plan-1",
                "avatar",
                "job-1",
                "project-1",
                "workspace-1",
                "PLAN",
                "summary",
                "text",
                "summary.txt",
                "outputs/summary.txt",
                null,
                Instant.parse("2026-05-22T10:00:00Z")
            );
        }
    }

    private static class StubAgentProfileService extends AgentProfileService {
        StubAgentProfileService() {
            super(null, null, null);
        }

        @Override
        public List<AgentProfile> list() {
            return List.of(new AgentProfile(
                "agent-1",
                "Research Agent",
                AgentProfileStatus.ACTIVE,
                "qwen3",
                null,
                List.of(),
                List.of(),
                false,
                Instant.parse("2026-05-22T10:00:00Z"),
                Instant.parse("2026-05-22T10:00:00Z")
            ));
        }
    }

    private static class StubJobService extends JobService {
        StubJobService() {
            super(null, null, null, null);
        }

        @Override
        public List<JobDefinition> listDefinitions() {
            return List.of();
        }
    }

    private static class StubInboxService extends InboxService {
        StubInboxService() {
            super(null, null);
        }

        @Override
        public List<InboxMessage> userInbox() {
            return List.of(new InboxMessage(
                "message-1",
                InboxMessageToType.USER,
                null,
                "agent-1",
                InboxMessageType.INFO,
                "Internal inbox message",
                null,
                null,
                null,
                null,
                Instant.parse("2026-05-22T10:00:00Z"),
                Instant.parse("2026-05-22T10:00:00Z")
            ));
        }
    }

    private static class EmptyAssignmentProvider implements ObjectProvider<AssignmentService> {
        @Override
        public AssignmentService getObject(Object... args) {
            return null;
        }

        @Override
        public AssignmentService getIfAvailable() {
            return null;
        }

        @Override
        public AssignmentService getIfUnique() {
            return null;
        }

        @Override
        public AssignmentService getObject() {
            return null;
        }

        @Override
        public java.util.Iterator<AssignmentService> iterator() {
            return List.<AssignmentService>of().iterator();
        }

        @Override
        public java.util.stream.Stream<AssignmentService> stream() {
            return java.util.stream.Stream.empty();
        }

        @Override
        public java.util.stream.Stream<AssignmentService> orderedStream() {
            return java.util.stream.Stream.empty();
        }
    }

    private record FixedProvider<T>(T value) implements ObjectProvider<T> {
        @Override
        public T getObject(Object... args) {
            return value;
        }

        @Override
        public T getIfAvailable() {
            return value;
        }

        @Override
        public T getIfUnique() {
            return value;
        }

        @Override
        public T getObject() {
            return value;
        }

        @Override
        public java.util.Iterator<T> iterator() {
            return List.of(value).iterator();
        }

        @Override
        public java.util.stream.Stream<T> stream() {
            return java.util.stream.Stream.of(value);
        }

        @Override
        public java.util.stream.Stream<T> orderedStream() {
            return stream();
        }
    }
}
