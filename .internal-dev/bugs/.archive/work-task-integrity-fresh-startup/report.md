# Work-Task Integrity Fresh-Startup Failure

## Summary

Fresh SQLite startup failed after migration 017 because migration 016's runner-owned work-task trigger reconciliation was skipped.

## Scope

`SchemaMigrationRunner` fresh initialization and the migration 016 promoted-Plan integrity trigger.

## Reproduction

Start Magenta against a brand-new SQLite file with ordered resources 001-017 present.

## Expected

The runner initializes `schema.sql`, installs runner-owned SQLite trigger bodies, validates the latest owned schema, and starts the application.

## Actual

Fresh initialization marked all 17 resources applied, called `ensureV16WorkTaskIntegrity` with target version 17, skipped its exact `version == 16` gate, and then failed `validateOwnedSchema` with `work-task integrity repair is incomplete` because `work_task_promoted_plan_delete_guard` was absent.

## Evidence

- Pre-fix bounded startup reproduced the exception immediately after fresh schema initialization.
- GitHub issue: https://github.com/dhickel/Magenta/issues/44
- Regression: `SchemaMigrationRunnerTest.freshSchemaIncludesV16WorkTaskIntegrityRepairWithLaterMigrations` initializes through migration 017 and verifies the trigger.
- Post-fix fresh database contains 17 bookkeeping rows, the trigger, and passes `PRAGMA integrity_check`.

## Impact

Every genuinely fresh database failed application startup once migration 017 became the latest resource. Warm databases that had applied migration 016 before migration 017 retained the trigger and generally avoided this path.

## Status

Resolved. The reconciliation gate now treats 016 as a minimum target version, preserving trigger installation and validation for every later fresh schema target.

## Next Action

Keep latest-resource fresh-start coverage whenever a runner-owned reconciliation hook is introduced. No further action is required for this defect.
