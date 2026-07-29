-- Phase 04 migration 017: Job reassignment operation persistence.
--
-- Adds durable idempotency and lineage tracking for queued job reassignment
-- operations. The operation key binds server actor, action, scope, source,
-- destination, and expected version into one canonical digest.
-- A single successful successor and unambiguous lineage are enforced.

create table if not exists job_reassignment_operations (
    operation_key       text not null check (length(operation_key) between 1 and 255),
    request_digest      text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state     text not null check (operation_state in ('RESERVED', 'COMPLETED', 'CONFLICT', 'FAILED')),
    scope_text          text not null check (length(scope_text) between 1 and 64),
    expected_version    integer not null check (expected_version >= 0),
    source_assignment_id text not null check (length(source_assignment_id) between 1 and 255),
    destination_agent_id text not null check (length(destination_agent_id) between 1 and 255
        and instr(destination_agent_id, '/') = 0 and instr(destination_agent_id, char(92)) = 0),
    successor_assignment_id text,
    successor_task_run_id   text,
    lineage_parent_id       text,
    terminal_outcome        text check (
        terminal_outcome is null or terminal_outcome in ('SUCCESS', 'CONFLICT', 'FAILED')),
    failure_class           text check (failure_class is null or length(failure_class) <= 128),
    created_at              text not null,
    completed_at            text,
    primary key (operation_key),
    unique (successor_assignment_id)
);

create index if not exists idx_job_reassignment_source
    on job_reassignment_operations (source_assignment_id, created_at desc);

create index if not exists idx_job_reassignment_destination
    on job_reassignment_operations (destination_agent_id, operation_state);

-- Enforce one JobRun per assignment link.
create unique index if not exists idx_job_runs_assignment_unique
    on job_runs (job_assignment_id)
    where job_assignment_id is not null;

-- SchemaMigrationRunner conditionally installs and backfills definition_version
-- before this resource executes. SQLite has no ADD COLUMN IF NOT EXISTS, and
-- warm databases may already have the compatibility column from old repository
-- bootstrap code; keeping this resource free of unconditional ALTER prevents
-- an otherwise-valid warm upgrade from failing on a duplicate column.
