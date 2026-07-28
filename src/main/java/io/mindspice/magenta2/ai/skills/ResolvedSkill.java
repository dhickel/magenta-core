package io.mindspice.magenta2.ai.skills;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Control-plane result for one logical key, including explicit shadow/conflict outcomes. */
public record ResolvedSkill(
    String logicalKey,
    SkillResolutionState state,
    CandidateVersion occupiedCandidate,
    ExactAssignmentOverlay overlay,
    List<CandidateVersion> shadowedCandidates,
    List<ExactAssignmentOverlay> conflictingOverlays
) {
    public ResolvedSkill {
        Objects.requireNonNull(logicalKey, "logicalKey");
        Objects.requireNonNull(state, "state");
        shadowedCandidates = shadowedCandidates == null ? List.of() : List.copyOf(shadowedCandidates);
        conflictingOverlays = conflictingOverlays == null ? List.of() : List.copyOf(conflictingOverlays);
        if (state.isOccupied() && occupiedCandidate == null) {
            throw new IllegalArgumentException("occupied state requires a candidate");
        }
        if (state == SkillResolutionState.CONFLICT && conflictingOverlays.isEmpty()) {
            throw new IllegalArgumentException("conflict state requires conflicting overlays");
        }
    }

    public ResolvedSkill withShadowedCandidates(List<CandidateVersion> shadowed) {
        return new ResolvedSkill(logicalKey, state, occupiedCandidate, overlay, shadowed, conflictingOverlays);
    }

    public static ResolvedSkill noCandidate(String key) {
        return result(key, SkillResolutionState.NO_CANDIDATE, null, null);
    }
    public static ResolvedSkill shadowed(String key, CandidateVersion by) {
        return new ResolvedSkill(key, SkillResolutionState.SHADOWED, null, null, List.of(by), List.of());
    }
    public static ResolvedSkill resolved(String key, CandidateVersion candidate, ExactAssignmentOverlay overlay) {
        return result(key, SkillResolutionState.RESOLVED, candidate, overlay);
    }
    public static ResolvedSkill disabled(String key, CandidateVersion candidate, ExactAssignmentOverlay overlay) {
        return result(key, SkillResolutionState.DISABLED, candidate, overlay);
    }
    public static ResolvedSkill stale(String key, CandidateVersion candidate, ExactAssignmentOverlay overlay) {
        return result(key, SkillResolutionState.STALE, candidate, overlay);
    }
    public static ResolvedSkill unavailable(String key, CandidateVersion candidate) {
        return result(key, SkillResolutionState.UNAVAILABLE, candidate, null);
    }
    public static ResolvedSkill malformed(String key, CandidateVersion candidate) {
        return result(key, SkillResolutionState.MALFORMED, candidate, null);
    }
    public static ResolvedSkill pending(String key, CandidateVersion candidate) {
        return result(key, SkillResolutionState.PENDING, candidate, null);
    }
    public static ResolvedSkill conflict(String key, CandidateVersion candidate,
                                         List<ExactAssignmentOverlay> conflicts) {
        return new ResolvedSkill(key, SkillResolutionState.CONFLICT, candidate, null, List.of(), conflicts);
    }

    private static ResolvedSkill result(String key, SkillResolutionState state,
                                        CandidateVersion candidate, ExactAssignmentOverlay overlay) {
        return new ResolvedSkill(key, state, candidate, overlay, List.of(), List.of());
    }

    /** Exact immutable candidate-version assignment. */
    public record ExactAssignmentOverlay(Long id, Long candidateVersionId, String logicalKey,
                                         AgentSkillTargetType targetType, String targetKey,
                                         boolean enabled, String operationKey, String actor,
                                         Instant createdAt, Instant updatedAt) {
        public ExactAssignmentOverlay {
            Objects.requireNonNull(logicalKey, "logicalKey");
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(targetKey, "targetKey");
        }
    }
}
