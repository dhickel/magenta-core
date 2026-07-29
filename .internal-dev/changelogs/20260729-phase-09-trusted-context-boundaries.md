# Changelog — 2026-07-29 Phase 09 Trusted Context Boundaries

## Date
2026-07-29

## Change Summary
Added 77 direct tests (38 TrustedContextPolicyAssembler + 39 WorkspaceMdResolver) proving terminal trusted-context and guidance-confinement boundaries. Fixed three classes of production defects: truncation math (hardcoded -40 → dynamic suffix), intermediate symlink detection (new `rejectLinkedComponents` path walk), and authority-boundary escape (lexical `startsWith` guard before any filesystem I/O).

## Files
### Production fixes (3 files, 100 insertions, 24 deletions)
- `src/main/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssembler.java` — Added `hasConsistentAvailableContext` consistency gate; replaced hardcoded `-40` truncation with `POLICY_MAX_CHARS - suffix.length()` plus zero-floor guard.
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolver.java` — Added `rejectLinkedComponents` component-level symlink walk; added `authorityBoundary` parameter with lexical `startsWith` escape guard; differentiated `Files.notExists` (definite) from indeterminate I/O on workspace MD existence check; added dual byte-limit check (`Files.size` then `readAllBytes`); extracted `exceedsMaximumSize` helper; widened realpath validation to check `realSearchRoot` and `realMd` against `realAuthority`.
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceDirectoryServiceTestSupport.java` — Added shared `materializationLocation` factory for test fixtures.

### New tests (2 files, 1422 lines)
- `src/test/java/io/mindspice/magenta2/ai/chat/service/turn/TrustedContextPolicyAssemblerTest.java` (38 tests)
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceMdResolverTest.java` (39 tests)

## Behavioral Impact
- `TrustedContextPolicyAssembler` now validates binding/location consistency before emitting policy text; inconsistent materialization bindings with mismatched key, generation, agent-id, or location agent descriptor produce terminal STALE instead of partial policy.
- `WorkspaceMdResolver` now validates every path component from the authority boundary to the search root for symlinks; rejects authority-boundary escapes before any I/O; treats indeterminate `Files.exists`/`Files.notExists` outcomes as terminal failures rather than silent absent; applies byte-limit check both pre- and post-read for TOCTOU hardening.
- Truncation suffix length is computed from the actual suffix string, removing the silent assumption that it's exactly 40 characters.

## Specification Impact
None. These are boundary-hardening fixes within the existing assembler and resolver contracts. The invariants defined in the phase document are now directly tested.

## Risks
Low. All changes are confined to the two target classes and their tests. The `rejectLinkedComponents` path walk uses only standard `Path` operations, and the authority boundary is always the trusted descriptor root. All 129 focused-suite tests pass (38 + 39 + 52 related).

## Follow-up Items
- Phase 10 integration closeout will use this phase's evidence.
- The `rejectLinkedComponents` walk shares no state with other workspace guards; future hardening may want a shared `ConfinedPathValidator` if more path-validation callers emerge.
