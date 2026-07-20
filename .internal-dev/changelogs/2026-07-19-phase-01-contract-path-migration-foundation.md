# Phase 01 — Contract, Path, and Migration Foundation

## Date

2026-07-20

## Git Commit

Base commit at implementation/review start: `69125eeae872cc0d1439d2908eaaf148bb935539`. The finalized phase commit includes this changelog and validator repairs.

## Change Summary

Implemented and independently repaired the Phase 01 task-run contract, trusted child-path foundation, and sole ordered migration owner.

- Locked the native identity matrix: `plan_runs.id`, `workflow_runs.id`, and `job_runs.id` are immutable one-to-one native run identities reused as canonical task-run ids. Assignment, workflow-child, and job-work-item identities remain separate links.
- Added `TaskSourceKind`, `TaskIdentity`, canonical `TaskPathKey`, trusted `TaskRunLocation`/`RunOutputLocation`, and non-authorizing `TaskRunWriteCapability` facts.
- Locked the exact v1 payload: `0x01`, source-kind byte, two-byte big-endian UTF-8 id length, and exact UTF-8 id bytes.
- Extended `WorkspacePathLayout` and `WorkspaceDirectoryService` for workspace-local `.skills/`, canonical agent/user task-run children, lazy output/staging resolution, key recomputation, confinement, relocation, and link rejection. `CHAT` remains rejected.
- Added `SchemaMigrationRunner` and repository initialization dependency. The runner initializes empty databases from `schema.sql`; Spring's generic SQL initializer is disabled so existing databases cannot bypass migration ownership. Migration application uses one SQLite `BEGIN IMMEDIATE` transaction, exact-resource SHA-256 bookkeeping, strict discovery/order validation, fail-closed malformed-state checks, and atomic rollback.
- Added migration 001 and an equivalent fresh-schema end state with five constrained companion tables.
- Updated architecture/services/decisions, package guides, technical docs, and workspace knowledge to supersede the old flat task-output rule without claiming later-phase cutover.

## Files

- `src/main/java/io/mindspice/magenta2/core/persistence/`
- `src/main/resources/db/migrations/001-task-run-foundation.sql`
- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/`
- Focused migration, identity, layout, and directory-service tests
- `.internal-dev/specifications/{architecture,services,decisions}.md`
- Workspace/core package guides and technical workspace/configuration/orchestration docs
- `.internal-dev/knowledge/{2026-07-19-filesystem-first-workspaces,root-relative-workspace-storage}.md`
- `.internal-dev/reviews/2026-07-20-phase-01-contract-path-migration-foundation-validation.md`

## Behavioral Impact

Phase 01 can deterministically migrate existing SQLite databases and represent/provision canonical task-run directories under `AGENT` or `USER`. Reads and startup do not create workspace, `tasks/`, `.skills/`, run, output, or staging directories. Run provisioning creates only the run hierarchy. Existing workspace-root `outputs/` remains legacy/non-task behavior; execution and publication are not cut over.

## Specification Impact

Updated `ARCH-20260720-01`, `ARCH-20260720-02`, added the canonical identity matrix, added `SVC-20260720-01` and `DECISION-20260720-01`, and aligned the earlier active workspace contract with the signed superseding handoff.

## Risks

- Writer capability authorization and persisted fence verification remain Phase 02 by contract.
- Reserved-tree mutation enforcement and subprocess confinement remain Phase 03.
- Run-local publication remains Phase 04.
- Older domain tables still use repository-local compatibility DDL until a later explicit migration transfers ownership.

## Follow-up Items

Proceed to Phase 02 only from this committed, validator-PASS foundation. Migration 001 is immutable after commit; corrections require migration 002.
