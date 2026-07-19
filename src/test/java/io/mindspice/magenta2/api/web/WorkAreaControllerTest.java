package io.mindspice.magenta2.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkArea;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabel;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabelAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkAreaControllerTest {

    @TempDir
    Path tempDir;

    private WorkAreaService workAreaService;
    private WorkAreaExplorerService explorerService;
    private WorkAreaController controller;

    @BeforeEach
    void setUp() {
        workAreaService = mock(WorkAreaService.class);
        explorerService = mock(WorkAreaExplorerService.class);
        controller = new WorkAreaController(workAreaService, explorerService);
    }

    @Test
    void deletePreflightAndExecuteUseStepSemantics() {
        WorkAreaExplorerService.DeletePreflight preflight = new WorkAreaExplorerService.DeletePreflight(
            "dir", true, 3, WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM, false
        );
        WorkAreaExplorerService.DeleteResult deleted = new WorkAreaExplorerService.DeleteResult("dir", 3);
        when(explorerService.deletePreflight("wa-1", "dir", WorkAreaExplorerService.DeleteStep.INTENT)).thenReturn(preflight);
        when(explorerService.delete("wa-1", "dir", WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM)).thenReturn(deleted);

        WorkAreaExplorerService.DeletePreflight preflightResult = controller.deletePreflight("wa-1", "dir", "INTENT");
        WorkAreaExplorerService.DeleteResult deleteResult = controller.deleteExecute(
            "wa-1", new WorkAreaController.DeleteExecuteRequest("dir", "DIRECTORY_RECURSIVE_CONFIRM")
        );

        assertThat(preflightResult.requiredStep()).isEqualTo(WorkAreaExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThat(deleteResult.deletedCount()).isEqualTo(3);
    }

    @Test
    void createMoveCopyAndLabelRoutesDelegateToService() {
        WorkAreaExplorerService.Entry entry = new WorkAreaExplorerService.Entry(
            "a.txt", "notes/a.txt", false, true, 10, Instant.now()
        );
        WorkspaceFileLabel label = new WorkspaceFileLabel("l1", "note", "Note", null, true, "{}", Instant.now(), Instant.now());
        WorkspaceFileLabel customLabel = new WorkspaceFileLabel(
            "l2", "project-alpha", "Project Alpha", null, false, "{}", Instant.now(), Instant.now()
        );
        WorkspaceFileLabelAssignment assignment = new WorkspaceFileLabelAssignment(
            "as1", "ws1", WorkspaceOwnerType.AGENT, "agent-1", "home/notes/a.txt", "home/notes/a.txt",
            label, "{}", Instant.now(), Instant.now()
        );
        when(explorerService.createTextFile("wa-1", "notes", "a.txt")).thenReturn(entry);
        when(explorerService.createMarkdownFile("wa-1", "notes", "a.md")).thenReturn(entry);
        when(explorerService.move("wa-1", "notes/a.txt", "archive", "b.txt")).thenReturn(entry);
        when(explorerService.copy("wa-1", "notes/a.txt", "archive", "c.txt")).thenReturn(entry);
        when(explorerService.ensureTag("project-alpha", "Project Alpha")).thenReturn(customLabel);
        when(explorerService.addLabel("wa-1", "notes/a.txt", "note")).thenReturn(assignment);
        when(explorerService.labels("wa-1", "notes/a.txt")).thenReturn(List.of(assignment));
        when(explorerService.removeLabel("wa-1", "notes/a.txt", "note")).thenReturn(1);

        assertThat(controller.createTextFile("wa-1", new WorkAreaController.CreateFileRequest("notes", "a.txt")).path())
            .isEqualTo("notes/a.txt");
        assertThat(controller.createMarkdownFile("wa-1", new WorkAreaController.CreateFileRequest("notes", "a.md")).path())
            .isEqualTo("notes/a.txt");
        assertThat(controller.move("wa-1", new WorkAreaController.MoveCopyRequest("notes/a.txt", "archive", "b.txt")).path())
            .isEqualTo("notes/a.txt");
        assertThat(controller.copy("wa-1", new WorkAreaController.MoveCopyRequest("notes/a.txt", "archive", "c.txt")).path())
            .isEqualTo("notes/a.txt");
        assertThat(controller.createTag("wa-1", new WorkAreaController.TagRequest("project-alpha", "Project Alpha")).slug())
            .isEqualTo("project-alpha");
        assertThat(controller.addLabel("wa-1", new WorkAreaController.LabelRequest("notes/a.txt", "note")).label().slug())
            .isEqualTo("note");
        assertThat(controller.labels("wa-1", "notes/a.txt")).hasSize(1);
        assertThat(controller.removeLabel("wa-1", "notes/a.txt", "note")).containsEntry("removed", 1);
    }

    @Test
    void recentActionsRouteReturnsBoundedResults() {
        WorkspaceFileActionRecord record = new WorkspaceFileActionRecord(
            "a1", "ws1", WorkspaceOwnerType.AGENT, "agent-1", "wa-1", "web", null,
            WorkspaceFileActionType.SAVE_TEXT, "home/note.txt", null, "SUCCEEDED", "{}", Instant.now()
        );
        when(explorerService.recentActions("wa-1", 200)).thenReturn(List.of(record));

        List<WorkspaceFileActionRecord> actions = controller.recentActions("wa-1", 999);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().actionType()).isEqualTo(WorkspaceFileActionType.SAVE_TEXT);
    }

    @Test
    void imageViewStreamsSupportedImageInline() throws Exception {
        when(explorerService.preview("wa-1", "pic.png"))
            .thenReturn(new WorkAreaExplorerService.FilePreview("pic.png", 3, false, null, false, "image"));
        when(explorerService.download("wa-1", "pic.png"))
            .thenReturn(new WorkAreaExplorerService.DownloadContent("pic.png", new byte[] {1, 2, 3}));

        ResponseEntity<?> response = controller.imageView("wa-1", "pic.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    @Test
    void imageViewRejectsNonImageKinds() {
        when(explorerService.preview("wa-1", "note.txt"))
            .thenReturn(new WorkAreaExplorerService.FilePreview("note.txt", 4, true, "text", false, "text"));

        ResponseEntity<?> response = controller.imageView("wa-1", "note.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "UNSUPPORTED_VIEW", "error", "inline view only supports image files"));
    }

    @Test
    void invalidDeleteStepReturnsBadRequest() {
        assertThatThrownBy(() -> controller.deletePreflight("wa-1", "file.txt", "bad-step"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void badRequestHandlerMapsNotFoundAndConflict() {
        ResponseEntity<Map<String, String>> notFound = controller.badRequest(
            new IllegalArgumentException("path does not exist: missing.txt")
        );
        ResponseEntity<Map<String, String>> conflict = controller.badRequest(
            new IllegalArgumentException("target already exists")
        );

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void compatibilityDeleteEndpointRemainsAvailable() {
        WorkAreaExplorerService.DeleteResult result = new WorkAreaExplorerService.DeleteResult("a.txt", 1);
        when(explorerService.deleteRecursive("wa-1", "a.txt", "a.txt")).thenReturn(result);

        WorkAreaExplorerService.DeleteResult response = controller.deleteCompat("wa-1", "a.txt", "a.txt");

        assertThat(response.deletedCount()).isEqualTo(1);
    }

    @Test
    void listRouteParsesOwnerType() {
        WorkArea area = new WorkArea(
            "wa-1", WorkspaceOwnerType.AGENT, "agent-1", "ws-1", "home", "home", "Home",
            true, true, true, "{}", Instant.now(), Instant.now()
        );
        when(workAreaService.list(eq(WorkspaceOwnerType.AGENT), eq("agent-1"), eq(false))).thenReturn(List.of(area));

        List<WorkArea> result = controller.list("AGENT", "agent-1", false);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo("wa-1");
    }
}
