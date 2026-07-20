# phase-06-scoped-skill-resolution

## Date

2026-07-20



## Git Commit

0dc7f866326c358a246b1f7585b15955f9f14103



## Change Summary

Implemented Phase 06 — Normalized Scoped Skill Discovery and Exact Assignment. Added migration 004, immutable candidate versions, deterministic precedence-based resolution, exact-version assignment overlays, and legacy conversion diagnostics. Global package skills and flat workspace `.skills/<name>.md` skills now share one deterministic control-plane catalog.

**New types:** `SkillScope`, `SkillProvenance`, `SkillResolutionState`, `CandidateVersion`, `SkillResolutionContext`, `ResolvedSkill` (with `ExactAssignmentOverlay`).

**New services:** `SkillScopeDiscoveryService` (read-only scoped discovery), `SkillResolutionService` (deterministic resolution with root precedence and assignment specificity).

**Updated:** `AgentSkillTargetType` extended with `PROJECT`, `JOB`, `TASK`, `WORKFLOW`, `CHAT_SESSION`. `AgentSkillCatalogService` now exposes normal scoped control-plane resolution. `AgentSkillAssignmentRepository` validates authority/target/actor/operation identifiers and bounded relative diagnostics while persisting observations, immutable candidates, overlays, and legacy diagnostics. Resolution now applies persisted overlays, compares exact candidate identity and digest, and returns explicit conflict and shadow data.

**Migration 004:** Added `skill_candidate_versions`, `skill_candidate_observations`, `skill_exact_assignments`, and `skill_legacy_conversion_diagnostics` tables with uniqueness and foreign-key constraints.

## Files

### Created
- `src/main/resources/db/migrations/004-scoped-skill-resolution.sql`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillScope.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillProvenance.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionState.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/CandidateVersion.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionContext.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/ResolvedSkill.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillScopeDiscoveryService.java`
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillResolutionService.java`
- `src/test/java/io/mindspice/magenta2/ai/skills/SkillScopeDiscoveryResolutionTest.java`
- `src/test/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentRepositoryOverlayTest.java`

### Modified
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillTargetType.java` — added PROJECT, JOB, TASK, WORKFLOW, CHAT_SESSION
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillCatalogService.java` — added loadableCatalogEntries, hasCandidates
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentRepository.java` — added overlay/observation/candidate/legacy persistence methods
- `src/main/resources/schema.sql` — added migration 004 equivalent tables
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java` — fixed pre-existing effectively-final lambda variable

## Behavioral Impact

- Skill discovery is now read-only and scoped: workspace → bound-agent → project (conditional) → global. Discovery never creates `.skills` roots.
- Parser diagnostics propagate from discovery; error diagnostics make the occupying candidate `MALFORMED` rather than `PENDING`.
- Resolution is deterministic: higher-precedence occupying candidates suppress lower candidates and expose those shadowed candidates explicitly. Exact overlays are applied in normal resolution, task/workflow ties become `CONFLICT`, and real persisted identity/digest comparison makes changed content `STALE`.
- Discovery never scans chat workspaces, task-run directories, service-account homes, or guessed project dirs.
- Workspace roots are obtained from trusted workspace services and `WorkspacePathLayout`, never from `Path`.
- Candidate-derived descriptions, hints, bodies, resource metadata, and conflicts never enter model-visible output.
- Legacy data is preserved non-destructively; observations update availability without deleting history.

## Specification Impact

Updated `AgentSkillTargetType` enum with extended assignment target types. Added migration 004 to `schema.sql`. Existing specification entries remain compatible; controller tests using `AgentSkillTargetType` enum may need updates (Phase 07 cutover).

## Risks

- The control-plane candidate pipeline remains intentionally separate from model-visible activation until Phase 07 trust/snapshot work.
- Project discovery remains unavailable unless a trusted project-root authority is explicitly supplied; no project path is guessed.

## Follow-up Items

- Phase 07: trust approval, revocation, immutable approved snapshots, activation cutover, executable authorization, and Skills UI.
- Phase 07: cut model-visible disclosure from Phase 06 control-plane resolution after snapshot approval.
- Validation now passes: 841 tests, 0 failures/errors, plus bounded clean startup.
