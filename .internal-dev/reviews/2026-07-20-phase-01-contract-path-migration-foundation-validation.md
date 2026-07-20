# Phase 01 Contract, Path, and Migration Foundation Validation

## Scope

Independent review-and-repair of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-01-contract-path-migration-foundation.md`, its concepts/orchestration context, and the Phase 01 repository implementation.

## Findings

The reported implementation did not initially satisfy the phase gate. Material defects included: a migration concurrency test that was sequential; deferred SQLite transactions rather than cross-process startup serialization; no structural detection for malformed partial migration-owned tables; an idempotency primary key that allowed one operation key with conflicting digests; missing schema checks and explicit identity columns; inconsistent output root-relative paths; key encoding tests that recomputed rather than locking literal vectors; no version byte in the hashed payload; no independent `TaskIdentity`; no key recomputation at the directory boundary; eager output creation during run provision; `.skills/` resolved at the data root rather than the selected workspace; public API growth; and stale/contradictory living contracts.

All identified in-scope defects were repaired. Ordered migration application now uses one `BEGIN IMMEDIATE` transaction with exact-byte SHA-256 bookkeeping, strict discovery/bookkeeping checks, owned-schema validation, repository initialization dependency, interruption rollback, and independent-connection concurrency coverage. Task identity/path/location types and directory operations now enforce the signed child-resource contract and lazy reads/provisioning. Living specifications, package guidance, technical documentation, and reusable workspace knowledge were aligned.

## Risk Assessment

No Phase 01 blocker remains. `TaskRunWriteCapability` intentionally remains non-authorizing; lifecycle issuance and fence verification are Phase 02. Execution and publication are intentionally not cut over. Older non-companion repository tables retain local compatibility DDL until explicitly transferred by later ordered migrations.

## Recommendations

- Treat `001-task-run-foundation.sql` as immutable after this phase commit.
- In Phase 02, persist and verify exact capability facts rather than generating or trusting them in the value type.
- Do not create run-local outputs or staging until publication ownership and reserved-tree enforcement are active.

## Follow-ups

Validation evidence:
- Focused migration/path/directory tests: 22 passed.
- Full Maven suite: 659 passed, 0 failures/errors/skips.
- Fresh bounded startup: application started; migration 001 recorded with 64-character checksum; five task-run tables present.
- Baseline-schema upgrade bounded startup: application started; migration 001 recorded; five task-run tables present.
- Fresh and upgraded roots contained no `tasks/` or `.skills/` directory after startup/read validation.
- Final static review confirmed `WorkspaceKind` remains `AGENT`, `CHAT`, `USER`; trusted child constructors are non-public; persisted task locations are canonical data-root-relative values; and no production caller derives workspace identity from `Path`.
