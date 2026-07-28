package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Snapshots-only activation service. Never reads live skill files.
 * Activation is a read of immutable persisted bytes plus current audit state.
 */
@Service
public class AgentSkillActivationService {
    private final SkillSnapshotRepository snapshotRepository;
    private final SkillTrustService trustService;
    private final SkillResolutionService resolutionService;
    private final Map<String, Set<String>> activeSkillsByConversation = new ConcurrentHashMap<>();

    public AgentSkillActivationService(SkillSnapshotRepository snapshotRepository,
                                        SkillTrustService trustService,
                                        SkillResolutionService resolutionService) {
        this.snapshotRepository = snapshotRepository;
        this.trustService = trustService;
        this.resolutionService = resolutionService;
    }

    /**
     * Activate a skill by name from approved snapshots. Verifies approval,
     * records activation audit, returns immutable instruction/resource bytes.
     * Never reads live skill files.
     */
    public ActivationResult activate(String skillName, String agentId,
                                      String taskRunId, String conversationId) {
        if (!StringUtils.hasText(skillName)) {
            return ActivationResult.failure(skillName, "Skill name is required");
        }
        String normalized = skillName.trim();

        // 1. Find the approved snapshot
        SkillSnapshotRepository.SkillSnapshot snapshot = trustService.getApprovedSnapshot(normalized);
        if (snapshot == null) {
            return ActivationResult.failure(normalized,
                "No approved snapshot found for skill: " + normalized);
        }

        // 2. Verify current approval (revocation check)
        if (!trustService.isCurrentlyApproved(normalized)) {
            return ActivationResult.failure(normalized,
                "Skill " + normalized + " is not currently approved (may be revoked)");
        }

        // 3. Verify snapshot matches latest approved
        SkillSnapshotRepository.SkillSnapshot latestApproved = trustService.getApprovedSnapshot(normalized);
        if (latestApproved == null || !latestApproved.id().equals(snapshot.id())) {
            return ActivationResult.failure(normalized,
                "Approved snapshot has changed since resolution");
        }

        // 4. Check deduplication per conversation
        if (StringUtils.hasText(conversationId)) {
            var active = activeSkillsByConversation.get(conversationId.trim());
            if (active != null && active.contains(normalized)) {
                return ActivationResult.failure(normalized,
                    "Skill is already active in this conversation");
            }
        }

        // 5. Create activation context and record
        String activationContext = buildActivationContext(agentId, taskRunId, conversationId);

        // Deduplicate activation by context+snapshot
        var existingActivation = snapshotRepository.findByActivationContext(snapshot.id(), activationContext);
        if (existingActivation.isPresent()) {
            // Already activated for this exact context — return same result
            return buildResult(normalized, snapshot, existingActivation.get());
        }

        // 6. Persist activation audit
        try {
            var activation = snapshotRepository.insertActivation(
                new SkillSnapshotRepository.SkillActivationRecord(
                    null, snapshot.id(), normalized, taskRunId != null ? taskRunId : "direct",
                    conversationId, agentId != null ? agentId : "system",
                    activationContext, Instant.now()));
            logActivation(normalized, snapshot.id(), activationContext);

            // Track per-conversation
            if (StringUtils.hasText(conversationId)) {
                activeSkillsByConversation.computeIfAbsent(
                    conversationId.trim(), k -> ConcurrentHashMap.newKeySet()).add(normalized);
            }

            return buildResult(normalized, snapshot, activation);
        } catch (Exception e) {
            return ActivationResult.failure(normalized,
                "Failed to persist activation audit: " + e.getMessage());
        }
    }

    /**
     * Same-run resume: rehydrate from a persisted activation for the
     * exact taskRunId. Does not re-check approval; only returns the
     * previously recorded activation for that run.
     */
    public ActivationResult resumeForTaskRun(String skillName, String taskRunId) {
        if (!StringUtils.hasText(skillName) || !StringUtils.hasText(taskRunId)) {
            return ActivationResult.failure(skillName,
                "Skill name and task run ID are required for resume");
        }
        String normalized = skillName.trim();

        var existing = snapshotRepository.findActivationForTaskRun(taskRunId, normalized);
        if (existing.isEmpty()) {
            return ActivationResult.failure(normalized,
                "No prior activation found for task run " + taskRunId);
        }

        var activation = existing.get();
        var snapshot = snapshotRepository.findById(activation.snapshotId());
        if (snapshot.isEmpty()) {
            return ActivationResult.failure(normalized,
                "Snapshot for prior activation no longer exists");
        }

        return buildResult(normalized, snapshot.get(), activation);
    }

    /**
     * List approved skill names available for model disclosure.
     * Only approved snapshots; no pending/rejected/revoked content.
     */
    public List<String> approvedSkillNames() {
        return trustService.approvedCatalog().stream()
            .map(AgentSkillCatalogEntry::name)
            .toList();
    }

    /**
     * Build model-visible catalog entries from approved snapshots only.
     */
    public List<AgentSkillCatalogEntry> approvedCatalog() {
        return trustService.approvedCatalog();
    }

    /**
     * Check if a snapshot's resource has current executable authorization.
     */
    public boolean canExecute(Long snapshotId, Long resourceId) {
        if (!snapshotRepository.isExecutableAuthorized(snapshotId, resourceId)) {
            return false;
        }
        // Check authorization is not revoked
        var auth = snapshotRepository.findExecAuthForResource(snapshotId, resourceId);
        return auth.map(a -> "AUTHORIZED".equals(a.decision())).orElse(false);
    }

    /** Clear per-conversation tracking. */
    public void clearConversation(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            activeSkillsByConversation.remove(conversationId.trim());
        }
    }

    /** Backward-compatible alias. */
    public void clearConversationActivations(String conversationId) {
        clearConversation(conversationId);
    }

    /**
     * Backward-compatible activation for tool use (no taskRunId).
     * Uses a synthetic taskRunId based on conversation.
     */
    public AgentSkillActivationResult activateSkill(String conversationId, String skillName) {
        String taskRunId = StringUtils.hasText(conversationId)
            ? "conv-" + conversationId : "direct-" + UUID.randomUUID().toString().substring(0, 8);
        ActivationResult result = activate(skillName, null, taskRunId, conversationId);
        if (result.isActivated()) {
            return new AgentSkillActivationResult(
                AgentSkillActivationOutcome.ACTIVATED,
                result.requestedName(),
                result.status(),
                result.content(),
                "skills/" + result.requestedName(),
                result.resourcePaths()
            );
        }
        return AgentSkillActivationResult.failure(
            AgentSkillActivationOutcome.SKILL_UNAVAILABLE,
            result.requestedName(),
            result.status()
        );
    }

    // ── Helpers ──

    private ActivationResult buildResult(String skillName,
                                          SkillSnapshotRepository.SkillSnapshot snapshot,
                                          SkillSnapshotRepository.SkillActivationRecord activation) {
        // Build content from immutable snapshot bytes
        String content = wrapSkillContent(skillName,
            snapshot.instructions(),
            "skills/" + skillName,
            snapshot,
            null);

        // Collect resource descriptors (without bytes)
        List<String> resourcePaths = new ArrayList<>();
        List<ResourceDescriptor> resources = new ArrayList<>();
        var snapshotResources = snapshotRepository.findResourcesBySnapshotId(snapshot.id());
        for (var res : snapshotResources) {
            if (!"SKILL_MD".equals(res.resourceType())) {
                resourcePaths.add(res.relativePath());
                resources.add(new ResourceDescriptor(
                    res.relativePath(), res.resourceType(),
                    res.contentLength(), res.contentDigest(),
                    res.id()));
            }
        }

        return new ActivationResult(
            skillName, snapshot.skillName(), snapshot.snapshotDigest(),
            snapshot.id(), activation.id(),
            content, resourcePaths, resources,
            "Activated from approved snapshot");
    }

    private String wrapSkillContent(String skillName, String instructions,
                                     String skillDirectory,
                                     SkillSnapshotRepository.SkillSnapshot snapshot,
                                     List<String> resourcePaths) {
        StringBuilder builder = new StringBuilder();
        builder.append("<skill_content name=\"").append(skillName).append("\">\n");
        builder.append(instructions != null ? instructions.strip() : "").append("\n\n");
        builder.append("Skill directory: ").append(skillDirectory).append("\n");
        builder.append("Relative paths in this skill are relative to the skill directory.\n");
        builder.append("<skill_resources>\n");
        var resources = snapshotRepository.findResourcesBySnapshotId(snapshot.id());
        for (var res : resources) {
            if (!"SKILL_MD".equals(res.resourceType())) {
                builder.append("  <file>").append(res.relativePath()).append("</file>\n");
            }
        }
        builder.append("</skill_resources>\n");
        builder.append("</skill_content>");
        return builder.toString();
    }

    private String buildActivationContext(String agentId, String taskRunId, String conversationId) {
        String agent = StringUtils.hasText(agentId) ? agentId : "system";
        String run = StringUtils.hasText(taskRunId) ? taskRunId : "direct";
        String conv = StringUtils.hasText(conversationId) ? conversationId : "";
        String base = agent + "|" + run;
        if (!conv.isEmpty()) base += "|" + conv;
        // Truncate to 512 chars max
        if (base.length() > 512) base = base.substring(0, 512);
        return base;
    }

    private void logActivation(String skillName, Long snapshotId, String context) {
        // Structured log; audit is in the database row.
    }

    // ── Public types ──

    public record ActivationResult(String requestedName, String activatedName,
                                    String snapshotDigest, Long snapshotId,
                                    Long activationId, String content,
                                    List<String> resourcePaths,
                                    List<ResourceDescriptor> resources,
                                    String status) {
        public ActivationResult(String requestedName, String activatedName,
                                 String snapshotDigest, Long snapshotId,
                                 Long activationId, String content,
                                 List<String> resourcePaths,
                                 List<ResourceDescriptor> resources,
                                 String status) {
            this.requestedName = requestedName;
            this.activatedName = activatedName;
            this.snapshotDigest = snapshotDigest;
            this.snapshotId = snapshotId;
            this.activationId = activationId;
            this.content = content;
            this.resourcePaths = resourcePaths != null ? List.copyOf(resourcePaths) : List.of();
            this.resources = resources != null ? List.copyOf(resources) : List.of();
            this.status = status;
        }

        public boolean isActivated() {
            return content != null && !content.isEmpty();
        }

        public static ActivationResult failure(String requestedName, String message) {
            return new ActivationResult(requestedName, null, null, null, null,
                null, List.of(), List.of(), message);
        }
    }

    public record ResourceDescriptor(String relativePath, String resourceType,
                                      int contentLength, String contentDigest,
                                      Long resourceId) { }
}
