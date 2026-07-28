# Phase 01 — Contract, Path, and Migration Foundation

## Context

The signed handoff supersedes the documented flat task-output rule. This phase records that contract, inspects native identity cardinality, and establishes the sole ordered-migration and trusted child-path foundations required by dependent phases.

## Goal

lock superseding contracts and trusted migration/run-path foundations

## In Scope

**Write Targets**: .internal-dev/specifications/architecture.md, src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java, src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspacePathLayout.java, src/main/resources/schema.sql

- Inspect native plan, workflow, job, assignment, workflow-child, and job-work-item identities and record their cardinality to one canonical task run.
- Update affected architecture, service, decision, and package contracts through declared specification ownership.
- Add ordered migration resources, migration bookkeeping, and equivalent fresh-schema definitions.
- Add trusted task identity, path-key, run-location, output-location, and non-authorizing write-capability value types through the existing workspace authorities.
- Add focused migration, path-layout, and directory-service tests adjacent to the owned code.

## Out of Scope

- Starting, resuming, retrying, replaying, claiming, or completing runs.
- Authorizing a writer capability or changing assignment lease behavior.
- Publishing artifacts into new locations or moving legacy outputs.
- Reserved-tree mutation enforcement, subprocess confinement, skill scanning, UI, or historical attribution/backfill.

## Dependencies

none

## Constraints

- Keep the closed `WorkspaceKind` set: `AGENT`, `CHAT`, and `USER`. Do not create a task-run workspace descriptor or generic workspace identity.
- A task run is a child of an effective `AGENT` or `USER` workspace. `CHAT` remains non-task-selectable.
- `WorkspaceDirectoryService` remains the sole trusted `WorkspaceDescriptor` and trusted child-location constructor; `WorkspacePathLayout` remains the sole structural segment authority.
- Reads do not create `tasks/`, `.skills/`, run, output, or staging directories.
- Persist only `(workspaceKind, workspaceKey, rootRelativePath)` for filesystem locations. Never persist absolute paths or infer workspace identity from `Path`.
- Reuse a native run ID as `taskRunId` only after proving immutable one-to-one cardinality with the intended location and validating its canonical filesystem representation. Otherwise persist a distinct task-run ID and explicit native links.
- `work_assignments.id` remains assignment identity and is never implicitly treated as task-run identity.
- Applied migration resources are immutable, ordered, append-only, and SHA-256 protected. A correction is a new migration.
- Existing native run tables remain status truth; companion structures must not replace their status ownership.
- Preserve unrelated edits and compatibility outside this signed handoff.

## Implementation Steps

1. **Inspect and lock identity contracts before schema design.** Read the repositories, schema, models, and nearest package guides for plans, workflows, jobs, assignments, workflow children, and job work items. Record in the architecture contract, for each execution family: immutable definition identity, native run identity, assignment/child/work-item identities, cardinality, retry behavior, filesystem safety, and whether native-ID reuse is valid. If one-to-one cardinality is not proven, select a distinct canonical `taskRunId` and require explicit unique links. Do not infer linkage from names, paths, timestamps, or status.

2. **Update living contracts.** State that task runs are child resources in `AGENT` or `USER` workspaces; chat remains non-task-selectable; legacy workspace-root `outputs/` files remain in place as legacy/non-task data; new task artifacts will use run-local output trees in later phases; and this signed handoff supersedes the old flat task-output rule. Record the canonical identity matrix and any durable choice in the appropriate specification/decision record without claiming Phase 02–04 behavior is already active.

3. **Implement the sole ordered migration owner.** Add `SchemaMigrationRunner` unless inspection finds an equivalent authoritative owner; if one exists, consolidate rather than create a second owner. It must:
   - create and validate `schema_migrations` through one bootstrap path;
   - discover only correctly named numeric SQL resources, reject duplicate versions/names and gaps, and apply them in numeric order;
   - calculate SHA-256 from the exact resource bytes and store version, resource name, checksum, and applied metadata;
   - skip only an exact applied name/checksum match and fail closed on drift, unknown applied versions, or inconsistent bookkeeping;
   - serialize concurrent startup safely for deployed SQLite, execute each migration and its bookkeeping atomically where SQLite permits, and leave restart-detectable state when interruption cannot be atomic;
   - use Spring JDBC script support without allowing repositories to recreate migrated structures.

4. **Add migration `001-task-run-foundation.sql` and fresh-schema equivalence.** Based on Step 1 findings, add companion structures for canonical run locations, attempts, idempotent operations, explicit native/assignment/workflow-child/job-work-item links, and writer-claim generations. Include foreign keys, uniqueness, request-digest storage, root-relative location fields, and constraints needed for monotonic generations. Update `schema.sql` to the exact migrated end state and remove duplicate repository DDL for structures now owned by migration. Detect incompatible partial legacy structures explicitly; never bless a malformed table merely because its name exists.

5. **Extend `WorkspacePathLayout` as structural grammar owner.** Add factories for `tasks/`, `.skills/`, `tasks/<taskPathKey>/<taskRunId>/`, run-local `outputs/`, and publication staging. Return normalized root-relative structural values; callers must not concatenate reserved segments. Validate task and run path components before path resolution.

6. **Define canonical task identity and key encoding.** Add closed source kinds `TASK`, `WORKFLOW`, and `JOB`. Use the immutable definition IDs proven in Step 1. Lock a byte-level, versioned encoding in code and the architecture contract: UTF-8 without Unicode normalization, explicit source-kind bytes, and length-prefixed stable-ID bytes so no delimiter ambiguity exists. Derive `TaskPathKey` as `v1-<lowercase-kind>-<full-lowercase-SHA-256-hex>` over that canonical payload. Add fixed input-byte/digest vectors for every kind, ASCII and non-ASCII IDs, case differences, and delimiter-like content. Display names and readable slugs are metadata only and never rename historical paths.

7. **Define immutable trusted child values.** Add `TaskRunLocation` and `RunOutputLocation` carrying workspace kind/key, task identity/key, canonical run ID, root-relative path, and a runtime normalized confined path. Keep constructors/factories inaccessible to arbitrary callers; only `WorkspaceDirectoryService`, using `WorkspacePathLayout`, may create them. Equality and persistence-facing data must not depend on the current absolute data root. Define `TaskRunWriteCapability` as opaque identity data containing the run/attempt/owner/fence identifiers required by Phase 02, but do not make possession authorize mutation in this phase.

8. **Add directory-service resolve and provision operations.** Accept a trusted task-capable descriptor plus validated identities, recompute and verify the task key, and resolve beneath the descriptor root. Reject chat, blank or malformed IDs, unsupported source kinds, key mismatch, traversal, separators, absolute components, reserved aliases, excessive component lengths, and existing link components. Read resolution may report an absent confined location and must not create directories. Provisioning must create only the canonical hierarchy and revalidate containment and component/link assumptions at the mutation boundary; it must not expose arbitrary child-path construction.

9. **Add focused tests and complete phase validation.** Cover migration and path invariants below, update required internal-development records, run the required validation gates, review the final diff for accidental schema/path ownership, and commit the phase only after validator PASS.

## Invariants and Edge Cases

- Two definitions with the same display name never share a key; rename does not change a key; delete/recreate shares a key only if the authoritative immutable definition ID is deliberately reused by contract.
- Source kind participates in hashing, so equal stable-ID text across `TASK`, `WORKFLOW`, and `JOB` cannot alias.
- Case, Unicode code points, combining sequences, delimiter-like content, leading/trailing whitespace, reserved names, and maximum accepted byte lengths have explicit accept/reject behavior and fixed vectors.
- A canonical run location is exactly `tasks/<taskPathKey>/<taskRunId>/`; outputs and staging are descendants produced only by layout factories.
- Root relocation changes only the runtime confined path. Persisted workspace identity and root-relative location remain valid.
- Missing task trees are valid on read. No read creates a root or historical directory.
- Chat selection, path-derived identity, absolute persisted locations, caller-created trusted values, symlink components, and containment escapes fail closed.
- Native IDs, assignments, workflow children, job runs, and job work items cannot silently alias a task run contrary to the recorded cardinality matrix.
- Same migration resources rerun as a no-op; changed applied bytes fail checksum validation. Missing, duplicate, reordered, or unknown versions fail before repository use.
- Fresh schema and fully migrated schema have equivalent tables, columns, indexes, constraints, and migration ownership.
- Interrupted migration, concurrent starters, and compatible pre-migration databases converge deterministically; incompatible partial state remains an explicit startup error.

## Required Guides

- `AGENTS.md`
- `.internal-dev/AGENTS.md`
- `.internal-dev/specifications/AGENTS.md`
- Relevant core, persistence, orchestration, plan, workflow, job, and workspace package guides
- `.internal-dev/knowledge/2026-07-19-filesystem-first-workspaces.md`
- `.internal-dev/knowledge/root-relative-workspace-storage.md`

## Technical Guidance

- Preserve `WorkspaceDescriptor` as containing-workspace authority and keep existing workspace factories unchanged.
- Keep canonical encoding in one small pure function. Tests should assert canonical payload bytes and final key strings, not merely repeated calls to the same implementation.
- Prefer closed records/enums and package-private construction over public factories. Do not add speculative extension points.
- Migration discovery and validation must complete before migrated repositories initialize. Ensure only one application component owns ordering and application.
- For SQLite concurrency, use the repository-supported transaction/locking mechanism and test with two independent startup connections. Do not rely only on an in-process Java lock.
- Do not pass arbitrary run/output `Path` values into execution context. Phase 02 will carry trusted locations and validate capabilities when callers are cut over.

## Validation

- Run focused tests for migration discovery/order, fresh application, representative existing schema upgrade, rerun no-op, checksum drift, unknown/missing/duplicate versions, incompatible partial state, interruption/restart, and two concurrent startup connections.
- Compare fresh `schema.sql` and migrated database metadata, including tables, columns, indexes, foreign keys, uniqueness, and checks.
- Run path/layout/directory tests for both `AGENT` and `USER`, chat rejection, relocation, lazy reads, provision idempotency, containing-descriptor resolution below `tasks/`, malformed and maximum-length IDs, fixed key vectors, duplicate names, traversal, symlinks, and parent replacement at provision time.
- Verify no production caller can publicly construct trusted child values or use the Phase 01 capability as authorization.
- Run the broader workspace/persistence tests and Maven suite through the required `gpt-5.3-codex` medium test subagent.
- Run bounded Spring startup for fresh and upgraded databases and prove migrations finish before repository use.
- Inspect a fresh data root and confirm reads create no `tasks/` or `.skills/` roots.
- Have the required `openai-codex/gpt-5.6-sol` medium validator review evidence, repair defects within phase scope, and report PASS.

## Exit Criteria

- Living contracts unambiguously reflect the signed superseding handoff and the inspected native/task-run cardinality matrix.
- Canonical `taskRunId` reuse or explicit linkage is justified for every native execution family; assignment identity remains separate.
- Canonical task-key encoding and fixed vectors are locked and cover all source kinds and relevant edge cases.
- `SchemaMigrationRunner` is the sole ordered migration owner; migration 001 is deterministic, checksum-protected, restart-safe, concurrent-start safe, and equivalent to fresh schema.
- Trusted child path types cannot escape their containing workspace, become workspace identities, be reconstructed from arbitrary paths, or persist absolute locations.
- Reads remain lazy; chat is rejected; root relocation and supported `AGENT`/`USER` provisioning pass.
- Writer capability remains non-authorizing, and execution/publication callers are not cut over prematurely.
- Required focused and broad tests, bounded startup, internal-development closeout, final-diff review, and post-phase validator review-and-repair all PASS.
- The phase is committed on the dedicated implementation branch before Phase 02 begins.
