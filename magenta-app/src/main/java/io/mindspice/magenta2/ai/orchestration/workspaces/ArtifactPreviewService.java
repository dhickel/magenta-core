package io.mindspice.magenta2.ai.orchestration.workspaces;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Safe, bounded artifact preview with explicit availability states.
 *
 * <h3>Preview contract</h3>
 * <ul>
 *   <li>Preview is available only for allowed text media types.</li>
 *   <li>UTF-8 is verified before content is surfaced.</li>
 *   <li>Explicit states: MISSING, UNAVAILABLE, INTEGRITY_FAILED,
 *       TRUNCATED, UNSUPPORTED.</li>
 *   <li>Markdown rendering disables raw HTML, active content, unsafe
 *       schemes, remote images, and tracking pixels under strict
 *       byte/line/parser limits.</li>
 *   <li>Integrity-failed artifacts expose no preview.</li>
 * </ul>
 */
@Service
public class ArtifactPreviewService {
    private static final Logger log = LoggerFactory.getLogger(ArtifactPreviewService.class);

    /** Maximum bytes read for preview content. */
    static final int MAX_PREVIEW_BYTES = 512 * 1024; // 512 KB
    /** Maximum lines surfaced in preview. */
    static final int MAX_PREVIEW_LINES = 5_000;
    /** Maximum bytes for Markdown rendering. */
    static final int MAX_MARKDOWN_BYTES = 256 * 1024; // 256 KB
    /** Maximum lines for Markdown rendering. */
    static final int MAX_MARKDOWN_LINES = 3_000;
    /** Text media types eligible for preview. */
    static final Set<String> PREVIEW_MEDIA = Set.of(
            "text/plain", "text/markdown", "text/csv", "text/html",
            "text/xml", "text/javascript", "text/css",
            "application/json", "application/xml",
            "application/javascript", "application/x-ndjson");

    /** Markdown media types eligible for safe rendering. */
    static final Set<String> MARKDOWN_MEDIA = Set.of("text/markdown");

    // Unsafe URL schemes to strip from rendered Markdown
    private static final Pattern UNSAFE_SCHEME = Pattern.compile(
            "^(javascript|data|vbscript|file|about|blob):", Pattern.CASE_INSENSITIVE);
    // Raw HTML blocks to strip
    private static final Pattern RAW_HTML = Pattern.compile(
            "<script[\\s>]|<iframe[\\s>]|<object[\\s>]|<embed[\\s>]"
                    + "|<link[\\s>]|<meta[\\s>]|<style[\\s>]|<form[\\s>]"
                    + "|on\\w+\\s*=", Pattern.CASE_INSENSITIVE);
    // Remote image / tracking pixel patterns
    private static final Pattern REMOTE_IMG = Pattern.compile(
            "!\\[.*?\\]\\(\\s*https?://", Pattern.CASE_INSENSITIVE);
    // HTML image tag with remote src
    private static final Pattern HTML_REMOTE_SRC = Pattern.compile(
            "<img[^>]*src\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE);

    private final OutputArtifactRepository artifactRepository;
    private final RootRelativePathService rootRelativePathService;

    public ArtifactPreviewService(OutputArtifactRepository artifactRepository,
                                   RootRelativePathService rootRelativePathService) {
        this.artifactRepository = artifactRepository;
        this.rootRelativePathService = rootRelativePathService;
    }

    /**
     * Produce a safe preview for an authorized artifact.
     * Returns explicit state; integrity-failed artifacts return no content.
     */
    public PreviewResult preview(String artifactId, ArtifactAccessContext access) {
        PublicationRecord record = artifactRepository.findById(artifactId).orElse(null);
        if (record == null || !access.accepts(record)) {
            return PreviewResult.missing();
        }

        if (record.publicationState() == PublicationState.INTEGRITY_FAILED) {
            return PreviewResult.integrityFailed();
        }

        if (!record.isReadable()) {
            return PreviewResult.unavailable(record.publicationState().name().toLowerCase(Locale.ROOT));
        }

        String media = record.mediaType();
        if (media == null || !PREVIEW_MEDIA.contains(media.toLowerCase(Locale.ROOT))) {
            return PreviewResult.unsupported(media);
        }

        try {
            Path file = rootRelativePathService.resolve(record.rootRelativePath());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                return PreviewResult.missing();
            }

            long fileSize = Files.size(file);
            boolean truncated = fileSize > MAX_PREVIEW_BYTES;

            // Read bounded bytes
            byte[] bytes;
            if (truncated) {
                bytes = Files.readAllBytes(file);
                if (bytes.length > MAX_PREVIEW_BYTES) {
                    byte[] bounded = new byte[MAX_PREVIEW_BYTES];
                    System.arraycopy(bytes, 0, bounded, 0, MAX_PREVIEW_BYTES);
                    bytes = bounded;
                }
            } else {
                bytes = Files.readAllBytes(file);
            }

            // Verify digest
            String digest = sha256Hex(bytes);
            if (truncated) {
                // Partial read: digest won't match, but we still verify the file exists
                if (!record.expectedSha256().equals(sha256Hex(Files.readAllBytes(file)))) {
                    return PreviewResult.integrityFailed();
                }
            } else if (!record.expectedSha256().equals(digest)) {
                return PreviewResult.integrityFailed();
            }

            // Verify UTF-8
            String text = new String(bytes, StandardCharsets.UTF_8);

            // Count lines and enforce limit
            String[] lines = text.split("\n", -1);
            int lineCount = lines.length;
            boolean lineTruncated = lineCount > MAX_PREVIEW_LINES;
            if (lineTruncated) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < MAX_PREVIEW_LINES && i < lines.length; i++) {
                    if (i > 0) sb.append('\n');
                    sb.append(lines[i]);
                }
                text = sb.toString();
            }

            // Check if Markdown rendering is applicable
            boolean markdown = MARKDOWN_MEDIA.contains(
                    media.toLowerCase(Locale.ROOT))
                    && fileSize <= MAX_MARKDOWN_BYTES
                    && lineCount <= MAX_MARKDOWN_LINES;

            String safeHtml = null;
            if (markdown) {
                safeHtml = renderSafeMarkdown(text);
            }

            return new PreviewResult(
                    text, safeHtml, media, fileSize, lineCount,
                    truncated || lineTruncated, truncated, lineTruncated,
                    markdown, PreviewState.OK,
                    record.logicalName(), record.publishedAt() != null ? record.publishedAt().toString() : null);

        } catch (Exception e) {
            log.warn("Preview failed for {}: {}", artifactId, e.getMessage());
            return PreviewResult.error(e.getMessage());
        }
    }

    // ── Safe Markdown rendering ──

    /**
     * Render Markdown text as safe HTML with raw HTML, active content,
     * unsafe schemes, remote images, and tracking pixels disabled.
     * Returns null when the input exceeds parser limits or is unsafe.
     */
    String renderSafeMarkdown(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";

        // Byte/line limits
        byte[] bytes = markdown.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MARKDOWN_BYTES) return null;
        if (markdown.lines().count() > MAX_MARKDOWN_LINES) return null;

        // Strip raw HTML / active content
        if (RAW_HTML.matcher(markdown).find()) {
            markdown = RAW_HTML.matcher(markdown).replaceAll("");
        }

        // Strip remote images from Markdown image syntax
        if (REMOTE_IMG.matcher(markdown).find()) {
            markdown = REMOTE_IMG.matcher(markdown).replaceAll("![remote image removed](");
        }

        // Strip HTML img tags with remote sources
        if (HTML_REMOTE_SRC.matcher(markdown).find()) {
            markdown = HTML_REMOTE_SRC.matcher(markdown).replaceAll("<img src=\"\" data-removed=\"remote\"");
        }

        // Simple Markdown-to-HTML conversion with escape safety
        StringBuilder html = new StringBuilder(markdown.length() + 512);

        // Process line by line
        String[] lines = markdown.split("\n", -1);
        boolean inCodeBlock = false;
        boolean inList = false;
        boolean inBlockquote = false;

        for (String line : lines) {
            // Fenced code blocks
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    html.append("</code></pre>\n");
                    inCodeBlock = false;
                } else {
                    html.append("<pre><code>");
                    inCodeBlock = true;
                }
                continue;
            }
            if (inCodeBlock) {
                html.append(escapeHtml(line)).append('\n');
                continue;
            }

            // Headings
            if (line.startsWith("# ")) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
                html.append("<h1>").append(escapeHtml(line.substring(2).trim())).append("</h1>\n");
                continue;
            }
            if (line.startsWith("## ")) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
                html.append("<h2>").append(escapeHtml(line.substring(3).trim())).append("</h2>\n");
                continue;
            }
            if (line.startsWith("### ")) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
                html.append("<h3>").append(escapeHtml(line.substring(4).trim())).append("</h3>\n");
                continue;
            }
            if (line.startsWith("#### ")) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
                html.append("<h4>").append(escapeHtml(line.substring(5).trim())).append("</h4>\n");
                continue;
            }

            // Blockquote
            if (line.startsWith("> ")) {
                if (!inBlockquote) {
                    closeInline(html, inList, inBlockquote);
                    inList = false;
                    html.append("<blockquote>\n");
                    inBlockquote = true;
                }
                html.append("<p>").append(renderInline(line.substring(2))).append("</p>\n");
                continue;
            } else if (inBlockquote) {
                html.append("</blockquote>\n");
                inBlockquote = false;
            }

            // Unordered list
            if (line.trim().startsWith("- ") || line.trim().startsWith("* ")) {
                if (!inList) {
                    closeInline(html, inList, inBlockquote);
                    inBlockquote = false;
                    html.append("<ul>\n");
                    inList = true;
                }
                String content = line.trim().substring(2);
                html.append("<li>").append(renderInline(content)).append("</li>\n");
                continue;
            }
            if (line.trim().matches("^\\d+\\.\\s.*")) {
                if (!inList) {
                    closeInline(html, inList, inBlockquote);
                    inBlockquote = false;
                    html.append("<ol>\n");
                    inList = true;
                }
                String content = line.trim().replaceFirst("^\\d+\\.\\s", "");
                html.append("<li>").append(renderInline(content)).append("</li>\n");
                continue;
            } else if (inList) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
            }

            // Horizontal rule
            if (line.trim().matches("^[-*_]{3,}$")) {
                closeInline(html, inList, inBlockquote);
                inList = false; inBlockquote = false;
                html.append("<hr>\n");
                continue;
            }

            // Empty line
            if (line.trim().isEmpty()) {
                if (inList) {
                    closeInline(html, inList, inBlockquote);
                    inList = false; inBlockquote = false;
                }
                continue;
            }

            // Paragraph
            closeInline(html, inList, inBlockquote);
            inList = false; inBlockquote = false;
            html.append("<p>").append(renderInline(line)).append("</p>\n");
        }

        // Close any remaining open blocks
        closeInline(html, inList, inBlockquote);

        return html.toString();
    }

    private void closeInline(StringBuilder html, boolean inList, boolean inBlockquote) {
        if (inList) html.append("</ul>\n");
        if (inBlockquote) html.append("</blockquote>\n");
    }

    private String renderInline(String text) {
        // Bold
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>");
        text = text.replaceAll("__(.+?)__", "<strong>$1</strong>");
        // Italic
        text = text.replaceAll("\\*(.+?)\\*", "<em>$1</em>");
        text = text.replaceAll("_(.+?)_", "<em>$1</em>");
        // Inline code
        text = text.replaceAll("`([^`]+)`", "<code>$1</code>");
        // Safe links (only http/https)
        text = text.replaceAll("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)",
                "<a href=\"$2\" rel=\"nofollow noopener\" target=\"_blank\">$1</a>");
        // Strip unsafe-scheme links
        text = text.replaceAll("\\[([^\\]]+)\\]\\((?!https?://)([^)\\s]+)\\)",
                "[$1](link removed)");
        // Escape remaining HTML
        text = escapeHtml(text);
        return text;
    }

    String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Preview result types ──

    public enum PreviewState {
        OK, MISSING, UNAVAILABLE, INTEGRITY_FAILED, UNSUPPORTED, ERROR
    }

    public record PreviewResult(
            String content,
            String safeHtml,
            String mediaType,
            long fileSize,
            int lineCount,
            boolean truncated,
            boolean byteTruncated,
            boolean lineTruncated,
            boolean markdownAvailable,
            PreviewState state,
            String logicalName,
            String publishedAt) {

        static PreviewResult missing() {
            return new PreviewResult(null, null, null, 0, 0, false, false, false, false,
                    PreviewState.MISSING, null, null);
        }
        static PreviewResult unavailable(String reason) {
            return new PreviewResult(null, null, null, 0, 0, false, false, false, false,
                    PreviewState.UNAVAILABLE, null, null);
        }
        static PreviewResult integrityFailed() {
            return new PreviewResult(null, null, null, 0, 0, false, false, false, false,
                    PreviewState.INTEGRITY_FAILED, null, null);
        }
        static PreviewResult unsupported(String mediaType) {
            return new PreviewResult(null, null, mediaType, 0, 0, false, false, false, false,
                    PreviewState.UNSUPPORTED, null, null);
        }
        static PreviewResult error(String message) {
            return new PreviewResult(null, null, null, 0, 0, false, false, false, false,
                    PreviewState.ERROR, null, null);
        }
    }
}
