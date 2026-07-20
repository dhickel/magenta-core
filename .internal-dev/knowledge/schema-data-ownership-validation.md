# Topic

Schema data ownership validation for workspace leases, output attribution, inbox tables, and orphan job schema

# Source References

- `src/main/resources/schema.sql`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/runtime/OrchestrationRuntimeRepository.java`
- `src/main/java/io/mindspice/magenta2/ai/orchestration/workflow/WorkflowRepository.java`
- `src/test/java/io/mindspice/magenta2/ai/orchestration/workspaces/WorkspaceRepositorySchemaMigrationTest.java`
- `/tmp/domain05-validation-9560d66/`

# Key Takeaways

- Clean `schema.sql` should describe the current table shape; repository add-column paths remain guarded warm-DB compatibility, not the primary clean-startup source.
- Migration validation must model each contiguous version separately. Treating versions 003 and 004 as one table set hid the scoped-skill foundation, while validating a latest `schema.sql` table against the version-001 shape produced the misleading `task_run_idempotent_ops` startup blocker.
- SQL-text structural checks must tolerate formatting around multiline `IN (` lists while still checking every required value; an exact single-line substring can reject an equivalent fresh schema.
- Once a domain table moves to ordered migration ownership, remove repository DDL and initialize unit-test databases explicitly from `schema.sql` rather than weakening production ownership.
- `workspace_roots` is legacy. Clean schema must not create it, and warm migration should copy roots into `workspaces`, rebuild leases only when the FK still points at roots, preserve lease rows, then drop roots.
- Workspace leases are durable orchestration state. Do not drop active, release-requested, or released lease rows during startup migration.
- `inbox_messages` is workflow-owned for workflow/user approval and run-output delivery. `agent_inbox_messages` is runtime-owned for direct-line agent/operator inbox messages.
- `job_work_items` has no current production owner. Clean schema should omit it, while warm local DBs may retain the unused table until an explicit destructive migration policy exists.

# Engine Relevance

Future schema changes should add clean-schema coverage before relying on repository bootstrap patches. Validation should include clean SQLite probes, bounded clean startup, warm legacy fixture startup, and warm current-FK fixture probes when changing ownership or migration behavior.

# Open Questions

- Should a future migration framework replace repository-local guarded DDL once public alpha schema stabilizes?
