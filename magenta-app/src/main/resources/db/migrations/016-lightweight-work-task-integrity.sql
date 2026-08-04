-- Phase 03 corrective migration for already-applied migration 015.
--
-- SchemaMigrationRunner performs the SQLite-safe additive column,
-- work_task_operations rebuild, and trigger creation before this script.

DROP INDEX IF EXISTS idx_work_assignments_task_origin_active;
create unique index if not exists idx_work_assignments_task_origin_active
    on work_assignments (origin_definition_id)
    where origin_definition_id glob 'WORK_TASK/*'
      and status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW');
