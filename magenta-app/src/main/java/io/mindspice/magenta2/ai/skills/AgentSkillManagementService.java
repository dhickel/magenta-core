package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillManagementService {
    private static final long NORMAL_TEXT_BYTES = 10L * 1024 * 1024;
    private static final long NORMAL_MARKDOWN_BYTES = 5L * 1024 * 1024;
    private static final long HARD_EDIT_BYTES = 25L * 1024 * 1024;

    private final AgentSkillCatalogService catalogService;
    private final AgentSkillAssignmentService assignmentService;
    private final AgentSkillRepositoryService repositoryService;

    public AgentSkillManagementService(
        AgentSkillCatalogService catalogService,
        AgentSkillAssignmentService assignmentService,
        AgentSkillRepositoryService repositoryService
    ) {
        this.catalogService = catalogService;
        this.assignmentService = assignmentService;
        this.repositoryService = repositoryService;
    }

    public SkillCatalog listSkills() {
        List<AgentSkill> skills = catalogService.listAll().stream()
            .sorted(Comparator.comparing(skill -> sortKey(skill.name(), skill.directorySlug())))
            .toList();
        int validCount = (int) skills.stream().filter(skill -> skill.status() == AgentSkillStatus.VALID).count();
        int warningCount = (int) skills.stream().filter(skill -> skill.status() == AgentSkillStatus.WARNING).count();
        int invalidCount = (int) skills.stream().filter(skill -> skill.status() == AgentSkillStatus.INVALID).count();
        return new SkillCatalog(skills, validCount, warningCount, invalidCount);
    }

    @Transactional
    public SkillCatalog refreshSkills() {
        AgentSkillCatalogService.AgentSkillRefreshResult refreshed = catalogService.refreshCatalog();
        return new SkillCatalog(
            refreshed.skills().stream()
                .sorted(Comparator.comparing(skill -> sortKey(skill.name(), skill.directorySlug())))
                .toList(),
            refreshed.validCount(),
            refreshed.warningCount(),
            refreshed.invalidCount()
        );
    }

    public AgentSkill getSkill(String skillName) {
        return resolveSkill(skillName);
    }

    public List<AgentSkillDiagnostic> diagnostics(String skillName) {
        return resolveSkill(skillName).diagnostics();
    }

    public SkillFileTree listFiles(String skillName, String relativePath) {
        AgentSkill skill = resolveSkill(skillName);
        Path skillDirectory = repositoryService.resolveSkillDirectory(skill.directorySlug());
        Path directory = resolveDirectory(skillDirectory, relativePath);
        try (var stream = Files.list(directory)) {
            List<SkillFileEntry> entries = stream
                .map(path -> toFileEntry(skillDirectory, path))
                .sorted(Comparator.comparing(SkillFileEntry::directory).reversed()
                    .thenComparing(SkillFileEntry::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
            return new SkillFileTree(relativePath(skillDirectory, directory), entries);
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to list skill files");
        }
    }

    public SkillFileView viewFile(String skillName, String relativePath) {
        AgentSkill skill = resolveSkill(skillName);
        Path skillDirectory = repositoryService.resolveSkillDirectory(skill.directorySlug());
        Path file = resolveFileForRead(skill.directorySlug(), relativePath);
        try {
            long size = Files.size(file);
            String kind = fileKind(file);
            if (size > HARD_EDIT_BYTES) {
                return new SkillFileView(relativePath(skillDirectory, file), size, false, "too_large", false, null);
            }
            long warningLimit = "markdown".equals(kind) ? NORMAL_MARKDOWN_BYTES : NORMAL_TEXT_BYTES;
            String text = readUtf8(file);
            if (text == null) {
                return new SkillFileView(relativePath(skillDirectory, file), size, false, "unsupported", false, null);
            }
            if (size > warningLimit) {
                return new SkillFileView(relativePath(skillDirectory, file), size, true, kind, true, null);
            }
            return new SkillFileView(relativePath(skillDirectory, file), size, true, kind, false, stripBom(text));
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to read skill file");
        }
    }

    @Transactional
    public SkillFileView saveText(String skillName, String relativePath, String content) {
        AgentSkill skill = resolveSkill(skillName);
        Path file = resolveFileForRead(skill.directorySlug(), relativePath);
        String normalized = stripBom(content == null ? "" : content);
        if (normalized.getBytes(StandardCharsets.UTF_8).length > HARD_EDIT_BYTES) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "text file is too large");
        }
        String existing = readUtf8(file);
        if (existing == null) {
            throw new SkillApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "file is not valid UTF-8 text");
        }
        String lineEnding = dominantLineEnding(existing);
        try {
            Files.writeString(
                file,
                applyLineEnding(normalized, lineEnding),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to save skill file");
        }
        triggerRefreshIfSkillMarkdown(relativePath);
        return viewFile(skillName, relativePath);
    }

    @Transactional
    public AgentSkill createSkill(String skillSlug, String description) {
        String slug;
        try {
            slug = repositoryService.requireValidSlug(skillSlug);
        } catch (IllegalArgumentException exception) {
            throw toSkillApiException(exception);
        }
        if (!StringUtils.hasText(description)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "description is required");
        }
        if (description.length() > 1024) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "description must be 1024 characters or less");
        }
        Path root = repositoryService.ensureSkillsRoot();
        Path directory = root.resolve(slug).normalize();
        if (!directory.startsWith(root)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "skill path escapes skills root");
        }
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillApiException(HttpStatus.CONFLICT, "skill directory already exists");
        }
        try {
            Files.createDirectory(directory);
            Path skillMarkdown = directory.resolve("SKILL.md").normalize();
            String markdown = """
                ---
                name: %s
                description: %s
                ---
                # %s
                """.formatted(slug, yamlQuoted(description.trim()), slug);
            Files.writeString(skillMarkdown, markdown, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to create skill directory");
        }
        refreshSkills();
        return resolveSkill(slug);
    }

    @Transactional
    public SkillFileEntry createTextFile(String skillName, String parentPath, String fileName, String content) {
        AgentSkill skill = resolveSkill(skillName);
        Path skillDirectory = repositoryService.resolveSkillDirectory(skill.directorySlug());
        Path parent = resolveDirectory(skillDirectory, parentPath);
        String safeName = requirePlainName(fileName);
        String parentRelative = relativePath(skillDirectory, parent);
        String childRelative = ".".equals(parentRelative) ? safeName : parentRelative + "/" + safeName;
        Path target = repositoryService.resolveRelativePath(skillDirectory, childRelative);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillApiException(HttpStatus.CONFLICT, "target already exists");
        }
        String text = stripBom(content == null ? "" : content);
        if (text.getBytes(StandardCharsets.UTF_8).length > HARD_EDIT_BYTES) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "text file is too large");
        }
        try {
            Files.writeString(target, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            triggerRefreshIfSkillMarkdown(relativePath(skillDirectory, target));
            return toFileEntry(skillDirectory, target);
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to create file");
        }
    }

    @Transactional
    public SkillFileEntry createOptionalDirectory(String skillName, String directoryName) {
        AgentSkill skill = resolveSkill(skillName);
        Path skillDirectory = repositoryService.resolveSkillDirectory(skill.directorySlug());
        String safeName = requirePlainName(directoryName);
        if (!List.of("scripts", "references", "assets").contains(safeName)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "directoryName must be scripts, references, or assets");
        }
        Path target = repositoryService.resolveRelativePath(skillDirectory, safeName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                return toFileEntry(skillDirectory, target);
            }
            throw new SkillApiException(HttpStatus.CONFLICT, "target already exists");
        }
        try {
            Files.createDirectory(target);
            catalogService.refreshCatalog();
            return toFileEntry(skillDirectory, target);
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to create directory");
        }
    }

    public List<AgentSkillAssignment> listAgentAssignments(String skillName) {
        String normalizedSkillName = requireSkillName(skillName);
        return catalogService.listAll().stream()
            .filter(skill -> normalizedSkillName.equals(skill.name()) || normalizedSkillName.equals(skill.directorySlug()))
            .findFirst()
            .map(skill -> assignmentService.listAssignmentsForSkill(skill.name()))
            .orElseThrow(() -> new SkillApiException(HttpStatus.NOT_FOUND, "skill not found: " + normalizedSkillName));
    }

    @Transactional
    public AgentSkillAssignment assignToAgent(String skillName, String agentId, boolean enabled) {
        AgentSkill skill = resolveSkill(skillName);
        try {
            return assignmentService.assignToAgent(agentId, skill.name(), enabled);
        } catch (IllegalArgumentException exception) {
            throw toSkillApiException(exception);
        } catch (IllegalStateException exception) {
            throw toSkillApiException(exception);
        }
    }

    @Transactional
    public void unassignFromAgent(String skillName, String agentId) {
        AgentSkill skill = resolveSkill(skillName);
        try {
            assignmentService.unassignFromAgent(agentId, skill.name());
        } catch (IllegalArgumentException exception) {
            throw toSkillApiException(exception);
        } catch (IllegalStateException exception) {
            throw toSkillApiException(exception);
        }
    }

    private AgentSkill resolveSkill(String skillName) {
        String normalized = requireSkillName(skillName);
        AgentSkill byName = null;
        AgentSkill bySlug = null;
        for (AgentSkill skill : catalogService.listAll()) {
            if (byName == null && normalized.equals(skill.name())) {
                byName = skill;
            }
            if (bySlug == null && normalized.equals(skill.directorySlug())) {
                bySlug = skill;
            }
        }
        AgentSkill resolved = byName != null ? byName : bySlug;
        if (resolved == null) {
            throw new SkillApiException(HttpStatus.NOT_FOUND, "skill not found: " + normalized);
        }
        return resolved;
    }

    private Path resolveDirectory(Path skillDirectory, String relativePath) {
        Path resolved = repositoryService.resolveRelativePath(skillDirectory, normalizeRelativePath(relativePath));
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillApiException(HttpStatus.NOT_FOUND, "path does not exist");
        }
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "path is not a directory");
        }
        return resolved;
    }

    private Path resolveFileForRead(String skillSlug, String relativePath) {
        Path file;
        try {
            file = repositoryService.resolveExistingRelativePath(skillSlug, normalizeRelativePath(relativePath));
        } catch (IllegalArgumentException exception) {
            throw toSkillApiException(exception);
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "path is not a regular file");
        }
        return file;
    }

    private String requireSkillName(String skillName) {
        if (!StringUtils.hasText(skillName)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "skillName is required");
        }
        return skillName.trim();
    }

    private String requirePlainName(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "fileName is required");
        }
        String normalized = fileName.trim();
        if (normalized.contains("/") || normalized.contains("\\") || ".".equals(normalized) || "..".equals(normalized)) {
            throw new SkillApiException(HttpStatus.BAD_REQUEST, "fileName must be a plain file name");
        }
        return normalized;
    }

    private String normalizeRelativePath(String relativePath) {
        return StringUtils.hasText(relativePath) ? relativePath.trim() : ".";
    }

    private SkillApiException toSkillApiException(RuntimeException exception) {
        String message = exception.getMessage() == null ? "request failed" : exception.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("not found")) {
            return new SkillApiException(HttpStatus.NOT_FOUND, message);
        }
        if (lower.contains("does not exist")) {
            return new SkillApiException(HttpStatus.NOT_FOUND, message);
        }
        if (lower.contains("already exists") || lower.contains("conflict")) {
            return new SkillApiException(HttpStatus.CONFLICT, message);
        }
        if (lower.contains("utf-8") || lower.contains("binary") || lower.contains("unsupported")) {
            return new SkillApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message);
        }
        return new SkillApiException(HttpStatus.BAD_REQUEST, message);
    }

    private SkillFileEntry toFileEntry(Path root, Path path) {
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        long size = 0L;
        Instant modifiedAt = null;
        try {
            if (!directory) {
                size = Files.size(path);
            }
            FileTime modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS);
            modifiedAt = modified == null ? null : modified.toInstant();
        } catch (IOException ignored) {
            // leave defaults
        }
        return new SkillFileEntry(
            path.getFileName().toString(),
            relativePath(root, path),
            directory,
            size,
            modifiedAt
        );
    }

    private String relativePath(Path root, Path path) {
        Path relative = root.normalize().relativize(path.normalize());
        String value = relative.toString().replace('\\', '/');
        return StringUtils.hasText(value) ? value : ".";
    }

    private String sortKey(String name, String fallbackSlug) {
        return StringUtils.hasText(name) ? name.toLowerCase(Locale.ROOT) : fallbackSlug.toLowerCase(Locale.ROOT);
    }

    private String fileKind(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) {
            return "markdown";
        }
        return "text";
    }

    private String readUtf8(Path file) {
        try {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
        } catch (CharacterCodingException exception) {
            return null;
        } catch (IOException exception) {
            throw new SkillApiException(HttpStatus.CONFLICT, "failed to read skill file");
        }
    }

    private String stripBom(String text) {
        if (text != null && !text.isEmpty() && text.charAt(0) == '\uFEFF') {
            return text.substring(1);
        }
        return text;
    }

    private String dominantLineEnding(String existingContent) {
        if (existingContent == null || existingContent.isEmpty()) {
            return "\n";
        }
        int crlf = 0;
        int lf = 0;
        for (int i = 0; i < existingContent.length(); i++) {
            char ch = existingContent.charAt(i);
            if (ch == '\n') {
                if (i > 0 && existingContent.charAt(i - 1) == '\r') {
                    crlf++;
                } else {
                    lf++;
                }
            }
        }
        return crlf > lf ? "\r\n" : "\n";
    }

    private String applyLineEnding(String text, String lineEnding) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if ("\n".equals(lineEnding)) {
            return normalized;
        }
        return normalized.replace("\n", lineEnding);
    }

    private String yamlQuoted(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }

    private void triggerRefreshIfSkillMarkdown(String relativePath) {
        String normalized = normalizeRelativePath(relativePath).replace('\\', '/');
        if ("SKILL.md".equals(normalized) || "./SKILL.md".equals(normalized)) {
            catalogService.refreshCatalog();
        }
    }

    public record SkillCatalog(
        List<AgentSkill> skills,
        int validCount,
        int warningCount,
        int invalidCount
    ) { }

    public record SkillFileTree(String path, List<SkillFileEntry> entries) { }

    public record SkillFileEntry(
        String name,
        String path,
        boolean directory,
        long size,
        Instant modifiedAt
    ) { }

    public record SkillFileView(
        String path,
        long size,
        boolean text,
        String kind,
        boolean warning,
        String content
    ) { }

    public static class SkillApiException extends RuntimeException {
        private final HttpStatus status;

        public SkillApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
