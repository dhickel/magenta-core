package io.mindspice.magenta2.ai.skills;

import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDirectoryService;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceDescriptor;
import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import io.mindspice.magenta2.core.config.MagentaRootProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Digest-bound approval and immutable snapshot management. */
@Service
public class SkillTrustService {
    private static final Logger log = LoggerFactory.getLogger(SkillTrustService.class);

    private final SkillSnapshotRepository snapshotRepository;
    private final AgentSkillAssignmentRepository assignmentRepository;
    private final WorkspaceDirectoryService workspaceDirectoryService;
    private final MagentaRootProperties magentaRootProperties;
    private final AgentSkillParser parser;
    private final ObjectMapper objectMapper;
    private final java.util.Map<String, MaterializationSkillAuthority> materializationAuthorities
            = new ConcurrentHashMap<>();

    public SkillTrustService(SkillSnapshotRepository snapshotRepository,
                             AgentSkillAssignmentRepository assignmentRepository,
                             WorkspaceDirectoryService workspaceDirectoryService,
                             MagentaRootProperties magentaRootProperties,
                             AgentSkillParser parser,
                             ObjectMapper objectMapper) {
        this.snapshotRepository = snapshotRepository;
        this.assignmentRepository = assignmentRepository;
        this.workspaceDirectoryService = workspaceDirectoryService;
        this.magentaRootProperties = magentaRootProperties;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    /**
     * Retain the typed authority captured during discovery for later approval
     * rereads. The persisted candidate scope identity is deliberately opaque;
     * it is never parsed into filesystem authority.
     */
    void retainMaterializationAuthority(CandidateVersion candidate,
                                        MaterializationSkillAuthority authority) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(authority, "authority");
        if (candidate.scopeType() != SkillScope.PROJECT
                || !candidate.authoritativeRootId().equals("project/" + authority.scopeIdentity())) {
            throw new IllegalArgumentException("Candidate does not match materialization authority");
        }
        materializationAuthorities.put(candidate.authoritativeRootId(), authority);
    }

    // ── Approval ──

    /**
     * Approve a candidate at the exact observed digest. Re-reads the confined
     * source, canonicalizes content, validates identity/digest match, and
     * creates an immutable snapshot.
     */
    public ApprovalResult approve(String operationKey, Long candidateVersionId,
                                   String observedDigest, String actor, String reason) {
        requireOperationKey(operationKey);
        if (candidateVersionId == null || candidateVersionId < 1)
            throw new IllegalArgumentException("candidateVersionId is required");
        requireDigest(observedDigest);

        // Check idempotent operation replay
        var existing = snapshotRepository.findDecisionByOperationKey(operationKey);
        if (existing.isPresent()) {
            var decision = existing.get();
            if (decision.candidateVersionId().equals(candidateVersionId)
                    && decision.requestDigest().equals(observedDigest)) {
                return new ApprovalResult(decision.id(), decision.snapshotId(), decision.decision(),
                        "SNAPSHOT_EXISTS", decision.decidedAt());
            }
            throw new IllegalArgumentException("Contradictory reuse of operation key: " + operationKey);
        }

        CandidateVersion candidate = assignmentRepository.findCandidateById(candidateVersionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown candidate: " + candidateVersionId));
        if (!candidate.contentDigest().equals(observedDigest)) {
            throw new IllegalArgumentException(
                "Digest mismatch: candidate has " + candidate.contentDigest()
                    + " but caller observed " + observedDigest);
        }

        // Re-read confined source and build canonical manifest
        CanonicalManifest manifest = buildManifest(candidate);
        if (!manifest.digest().equals(observedDigest)) {
            throw new IllegalArgumentException(
                "Content changed since discovery: manifest digest " + manifest.digest()
                    + " does not match observed " + observedDigest);
        }

        // Check no existing snapshot for this candidate (race protection)
        if (snapshotRepository.snapshotExistsForCandidate(candidateVersionId)) {
            throw new IllegalArgumentException(
                "Snapshot already exists for candidate " + candidateVersionId);
        }

        // Parse for model-visible metadata
        String markdown = manifest.skillMarkdown();
        String slug = candidate.provenance() == SkillProvenance.GLOBAL_PACKAGE
            ? candidate.logicalKey() : null;
        AgentSkillParseResult parsed = parser.parse(markdown, slug,
            candidate.provenance() == SkillProvenance.GLOBAL_PACKAGE
                ? candidate.logicalKey() + "/SKILL.md" : candidate.logicalKey() + ".md");
        if (!parsed.loadable()) {
            throw new IllegalArgumentException("Skill is malformed and cannot be approved");
        }

        String skillName = parsed.frontmatter() != null && StringUtils.hasText(parsed.frontmatter().name())
            ? parsed.frontmatter().name().trim() : candidate.logicalKey();
        String description = parsed.frontmatter() != null && StringUtils.hasText(parsed.frontmatter().description())
            ? parsed.frontmatter().description().trim() : "";
        if (skillName.length() > 128 || description.length() > 1024) {
            throw new IllegalArgumentException("Skill name or description exceeds allowed length");
        }
        if (parsed.body() != null && parsed.body().length() > 262144) {
            throw new IllegalArgumentException("Skill instructions exceed allowed length");
        }
        String hints = parsed.frontmatter() != null ? parsed.frontmatter().compatibility() : null;
        String allowedTools = parsed.frontmatter() != null ? parsed.frontmatter().allowedTools() : null;

        // Build resource manifest
        List<ResourceManifestEntry> manifestEntries = new ArrayList<>();
        for (CanonicalManifest.ManifestEntry entry : manifest.entries()) {
            manifestEntries.add(new ResourceManifestEntry(
                entry.relativePath(), entry.resourceType(), entry.contentLength(), entry.contentDigest()));
        }
        String manifestJson;
        try {
            manifestJson = objectMapper.writeValueAsString(manifestEntries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize resource manifest", e);
        }

        // Insert immutable snapshot
        var snapshot = new SkillSnapshotRepository.SkillSnapshot(
            null, candidateVersionId, candidate.logicalKey(),
            candidate.provenance(), candidate.formatVersion(),
            manifest.digest(), skillName, description,
            parsed.body() != null ? parsed.body() : "",
            hints, allowedTools, manifestJson, Instant.now()
        );
        var persisted = snapshotRepository.insertSnapshot(snapshot);

        // Insert resource bytes
        for (CanonicalManifest.ManifestEntry entry : manifest.entries()) {
            snapshotRepository.insertResource(new SkillSnapshotRepository.SkillSnapshotResource(
                null, persisted.id(), entry.relativePath(), entry.resourceType(),
                entry.contentLength(), entry.contentBytes(), entry.contentDigest()));
        }

        // Record trust decision
        String requestDigest = sha256((operationKey + "|" + candidateVersionId
            + "|" + observedDigest + "|" + manifest.digest()).getBytes(StandardCharsets.UTF_8));
        var decision = snapshotRepository.insertDecision(new SkillSnapshotRepository.TrustDecisionRecord(
            null, candidateVersionId, candidate.logicalKey(), "APPROVE",
            persisted.id(), actor, reason, "INSTRUCTION",
            operationKey, requestDigest, Instant.now()));

        log.info("Approved snapshot {} for candidate {} (logical key: {})",
            persisted.id(), candidateVersionId, candidate.logicalKey());
        return new ApprovalResult(decision.id(), persisted.id(), "APPROVE",
            "SNAPSHOT_CREATED", decision.decidedAt());
    }

    /** Reject a candidate. Idempotent with operation key. */
    public TrustResult reject(String operationKey, Long candidateVersionId,
                               String actor, String reason) {
        requireOperationKey(operationKey);
        if (candidateVersionId == null || candidateVersionId < 1)
            throw new IllegalArgumentException("candidateVersionId is required");

        var existing = snapshotRepository.findDecisionByOperationKey(operationKey);
        if (existing.isPresent()) {
            var d = existing.get();
            return new TrustResult(d.id(), d.decision(), d.decidedAt());
        }

        CandidateVersion candidate = assignmentRepository.findCandidateById(candidateVersionId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown candidate: " + candidateVersionId));

        String requestDigest = sha256((operationKey + "|" + candidateVersionId + "|REJECT")
            .getBytes(StandardCharsets.UTF_8));
        var decision = snapshotRepository.insertDecision(new SkillSnapshotRepository.TrustDecisionRecord(
            null, candidateVersionId, candidate.logicalKey(), "REJECT",
            null, actor, reason, "INSTRUCTION",
            operationKey, requestDigest, Instant.now()));

        return new TrustResult(decision.id(), "REJECT", decision.decidedAt());
    }

    /** Revoke a previously approved logical key. Blocks future activations. */
    public TrustResult revoke(String operationKey, String logicalKey, String actor, String reason) {
        requireOperationKey(operationKey);
        requireLogicalKey(logicalKey);

        var existing = snapshotRepository.findDecisionByOperationKey(operationKey);
        if (existing.isPresent()) {
            var d = existing.get();
            return new TrustResult(d.id(), d.decision(), d.decidedAt());
        }

        String requestDigest = sha256((operationKey + "|" + logicalKey + "|REVOKE")
            .getBytes(StandardCharsets.UTF_8));
        var decision = snapshotRepository.insertDecision(new SkillSnapshotRepository.TrustDecisionRecord(
            null, null, logicalKey, "REVOKE",
            null, actor, reason, "INSTRUCTION",
            operationKey, requestDigest, Instant.now()));

        return new TrustResult(decision.id(), "REVOKE", decision.decidedAt());
    }

    // ── Catalog / disclosure ──

    /**
     * Build a model-visible catalog from approved snapshots only.
     * Pending, rejected, and revoked candidates are absent.
     */
    public List<AgentSkillCatalogEntry> approvedCatalog() {
        return snapshotRepository.findCurrentlyApprovedSnapshots().stream()
            .map(snapshot -> new AgentSkillCatalogEntry(snapshot.skillName(), snapshot.description()))
            .toList();
    }

    /**
     * Check whether a logical key currently has an approved snapshot.
     */
    public boolean isCurrentlyApproved(String logicalKey) {
        return snapshotRepository.isCurrentlyApproved(logicalKey);
    }

    /**
     * Get the latest approved snapshot for a logical key.
     */
    public SkillSnapshotRepository.SkillSnapshot getApprovedSnapshot(String logicalKey) {
        return snapshotRepository.findLatestApproved(logicalKey)
            .orElse(null);
    }

    // ── Canonical manifest ──

    /**
     * Build a canonical manifest from the confined source of a candidate.
     * Re-opens the file through trusted services, validates the tree,
     * canonicalizes and hashes all content. Rejects links, devices,
     * absolute paths, traversal, duplicates.
     */
    CanonicalManifest buildManifest(CandidateVersion candidate) {
        Path sourceRoot;
        Path markdownFile;

        if (candidate.provenance() == SkillProvenance.WORKSPACE_FLAT) {
            // Flat workspace or materialization skill: a single .md file
            Path skillsRoot = resolveSkillsRoot(candidate);
            if (skillsRoot == null) throw new IllegalArgumentException("Skills root not found");
            markdownFile = skillsRoot.resolve(candidate.logicalKey() + ".md").normalize();
            if (!markdownFile.startsWith(skillsRoot))
                throw new IllegalArgumentException("Skill markdown path escapes skills root");
            sourceRoot = skillsRoot; // For flat files, the root is the .skills dir
        } else {
            // Global package: a directory with SKILL.md
            Path globalRoot = magentaRootProperties.skillsRoot();
            Path pkgDir = globalRoot.resolve(candidate.logicalKey()).normalize();
            if (!pkgDir.startsWith(globalRoot))
                throw new IllegalArgumentException("Global package path escapes root");
            markdownFile = pkgDir.resolve("SKILL.md").normalize();
            if (!markdownFile.startsWith(pkgDir))
                throw new IllegalArgumentException("SKILL.md path escapes package directory");
            sourceRoot = pkgDir;
        }

        validateConfined(sourceRoot, markdownFile);
        if (!Files.isRegularFile(markdownFile, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("Skill markdown is not a regular file");

        byte[] markdownBytes;
        try {
            markdownBytes = Files.readAllBytes(markdownFile);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read SKILL.md", e);
        }
        String markdown = new String(markdownBytes, StandardCharsets.UTF_8);

        List<CanonicalManifest.ManifestEntry> entries = new ArrayList<>();

        // Add SKILL.md as the first entry
        String skillMdRelative = candidate.provenance() == SkillProvenance.WORKSPACE_FLAT
            ? candidate.logicalKey() + ".md" : "SKILL.md";
        entries.add(new CanonicalManifest.ManifestEntry(
            skillMdRelative, "SKILL_MD", markdownBytes.length, sha256(markdownBytes), markdownBytes));

        // For global packages, walk the directory tree
        if (candidate.provenance() == SkillProvenance.GLOBAL_PACKAGE) {
            Path pkgDir = magentaRootProperties.skillsRoot().resolve(candidate.logicalKey()).normalize();
            try (Stream<Path> walk = Files.walk(pkgDir)) {
                List<Path> files = walk
                    .filter(path -> !path.equals(pkgDir) && !path.endsWith("SKILL.md"))
                    .filter(SkillTrustService::isRegularFileSafe)
                    .sorted(Comparator.comparing(p -> canonicalRelative(pkgDir.relativize(p))))
                    .toList();

                Set<String> seen = new HashSet<>();
                for (Path file : files) {
                    String relative = canonicalRelative(pkgDir.relativize(file));
                    String collision = Normalizer.normalize(relative, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT);
                    if (!seen.add(collision))
                        throw new IllegalArgumentException("Duplicate resource path: " + relative);

                    byte[] content = Files.readAllBytes(file);
                    long size = content.length;
                    String resourceType = classifyResource(relative);
                    entries.add(new CanonicalManifest.ManifestEntry(
                        relative, resourceType, (int) size, sha256(content), content));
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("Failed to walk package tree", e);
            }
        }

        // Build canonical digest
        String digest = computeCanonicalDigest(entries);

        return new CanonicalManifest(digest, markdown, List.copyOf(entries));
    }

    static String computeCanonicalDigest(List<CanonicalManifest.ManifestEntry> entries) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (CanonicalManifest.ManifestEntry entry : entries) {
                byte[] path = entry.relativePath().getBytes(StandardCharsets.UTF_8);
                md.update((byte) (path.length >>> 24));
                md.update((byte) (path.length >>> 16));
                md.update((byte) (path.length >>> 8));
                md.update((byte) path.length);
                md.update(path);
                md.update((byte) (entry.contentLength() >>> 24));
                md.update((byte) (entry.contentLength() >>> 16));
                md.update((byte) (entry.contentLength() >>> 8));
                md.update((byte) entry.contentLength());
                md.update(entry.contentDigest().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ── Helper methods ──

    /**
     * Resolve a workspace descriptor or captured materialization location for
     * a candidate. Materialization authority is retained as a typed value at
     * discovery and is never recovered by parsing a persisted root string.
     */
    private Path resolveSkillsRoot(CandidateVersion candidate) {
        MaterializationSkillAuthority authority = materializationAuthorities.get(
                candidate.authoritativeRootId());
        if (authority != null) {
            return resolveMaterializationSkillsRoot(authority);
        }
        if (candidate.scopeType() == SkillScope.PROJECT) {
            throw new IllegalArgumentException("Materialization skill authority is unavailable");
        }

        String authoritativeRootId = candidate.authoritativeRootId();
        int slash = authoritativeRootId.indexOf('/');
        if (slash < 1 || slash != authoritativeRootId.lastIndexOf('/')) {
            throw new IllegalArgumentException("Invalid workspace authority");
        }
        String kind = authoritativeRootId.substring(0, slash);
        String key = authoritativeRootId.substring(slash + 1);
        WorkspaceKind wk = switch (kind) {
            case "agent" -> WorkspaceKind.AGENT;
            case "user" -> WorkspaceKind.USER;
            default -> throw new IllegalArgumentException("Invalid workspace kind: " + kind);
        };
        WorkspaceDescriptor descriptor = workspaceDirectoryService.resolveForRead(wk, key);
        return workspaceDirectoryService.resolveSkillsRoot(descriptor);
    }

    /**
     * Re-resolve a materialization-scoped skills root from the exact typed
     * captured authority. The directory service revalidates owner, generation,
     * readiness, canonical root-relative identity, and confinement.
     */
    private Path resolveMaterializationSkillsRoot(MaterializationSkillAuthority authority) {
        var agentDescriptor = workspaceDirectoryService.resolveForRead(
                WorkspaceKind.AGENT, authority.agentId());
        var location = workspaceDirectoryService.resolveMaterializationLocation(
                agentDescriptor, authority.materializationKey(), authority.capturedGeneration());
        if (!location.materializationKey().serialized().equals(authority.rootRelativeIdentity())
                || location.generation() != authority.capturedGeneration()) {
            throw new IllegalArgumentException("Materialization authority no longer matches its location");
        }

        Path skillsRoot = location.confinedPath().resolve(".skills").normalize();
        if (!skillsRoot.startsWith(location.confinedPath())) {
            throw new IllegalArgumentException("Materialization .skills escapes root");
        }
        return skillsRoot;
    }

    private void validateConfined(Path root, Path child) {
        try {
            Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
            Path realChild = child.toRealPath();
            if (!realChild.startsWith(realRoot))
                throw new IllegalArgumentException("Path escapes root");
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to validate path confinement", e);
        }
    }

    private static String canonicalRelative(Path relative) {
        if (relative.isAbsolute()) throw new IllegalArgumentException("Absolute paths are not allowed");
        Path normalized = relative.normalize();
        if (normalized.getNameCount() == 0 || normalized.startsWith(".."))
            throw new IllegalArgumentException("Path escapes root");
        return normalized.toString().replace('\\', '/');
    }

    private static String classifyResource(String relativePath) {
        if (relativePath.startsWith("scripts/")) return "SCRIPT";
        if (relativePath.startsWith("references/")) return "REFERENCE";
        if (relativePath.startsWith("assets/")) return "ASSET";
        return "REFERENCE";
    }

    private static boolean isRegularFileSafe(Path path) {
        return Files.isRegularFile(path);
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void requireOperationKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 255)
            throw new IllegalArgumentException("operationKey is required and must be <= 255 chars");
    }

    private static void requireDigest(String digest) {
        if (!StringUtils.hasText(digest) || digest.length() != 64
                || !digest.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
            throw new IllegalArgumentException("digest must be 64-char lowercase hex");
    }

    private static void requireLogicalKey(String key) {
        if (!StringUtils.hasText(key) || key.length() > 64)
            throw new IllegalArgumentException("logicalKey is required");
    }

    // ── Result types ──

    public record ApprovalResult(Long decisionId, Long snapshotId, String decision,
                                  String outcome, Instant decidedAt) { }

    public record TrustResult(Long decisionId, String decision, Instant decidedAt) { }

    public record ResourceManifestEntry(String relativePath, String resourceType,
                                         int contentLength, String contentDigest) { }

    public record CanonicalManifest(String digest, String skillMarkdown,
                                     List<ManifestEntry> entries) {
        public record ManifestEntry(String relativePath, String resourceType,
                                     int contentLength, String contentDigest,
                                     byte[] contentBytes) { }
    }
}
