package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.mindspice.magenta2.ai.orchestration.workspaces.WorkspaceKind;
import org.springframework.stereotype.Service;

/** Deterministic root-precedence and exact-overlay resolution. */
@Service
public class SkillResolutionService {
    private final SkillScopeDiscoveryService discoveryService;
    private final AgentSkillAssignmentRepository assignmentRepository;

    public SkillResolutionService(SkillScopeDiscoveryService discoveryService,
                                  AgentSkillAssignmentRepository assignmentRepository) {
        this.discoveryService = discoveryService;
        this.assignmentRepository = assignmentRepository;
    }

    public SkillResolutionResult resolve(SkillResolutionContext context) {
        validateContext(context);
        SkillScopeDiscoveryService.ScopeDiscoveryResult discovery = discoveryService.discoverAll(context);
        PersistedDiscovery persisted = persistDiscovery(discovery);

        Map<String, List<CandidateVersion>> candidatesByKey = new LinkedHashMap<>();
        for (CandidateVersion candidate : persisted.candidates()) {
            candidatesByKey.computeIfAbsent(candidate.logicalKey(), ignored -> new ArrayList<>()).add(candidate);
        }

        Map<String, ResolvedSkill> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<CandidateVersion>> entry : candidatesByKey.entrySet()) {
            CandidateVersion occupant = entry.getValue().getFirst();
            List<CandidateVersion> shadowed = entry.getValue().subList(1, entry.getValue().size());
            SkillScopeDiscoveryService.CandidateObservation observation =
                    persisted.observations().get(occupant.identityKey());
            resolved.put(entry.getKey(), resolveOccupied(entry.getKey(), occupant, observation, context)
                    .withShadowedCandidates(shadowed));
        }
        for (String overlayOnlyKey : assignmentRepository.findApplicableOverlayKeys(context)) {
            resolved.putIfAbsent(overlayOnlyKey, ResolvedSkill.noCandidate(overlayOnlyKey));
        }
        return new SkillResolutionResult(Map.copyOf(resolved), discovery.scanId(), discovery.scanTime());
    }

    public ResolvedSkill resolveKey(String logicalKey, SkillResolutionContext context) {
        Objects.requireNonNull(logicalKey, "logicalKey");
        return resolve(context).resolvedKeys().getOrDefault(logicalKey, ResolvedSkill.noCandidate(logicalKey));
    }

    private PersistedDiscovery persistDiscovery(SkillScopeDiscoveryService.ScopeDiscoveryResult discovery) {
        List<CandidateVersion> persisted = new ArrayList<>();
        Map<String, SkillScopeDiscoveryService.CandidateObservation> observations = new LinkedHashMap<>();
        for (CandidateVersion candidate : discovery.allByPrecedence()) {
            CandidateVersion stored = assignmentRepository.insertCandidateVersion(candidate);
            SkillScopeDiscoveryService.CandidateObservation observation =
                    discovery.observationsByIdentityKey().get(candidate.identityKey());
            if (observation != null) {
                assignmentRepository.insertObservation(stored.id(), observation.available(),
                        observation.diagnostics(), observation.observedAt(), observation.scanId());
                observations.put(stored.identityKey(), new SkillScopeDiscoveryService.CandidateObservation(
                        stored, observation.available(), observation.diagnostics(),
                        observation.observedAt(), observation.scanId()));
            }
            persisted.add(stored);
        }
        return new PersistedDiscovery(List.copyOf(persisted), Map.copyOf(observations));
    }

    private ResolvedSkill resolveOccupied(String logicalKey, CandidateVersion occupant,
                                           SkillScopeDiscoveryService.CandidateObservation observation,
                                           SkillResolutionContext context) {
        if (observation == null || !observation.available()) {
            return ResolvedSkill.unavailable(logicalKey, occupant);
        }
        if (observation.malformed()) {
            return ResolvedSkill.malformed(logicalKey, occupant);
        }
        List<ResolvedSkill.ExactAssignmentOverlay> overlays =
                assignmentRepository.findMostSpecificOverlays(logicalKey, context);
        if (overlays.size() > 1) {
            return ResolvedSkill.conflict(logicalKey, occupant, overlays);
        }
        return resolveWithOverlay(logicalKey, occupant, true, false,
                overlays.isEmpty() ? null : overlays.getFirst());
    }

    public ResolvedSkill resolveWithOverlay(String logicalKey, CandidateVersion occupant,
                                             boolean available, boolean malformed,
                                             ResolvedSkill.ExactAssignmentOverlay overlay) {
        if (!available) return ResolvedSkill.unavailable(logicalKey, occupant);
        if (malformed) return ResolvedSkill.malformed(logicalKey, occupant);
        if (overlay == null) return ResolvedSkill.pending(logicalKey, occupant);
        if (!matchesDigest(occupant, overlay)) return ResolvedSkill.stale(logicalKey, occupant, overlay);
        return overlay.enabled() ? ResolvedSkill.resolved(logicalKey, occupant, overlay)
                : ResolvedSkill.disabled(logicalKey, occupant, overlay);
    }

    /** CHAT_SESSION > (WORKFLOW = TASK) > JOB > PROJECT > AGENT. */
    public int specificityScore(AgentSkillTargetType type) {
        return specificity(type);
    }

    public static int compareSpecificity(AgentSkillTargetType first, AgentSkillTargetType second) {
        return Integer.compare(specificity(first), specificity(second));
    }

    private static int specificity(AgentSkillTargetType type) {
        return switch (type) {
            case CHAT_SESSION -> 0;
            case WORKFLOW, TASK -> 1;
            case JOB -> 2;
            case PROJECT -> 3;
            case AGENT -> 4;
        };
    }

    private boolean matchesDigest(CandidateVersion candidate,
                                  ResolvedSkill.ExactAssignmentOverlay overlay) {
        if (candidate.id() == null || overlay.candidateVersionId() == null) return false;
        CandidateVersion assigned = assignmentRepository.findCandidateById(overlay.candidateVersionId())
                .orElse(null);
        return assigned != null
                && candidate.id().equals(assigned.id())
                && candidate.logicalKey().equals(assigned.logicalKey())
                && candidate.scopeType() == assigned.scopeType()
                && candidate.authoritativeRootId().equals(assigned.authoritativeRootId())
                && candidate.provenance() == assigned.provenance()
                && candidate.formatVersion() == assigned.formatVersion()
                && candidate.contentDigest().equals(assigned.contentDigest());
    }

    private void validateContext(SkillResolutionContext context) {
        Objects.requireNonNull(context, "context");
        if (context.effectiveWorkspace() != null && context.effectiveWorkspace().kind() == WorkspaceKind.CHAT) {
            throw new IllegalArgumentException("CHAT workspaces are not valid for skill resolution");
        }
    }

    private record PersistedDiscovery(List<CandidateVersion> candidates,
                                      Map<String, SkillScopeDiscoveryService.CandidateObservation> observations) { }

    public record SkillResolutionResult(Map<String, ResolvedSkill> resolvedKeys,
                                        String scanId, Instant scanTime) {
        public List<ResolvedSkill> resolvedSkills() { return List.copyOf(resolvedKeys.values()); }
        public List<ResolvedSkill> loadableSkills() {
            return resolvedKeys.values().stream().filter(skill -> skill.state().isLoadable()).toList();
        }
    }
}
