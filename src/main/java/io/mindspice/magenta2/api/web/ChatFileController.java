package io.mindspice.magenta2.api.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import io.mindspice.magenta2.ai.chat.model.ChatFileListing;
import io.mindspice.magenta2.ai.chat.service.ChatFileService;
import io.mindspice.magenta2.ai.chat.service.ChatService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceFileException;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/chat/{conversationId}/files")
public class ChatFileController {
    private static final long MAX_CONTENT_BYTES = 10 * 1024 * 1024;

    private final ChatService chatService;
    private final ChatFileService chatFileService;

    public ChatFileController(ChatService chatService, ChatFileService chatFileService) {
        this.chatService = chatService;
        this.chatFileService = chatFileService;
    }

    @GetMapping
    public ChatFileListing files(@PathVariable String conversationId) {
        requireExistingConversation(conversationId);
        return chatFileService.listFiles(conversationId);
    }

    @GetMapping("/download")
    public ResponseEntity<?> download(
        @PathVariable String conversationId,
        @RequestParam("path") String relativePath
    ) {
        try {
            requireExistingConversation(conversationId);
            Path path = chatFileService.resolveDownload(conversationId, relativePath);
            long size = Files.size(path);
            if (size > MAX_CONTENT_BYTES) {
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "Chat file too large"));
            }

            String fileName = path.getFileName().toString();
            InputStreamResource resource = new InputStreamResource(Files.newInputStream(path));
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(resolveMediaType(fileName))
                .contentLength(size)
                .body(resource);
        } catch (WorkspaceFileException e) {
            return ResponseEntity.status(fileStatus(e)).body(Map.of("error", fileError(e)));
        } catch (WorkspaceResolutionException e) {
            HttpStatus status = e.reason() == WorkspaceResolutionException.Reason.NOT_FOUND
                    ? HttpStatus.NOT_FOUND : HttpStatus.CONFLICT;
            return ResponseEntity.status(status).body(Map.of("error", "Chat file is unavailable"));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Failed to read chat file"));
        }
    }

    private static HttpStatus fileStatus(WorkspaceFileException exception) {
        return switch (exception.reason()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
    }

    private static String fileError(WorkspaceFileException exception) {
        return switch (exception.reason()) {
            case INVALID -> "Invalid chat file request";
            case NOT_FOUND -> "Chat file not found";
            case CONFLICT -> "Chat file request conflicts with current state";
        };
    }

    private void requireExistingConversation(String conversationId) {
        requireValidUuid(conversationId);
        if (!chatService.conversationExists(conversationId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found: " + conversationId);
        }
    }

    private void requireValidUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid UUID: " + value);
        }
    }

    private MediaType resolveMediaType(String fileName) {
        if (!StringUtils.hasText(fileName)) return MediaType.APPLICATION_OCTET_STREAM;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (lower.endsWith(".md")) return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".txt")) return MediaType.TEXT_PLAIN;
        if (lower.endsWith(".csv")) return MediaType.valueOf("text/csv");
        if (lower.endsWith(".html")) return MediaType.TEXT_HTML;
        if (lower.endsWith(".xml")) return MediaType.APPLICATION_XML;
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        if (lower.endsWith(".gif")) return MediaType.valueOf("image/gif");
        if (lower.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (lower.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (lower.endsWith(".zip")) return MediaType.valueOf("application/zip");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
