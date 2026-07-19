package io.mindspice.magenta2.api.web;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceExplorerService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileActionRecord;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabel;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileLabelAssignment;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import io.mindspice.magenta2.core.util.PlainPathSegmentValidator;
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
@RequestMapping("/api/workspace")
public class WorkspaceController {
    private static final long MAX_DOWNLOAD_BYTES = 10L * 1024 * 1024;

    private final WorkspaceDirectoryService directoryService;
    private final WorkspaceExplorerService explorerService;

    public WorkspaceController(
        WorkspaceDirectoryService directoryService,
        WorkspaceExplorerService explorerService
    ) {
        this.directoryService = directoryService;
        this.explorerService = explorerService;
    }

    /* ── User workspace collection ── */

    @GetMapping("/user")
    public List<UserSummary> listUsers() {
        return directoryService.listUserWorkspaces().stream()
            .map(d -> new UserSummary(d.kind().token(), d.key()))
            .toList();
    }

    @PostMapping("/user")
    @ResponseStatus(HttpStatus.CREATED)
    public UserSummary createUser(@RequestBody UserCreateRequest request) {
        String name = request.name();
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User workspace name is required");
        }
        try {
            WorkspaceDescriptor descriptor = directoryService.createUserWorkspace(name);
            return new UserSummary(descriptor.kind().token(), descriptor.key());
        } catch (WorkspaceResolutionException e) {
            throw mapResolution(e);
        }
    }

    /* ── Typed workspace file operations ── */

    @GetMapping("/{type}/{id}/explorer")
    public String explorer(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = ".") String path,
        @RequestParam(required = false) String selected
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.RelativePath relativePath = explorerService.normalizeRelativePath(path);
        WorkspaceExplorerService.DirectoryListing listing = explorerService.list(descriptor, relativePath);
        WorkspaceExplorerService.Entry inspected = selected != null && !selected.isBlank()
            ? explorerService.inspect(descriptor, explorerService.normalizeRelativePath(selected)) : null;
        return WorkspaceExplorerFragments.shell(listing, inspected, selected, descriptor.kind().token(), descriptor.key());
    }

    @GetMapping("/{type}/{id}/viewer")
    public String viewer(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.FilePreview preview = explorerService.preview(descriptor, explorerService.normalizeRelativePath(path));
        return WorkspaceExplorerFragments.viewer(descriptor.kind().token(), descriptor.key(), preview);
    }

    @GetMapping("/{type}/{id}/viewer/text")
    public String viewerText(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam(defaultValue = "text") String tab
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.FilePreview preview = explorerService.preview(descriptor, explorerService.normalizeRelativePath(path));
        return WorkspaceExplorerFragments.textViewer(descriptor.kind().token(), descriptor.key(), preview, tab);
    }

    @GetMapping("/{type}/{id}/modal/{action}")
    public String actionModal(
        @PathVariable String type,
        @PathVariable String id,
        @PathVariable String action,
        @RequestParam(defaultValue = ".") String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.RelativePath relativePath = explorerService.normalizeRelativePath(path);
        WorkspaceExplorerService.DeletePreflight preflight = null;
        if ("delete".equals(action) || "delete-recursive".equals(action)) {
            try {
                preflight = explorerService.deletePreflight(descriptor, relativePath,
                    "delete-recursive".equals(action)
                        ? WorkspaceExplorerService.DeleteStep.DIRECTORY_RECURSIVE_CONFIRM
                        : WorkspaceExplorerService.DeleteStep.INTENT);
            } catch (WorkspaceFileException e) {
                if (e.reason() != WorkspaceFileException.Reason.NOT_FOUND) throw e;
            }
        }
        return WorkspaceExplorerFragments.actionModal(descriptor.kind().token(), descriptor.key(), action,
            relativePath.value(), preflight);
    }

    @GetMapping("/{type}/{id}/modal/close")
    public String modalClose() {
        return "";
    }

    /* ── File listing and inspection ── */

    @GetMapping("/{type}/{id}/files")
    public WorkspaceExplorerService.DirectoryListing files(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = ".") String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        return explorerService.list(descriptor, explorerService.normalizeRelativePath(path));
    }

    @GetMapping("/{type}/{id}/files/inspect")
    public WorkspaceExplorerService.Entry inspect(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        return explorerService.inspect(descriptor, explorerService.normalizeRelativePath(path));
    }

    @GetMapping("/{type}/{id}/files/preview")
    public WorkspaceExplorerService.FilePreview preview(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        return explorerService.preview(descriptor, explorerService.normalizeRelativePath(path));
    }

    @GetMapping("/{type}/{id}/files/view")
    public ResponseEntity<?> imageView(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.FilePreview preview = explorerService.preview(descriptor, explorerService.normalizeRelativePath(path));
        if (!"image".equals(preview.kind())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "UNSUPPORTED_VIEW", "error", "inline view only supports image files"));
        }
        WorkspaceExplorerService.DownloadContent file = explorerService.download(descriptor, explorerService.normalizeRelativePath(path));
        byte[] bytes = file.bytes();
        MediaType mediaType = imageMediaType(file.fileName().toLowerCase(Locale.ROOT));
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.inline().filename(file.fileName()).build().toString())
            .contentType(mediaType)
            .contentLength(bytes.length)
            .body(bytes);
    }

    @GetMapping("/{type}/{id}/files/download")
    public ResponseEntity<?> download(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        WorkspaceExplorerService.DownloadContent file = explorerService.download(descriptor, explorerService.normalizeRelativePath(path));
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
    }

    /* ── Mutations ── */

    @PutMapping("/{type}/{id}/files/text")
    public WorkspaceExplorerService.FilePreview saveText(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestBody TextSaveRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.saveText(descriptor, explorerService.normalizeRelativePath(path), request.content());
    }

    @PostMapping("/{type}/{id}/directories")
    public WorkspaceExplorerService.Entry createDirectory(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody PathRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.createDirectory(descriptor, explorerService.normalizeRelativePath(request.path()));
    }

    @PostMapping("/{type}/{id}/files/text")
    public WorkspaceExplorerService.Entry createTextFile(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody CreateFileRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.createTextFile(descriptor,
            explorerService.normalizeRelativePath(request.parentPath()), request.fileName());
    }

    @PostMapping("/{type}/{id}/files/markdown")
    public WorkspaceExplorerService.Entry createMarkdownFile(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody CreateFileRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.createMarkdownFile(descriptor,
            explorerService.normalizeRelativePath(request.parentPath()), request.fileName());
    }

    @PostMapping("/{type}/{id}/files/rename")
    public WorkspaceExplorerService.Entry rename(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody RenameRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.rename(descriptor, explorerService.normalizeRelativePath(request.path()), request.newName());
    }

    @PostMapping("/{type}/{id}/files/move")
    public WorkspaceExplorerService.Entry move(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody MoveCopyRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.move(descriptor,
            explorerService.normalizeRelativePath(request.path()),
            explorerService.normalizeRelativePath(request.destinationDirectoryPath()),
            request.newName());
    }

    @PostMapping("/{type}/{id}/files/copy")
    public WorkspaceExplorerService.Entry copy(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody MoveCopyRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.copy(descriptor,
            explorerService.normalizeRelativePath(request.path()),
            explorerService.normalizeRelativePath(request.destinationDirectoryPath()),
            request.newName());
    }

    @GetMapping("/{type}/{id}/files/delete/preflight")
    public WorkspaceExplorerService.DeletePreflight deletePreflight(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam(defaultValue = "INTENT") String step
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.deletePreflight(descriptor, explorerService.normalizeRelativePath(path), parseDeleteStep(step));
    }

    @PostMapping("/{type}/{id}/files/delete")
    public WorkspaceExplorerService.DeleteResult deleteExecute(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody DeleteExecuteRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return explorerService.delete(descriptor, explorerService.normalizeRelativePath(request.path()), parseDeleteStep(request.step()));
    }

    /* ── Labels and tags ── */

    @GetMapping("/{type}/{id}/files/labels")
    public List<LabelAssignmentSummary> labels(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        return explorerService.labels(descriptor, path).stream().map(this::labelSummary).toList();
    }

    @PostMapping("/{type}/{id}/tags")
    public WorkspaceFileLabel createTag(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody TagRequest request
    ) {
        resolveForRead(type, id);
        return explorerService.ensureTag(request.label(), request.displayName());
    }

    @PostMapping("/{type}/{id}/files/labels")
    public LabelAssignmentSummary addLabel(
        @PathVariable String type,
        @PathVariable String id,
        @RequestBody LabelRequest request
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        return labelSummary(explorerService.addLabel(descriptor, request.path(), request.label()));
    }

    @DeleteMapping("/{type}/{id}/files/labels")
    public Map<String, Object> removeLabel(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String label
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        int removed = explorerService.removeLabel(descriptor, path, label);
        return Map.of("removed", removed);
    }

    /* ── Recent actions ── */

    @GetMapping("/{type}/{id}/files/actions/recent")
    public List<ActionSummary> recentActions(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = "20") int limit
    ) {
        WorkspaceDescriptor descriptor = resolveForRead(type, id);
        return explorerService.recentActions(descriptor, Math.max(1, Math.min(limit, 200))).stream()
            .map(this::actionSummary)
            .toList();
    }

    /* ── Explorer fragment mutation responses ── */

    @PostMapping("/{type}/{id}/explorer/create-folder")
    public String explorerCreateFolder(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = ".") String path,
        @RequestParam String name
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        String childPath = ".".equals(path) ? name : path + "/" + name;
        explorerService.createDirectory(descriptor, explorerService.normalizeRelativePath(childPath));
        return mutationResponse(descriptor, path, "Created " + childPath + ".");
    }

    @PostMapping("/{type}/{id}/explorer/create-text")
    public String explorerCreateText(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = ".") String path,
        @RequestParam String fileName
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        WorkspaceExplorerService.Entry created = explorerService.createTextFile(
            descriptor, explorerService.normalizeRelativePath(path), fileName);
        return textCreateResponse(descriptor, path, created);
    }

    @PostMapping("/{type}/{id}/explorer/create-markdown")
    public String explorerCreateMarkdown(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam(defaultValue = ".") String path,
        @RequestParam String fileName
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        WorkspaceExplorerService.Entry created = explorerService.createMarkdownFile(
            descriptor, explorerService.normalizeRelativePath(path), fileName);
        return textCreateResponse(descriptor, path, created);
    }

    @PostMapping("/{type}/{id}/explorer/rename")
    public String explorerRename(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String name
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        WorkspaceExplorerService.Entry renamed = explorerService.rename(
            descriptor, explorerService.normalizeRelativePath(path), name);
        return mutationResponse(descriptor, parentPath(path), "Renamed to " + renamed.name() + ".");
    }

    @PostMapping("/{type}/{id}/explorer/copy")
    public String explorerCopy(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String destination,
        @RequestParam(required = false) String name
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        explorerService.copy(descriptor,
            explorerService.normalizeRelativePath(path),
            explorerService.normalizeRelativePath(destination), name);
        return mutationResponse(descriptor, destination, "Copied " + path + ".");
    }

    @PostMapping("/{type}/{id}/explorer/move")
    public String explorerMove(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String destination,
        @RequestParam(required = false) String name
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        explorerService.move(descriptor,
            explorerService.normalizeRelativePath(path),
            explorerService.normalizeRelativePath(destination), name);
        return mutationResponse(descriptor, destination, "Moved " + path + ".");
    }

    @PostMapping("/{type}/{id}/explorer/delete")
    public String explorerDelete(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String step
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        WorkspaceExplorerService.DeleteStep deleteStep;
        try {
            deleteStep = WorkspaceExplorerService.DeleteStep.valueOf(step.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delete step: " + step);
        }
        explorerService.delete(descriptor, explorerService.normalizeRelativePath(path), deleteStep);
        return mutationResponse(descriptor, parentPath(path), "Deleted " + path + ".");
    }

    @PostMapping("/{type}/{id}/explorer/tag")
    public String explorerTag(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String label
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        explorerService.addLabel(descriptor, path, label);
        return mutationResponse(descriptor, parentPath(path), "Tagged " + path + ".");
    }

    @DeleteMapping("/{type}/{id}/explorer/tag")
    public String explorerUntag(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String label
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        explorerService.removeLabel(descriptor, path, label);
        return mutationResponse(descriptor, parentPath(path), "Removed tag from " + path + ".");
    }

    @PutMapping("/{type}/{id}/explorer/save-text")
    public String explorerSaveText(
        @PathVariable String type,
        @PathVariable String id,
        @RequestParam String path,
        @RequestParam String content
    ) {
        WorkspaceDescriptor descriptor = resolveForWrite(type, id);
        WorkspaceExplorerService.FilePreview preview = explorerService.saveText(
            descriptor, explorerService.normalizeRelativePath(path), content);
        WorkspaceExplorerService.DirectoryListing listing = explorerService.list(descriptor,
            explorerService.normalizeRelativePath(parentPath(path)));
        WorkspaceExplorerService.Entry inspected = explorerService.inspect(descriptor,
            explorerService.normalizeRelativePath(path));
        return WorkspaceExplorerFragments.textSaveResponse(
            descriptor.kind().token(), descriptor.key(), preview, listing, inspected, path,
            "Saved " + path + ".");
    }

    /* ── Descriptor resolution ── */

    private WorkspaceDescriptor resolveForRead(String typeToken, String id) {
        WorkspaceKind kind = parseKind(typeToken);
        String decoded = decodeAndValidate(kind, id);
        try {
            return directoryService.resolveForRead(kind, decoded);
        } catch (WorkspaceResolutionException e) {
            throw mapResolution(e);
        }
    }

    private WorkspaceDescriptor resolveForWrite(String typeToken, String id) {
        // Mutations resolve without creating the physical root. The explorer creates
        // agent/chat roots only after the complete operation request is validated.
        return resolveForRead(typeToken, id);
    }

    private WorkspaceKind parseKind(String token) {
        try {
            return WorkspaceKind.fromToken(token);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown workspace type: " + token);
        }
    }

    private String decodeAndValidate(WorkspaceKind kind, String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace id is required");
        }
        String decoded;
        try {
            decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid workspace id encoding");
        }
        if (!encoded.equals(decoded)) {
            rejectEncodedDanger(decoded);
        }
        String label = switch (kind) {
            case AGENT -> "agentId";
            case CHAT -> "conversationId";
            case USER -> "userName";
        };
        try {
            PlainPathSegmentValidator.requirePlainSegment(decoded, label);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return decoded;
    }

    private void rejectEncodedDanger(String decoded) {
        if (decoded.contains("/") || decoded.contains("\\") || decoded.contains("..") || decoded.contains("./")
            || decoded.contains("%2f") || decoded.contains("%2F") || decoded.contains("%5c") || decoded.contains("%5C")
            || decoded.contains("%2e") || decoded.contains("%2E") || decoded.contains("%00")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Workspace id contains invalid characters");
        }
    }

    /* ── Error mapping ── */

    private ResponseStatusException mapResolution(WorkspaceResolutionException e) {
        return switch (e.reason()) {
            case NOT_FOUND -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
            case CONFLICT -> new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        };
    }

    @ExceptionHandler(WorkspaceFileException.class)
    public ResponseEntity<Map<String, String>> handleWorkspaceFile(WorkspaceFileException e) {
        HttpStatus status = switch (e.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status)
            .body(Map.of("error", e.getMessage() == null ? "request failed" : e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? "request failed" : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error", reason));
    }

    private WorkspaceExplorerService.DeleteStep parseDeleteStep(String step) {
        try {
            return WorkspaceExplorerService.DeleteStep.valueOf(step.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid delete step: " + step);
        }
    }

    private MediaType imageMediaType(String fileNameLower) {
        if (fileNameLower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (fileNameLower.endsWith(".jpg") || fileNameLower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (fileNameLower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (fileNameLower.endsWith(".webp")) return MediaType.parseMediaType("image/webp");
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /* ── Mutation helpers ── */

    private String mutationResponse(WorkspaceDescriptor descriptor, String path, String message) {
        WorkspaceExplorerService.DirectoryListing listing = explorerService.list(descriptor, explorerService.normalizeRelativePath(path));
        return WorkspaceExplorerFragments.mutationResponse(listing, null, null, message,
            descriptor.kind().token(), descriptor.key());
    }

    private String textCreateResponse(WorkspaceDescriptor descriptor, String path, WorkspaceExplorerService.Entry created) {
        WorkspaceExplorerService.DirectoryListing listing = explorerService.list(descriptor, explorerService.normalizeRelativePath(path));
        WorkspaceExplorerService.FilePreview preview = explorerService.preview(descriptor, explorerService.normalizeRelativePath(created.path()));
        WorkspaceExplorerService.Entry inspected = explorerService.inspect(descriptor, explorerService.normalizeRelativePath(created.path()));
        return WorkspaceExplorerFragments.textCreateResponse(
            descriptor.kind().token(), descriptor.key(), preview, listing, inspected, null,
            "Created " + created.path() + ".");
    }

    private String parentPath(String path) {
        String clean = path == null || path.isBlank() ? "." : path;
        int idx = clean.lastIndexOf('/');
        if (idx <= 0) return ".";
        return clean.substring(0, idx);
    }

    private LabelAssignmentSummary labelSummary(WorkspaceFileLabelAssignment assignment) {
        return new LabelAssignmentSummary(assignment.id(), assignment.fileRelativePath(), assignment.label(),
            assignment.metadataJson(), assignment.createdAt(), assignment.updatedAt());
    }

    private ActionSummary actionSummary(WorkspaceFileActionRecord action) {
        return new ActionSummary(action.id(), action.actorType(), action.actorId(), action.actionType().name(),
            action.sourceRelativePath(), action.targetRelativePath(), action.result(), action.payloadJson(), action.createdAt());
    }

    /* ── Request/response types ── */

    public record UserSummary(String type, String name) {}
    public record LabelAssignmentSummary(String id, String fileRelativePath, WorkspaceFileLabel label,
                                         String metadataJson, java.time.Instant createdAt, java.time.Instant updatedAt) {}
    public record ActionSummary(String id, String actorType, String actorId, String actionType,
                                String sourceRelativePath, String targetRelativePath, String result,
                                String payloadJson, java.time.Instant createdAt) {}
    public record UserCreateRequest(String name) {}
    public record TextSaveRequest(String content) {}
    public record PathRequest(String path) {}
    public record CreateFileRequest(String parentPath, String fileName) {}
    public record RenameRequest(String path, String newName) {}
    public record MoveCopyRequest(String path, String destinationDirectoryPath, String newName) {}
    public record DeleteExecuteRequest(String path, String step) {}
    public record LabelRequest(String path, String label) {}
    public record TagRequest(String label, String displayName) {}
}
