# Phase 03 Binding Lifecycle Validator Repair

## Date
2026-07-27

## Change Summary
- Made assignment binding capture and durable assignment/task-run owner links mandatory.
- Added a transactional assignment-operation reservation so compatible retries converge before binding/assignment/run creation.
- Made runner startup validate assignment and task-run owner links, binding ownership, materialization state/generation, and mutable-checkpoint disagreement before provisioning.
- Added durable workflow/job child binding links and focused idempotency, race, stale-checkpoint, and retry-lineage coverage.

## Files
- `AssignmentService`, `TaskRunLifecycleService`, `OrchestrationRunnerService`, `PlanService`
- Context persistence/service support and migration `012-assignment-binding-operations.sql`
- `OrchestrationRunnerLifecycleIntegrationTest`

## Behavioral Impact
Executable assignments now always carry an immutable binding. Contradictory retries fail without new durable work, and stale or forged runtime context fails before filesystem provisioning.

## Specification Impact
None. This repair implements the existing Phase 03 immutable-binding lifecycle contract; Phase 07 remains the owner of consolidated living-specification updates.

## Risks
The local SQLite writer path serializes same-process assignment submission before the durable operation-key arbiter; multi-process convergence remains enforced by the unique migration-owned operation record.

## Follow-up Items
None.
