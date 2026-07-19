package io.mindspice.magenta2.api.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkArea;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkAreaService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabel;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabelAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceOwnerType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/work-areas")
public class WorkAreaController {
    private static final long MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;

    private final WorkAreaService workAreaService;
    private final WorkAreaExplorerService explorerService;

    public WorkAreaController(WorkAreaService workAreaService, WorkAreaExplorerService explorerService) {
        this.workAreaService = workAreaService;
        this.explorerService = explorerService;
    }

    @GetMapping
    public List<WorkArea> list(
        @RequestParam String ownerType,
        @RequestParam String ownerId,
        @RequestParam(defaultValue = "false") boolean includeInactive
    ) {
        return workAreaService.list(parseOwnerType(ownerType), ownerId, includeInactive);
    }

    @PostMapping("/home")
    public WorkArea ensureHome(@RequestBody WorkAreaOwnerRequest request) {
        return workAreaService.ensureHome(parseOwnerType(request.ownerType()), request.ownerId(), request.displayName());
    }

    @PostMapping
    public WorkArea mark(@RequestBody MarkWorkAreaRequest request) {
        try {
            return workAreaService.markDirectory(
                parseOwnerType(request.ownerType()),
                request.ownerId(),
                request.path(),
                request.displayName()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @DeleteMapping("/{workAreaId}")
    public WorkArea unmark(@PathVariable String workAreaId) {
        try {
            return workAreaService.unmark(workAreaId);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    @GetMapping("/{workAreaId}/files")
    public WorkAreaExplorerService.DirectoryListing files(
        @PathVariable String workAreaId,
        @RequestParam(defaultValue = ".") String path
    ) {
        return explorerService.list(workAreaId, path);
    }

    @GetMapping("/{workAreaId}/files/preview")
    public WorkAreaExplorerService.FilePreview preview(
        @PathVariable String workAreaId,
        @RequestParam String path
    ) {
        return explorerService.preview(workAreaId, path);
    }

    @GetMapping("/{workAreaId}/files/view")
    public ResponseEntity<?> imageView(
        @PathVariable String workAreaId,
        @RequestParam String path
    ) {
        try {
            WorkAreaExplorerService.FilePreview preview = explorerService.preview(workAreaId, path);
            if (!"image".equals(preview.kind())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("code", "UNSUPPORTED_VIEW", "error", "inline view only supports image files"));
            }
            WorkAreaExplorerService.DownloadContent file = explorerService.download(workAreaId, path);
            byte[] bytes = file.bytes();
            MediaType mediaType = imageMediaType(file.fileName().toLowerCase(Locale.ROOT));
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.inline().filename(file.fileName()).build().toString())
                .contentType(mediaType)
                .contentLength(bytes.length)
                .body(bytes);
        } catch (IllegalArgumentException exception) {
            throw mapExplorerError(exception);
        }
    }

    @GetMapping("/{workAreaId}/files/download")
    public ResponseEntity<?> download(
        @PathVariable String workAreaId,
        @RequestParam String path
    ) {
        try {
            WorkAreaExplorerService.DownloadContent file = explorerService.download(workAreaId, path);
            byte[] bytes = file.bytes();
            if (bytes.length > MAX_DOWNLOAD_BYTES) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "file too large: " + bytes.length + " bytes"));
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(bytes.length)
                .body(bytes);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", exception.getMessage()));
        }
    }

    @PutMapping("/{workAreaId}/files/text")
    public WorkAreaExplorerService.FilePreview saveText(
        @PathVariable String workAreaId,
        @RequestParam String path,
        @RequestBody TextSaveRequest request
    ) {
        return explorerService.saveText(workAreaId, path, request.content());
    }

    @PostMapping("/{workAreaId}/directories")
    public WorkAreaExplorerService.Entry createDirectory(
        @PathVariable String workAreaId,
        @RequestBody PathRequest request
    ) {
        return explorerService.createDirectory(workAreaId, request.path());
    }

    @PostMapping("/{workAreaId}/files/text")
    public WorkAreaExplorerService.Entry createTextFile(
        @PathVariable String workAreaId,
        @RequestBody CreateFileRequest request
    ) {
        return explorerService.createTextFile(workAreaId, request.parentPath(), request.fileName());
    }

    @PostMapping("/{workAreaId}/files/markdown")
    public WorkAreaExplorerService.Entry createMarkdownFile(
        @PathVariable String workAreaId,
        @RequestBody CreateFileRequest request
    ) {
        return explorerService.createMarkdownFile(workAreaId, request.parentPath(), request.fileName());
    }

    @PostMapping("/{workAreaId}/files/rename")
    public WorkAreaExplorerService.Entry rename(
        @PathVariable String workAreaId,
        @RequestBody RenameRequest request
    ) {
        return explorerService.rename(workAreaId, request.path(), request.newName());
    }

    @PostMapping("/{workAreaId}/files/move")
    public WorkAreaExplorerService.Entry move(
        @PathVariable String workAreaId,
        @RequestBody MoveCopyRequest request
    ) {
        return explorerService.move(workAreaId, request.path(), request.destinationDirectoryPath(), request.newName());
    }

    @PostMapping("/{workAreaId}/files/copy")
    public WorkAreaExplorerService.Entry copy(
        @PathVariable String workAreaId,
        @RequestBody MoveCopyRequest request
    ) {
        return explorerService.copy(workAreaId, request.path(), request.destinationDirectoryPath(), request.newName());
    }

    @GetMapping("/{workAreaId}/files/delete/preflight")
    public WorkAreaExplorerService.DeletePreflight deletePreflight(
        @PathVariable String workAreaId,
        @RequestParam String path,
        @RequestParam(defaultValue = "INTENT") String step
    ) {
        return explorerService.deletePreflight(workAreaId, path, parseDeleteStep(step));
    }

    @PostMapping("/{workAreaId}/files/delete")
    public WorkAreaExplorerService.DeleteResult deleteExecute(
        @PathVariable String workAreaId,
        @RequestBody DeleteExecuteRequest request
    ) {
        return explorerService.delete(workAreaId, request.path(), parseDeleteStep(request.step()));
    }

    @DeleteMapping("/{workAreaId}/files")
    public WorkAreaExplorerService.DeleteResult deleteCompat(
        @PathVariable String workAreaId,
        @RequestParam String path,
        @RequestParam String confirm
    ) {
        return explorerService.deleteRecursive(workAreaId, path, confirm);
    }

    @GetMapping("/{workAreaId}/files/labels")
    public List<WorkspaceFileLabelAssignment> labels(
        @PathVariable String workAreaId,
        @RequestParam String path
    ) {
        return explorerService.labels(workAreaId, path);
    }

    @PostMapping("/{workAreaId}/tags")
    public WorkspaceFileLabel createTag(
        @PathVariable String workAreaId,
        @RequestBody TagRequest request
    ) {
        return explorerService.ensureTag(request.label(), request.displayName());
    }

    @PostMapping("/{workAreaId}/files/labels")
    public WorkspaceFileLabelAssignment addLabel(
        @PathVariable String workAreaId,
        @RequestBody LabelRequest request
    ) {
        return explorerService.addLabel(workAreaId, request.path(), request.label());
    }

    @DeleteMapping("/{workAreaId}/files/labels")
    public Map<String, Object> removeLabel(
        @PathVariable String workAreaId,
        @RequestParam String path,
        @RequestParam String label
    ) {
        int removed = explorerService.removeLabel(workAreaId, path, label);
        return Map.of("removed", removed);
    }

    @GetMapping("/{workAreaId}/files/actions/recent")
    public List<WorkspaceFileActionRecord> recentActions(
        @PathVariable String workAreaId,
        @RequestParam(defaultValue = "20") int limit
    ) {
        return explorerService.recentActions(workAreaId, Math.max(1, Math.min(limit, 200)));
    }

    @PostMapping("/{workAreaId}/files/mark-work-area")
    public WorkArea markNested(
        @PathVariable String workAreaId,
        @RequestBody MarkNestedWorkAreaRequest request
    ) {
        return explorerService.mark(workAreaId, request.path(), request.displayName());
    }

    private WorkspaceOwnerType parseOwnerType(String ownerType) {
        try {
            return WorkspaceOwnerType.valueOf(ownerType.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid ownerType: " + ownerType);
        }
    }

    private WorkAreaExplorerService.DeleteStep parseDeleteStep(String step) {
        try {
            return WorkAreaExplorerService.DeleteStep.valueOf(step.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delete step: " + step);
        }
    }

    private MediaType imageMediaType(String fileNameLower) {
        if (fileNameLower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (fileNameLower.endsWith(".jpg") || fileNameLower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (fileNameLower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (fileNameLower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private ResponseStatusException mapExplorerError(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "bad request" : exception.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("does not exist") || lower.contains("not found")) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        if (lower.contains("already exists") || lower.contains("collision") || lower.contains("conflict")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, message);
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException exception) {
        ResponseStatusException statusException = mapExplorerError(exception);
        return ResponseEntity.status(statusException.getStatusCode())
            .body(Map.of("error", statusException.getReason() == null ? "request failed" : statusException.getReason()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> statusError(ResponseStatusException exception) {
        String reason = exception.getReason() == null ? "request failed" : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("error", reason));
    }

    public record WorkAreaOwnerRequest(String ownerType, String ownerId, String displayName) {
    }

    public record MarkWorkAreaRequest(String ownerType, String ownerId, String path, String displayName) {
    }

    public record MarkNestedWorkAreaRequest(String path, String displayName) {
    }

    public record TextSaveRequest(String content) {
    }

    public record PathRequest(String path) {
    }

    public record RenameRequest(String path, String newName) {
    }

    public record CreateFileRequest(String parentPath, String fileName) {
    }

    public record MoveCopyRequest(String path, String destinationDirectoryPath, String newName) {
    }

    public record DeleteExecuteRequest(String path, String step) {
    }

    public record LabelRequest(String path, String label) {
    }

    public record TagRequest(String label, String displayName) {
    }
}
