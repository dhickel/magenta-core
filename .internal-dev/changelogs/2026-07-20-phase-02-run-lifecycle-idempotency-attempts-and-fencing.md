## Date
2026-07-20

## Git Commit
Phase commit: `09c0af5d` (amended only to record this hash in the changelog).

## Change Summary
Completed the Phase 02 repair after two blocked implementation attempts. Submission now reserves the actual native task/workflow/job run id before dispatch; the runner consumes that reservation, provisions the trusted root, acquires and closes writer claims, and fails closed. Resume, retry, replay, workflow children, job work items, force interrupt, stale leases, and file/shell/output/checkpoint/terminal mutation paths now participate in lifecycle and fencing.

## Files
- Lifecycle records/enums and `TaskRunLifecycleService`
- `db/migrations/002-run-lifecycle-fencing.sql` and equivalent `schema.sql`
- `SchemaMigrationRunner` and migration tests
- `OrchestrationRuntimeRepository`, `WorkAssignment`, `AssignmentService`, and runner call sites
- Plan/workflow/job native-id consumption, workflow/job typed child lifecycle links, and operator retry/replay routes
- File, shell, output, assignment checkpoint, heartbeat, force-interrupt, and terminal writer verification
- Focused lifecycle/fencing tests plus `OrchestrationRunnerLifecycleIntegrationTest`
- Updated `SchemaMigrationRunnerTest` migration-002 fixtures and equivalence assertions
- `.internal-dev/reviews/2026-07-20-phase-02-run-lifecycle-fencing-validation.md`
- `.internal-dev/knowledge/run-lifecycle-idempotency-and-fencing.md`

## Behavioral Impact
- Operation keys are scoped by operation type; same-key/same-digest returns stable IDs and conflicting input has no lifecycle side effects.
- Generated run/native IDs are excluded from canonical operation digests.
- Reservation persists root-relative location without creating the task directory.
- Retry/replay preserve typed lineage and distinct sibling run locations; resume reuses the run and opens a new attempt.
- Writer generations are monotonic across a run, secret-token verified, and limited to one current writer per attempt.
- Runner-owned assignment saves include the assignment fence; writer claims are verified at mutation boundaries and completed, failed, waited, interrupted, or released.
- Submission operation keys are unique by default and caller-reusable; identical definition/input submissions no longer collapse accidentally.
- Workflow task children and job work items use typed `task_run_links` and canonical sibling roots.
- Lifecycle result models contain no path, owner, fence, token, or capability fields.
- Migration tests exercise the 001→002 sequence and current fresh-schema shape. The complete suite passes with 671 tests, including a real Spring runner execution; bounded startup passes.

## Specification Impact
Added active `ARCH-20260720-03`, `SVC-20260720-02`, and the run-lifecycle API contract. Updated technical and end-user execution documentation.

## Risks
- Shell capability verification is necessarily preflight in this phase; Phase 03 still owns process confinement and reserved-tree enforcement after process start.
- Output publication still targets the legacy workspace-root output area until Phase 04, although publication mutations now require a current writer during task execution.

## Follow-up Items
- Phase 03 should consume the trusted context and current-writer boundary without reintroducing host-path authority.
- Phase 04 should publish only through `runOutputLocation`.
