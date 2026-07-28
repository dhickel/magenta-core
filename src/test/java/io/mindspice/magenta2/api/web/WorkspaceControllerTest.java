package io.mindspice.magenta2.api.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryServiceTestSupport;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionType;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabel;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabelAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceControllerTest {

    @TempDir
    Path tempDir;

    private WorkspaceDirectoryService directoryService;
    private WorkspaceExplorerService explorerService;
    private WorkspaceController controller;

    @BeforeEach
    void setUp() {
        directoryService = mock(WorkspaceDirectoryService.class);
        explorerService = mock(WorkspaceExplorerService.class);
        controller = new WorkspaceController(directoryService, explorerService);
    }

    private WorkspaceDescriptor agentDescriptor() {
        return WorkspaceDirectoryServiceTestSupport.descriptor(WorkspaceKind.AGENT, "agent-1",
            tempDir.resolve("agent-1"));
    }

    private WorkspaceDescriptor userDescriptor() {
        return WorkspaceDirectoryServiceTestSupport.descriptor(WorkspaceKind.USER, "alice",
            tempDir.resolve("user/alice"));
    }

    @Test
    void parseDeleteStepAndPreflightExecuteFlow() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceExplorerService.RelativePath relPath = new WorkspaceExplorerService.RelativePath("dir");
        WorkspaceExplorerService.DeletePreflight preflight = new WorkspaceExplorerService.DeletePreflight(
            "dir", true, 3, WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM, false);
        WorkspaceExplorerService.DeleteResult deleted = new WorkspaceExplorerService.DeleteResult("dir", 3);
        when(explorerService.normalizeRelativePath("dir")).thenReturn(relPath);
        when(explorerService.deletePreflight(descriptor, relPath, WorkspaceExplorerService.DeleteStep.INTENT)).thenReturn(preflight);
        when(explorerService.delete(descriptor, relPath, WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM)).thenReturn(deleted);

        WorkspaceExplorerService.DeletePreflight preflightResult = controller.deletePreflight("agent", "agent-1", "dir", "INTENT");
        WorkspaceExplorerService.DeleteResult deleteResult = controller.deleteExecute(
            "agent", "agent-1", new WorkspaceController.DeleteExecuteRequest("dir", "DIRECTORY_RECURSIVE_CONFIRM"));

        assertThat(preflightResult.requiredStep()).isEqualTo(WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM);
        assertThat(deleteResult.deletedCount()).isEqualTo(3);
    }

    @Test
    void crudAndLabelRoutesDelegateToService() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceExplorerService.RelativePath relParent = new WorkspaceExplorerService.RelativePath("notes");
        WorkspaceExplorerService.RelativePath relFile = new WorkspaceExplorerService.RelativePath("notes/a.txt");
        WorkspaceExplorerService.RelativePath relDest = new WorkspaceExplorerService.RelativePath("archive");
        when(explorerService.normalizeRelativePath("notes")).thenReturn(relParent);
        when(explorerService.normalizeRelativePath("notes/a.txt")).thenReturn(relFile);
        when(explorerService.normalizeRelativePath("archive")).thenReturn(relDest);

        WorkspaceExplorerService.Entry entry = new WorkspaceExplorerService.Entry(
            "a.txt", "notes/a.txt", false, true, 10, Instant.now(), Instant.now(),
            "Text", "10 B", WorkspaceExplorerService.ViewerKind.TEXT, List.of(),
            true, true, true, true, true, true);
        when(explorerService.createTextFile(descriptor, relParent, "a.txt")).thenReturn(entry);
        when(explorerService.createMarkdownFile(descriptor, relParent, "a.md")).thenReturn(entry);
        when(explorerService.move(descriptor, relFile, relDest, "b.txt")).thenReturn(entry);
        when(explorerService.copy(descriptor, relFile, relDest, "c.txt")).thenReturn(entry);

        assertThat(controller.createTextFile("agent", "agent-1",
            new WorkspaceController.CreateFileRequest("notes", "a.txt")).path()).isEqualTo("notes/a.txt");
        assertThat(controller.createMarkdownFile("agent", "agent-1",
            new WorkspaceController.CreateFileRequest("notes", "a.md")).path()).isEqualTo("notes/a.txt");
        assertThat(controller.move("agent", "agent-1",
            new WorkspaceController.MoveCopyRequest("notes/a.txt", "archive", "b.txt")).path()).isEqualTo("notes/a.txt");
        assertThat(controller.copy("agent", "agent-1",
            new WorkspaceController.MoveCopyRequest("notes/a.txt", "archive", "c.txt")).path()).isEqualTo("notes/a.txt");
    }

    @Test
    void labelAndTagRoutesDelegateToService() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);

        WorkspaceFileLabel label = new WorkspaceFileLabel("l1", "note", "Note", null, true, "{}", Instant.now(), Instant.now());
        WorkspaceFileLabel customLabel = new WorkspaceFileLabel(
            "l2", "project-alpha", "Project Alpha", null, false, "{}", Instant.now(), Instant.now());
        WorkspaceFileLabelAssignment assignment = new WorkspaceFileLabelAssignment(
            "as1", descriptor.identity(), "notes/a.txt",
            label, "{}", Instant.now(), Instant.now());

        when(explorerService.ensureTag("project-alpha", "Project Alpha")).thenReturn(customLabel);
        when(explorerService.addLabel(descriptor, "notes/a.txt", "note")).thenReturn(assignment);
        when(explorerService.labels(descriptor, "notes/a.txt")).thenReturn(List.of(assignment));
        when(explorerService.removeLabel(descriptor, "notes/a.txt", "note")).thenReturn(1);

        assertThat(controller.createTag("agent", "agent-1",
            new WorkspaceController.TagRequest("project-alpha", "Project Alpha")).slug()).isEqualTo("project-alpha");
        assertThat(controller.addLabel("agent", "agent-1",
            new WorkspaceController.LabelRequest("notes/a.txt", "note")).label().slug()).isEqualTo("note");
        assertThat(controller.labels("agent", "agent-1", "notes/a.txt")).hasSize(1);
        assertThat(controller.removeLabel("agent", "agent-1", "notes/a.txt", "note"))
            .containsEntry("removed", 1);
    }

    @Test
    void recentActionsRouteReturnsBoundedResults() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceFileActionRecord record = new WorkspaceFileActionRecord(
            "a1", descriptor.identity(), "web", null,
            WorkspaceFileActionType.SAVE_TEXT, "note.txt", null, "SUCCEEDED", "{}", Instant.now());
        when(explorerService.recentActions(descriptor, 200)).thenReturn(List.of(record));

        List<WorkspaceController.ActionSummary> actions = controller.recentActions("agent", "agent-1", 999);

        assertThat(actions).hasSize(1);
        assertThat(actions.getFirst().actionType()).isEqualTo(WorkspaceFileActionType.SAVE_TEXT.name());
    }

    @Test
    void imageViewStreamsSupportedImageInline() throws Exception {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceExplorerService.RelativePath relPath = new WorkspaceExplorerService.RelativePath("pic.png");
        when(explorerService.normalizeRelativePath("pic.png")).thenReturn(relPath);
        when(explorerService.preview(descriptor, relPath))
            .thenReturn(new WorkspaceExplorerService.FilePreview("pic.png", 3, false, null, false, "image"));
        when(explorerService.download(descriptor, relPath))
            .thenReturn(new WorkspaceExplorerService.DownloadContent("pic.png", new byte[] {1, 2, 3}));

        ResponseEntity<?> response = controller.imageView("agent", "agent-1", "pic.png");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentDisposition().isInline()).isTrue();
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    @Test
    void imageViewRejectsNonImageKinds() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceExplorerService.RelativePath relPath = new WorkspaceExplorerService.RelativePath("note.txt");
        when(explorerService.normalizeRelativePath("note.txt")).thenReturn(relPath);
        when(explorerService.preview(descriptor, relPath))
            .thenReturn(new WorkspaceExplorerService.FilePreview("note.txt", 4, true, "text", false, "text"));

        ResponseEntity<?> response = controller.imageView("agent", "agent-1", "note.txt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "UNSUPPORTED_VIEW", "error", "inline view only supports image files"));
    }

    @Test
    void invalidDeleteStepReturnsBadRequest() {
        WorkspaceDescriptor descriptor = agentDescriptor();
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "agent-1")).thenReturn(descriptor);
        WorkspaceExplorerService.RelativePath relPath = new WorkspaceExplorerService.RelativePath("file.txt");
        when(explorerService.normalizeRelativePath("file.txt")).thenReturn(relPath);

        assertThatThrownBy(() -> controller.deletePreflight("agent", "agent-1", "file.txt", "bad-step"))
            .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void workspaceFileExceptionMapsStatusCodes() {
        ResponseEntity<Map<String, String>> notFound = controller.handleWorkspaceFile(
            new WorkspaceFileException(WorkspaceFileException.Reason.NOT_FOUND, "path does not exist: missing.txt"));
        ResponseEntity<Map<String, String>> conflict = controller.handleWorkspaceFile(
            new WorkspaceFileException(WorkspaceFileException.Reason.CONFLICT, "target already exists"));
        ResponseEntity<Map<String, String>> invalid = controller.handleWorkspaceFile(
            new WorkspaceFileException(WorkspaceFileException.Reason.INVALID, "path escapes workspace"));

        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownWorkspaceTypeReturnsBadRequest() {
        assertThatThrownBy(() -> controller.files("badtype", "id", "."))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> {
                ResponseStatusException rse = (ResponseStatusException) e;
                assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(rse.getReason()).contains("Unknown workspace type");
            });
    }

    @Test
    void encodedSeparatorsAndTraversalIdsReturnBadRequest() {
        for (String id : List.of("agent%2Fescape", "agent%252fescape", "%2e%2e", "agent%5Cescape")) {
            assertThatThrownBy(() -> controller.files("agent", id, "."))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    void apiResponseDtosDoNotExposeCanonicalIdentityOrRoots() {
        assertThat(WorkspaceController.UserSummary.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("type", "name");
        assertThat(WorkspaceController.LabelAssignmentSummary.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("workspaceIdentity", "canonicalIdentity", "root");
        assertThat(WorkspaceController.ActionSummary.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .doesNotContain("workspaceIdentity", "canonicalIdentity", "root");
    }

    @Test
    void workspaceResolutionNotFoundReturns404() {
        when(directoryService.resolveForRead(WorkspaceKind.AGENT, "missing"))
            .thenThrow(new WorkspaceResolutionException(WorkspaceResolutionException.Reason.NOT_FOUND, "Agent not found"));

        assertThatThrownBy(() -> controller.files("agent", "missing", "."))
            .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void workspaceResolutionConflictReturns409() {
        when(directoryService.resolveForRead(WorkspaceKind.USER, "existing"))
            .thenThrow(new WorkspaceResolutionException(WorkspaceResolutionException.Reason.CONFLICT, "User workspace already exists"));

        assertThatThrownBy(() -> controller.createDirectory("user", "existing", new WorkspaceController.PathRequest("dir")))
            .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void userCreateAndListRoutesWork() throws Exception {
        WorkspaceDescriptor userDesc = userDescriptor();
        when(directoryService.listUserWorkspaces()).thenReturn(List.of(userDesc));

        List<WorkspaceController.UserSummary> users = controller.listUsers();
        assertThat(users).hasSize(1);
        assertThat(users.getFirst().type()).isEqualTo("user");
        assertThat(users.getFirst().name()).isEqualTo("alice");
    }
}
