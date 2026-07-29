# Topic

Schema data ownership validation for workspace leases, output attribution, inbox tables, and orphan job schema

# Source References

- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepository.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepositorySchemaMigrationTest.java`
- `src/main/resources/db/migrations/017-job-reassignment-operations.sql`
- `src/main/java/io/mindspice/magenta2/core/persistence/SchemaMigrationRunner.java`
- `/tmp/domain05-validation-9560d66/`

# Key Takeaways

- Clean `schema.sql` should describe the current table shape; repository add-column paths remain guarded warm-DB compatibility, not the primary clean-startup source.
- Migration validation must model each contiguous version separately. Treating versions 003 and 004 as one table set hid the scoped-skill foundation, while validating a latest `schema.sql` table against the version-001 shape produced the misleading `task_run_idempotent_ops` startup blocker.
- SQL-text structural checks must tolerate formatting around multiline `IN (` lists while still checking every required value; an exact single-line substring can reject an equivalent fresh schema.
- Once a domain table moves to ordered migration ownership, remove repository DDL and initialize unit-test databases explicitly from `schema.sql` rather than weakening production ownership.
- Never edit a migration resource after a local or deployed database records its checksum. Even a corrective constraint change makes warm startup fail closed; restore the exact applied bytes, then route the correction through a new contiguous migration with explicit scope approval. SQLite cannot add a `CHECK` constraint to an existing column, so that corrective resource must transactionally rebuild the affected table(s), copy only constraint-valid rows, recreate their indexes/FKs, and preserve fresh-schema metadata equivalence.
- Fresh initialization marks discovered migrations as applied without executing their scripts. Any migration-owned SQLite trigger body that cannot pass through `ScriptUtils` must be installed idempotently by the runner's final fresh/latest-version reconciliation before its integrity validator runs; test the fresh path through that version explicitly.
- SQLite has no `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`. A new migration that adds a compatibility column can encounter an already-present column from older repository bootstrap code on a warm database. Install/backfill that column conditionally in the ordered migration runner before executing the resource, keep the resource free of unconditional `ALTER`, and validate both fresh and adopted warm starts.
- `workspace_roots` is legacy. Clean schema must not create it, and warm migration should copy roots into `workspaces`, rebuild leases only when the FK still points at roots, preserve lease rows, then drop roots.
- Workspace leases are durable orchestration state. Do not drop active, release-requested, or released lease rows during startup migration.
- `inbox_messages` is workflow-owned for workflow/user approval and run-output delivery. `agent_inbox_messages` is runtime-owned for direct-line agent/operator inbox messages.
- `job_work_items` has no current production owner. Clean schema should omit it, while warm local DBs may retain the unused table until an explicit destructive migration policy exists.

# Engine Relevance

Future schema changes should add clean-schema coverage before relying on repository bootstrap patches. Validation should include clean SQLite probes, bounded clean startup, warm legacy fixture startup, and warm current-FK fixture probes when changing ownership or migration behavior.

# Open Questions

- Should a future migration framework replace repository-local guarded DDL once public alpha schema stabilizes?
