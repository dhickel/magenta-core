package io.mindspice.magenta2.ai.skills;

/** Typed resolution state for a single logical-key lookup. */
public enum SkillResolutionState {
    /** Occupying candidate is available, not stale, and has a matching enabled overlay. */
    RESOLVED,

    /** Occupying candidate exists but is explicitly disabled by overlay. */
    DISABLED,

    /** Occupying candidate exists but its digest has changed since assignment. */
    STALE,

    /** Occupying candidate exists but is currently unavailable (not observed in latest scan). */
    UNAVAILABLE,

    /** Occupying candidate exists but is malformed (parse/digest/structural error). */
    MALFORMED,

    /** Occupying candidate exists, is pending (not approved/trusted). */
    PENDING,

    /** Occupying candidate exists but has an equal-specificity conflict in overlays. */
    CONFLICT,

    /** No candidate occupies this logical key. */
    NO_CANDIDATE,

    /** A higher-precedence scope occupies this key (shadowed candidate). */
    SHADOWED;

    public boolean isOccupied() {
        return this != NO_CANDIDATE && this != SHADOWED;
    }

    public boolean isLoadable() {
        return this == RESOLVED || this == PENDING;
    }
}
