package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;

import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillRepositoryService {
    private static final Pattern SKILL_SLUG = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Pattern WINDOWS_DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    private final Path skillsRoot;

    public AgentSkillRepositoryService(MagentaRootProperties magentaRootProperties) {
        this.skillsRoot = magentaRootProperties.skillsRoot();
    }

    public Path ensureSkillsRoot() {
        try {
            Files.createDirectories(skillsRoot);
            return skillsRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("failed to create skills root directory", exception);
        }
    }

    public Path skillsRoot() {
        return skillsRoot;
    }

    public String requireValidSlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new IllegalArgumentException("skillSlug is required");
        }
        String normalized = slug.trim();
        if (!SKILL_SLUG.matcher(normalized).matches()) {
            throw new IllegalArgumentException("skillSlug must match ^[a-z0-9]+(?:-[a-z0-9]+)*$");
        }
        return normalized;
    }

    public Path resolveSkillDirectory(String skillSlug) {
        String slug = requireValidSlug(skillSlug);
        Path root = ensureSkillsRoot();
        Path directory = root.resolve(slug).normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException("skill path escapes skills root");
        }
        try {
            Path real = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root)) {
                throw new IllegalArgumentException("skill path escapes skills root");
            }
            if (Files.isSymbolicLink(directory)) {
                throw new IllegalArgumentException("symbolic links are not allowed for skills");
            }
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("skill path is not a directory");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("skill directory does not exist: " + slug, exception);
        }
    }

    public Path resolveSkillMarkdown(String skillSlug) {
        Path directory = resolveSkillDirectory(skillSlug);
        Path file = directory.resolve("SKILL.md").normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalArgumentException("skill file escapes skill directory");
        }
        if (Files.isSymbolicLink(file)) {
            throw new IllegalArgumentException("symbolic links are not allowed for skill markdown");
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("skill markdown file does not exist");
        }
        return file;
    }

    public Path resolveExistingRelativePath(String skillSlug, String relativePath) {
        Path directory = resolveSkillDirectory(skillSlug);
        Path resolved = resolveRelativePath(directory, relativePath);
        if (!Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path does not exist");
        }
        return resolved;
    }

    Path resolveRelativePath(Path directory, String relativePath) {
        String text = StringUtils.hasText(relativePath) ? relativePath.trim().replace('\\', '/') : ".";
        if (WINDOWS_DRIVE_PREFIX.matcher(text).matches()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        Path path = Path.of(text).normalize();
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are not allowed");
        }
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                throw new IllegalArgumentException("path escapes skill directory");
            }
        }
        Path resolved = directory.resolve(path).normalize();
        if (!resolved.startsWith(directory)) {
            throw new IllegalArgumentException("path escapes skill directory");
        }
        try {
            Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            rejectSymbolicAncestors(realDirectory, resolved);
            Path existing = nearestExistingAncestor(resolved);
            if (existing != null) {
                Path realExisting = existing.toRealPath();
                if (!realExisting.startsWith(realDirectory)) {
                    throw new IllegalArgumentException("path escapes skill directory");
                }
            }
            return resolved;
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid relative path", exception);
        }
    }

    private void rejectSymbolicAncestors(Path directory, Path target) {
        Path relative = directory.relativize(target.normalize());
        Path current = directory;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException("symbolic links are not allowed in skill paths");
            }
        }
    }

    private Path nearestExistingAncestor(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            current = current.getParent();
        }
        return current;
    }
}
