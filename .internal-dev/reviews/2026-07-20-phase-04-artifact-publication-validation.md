# Phase 04 Artifact Publication Validation

## Scope

Independent validation of `.internal-dev/plans/20260720-004112-home-dhickel-code-java-magenta-internal-dev-ha/phase-04-artifact-publication-reconciliation.md`, including the requested full Maven suite and checks for idempotency, create-only placement, legacy preservation, writer fencing, restart safety, SQLite consistency, and root-relative persistence.

## Findings

### Blocking

1. **The full Maven suite fails.** `mvn test` reports `Tests run: 836, Failures: 0, Errors: 27, Skipped: 0` and `BUILD FAILURE`. Spring contexts fail in `SchemaMigrationRunner` with `task_run_idempotent_ops is missing scoped digest or ownership constraints`. The claimed “836 tests, 0 failures” omits 27 errors.

2. **Active producers were not cut over.** Production search finds no callers of `publishTaskRunBytes`, `publishTaskRunFile`, or `publishChatBytes`. `WorkflowRunner` still calls deprecated arbitrary-`Path` `OutputArtifactService.materialize(...)` at lines 641 and 715, while runtime/job paths still query/backfill legacy artifacts. This fails the typed task/workflow/job/runner/chat cutover contract.

3. **Task writer fencing is absent on critical transitions and reconciliation mutations.** `OutputArtifactService` performs `STAGED -> PUBLISHED` with null owner/fence arguments. `ArtifactReconciliationService` performs publication and integrity transitions with null owner/fence, promotes with raw `Files.copy`, and cleans with raw `Files.delete`. These mutations do not verify the live writer immediately before each task mutation.

4. **Persisted staging/restart recovery is broken.** `stageThenPromote` computes `stagingRelative` but does not persist it; it calls `compareAndSetState` rather than `updateStagedFacts`. A crash with a `RESERVED` row and an existing staging file makes replay attempt `CREATE_NEW` against the same staging path. A `STAGED` row normally has null `staging_path`, so reconciliation cannot prove or resume the staged file. The only staging recovery test manually inserts a `staging_path` and also pre-creates the final file, avoiding the real crash boundaries.

5. **Migration legacy semantics violate the contract.** `003-artifact-publication.sql` copies legacy `run_id` into `task_run_id`, despite the requirement that legacy publication attribution remain null. It also copies `run_output_artifacts.file_path` directly into a root-relative constrained column; representative legacy rows can contain absolute paths, so upgrade preservation is not safe. No migration test validates representative legacy publication rows/bytes.

6. **Schema ownership is duplicated.** `OutputArtifactRepository` calls `ensurePublicationSchema()` and creates `output_publications` and its indexes locally, contrary to `SchemaMigrationRunner` being the sole ordered migration owner.

7. **Collision reservation is not transactional/retrying.** Task final-name allocation checks only repository rows, not existing filesystem entries. Distinct concurrent operations can select the same name; a unique-path insert conflict is only re-read by operation key and is not retried with the next suffix. Existing unexplained filesystem entries therefore do not receive the required deterministic suffix reservation.

8. **Integrity is not verified at byte-open time.** `readPublishedBytes` trusts persisted `isReadable()` facts and directly reads the file. External mutation after publication can therefore be returned unless a separate explicit integrity check/reconciliation happened first, contrary to “verified current integrity.”

### Partial confirmations

- Focused `OutputArtifactPublicationServiceTest` passes: 13 tests, 0 failures/errors.
- Chat same-key/same-digest replay and same-key/different-digest conflict are covered and pass.
- No `REPLACE_EXISTING` usage was found in the new publication path. `ConfinedPathMutationService.move` uses `renameat2(..., RENAME_NOREPLACE)`, and writes use `CREATE_NEW`. However reconciliation bypasses the confined primitive with raw `Files.copy`, so the complete create-only/identity contract is not proven.
- New service-generated publication paths are intended to be data-root-relative, but legacy migration handling and absence of migration coverage prevent the no-absolute-path criterion from passing.

## Risk Assessment

Critical. The implementation is not safely deployable for task-run publication: active callers bypass it, restart recovery loses staging ownership facts, stale writers can mutate publication state/files, and the application context currently fails in 27 tests.

## Recommendations

- Repair fresh/upgraded schema consistency and add migration 003 coverage for representative legacy rows, absolute legacy paths, rerun, restart, and fresh-schema equivalence.
- Remove repository-local publication DDL.
- Cut every active producer to typed task/chat publication.
- Persist staging ownership/facts atomically and add fault tests at every committed boundary.
- Require writer owner/fence in repository CAS for every task transition and verify live writer before every filesystem mutation, cleanup, and repair.
- Use confined create-only primitives for reconciliation and transactional bounded suffix allocation.
- Verify current integrity inside the byte-open operation.
- Re-run focused task publication tests, full `mvn test`, bounded startup, and real restart/recovery scenarios.

## Follow-ups

Initial verdict: **BLOCKED**.

## Revalidation — 2026-07-20

All listed blockers were repaired. Ordered migrations 001-004 validate distinct owned foundations; representative legacy migration leaves `task_run_id` null and replaces absolute publication paths with an honest unavailable relative marker. Publication repositories contain no DDL. Workflow production uses typed publication when a trusted writer context exists and does not publish otherwise. Staging paths persist before writes, collision reservation is transactional, task publication CAS requires owner/fence, reconciliation is confined/create-only and refuses unauthorized task repair, and reads verify the exact returned bytes. `mvn test`: 841 tests, 0 failures, 0 errors. Bounded clean Spring startup passed. Revalidation verdict: **PASS**.
