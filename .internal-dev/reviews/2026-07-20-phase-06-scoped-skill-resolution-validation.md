# Phase 06 Scoped Skill Resolution Validation

## Scope

Independent validation of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-06-scoped-skill-resolution.md` against the current repository, including the seven checks requested by the user.

## Findings

1. **Full suite fails.** `mvn test` ran 836 tests with 0 assertion failures and 27 errors. Spring contexts fail because `SchemaMigrationRunner` rejects the fresh schema: `task_run_idempotent_ops is missing scoped digest or ownership constraints`. This contradicts the supplied `823 tests, 0 failures` evidence and prevents the required startup-backed tests from running.
2. **Discovery is read-only for absent `.skills` roots.** `SkillScopeDiscoveryService` calls `WorkspaceDirectoryService.resolveSkillsRoot`, which resolves and verifies without creating directories; it only scans an existing directory.
3. **Forbidden-root scanning is not present in the discovery service.** Effective CHAT contexts are rejected, CHAT descriptors return no workspace candidates, and discovery derives roots only from trusted descriptors or configured global root. It does not derive task-run, process-working-directory, service-home, or environment-home roots.
4. **Malformed occupancy is broken.** `SkillScopeDiscoveryService` parses sources but discards parser diagnostics and records every returned candidate with an empty diagnostic list. `SkillResolutionService` therefore resolves malformed candidates as `PENDING`, not `MALFORMED`, even though they still occupy the key.
5. **Digest-change staleness is broken.** Exact overlays persist a `candidate_version_id`, but `SkillResolutionService` is not wired to `AgentSkillAssignmentRepository`; `matchesDigest` is an explicit placeholder returning `true`. Resolution also rescans repeatedly and does not use persisted observations. A changed digest cannot make an old assignment stale through the main resolution path.
6. **Absolute-path persistence is not enforced.** The migration has no path columns, but `authoritative_root_id`, target keys, actor, operation key, and diagnostics are accepted without bounded/path-form validation. `CandidateVersion` accepts arbitrary authoritative root strings, including absolute paths, and diagnostics serialization can persist arbitrary source paths.
7. **Candidate disclosure boundary is structurally separated but inadequately proven.** `CandidateVersion` carries no description/body/resource metadata and the existing model-visible runtime catalog remains on legacy `AgentSkill` data. However, there are no negative tests showing newly discovered candidate descriptions/diagnostics/overlays cannot enter prompts, and `loadableCatalogEntries()` explicitly exports legacy descriptions without demonstrating the required candidate boundary.
8. **Additional phase-contract gaps:** `keysWithOverlaysOnly` is a hard-coded empty list; shadow/conflict data is not surfaced; equal-specificity conflict handling is impossible under the single-row uniqueness rule; failed/incomplete scan availability semantics are not implemented end-to-end; package digest code has no required bounds/collision/Unicode/link/replacement validation; project-root authority is represented as a workspace descriptor despite the current architecture declaring project association metadata-only; focused tests cover mostly value objects and happy-path persistence rather than discovery/confinement/disclosure adversarial cases.

## Risk Assessment

Critical. The phase cannot open its dependency barrier: the full suite is red, required resolution semantics contain explicit placeholders, malformed higher-precedence candidates are misclassified, stale exact assignments can resolve as current, and security/confinement requirements lack implementation and adversarial proof.

## Recommendations

- Complete discovery-result modeling so malformed and bounded diagnostics survive scanning and occupy keys without becoming loadable.
- Wire resolution to persisted candidate observations and exact-version overlays; compare exact candidate identity/id and surface stale/unavailable/conflict states without fallback.
- Add strict bounded validation for persisted root identifiers, diagnostics, target identifiers, operation keys, and actors; reject absolute/path-like authority identifiers.
- Implement canonical package-tree validation and bounds from the phase contract.
- Add end-to-end tests for absent-root non-creation, CHAT/forbidden-root rejection, malformed shadowing, digest-change staleness, no absolute-path persistence, and negative prompt disclosure.
- Repair fresh-schema migration validation and rerun the complete suite plus bounded startup.

## Follow-ups

Initial verdict: **BLOCKED**.

## Revalidation — 2026-07-20

Discovery now propagates bounded parser diagnostics and marks error-bearing occupants `MALFORMED`. Normal resolution persists observations/candidates, loads exact overlays, compares persisted candidate identity and digest, reports stale bindings, exposes shadow candidates and equal-specificity conflicts, and includes overlay-only keys. Candidate/root/target/operation/actor/diagnostic values are validated against bounded known forms. Package hashing rejects escaping, linked, non-regular, and canonical-collision entries. Migration 004 is included in distinct version-4 foundation checks. `mvn test`: 841 tests, 0 failures, 0 errors. Bounded clean Spring startup passed. Revalidation verdict: **PASS**.
