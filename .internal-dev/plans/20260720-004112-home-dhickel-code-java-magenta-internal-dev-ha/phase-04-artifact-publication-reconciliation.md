# Phase 04 — Run-Isolated Artifact Publication and Reconciliation

## Context

Current publication accepts arbitrary output paths, can expose final bytes before durable publication state, and lacks persisted operation idempotency, integrity facts, and deterministic recovery. This phase cuts active publication to trusted run-local or chat-local destinations while preserving legacy files and rows in place. It opens no new broad artifact access surface.

## Goal

run-local create-only publication with idempotency, integrity, and recovery

## In Scope

**Write Targets**: src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactService.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/OutputArtifactRepository.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/ArtifactReconciliationService.java, src/main/resources/db/migrations/004-artifact-publication.sql

- Add publication origin, operation, state, integrity, and reconciliation persistence through the sole ordered migration owner.
- Cut task, workflow, job, runner, and chat callers to typed publication operations; supporting caller, test, fresh-schema-equivalence, specification, documentation, and closeout edits are permitted only where required for this cutover.
- Preserve legacy flat rows and bytes in place with honest unavailable facts.
- Add state-machine, fault-injection, attribution, path, collision, relocation, integrity, and reconciliation coverage.

## Out of Scope

- Authorization-backed artifact browsing, full-text search, snippets, preview rendering, download endpoints, lineage presentation, retention policy, and artifact UI.
- Moving, rewriting, hashing, or inferring task ownership for legacy bytes.
- Binary extraction or content indexing.
- A new workspace kind, task-run workspace descriptor, path-derived identity, or generic filesystem identity.

## Dependencies

phase-03-reserved-tree-confinement.md

## Constraints

- Task publication accepts a trusted `RunOutputLocation` derived from `TaskRunLocation` and a live `TaskRunWriteCapability`; it never accepts an arbitrary destination path. Chat publication is a separate non-task operation confined to `workspace/chat/<id>/outputs/`.
- Persist locations only as `(workspaceKind, workspaceKey, rootRelativePath)`. Resolve them through workspace authorities at the mutation boundary; never persist absolute paths or reconstruct workspace identity from `Path`.
- The assignment lease and writer fence are distinct. Every stage write, promotion, state transition, cleanup, and repair that can mutate a run publication verifies the expected writer owner and monotonic fence immediately before mutation.
- SQLite and filesystem state are separate consistency domains. Every committed boundary is restart-safe, idempotently reconcilable, or recorded as safely unresolved.
- Reusing an operation key with the same request digest returns or resumes the one recorded artifact and reservation. Reusing it with different input is a conflict.
- A reservation exclusively owns one final root-relative name. Final placement is create-only in every capability branch and never uses `REPLACE_EXISTING`.
- Published bytes are immutable through application mutation APIs. Only `PUBLISHED` records with verified current integrity may be opened by existing internal consumers; `INTEGRITY_FAILED`, missing, ambiguous, or nonterminal records expose no bytes.
- Reconciliation never adopts unexplained bytes, guesses producers, reallocates a retry to a second artifact, or deletes a path whose operation ownership and current file identity cannot be proved.
- Existing workspace-root output rows remain legacy/non-task, retain their paths and bytes, and receive null task attribution plus unavailable digest/media facts.

## Implementation Steps

1. **Establish migration-owned persistence.** Add immutable migration 004 through `SchemaMigrationRunner`; update the repository’s authoritative fresh-database representation as required by the established migration contract and remove no history. Add publication/reconciliation tables or columns, constraints, indexes, and migration checksum coverage. Preserve existing rows as legacy with null task attribution and unavailable digest, size, and verified-media facts. Do not add repository-local DDL or move, hash, or infer producers for legacy files.

2. **Persist the complete publication record.** Store artifact ID; publication origin (`TASK_RUN`, `CHAT`, `LEGACY`); effective workspace kind/key; task-run and native producer links when applicable; root-relative final and staging paths; operation key; versioned request digest; state; expected and verified SHA-256; verified size and media type; writer owner/fence for guarded task mutations; failure/integrity detail; reconciliation status; and transition timestamps. Enforce scoped operation-key uniqueness and unique reserved final path. Keep native run records as status truth.

3. **Define deterministic idempotency.** Build the request digest with a documented, versioned canonical encoding over publication scope, owner/run identity, normalized logical name and extension/type intent, source/value content digest, and media intent. Exclude absolute paths and mutable display metadata. For path sources, obtain the confined source digest, reserve against that digest, then hash while staging and reject mutation if the staged digest differs. Same key/same digest resumes or returns the recorded artifact in its current safe state; same key/different digest returns a conflict without filesystem mutation.

4. **Cut over typed publication entry points.** Replace arbitrary destination APIs with separate typed task-run and chat operations. Update every active task, plan, workflow child, job work item, runner, and chat caller. Task-capable `AGENT` and `USER` executions publish only below their own canonical run `outputs/`; `CHAT` is rejected for task selection and continues non-task publication below the chat root. Remove or fail closed obsolete arbitrary-path entry points after all callers move.

5. **Reserve before writing.** Validate publication input, confined source, run linkage, writer owner/fence, and trusted output location. In one database transaction, reuse an idempotent operation or reserve artifact identity, deterministic final name, unique staging name, and initial `RESERVED` state. Write staging bytes only through the confined publication primitive, with no-follow/link/race protections from Phase 03; hash and count the bytes during the write, flush and sync where supported, compare against the request content digest, then persist `STAGED` facts. Recheck the live writer capability before each filesystem or guarded state mutation.

6. **Promote without replacement.** Prefer a same-filesystem atomic move that requests create-only semantics and fails if the final entry exists. Where that capability is unavailable, use a probed same-filesystem create-new link or copy strategy that opens the final entry with exclusive creation, never follows a link, and verifies final bytes and file identity before publication commit. Never use `REPLACE_EXISTING`, truncate an existing entry, or assume a provider’s move behavior without a capability test. A failed fallback leaves the reservation and staging facts recoverable; if partial-final ownership or identity cannot be proved, mark reconciliation unresolved instead of deleting or overwriting it.

7. **Allocate collision suffixes transactionally.** Normalize and validate the requested logical name once. Reserve the unsuffixed name or the lowest deterministic suffix through the unique final-path constraint, retrying only uniqueness conflicts inside a bounded allocator. Concurrent distinct operations receive distinct names; a retry of one operation reuses its persisted name and artifact ID. Existing files of any type, including links, count as collisions and are never replaced.

8. **Commit and enforce integrity.** After final placement, reopen the final entry with no-follow confined handling, verify its identity, digest, size, and verified media type, and transition to `PUBLISHED`. A crash after placement but before commit remains recoverable from the recorded reservation. Block application-managed edit, upload, copy, move, and delete routes from published paths. On missing bytes, identity ambiguity, or digest/size mismatch, atomically record `INTEGRITY_FAILED` or a safe unresolved reconciliation state and suppress all byte opening pending later reconciliation or authorized disposition.

9. **Reconcile divergence deterministically.** Process bounded persisted candidates by state and stable key. Resume `RESERVED`/`STAGED` operations from their recorded paths; convert a verified final plus staged record to `PUBLISHED`; classify a published row with missing or changed bytes as integrity failed; clean only provably operation-owned temporary state; record final files without rows as unexplained and leave them untouched; and repair stale publication/index compatibility markers without manufacturing content facts. Repeated reconciliation produces the same result. Do not allocate a new artifact or final name during repair.

10. **Constrain cleanup and interrupted deletion recovery.** Remove abandoned staging only when the operation is terminal or cleanup-eligible, no live writer claim exists, the recorded staging path remains confined, and current file identity proves it is the operation-owned staging entry. Preserve active attempts and claims. For a previously initiated deletion state or stale deletion marker, finish only a recorded, authorized, identity-verified transition; otherwise retain the bytes and report unresolved state. This supplies reconciliation facts for Phase 05 without introducing retention policy here.

11. **Verify producer and origin semantics.** Assert that plan, workflow, job, assignment, and child-run publication uses the canonical task-run/native linkage inspected in Phases 01–02. Each child publishes to its own run output location unless the persisted one-to-one mapping proves it owns the parent location. Chat records remain non-task; legacy records remain unattributed. Workspace-root `outputs/` receives no new task artifact.

12. **Exercise every committed boundary and complete closeout.** Inject failure immediately before and after operation reservation, staging creation, staged-fact commit, promotion, final verification, published-state commit, integrity transition, and cleanup. Cover concurrent same-operation retries, operation-digest conflict, name collisions, stale writer fences, source mutation, destination symlinks and hard links where supported, parent replacement, partial fallback copy, file/row divergence, relocation from workspace identity plus root-relative path, external mutation, stale markers, interrupted deletion, and legacy queries. Update affected specifications, technical/API documentation, knowledge, changelog, and archived phase records; then run validator review-and-repair and commit only after PASS.

## Required Guides

- `AGENTS.md` and the nearest package guides for every changed package
- `.internal-dev/AGENTS.md` and `.internal-dev/specifications/AGENTS.md`
- Relevant architecture, API, service, and workspace specifications
- `.internal-dev/knowledge/output-artifact-attribution-query-and-backfill-pattern.md`
- `.internal-dev/knowledge/output-file-path-realpath-confinement.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`

## Technical Guidance

Use explicit guarded transitions rather than deriving state from file presence:

- `RESERVED -> STAGED` only after staging bytes and their digest/size are durable enough for the probed platform contract.
- `STAGED -> PUBLISHED` only after create-only final placement and final-byte verification.
- `RESERVED|STAGED -> FAILED` only for a classified terminal publication failure that retains enough facts for safe cleanup.
- `PUBLISHED -> INTEGRITY_FAILED` when expected bytes are missing, changed, linked ambiguously, or unverifiable.
- Deletion/reconciliation markers may be persisted for compatibility, but this phase adds no retention policy or public deletion surface.

A reconciler may complete a recorded transition when all preconditions and file identity are provable. It must retain a safe unresolved record when they are not. Repository methods should express compare-and-set transitions with expected current state and, for task publications, expected writer owner/fence; service-level read-then-write checks are insufficient.

## Validation

- Run focused repository/service tests for request idempotency, scoped uniqueness, guarded state transitions, deterministic names, attribution, root-relative relocation, integrity gating, and bounded reconciliation.
- Exercise task, workflow, job-child, runner, and chat publication with real files in `AGENT`, `USER`, and `CHAT` roots; prove chat task selection fails and no task artifact lands in workspace-root `outputs/`.
- Inject faults at every listed persistence/filesystem boundary, restart the service, and prove convergence or an explicit safe unresolved state without duplicate logical artifacts.
- Prove same-key/same-digest replay returns one artifact; same-key/different-digest conflicts; stale owners/fences cannot stage, promote, commit, clean, or reconcile; and concurrent collisions never overwrite bytes.
- Exercise atomic and forced fallback promotion capability branches. Verify an existing final regular file, symlink, hard-link case where supported, directory, and externally swapped entry are never replaced or truncated.
- Verify final mutation after publication causes access suppression and an accurate integrity transition. Confirm unexplained files and legacy bytes are neither adopted nor deleted.
- Run migration tests for fresh and representative existing databases, rerun no-op behavior, checksum drift, interruption/restart, partial legacy state, concurrent startup, and fresh-schema equivalence.
- Through the required `gpt-5.3-codex` medium-reasoning test subagent, run focused tests, the broader Maven suite, bounded Spring startup, and real publication/recovery checks.
- Have the required `openai-codex/gpt-5.6-sol` medium-reasoning validator inspect evidence, repair in-scope defects, and report PASS.

## Exit Criteria

- Migration 004 is checksum-governed, fresh and upgraded schemas are equivalent, legacy rows and bytes remain in place, and no duplicate DDL owner exists.
- No active task caller publishes to workspace-root outputs, supplies an arbitrary destination, bypasses trusted run location, or mutates without a live matching writer fence.
- Publication retries map to one artifact and reserved name; collisions are deterministic; every promotion path is create-only; existing final bytes are never replaced or truncated.
- Persisted attribution, digest, size, verified media, state, root-relative locations, and failure facts are accurate or explicitly unavailable; no absolute path or inferred legacy producer is stored.
- Reservation, staging, promotion, publication, integrity failure, cleanup, and deletion-marker divergence reconcile idempotently to a verified state or a safe unresolved state.
- Integrity failure, missing bytes, and ambiguity suppress byte access; no search, preview, download, broad metadata, retention, or artifact UI surface has been opened.
- Focused tests, migration tests, the Maven suite, bounded startup, and real file/recovery checks pass; required internal-development records and docs are complete.
- Post-phase validator review-and-repair reports PASS, and the phase commit contains only the authorized implementation and closeout changes.
