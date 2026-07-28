package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Read-only, precedence-ordered discovery of confined skill candidates. */
@Service
public class SkillScopeDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(SkillScopeDiscoveryService.class);

    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final MagentaRootProperties magentaRootProperties;
    private final AgentSkillParser parser;

    public SkillScopeDiscoveryService(WorkspaceDirectoryService workspaceDirectoryService,
                                      MagentaRootProperties magentaRootProperties,
                                      AgentSkillParser parser) {
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.magentaRootProperties = magentaRootProperties;
        this.parser = parser;
    }

    public ScopeDiscoveryResult discoverAll(SkillResolutionContext context) {
        String scanId = UUID.randomUUID().toString();
        Instant scanTime = Instant.now();
        List<DiscoveredCandidate> workspace = context.effectiveWorkspace() == null ? List.of()
                : discoverWorkspace(SkillScope.WORKSPACE, context.effectiveWorkspace().identity(),
                        context.effectiveWorkspace(), scanTime);
        List<DiscoveredCandidate> bound = context.boundAgentWorkspace() == null
                || samePhysicalRoot(context.boundAgentWorkspace(), context.effectiveWorkspace()) ? List.of()
                : discoverWorkspace(SkillScope.BOUND_AGENT, context.boundAgentWorkspace().identity(),
                        context.boundAgentWorkspace(), scanTime);
        List<DiscoveredCandidate> project = context.materializationSkillAuthority() == null ? List.of()
                : discoverMaterializationScope(context.materializationSkillAuthority(), scanTime);
        List<DiscoveredCandidate> global = discoverGlobal(scanTime);

        Map<String, CandidateObservation> observations = new LinkedHashMap<>();
        for (DiscoveredCandidate discovered : concat(workspace, bound, project, global)) {
            observations.putIfAbsent(discovered.candidate().identityKey(),
                    new CandidateObservation(discovered.candidate(), true, discovered.diagnostics(),
                            scanTime, scanId));
        }
        return new ScopeDiscoveryResult(candidates(workspace), candidates(bound), candidates(project),
                candidates(global), Map.copyOf(observations), scanId, scanTime);
    }

    /**
     * Discover materialization-scoped {@code .skills} through the exact
     * captured materialization authority. Resolves the materialization
     * location on every use, verifying owner, generation, readiness,
     * and confinement before opening the {@code .skills} directory.
     *
     * <p>A mismatch, stale generation, missing location, revoked/non-ready
     * state, or confinement failure produces an empty result (typed
     * unavailable). The caller must not silently substitute the effective
     * workspace, bound-agent root, project metadata, or a lower-precedence
     * root.
     */
    private List<DiscoveredCandidate> discoverMaterializationScope(
            MaterializationSkillAuthority authority, Instant scanTime) {
        try {
            var agentDescriptor = workspaceDirectoryService.resolveForRead(
                    WorkspaceKind.AGENT, authority.agentId());
            var location = workspaceDirectoryService.resolveMaterializationLocation(
                    agentDescriptor, authority.materializationKey(),
                    authority.capturedGeneration());

            // Verify confinement
            Path skillsRoot = location.confinedPath().resolve(".skills").normalize();
            if (!skillsRoot.startsWith(location.confinedPath())) {
                log.warn("Materialization .skills escapes root for {}",
                        authority.serializedKey());
                return List.of();
            }

            if (!Files.isDirectory(skillsRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(skillsRoot)) {
                return List.of();
            }

            // Encode materialization identity for trust-service re-resolution:
            // mat/<agentId>/<serializedKey>/<capturedGeneration>
            String rootId = "mat/" + authority.agentId() + "/"
                    + authority.serializedKey() + "/" + authority.capturedGeneration();
            List<DiscoveredCandidate> result = new ArrayList<>();
            try (Stream<Path> stream = Files.list(skillsRoot)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .forEach(path -> {
                            DiscoveredCandidate candidate = discoverFlatSkill(
                                    SkillScope.PROJECT, rootId, skillsRoot, path, scanTime);
                            if (candidate != null) result.add(candidate);
                        });
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            log.debug("Materialization skill authority is unavailable for {}",
                    authority.serializedKey());
            return List.of();
        }
    }

    private List<DiscoveredCandidate> discoverWorkspace(SkillScope scope, String rootId,
                                                         WorkspaceDescriptor descriptor, Instant scanTime) {
        if (descriptor.kind() == WorkspaceKind.CHAT) return List.of();
        Path skillsRoot = workspaceDirectoryService.resolveSkillsRoot(descriptor);
        if (skillsRoot == null || !Files.isDirectory(skillsRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(skillsRoot)) return List.of();
        List<DiscoveredCandidate> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        DiscoveredCandidate candidate = discoverFlatSkill(scope, rootId, skillsRoot, path, scanTime);
                        if (candidate != null) result.add(candidate);
                    });
        } catch (IOException exception) {
            log.warn("Failed to list .skills root for {}: {}", rootId, exception.getMessage());
        }
        return List.copyOf(result);
    }

    private List<DiscoveredCandidate> discoverGlobal(Instant scanTime) {
        Path skillsRoot = magentaRootProperties.skillsRoot();
        if (!Files.isDirectory(skillsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(skillsRoot)) {
            return List.of();
        }
        List<DiscoveredCandidate> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(skillsRoot)) {
            stream.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        DiscoveredCandidate candidate = discoverGlobalPackage(skillsRoot, path, scanTime);
                        if (candidate != null) result.add(candidate);
                    });
        } catch (IOException exception) {
            log.warn("Failed to list global skills root: {}", exception.getMessage());
        }
        return List.copyOf(result);
    }

    private DiscoveredCandidate discoverFlatSkill(SkillScope scope, String rootId, Path skillsRoot,
                                                    Path markdownFile, Instant scanTime) {
        String filename = markdownFile.getFileName().toString();
        if (Files.isSymbolicLink(markdownFile)
                || !Files.isRegularFile(markdownFile, LinkOption.NOFOLLOW_LINKS)
                || !isConfined(skillsRoot, markdownFile)) {
            return malformedCandidate(scope, rootId, SkillProvenance.WORKSPACE_FLAT, 1,
                    fallbackFlatKey(filename), new byte[0], filename,
                    AgentSkillDiagnosticCode.SKILL_PATH_ESCAPE_REJECTED,
                    "flat skill path is not a confined regular file", scanTime);
        }
        try {
            byte[] bytes = Files.readAllBytes(markdownFile);
            String markdown = decodeUtf8(bytes);
            AgentSkillParseResult parsed = parser.parse(markdown, null, filename);
            String logicalKey = resolveLogicalKey(parsed, fallbackFlatKey(filename));
            if (!StringUtils.hasText(logicalKey)) return null;
            CandidateVersion candidate = CandidateVersion.of(logicalKey, scope, rootId,
                    SkillProvenance.WORKSPACE_FLAT, 1, digestFlatFile(bytes), scanTime);
            return new DiscoveredCandidate(candidate, boundedDiagnostics(parsed.diagnostics()));
        } catch (CharacterCodingException exception) {
            return malformedCandidate(scope, rootId, SkillProvenance.WORKSPACE_FLAT, 1,
                    fallbackFlatKey(filename), safeRead(markdownFile), filename,
                    AgentSkillDiagnosticCode.SKILL_MD_READ_FAILED, "skill markdown is not valid UTF-8", scanTime);
        } catch (IOException exception) {
            log.warn("Failed to read flat skill {}: {}", filename, exception.getMessage());
            return null;
        }
    }

    private DiscoveredCandidate discoverGlobalPackage(Path skillsRoot, Path packageDirectory, Instant scanTime) {
        String slug = packageDirectory.getFileName().toString();
        Path skillMarkdown = packageDirectory.resolve("SKILL.md").normalize();
        if (Files.isSymbolicLink(packageDirectory) || !isConfined(skillsRoot, packageDirectory)
                || !Files.isRegularFile(skillMarkdown, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(skillMarkdown) || !isConfined(packageDirectory, skillMarkdown)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(skillMarkdown);
            String markdown = decodeUtf8(bytes);
            AgentSkillParseResult parsed = parser.parse(markdown, slug, slug + "/SKILL.md");
            String logicalKey = resolveLogicalKey(parsed, slug);
            if (!StringUtils.hasText(logicalKey)) return null;
            CandidateVersion candidate = CandidateVersion.of(logicalKey, SkillScope.GLOBAL, "global",
                    SkillProvenance.GLOBAL_PACKAGE, 2, digestPackageTree(packageDirectory, bytes), scanTime);
            return new DiscoveredCandidate(candidate, boundedDiagnostics(parsed.diagnostics()));
        } catch (CharacterCodingException exception) {
            return malformedCandidate(SkillScope.GLOBAL, "global", SkillProvenance.GLOBAL_PACKAGE, 2,
                    slug, safeRead(skillMarkdown), slug + "/SKILL.md",
                    AgentSkillDiagnosticCode.SKILL_MD_READ_FAILED, "SKILL.md is not valid UTF-8", scanTime);
        } catch (IOException | IllegalArgumentException exception) {
            return malformedCandidate(SkillScope.GLOBAL, "global", SkillProvenance.GLOBAL_PACKAGE, 2,
                    slug, safeRead(skillMarkdown), slug + "/SKILL.md",
                    AgentSkillDiagnosticCode.SKILL_PATH_ESCAPE_REJECTED,
                    "package tree is unsafe: " + exception.getMessage(), scanTime);
        }
    }

    private DiscoveredCandidate malformedCandidate(SkillScope scope, String rootId, SkillProvenance provenance,
                                                     int format, String key, byte[] bytes, String sourcePath,
                                                     AgentSkillDiagnosticCode code, String message, Instant time) {
        if (!StringUtils.hasText(key)) return null;
        CandidateVersion candidate = CandidateVersion.of(key, scope, rootId, provenance, format,
                digestFlatFile(bytes), time);
        return new DiscoveredCandidate(candidate, List.of(new AgentSkillDiagnostic(
                AgentSkillDiagnosticSeverity.ERROR, code, truncate(message, 512), safeRelativeSource(sourcePath))));
    }

    static String digestFlatFile(byte[] bytes) {
        return sha256(bytes);
    }

    /** Hashes a validated, link-free package tree using canonical relative path framing. */
    static String digestPackageTree(Path packageDirectory, byte[] ignoredSkillMarkdownBytes) {
        try {
            Path root = packageDirectory.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(packageDirectory) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("package root is not a regular directory");
            }
            List<Path> files = new ArrayList<>();
            Set<String> canonicalNames = new HashSet<>();
            try (Stream<Path> walk = Files.walk(packageDirectory)) {
                for (Path entry : walk.filter(path -> !path.equals(packageDirectory)).toList()) {
                    Path normalized = entry.toAbsolutePath().normalize();
                    if (!normalized.startsWith(packageDirectory.toAbsolutePath().normalize())
                            || Files.isSymbolicLink(entry)) {
                        throw new IllegalArgumentException("package entry escapes or is a symbolic link");
                    }
                    if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IllegalArgumentException("package contains a non-regular entry");
                    }
                    Path real = entry.toRealPath();
                    if (!real.startsWith(root)) throw new IllegalArgumentException("package entry escapes root");
                    String relative = canonicalRelative(packageDirectory.relativize(entry));
                    String collisionKey = Normalizer.normalize(relative, Normalizer.Form.NFC)
                            .toLowerCase(Locale.ROOT);
                    if (!canonicalNames.add(collisionKey)) {
                        throw new IllegalArgumentException("package contains colliding relative paths");
                    }
                    files.add(entry);
                }
            }
            files.sort(Comparator.comparing(path -> canonicalRelative(packageDirectory.relativize(path))));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path file : files) {
                frameEntry(digest, canonicalRelative(packageDirectory.relativize(file)), Files.readAllBytes(file));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to compute package tree digest", exception);
        }
    }

    private static void frameEntry(MessageDigest digest, String relativePath, byte[] content) {
        byte[] path = relativePath.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (path.length >>> 24));
        digest.update((byte) (path.length >>> 16));
        digest.update((byte) (path.length >>> 8));
        digest.update((byte) path.length);
        digest.update(path);
        long size = content.length;
        for (int shift = 56; shift >= 0; shift -= 8) digest.update((byte) (size >>> shift));
        digest.update(content);
    }

    private static String canonicalRelative(Path relative) {
        if (relative.isAbsolute()) throw new IllegalArgumentException("absolute package path");
        Path normalized = relative.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith("..")) {
            throw new IllegalArgumentException("escaping package path");
        }
        return normalized.toString().replace('\\', '/');
    }

    private static boolean isConfined(Path root, Path child) {
        try {
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realChild = child.toRealPath();
            return realChild.startsWith(realRoot);
        } catch (IOException exception) {
            return false;
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private static String resolveLogicalKey(AgentSkillParseResult parsed, String fallback) {
        return parsed.frontmatter() != null && StringUtils.hasText(parsed.frontmatter().name())
                ? parsed.frontmatter().name().trim() : fallback;
    }

    private static String fallbackFlatKey(String filename) {
        return filename != null && filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
    }

    private static List<AgentSkillDiagnostic> boundedDiagnostics(List<AgentSkillDiagnostic> diagnostics) {
        if (diagnostics == null) return List.of();
        return diagnostics.stream().limit(32).map(diagnostic -> new AgentSkillDiagnostic(
                diagnostic.severity(), diagnostic.code(), truncate(diagnostic.message(), 512),
                safeRelativeSource(diagnostic.sourcePath()))).toList();
    }

    private static String safeRelativeSource(String source) {
        if (!StringUtils.hasText(source)) return null;
        try {
            Path path = Path.of(source.replace('\\', '/')).normalize();
            if (path.isAbsolute() || path.startsWith("..")) return null;
            return truncate(path.toString().replace('\\', '/'), 255);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private static byte[] safeRead(Path path) {
        try { return Files.readAllBytes(path); } catch (IOException ignored) { return new byte[0]; }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static boolean samePhysicalRoot(WorkspaceDescriptor first, WorkspaceDescriptor second) {
        return first != null && second != null && first.root().equals(second.root());
    }

    @SafeVarargs
    private static List<DiscoveredCandidate> concat(List<DiscoveredCandidate>... lists) {
        List<DiscoveredCandidate> all = new ArrayList<>();
        for (List<DiscoveredCandidate> list : lists) all.addAll(list);
        return all;
    }

    private static List<CandidateVersion> candidates(List<DiscoveredCandidate> discovered) {
        return discovered.stream().map(DiscoveredCandidate::candidate).toList();
    }

    private record DiscoveredCandidate(CandidateVersion candidate, List<AgentSkillDiagnostic> diagnostics) { }

    public record ScopeDiscoveryResult(List<CandidateVersion> workspaceCandidates,
                                       List<CandidateVersion> boundAgentCandidates,
                                       List<CandidateVersion> projectCandidates,
                                       List<CandidateVersion> globalCandidates,
                                       Map<String, CandidateObservation> observationsByIdentityKey,
                                       String scanId,
                                       Instant scanTime) {
        public List<CandidateVersion> allByPrecedence() {
            List<CandidateVersion> all = new ArrayList<>();
            all.addAll(workspaceCandidates);
            all.addAll(boundAgentCandidates);
            all.addAll(projectCandidates);
            all.addAll(globalCandidates);
            return List.copyOf(all);
        }
    }

    public record CandidateObservation(CandidateVersion candidate, boolean available,
                                       List<AgentSkillDiagnostic> diagnostics,
                                       Instant observedAt, String scanId) {
        public boolean malformed() {
            return diagnostics != null && diagnostics.stream()
                    .anyMatch(diagnostic -> diagnostic.severity() == AgentSkillDiagnosticSeverity.ERROR);
        }
    }
}
