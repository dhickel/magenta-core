package io.mindspice.magenta2.ai.chat.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import io.mindspice.magenta2.ai.chat.model.ChatFileListing;
import io.mindspice.magenta2.ai.chat.model.ChatFileSummary;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceResolutionException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ChatFileService {
    public static final int MAX_LIST_FILES = 500;

    private final WorkspaceDirectoryService workspaceDirectoryService;

    public ChatFileService(WorkspaceDirectoryService workspaceDirectoryService) {
        this.workspaceDirectoryService = workspaceDirectoryService;
    }

    public int countFiles(String conversationId) {
        var descriptor = resolveChat(conversationId);
        Path root = existingRoot(descriptor);
        if (root == null) {
            return 0;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            long count = paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .count();
            return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("Failed to count chat files for " + conversationId, e);
        }
    }

    public ChatFileListing listFiles(String conversationId) {
        var descriptor = resolveChat(conversationId);
        Path root = existingRoot(descriptor);
        if (root == null) {
            return new ChatFileListing(conversationId, 0, false, List.of());
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<ChatFileSummary> files = paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .sorted(Comparator.comparing(path -> relativePath(root, path)))
                .limit(MAX_LIST_FILES)
                .map(path -> summarize(root, path))
                .toList();
            long totalCount;
            try (Stream<Path> allPaths = Files.walk(root)) {
                totalCount = allPaths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .count();
            }
            int total = totalCount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalCount;
            return new ChatFileListing(conversationId, total, total > files.size(), files);
        } catch (IOException | UncheckedIOException e) {
            throw new IllegalStateException("Failed to list chat files for " + conversationId, e);
        }
    }

    public Path resolveDownload(String conversationId, String relativePath) throws IOException {
        if (!StringUtils.hasText(relativePath)) {
            throw new IllegalArgumentException("file path is required");
        }
        var descriptor = resolveChat(conversationId);
        Path root = existingRoot(descriptor);
        if (root == null) {
            throw new IllegalArgumentException("chat file not found");
        }
        Path target = workspaceDirectoryService.confinedRelative(descriptor, relativePath);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("chat file not found");
        }
        Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root) || !Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("chat file not found");
        }
        return real;
    }

    private WorkspaceDescriptor resolveChat(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new WorkspaceResolutionException(
                    WorkspaceResolutionException.Reason.NOT_FOUND,
                    "Chat conversation id is required");
        }
        return workspaceDirectoryService.resolveForRead(WorkspaceKind.CHAT, conversationId);
    }

    private Path existingRoot(WorkspaceDescriptor descriptor) {
        if (!Files.exists(descriptor.root(), LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return workspaceDirectoryService.confinedRelative(descriptor, ".");
    }

    private ChatFileSummary summarize(Path root, Path path) {
        String fileName = path.getFileName().toString();
        String extension = extension(fileName);
        try {
            return new ChatFileSummary(
                relativePath(root, path),
                fileName,
                extension,
                formatLabel(extension),
                Files.size(path),
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to summarize chat file", e);
        }
    }

    private String relativePath(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private String extension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index <= 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String formatLabel(String extension) {
        return switch (extension) {
            case "md" -> "Markdown";
            case "txt" -> "Text";
            case "json" -> "JSON";
            case "csv" -> "CSV";
            case "html", "htm" -> "HTML";
            case "xml" -> "XML";
            case "png", "jpg", "jpeg", "gif", "webp" -> "Image";
            case "pdf" -> "PDF";
            case "" -> "file";
            default -> extension.toUpperCase(Locale.ROOT);
        };
    }
}
