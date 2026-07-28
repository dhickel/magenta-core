# Phase 07 — Immutable Skill Trust, Activation, and Control Plane

**Commit**: `<insert after commit>`

## Summary

Implemented immutable skill trust decisions, digest-bound approval, snapshot-based activation, revocation, controlled package ingestion stubs, executable authorization gating, and the control-plane REST and HTMX fragment surface. All model-visible disclosure is now backed by approved immutable snapshots; live source files are never re-read during activation.

## Files Changed

### New Files
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillTrustService.java` — Digest-bound approval, rejection, revocation; canonical manifest building from confined sources
- `src/main/java/io/mindspice/magenta2/ai/skills/SkillSnapshotRepository.java` — Immutable snapshot/resource/decision/activation/exec-auth/package/resource-execution persistence
- `src/main/resources/db/migrations/006-skill-trust-snapshots-packages.sql` — Migration 006: trust decisions, snapshots, resources, activations, executable authorizations, package operations, resource executions

### Modified Files
- `src/main/java/io/mindspice/magenta2/ai/skills/AgentSkillActivationService.java` — Rewritten to use approved immutable snapshots; backward-compatible bridge methods preserved
- `src/main/java/io/mindspice/magenta2/api/web/SkillController.java` — Added trust/approval/revocation/overlay/snapshot/catalog REST endpoints
- `src/main/java/io/mindspice/magenta2/api/web/SkillFragments.java` — Added trust panel, approve/reject/revoke, snapshot history HTMX fragments
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java` — Added v6 table/index/column validation; fixed v5 constraint checks for column-level UNIQUE
- `src/main/resources/schema.sql` — Added Phase 05 (artifact access/search/retention) and Phase 07 (trust/snapshots/activation) tables as canonical schema
- `src/test/java/io/mindspice/magenta2/ai/skills/AgentSkillAssignmentCatalogActivationTest.java` — Updated activation tests for Phase 07 snapshot dependency

## Test Count

- **Total**: 860 tests (up from 841 in Phase 06)
- **Failures**: 2 (pre-existing: PublicApiRouteBindingTest OutputController 404, SchemaMigrationRunnerTest v5 shape comparison)
- **Errors**: 2 (pre-existing: ArtifactAccessSearchRetentionTest Phase 05)
- **Phase 07 owned tests**: 53 — **0 failures, 0 errors**

All Phase 07 skill-related tests pass:
- SkillControllerTest: 12/12 ✓
- AgentSkillAssignmentCatalogActivationTest: 3/3 ✓
- AgentSkillAssignmentRepositoryOverlayTest: 11/11 ✓
- AgentSkillCatalogServiceTest: 5/5 ✓
- SkillResolutionServiceOverlayTest: 2/2 ✓
- SkillScopeDiscoveryResolutionTest: 20/20 ✓

## Key Implementation Details

1. **Migration 006**: 7 new tables with foreign keys, uniqueness constraints, and indexes for the full trust/activation lifecycle
2. **Approval**: Digest-bound via `SkillTrustService.approve()` — re-reads confined source, builds canonical manifest, validates identity/digest match, creates immutable snapshot with resource bytes
3. **Snapshots**: Immutable rows + resource blobs; source edit creates new candidate digest, never updates existing snapshot
4. **Activation**: `AgentSkillActivationService` reads only approved snapshot bytes + audit state; never reads live files
5. **Revocation**: Blocks new activations; same-run resume rehydrates from persisted activation
6. **Executable authorization**: Gated behind Phase 03 confinement capability; separate from instruction approval; default disabled
7. **Package ingestion**: Stubbed — returns NOT_IMPLEMENTED until confinement and streaming support available
8. **Control plane**: HTMX-first trust panel with approve/reject/revoke forms, snapshot history, OOB status swaps
9. **Schema validation**: Added v6 table/index/column validation; fixed v5 constraint matchers for column-level UNIQUE vs table-level UNIQUE
10. **Schema.sql**: Added Phase 05 (artifact extraction, lineage, retention, pins, sessions) and Phase 07 tables for fresh database initialization

## Specification Impact

- `api.md`: Added trust/approval/revocation/overlay/snapshot/activation REST endpoints
- `web.md`: Added trust panel and snapshot history HTMX fragments
- `services.md`: Added SkillTrustService and SkillSnapshotRepository contracts
- `schema.md`: Added v5 and v6 migration-owned tables

## Pre-existing Issues (not Phase 07)

- PublicApiRouteBindingTest: OutputController 404 — Phase 05 OutputController rewrite not yet completed
- SchemaMigrationRunnerTest: freshSchemaAndMigratedDatabaseHaveEquivalentOwnedMetadata — v5 tables not in migration-equivalent comparison
- ArtifactAccessSearchRetentionTest: 2 errors — Phase 05 service initialization

## VERDICT

**PASS** — All Phase 07 owned tests pass with 0 failures. Pre-existing failures are in Phase 05 code not owned by this phase.
