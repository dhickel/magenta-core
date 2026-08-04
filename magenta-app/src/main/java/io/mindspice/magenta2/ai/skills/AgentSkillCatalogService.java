package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class AgentSkillCatalogService {
    private final AgentSkillRepositoryService repositoryService;
    private final AgentSkillParser parser;
    private final AgentSkillRepository repository;
    private SkillResolutionService skillResolutionService;

    public AgentSkillCatalogService(
        AgentSkillRepositoryService repositoryService,
        AgentSkillParser parser,
        AgentSkillRepository repository
    ) {
        this.repositoryService = repositoryService;
        this.parser = parser;
        this.repository = repository;
    }

    @Autowired(required = false)
    void setSkillResolutionService(SkillResolutionService skillResolutionService) {
        this.skillResolutionService = skillResolutionService;
    }

    /** Normal control-plane catalog resolution, including exact overlays. */
    public SkillResolutionService.SkillResolutionResult resolveScoped(SkillResolutionContext context) {
        if (skillResolutionService == null) {
            throw new IllegalStateException("Scoped skill resolution is unavailable");
        }
        return skillResolutionService.resolve(context);
    }

    public List<AgentSkill> listAll() {
        return repository.findAll();
    }

    @Transactional
    public AgentSkillRefreshResult refreshCatalog() {
        Path root = repositoryService.ensureSkillsRoot();
        Instant scanTime = Instant.now();
        Set<String> seenSlugs = new LinkedHashSet<>();
        List<AgentSkill> refreshed = new ArrayList<>();

        try (Stream<Path> stream = Files.list(root)) {
            List<Path> entries = stream
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
            for (Path entry : entries) {
                String slug = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)) {
                    seenSlugs.add(slug);
                    refreshed.add(save(scanTime, malformedSkill(root, slug, diagnostic(
                        AgentSkillDiagnosticSeverity.ERROR,
                        AgentSkillDiagnosticCode.SKILL_SYMLINK_REJECTED,
                        "symbolic links are not allowed in the skills repository",
                        root.relativize(entry).toString().replace('\\', '/')
                    ))));
                    continue;
                }
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                seenSlugs.add(slug);
                refreshed.add(save(scanTime, discoverDirectory(root, entry, slug)));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to scan skills root", exception);
        }

        repository.deleteByDirectorySlugNotIn(seenSlugs);
        long valid = refreshed.stream().filter(skill -> skill.status() == AgentSkillStatus.VALID).count();
        long warning = refreshed.stream().filter(skill -> skill.status() == AgentSkillStatus.WARNING).count();
        long invalid = refreshed.stream().filter(skill -> skill.status() == AgentSkillStatus.INVALID).count();
        return new AgentSkillRefreshResult(List.copyOf(refreshed), (int) valid, (int) warning, (int) invalid);
    }

    private AgentSkill discoverDirectory(Path root, Path directory, String slug) {
        String skillRootRelative = root.relativize(directory).toString().replace('\\', '/');
        try {
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realDirectory = directory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!realDirectory.startsWith(realRoot)) {
                return malformedSkill(
                    root,
                    slug,
                    diagnostic(
                        AgentSkillDiagnosticSeverity.ERROR,
                        AgentSkillDiagnosticCode.SKILL_PATH_ESCAPE_REJECTED,
                        "skill directory escapes the configured skills root",
                        skillRootRelative
                    )
                );
            }
        } catch (IOException exception) {
            return malformedSkill(
                root,
                slug,
                diagnostic(
                    AgentSkillDiagnosticSeverity.ERROR,
                    AgentSkillDiagnosticCode.SKILL_PATH_INVALID,
                    "skill directory path cannot be resolved",
                    skillRootRelative
                )
            );
        }

        Path skillMarkdown = directory.resolve("SKILL.md").normalize();
        if (!skillMarkdown.startsWith(directory)) {
            return malformedSkill(
                root,
                slug,
                diagnostic(
                    AgentSkillDiagnosticSeverity.ERROR,
                    AgentSkillDiagnosticCode.SKILL_PATH_ESCAPE_REJECTED,
                    "skill markdown path escapes directory",
                    skillRootRelative
                )
            );
        }
        if (Files.isSymbolicLink(skillMarkdown)) {
            return malformedSkill(
                root,
                slug,
                diagnostic(
                    AgentSkillDiagnosticSeverity.ERROR,
                    AgentSkillDiagnosticCode.SKILL_SYMLINK_REJECTED,
                    "SKILL.md cannot be a symbolic link",
                    skillRootRelative + "/SKILL.md"
                )
            );
        }
        if (!Files.isRegularFile(skillMarkdown, LinkOption.NOFOLLOW_LINKS)) {
            return malformedSkill(
                root,
                slug,
                diagnostic(
                    AgentSkillDiagnosticSeverity.ERROR,
                    AgentSkillDiagnosticCode.SKILL_MD_MISSING,
                    "directory does not contain required SKILL.md",
                    skillRootRelative
                )
            );
        }

        String skillMarkdownRelative = root.relativize(skillMarkdown).toString().replace('\\', '/');
        String markdown;
        try {
            markdown = Files.readString(skillMarkdown, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return malformedSkill(
                root,
                slug,
                diagnostic(
                    AgentSkillDiagnosticSeverity.ERROR,
                    AgentSkillDiagnosticCode.SKILL_MD_READ_FAILED,
                    "failed to read SKILL.md",
                    skillMarkdownRelative
                )
            );
        }

        AgentSkillParseResult parsed = parser.parse(markdown, slug, skillMarkdownRelative);
        AgentSkillFrontmatter frontmatter = parsed.frontmatter();
        String fallbackName = StringUtils.hasText(slug) ? slug : skillRootRelative;
        String name = frontmatter != null && StringUtils.hasText(frontmatter.name()) ? frontmatter.name() : fallbackName;
        String description = frontmatter != null ? frontmatter.description() : null;
        String license = frontmatter != null ? frontmatter.license() : null;
        String compatibility = frontmatter != null ? frontmatter.compatibility() : null;
        String allowedTools = frontmatter != null ? frontmatter.allowedTools() : null;
        boolean hasScripts = Files.isDirectory(directory.resolve("scripts"), LinkOption.NOFOLLOW_LINKS);
        boolean hasReferences = Files.isDirectory(directory.resolve("references"), LinkOption.NOFOLLOW_LINKS);
        boolean hasAssets = Files.isDirectory(directory.resolve("assets"), LinkOption.NOFOLLOW_LINKS);
        String contentHash = computeContentHash(directory, markdown);

        return new AgentSkill(
            null,
            name,
            slug,
            description,
            license,
            compatibility,
            frontmatter == null ? java.util.Map.of() : frontmatter.metadata(),
            allowedTools,
            skillRootRelative,
            skillMarkdownRelative,
            parsed.status(),
            parsed.diagnostics(),
            hasScripts,
            hasReferences,
            hasAssets,
            contentHash,
            null,
            null,
            null,
            null
        );
    }

    private AgentSkill malformedSkill(Path root, String slug, AgentSkillDiagnostic diagnostic) {
        String skillRootRelative = slug;
        return new AgentSkill(
            null,
            slug,
            slug,
            null,
            null,
            null,
            java.util.Map.of(),
            null,
            skillRootRelative,
            null,
            AgentSkillStatus.INVALID,
            List.of(diagnostic),
            false,
            false,
            false,
            null,
            null,
            null,
            null,
            null
        );
    }

    private AgentSkill save(Instant scanTime, AgentSkill skill) {
        AgentSkill existing = repository.findByDirectorySlug(skill.directorySlug()).orElse(null);
        AgentSkill toSave = new AgentSkill(
            existing == null ? null : existing.id(),
            skill.name(),
            skill.directorySlug(),
            skill.description(),
            skill.license(),
            skill.compatibility(),
            skill.metadata(),
            skill.allowedTools(),
            skill.skillRootRelativePath(),
            skill.skillMdRootRelativePath(),
            skill.status(),
            skill.diagnostics(),
            skill.hasScripts(),
            skill.hasReferences(),
            skill.hasAssets(),
            skill.contentHash(),
            existing == null ? scanTime : existing.discoveredAt(),
            scanTime,
            existing == null ? scanTime : existing.createdAt(),
            scanTime
        );
        return repository.save(toSave);
    }

    private AgentSkillDiagnostic diagnostic(
        AgentSkillDiagnosticSeverity severity,
        AgentSkillDiagnosticCode code,
        String message,
        String sourcePath
    ) {
        return new AgentSkillDiagnostic(severity, code, message, sourcePath);
    }

    private String computeContentHash(Path skillDirectory, String markdown) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(markdown.getBytes(StandardCharsets.UTF_8));
            try (Stream<Path> walk = Files.walk(skillDirectory)) {
                walk.filter(path -> !path.equals(skillDirectory))
                    .sorted()
                    .forEach(path -> addPathFingerprint(digest, skillDirectory, path));
            } catch (IOException exception) {
                digest.update(("walk-error:" + exception.getMessage()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("sha-256 is not available", exception);
        }
    }

    private void addPathFingerprint(MessageDigest digest, Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        digest.update(relative.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) ':');
        try {
            if (Files.isSymbolicLink(path)) {
                digest.update("symlink".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                digest.update("file".getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(Files.size(path)).getBytes(StandardCharsets.UTF_8));
                digest.update(Long.toString(Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis())
                    .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                digest.update("dir".getBytes(StandardCharsets.UTF_8));
                return;
            }
            digest.update("other".getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            digest.update(("error:" + exception.getClass().getSimpleName()).getBytes(StandardCharsets.UTF_8));
        }
    }

    public record AgentSkillRefreshResult(
        List<AgentSkill> skills,
        int validCount,
        int warningCount,
        int invalidCount
    ) { }

    // ── Phase 06 control-plane bridge ──

    /**
     * List all catalog entries that are loadable. Preserves the existing
     * model-visible behavior: only name and description are exposed.
     * Phase 07 will cut over to approved snapshots.
     */
    public List<AgentSkillCatalogEntry> loadableCatalogEntries() {
        List<AgentSkillCatalogEntry> entries = new ArrayList<>();
        for (AgentSkill skill : listAll()) {
            if (skill.status().loadable()
                && skill.name() != null && !skill.name().isBlank()
                && skill.description() != null && !skill.description().isBlank()) {
                entries.add(new AgentSkillCatalogEntry(skill.name(), skill.description()));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Check whether a refresh would change any persisted candidate.
     * Used by resolution callers to decide whether to persist observations.
     */
    public boolean hasCandidates() {
        return !listAll().isEmpty();
    }
}
