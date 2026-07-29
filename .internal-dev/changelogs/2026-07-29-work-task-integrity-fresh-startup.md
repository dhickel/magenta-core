# Work-Task Integrity Fresh-Startup Repair

## Date

2026-07-29

## Change Summary

Fixed the recurring fresh-database `work-task integrity repair is incomplete` startup failure. Migration 016's runner-owned reconciliation now applies to every target version at or beyond 016 instead of only when 016 is the latest discovered resource. Added regression coverage through migration 017 so later migrations cannot silently bypass the trigger installation path.

## Files

- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `src/test/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunnerTest.java`
- `.internal-dev/specifications/architecture.md`
- `.internal-dev/knowledge/schema-data-ownership-validation.md`
- `.internal-dev/bugs/.archive/work-task-integrity-fresh-startup/report.md`
- `docs/technical/00-index.md`
- `docs/technical/architecture.md`
- `docs/technical/data-model.md`
- `docs/technical/migrations-001-009.md`

## Behavioral Impact

Fresh SQLite databases with migration 017 or any later target install `work_task_promoted_plan_delete_guard` before owned-schema validation. The integrity validator remains fail-closed and unchanged: startup still fails if the required columns, operation types, or trigger are absent after reconciliation. Warm migration behavior remains idempotent.

## Specification Impact

`ARCH-20260720-02` was synchronized with the existing contiguous 001-017 migration inventory and now explicitly states that runner-owned reconciliation applies at every target version at or beyond the introducing migration. This clarifies the existing fresh-schema contract rather than changing product behavior.

## Risks

Low. The repair broadens an idempotent version gate only after migration 016; table/column checks prevent duplicate structural changes, the operation-table rebuild remains conditional, and trigger creation uses `IF NOT EXISTS`.

## Follow-up Items

None. Focused migration tests, the full Maven suite, and bounded startup against a brand-new SQLite database pass.
