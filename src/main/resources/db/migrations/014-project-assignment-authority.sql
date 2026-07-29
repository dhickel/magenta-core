-- Phase 01: Authority and Assignment Foundation.
-- Singular assignment authority, gated project safety, scoped pagination.
--
-- This migration owns all Phase-01 persistent shape. Fresh databases receive
-- the same end state from schema.sql; warm databases receive these additive
-- columns before the indexes and control table below are created.

-- Additive columns are applied by SchemaMigrationRunner before this SQL
-- resource so an existing database that already received the clean-schema
-- shape can be adopted safely without duplicate-column failure.

-- The runner installs the equivalent trigger after executing this resource.
-- Spring's SQL script splitter cannot safely parse SQLite trigger bodies.

-- ── Indexes for scoped views ──
create index if not exists idx_work_assignments_agent_active
    on work_assignments(agent_id, status, priority desc, created_at asc)
    where status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW');

create index if not exists idx_work_assignments_project_active
    on work_assignments(project_id, status, priority desc, created_at asc)
    where project_id is not null
      and status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW');

create index if not exists idx_work_assignments_project_history
    on work_assignments(project_id, coalesce(completed_at, updated_at, created_at) desc)
    where project_id is not null
      and status in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW');

-- ── Authenticated bounded query snapshots ──
create table if not exists assignment_query_snapshots (
    snapshot_id text primary key check (length(snapshot_id) between 1 and 255),
    scope_digest text not null check (length(scope_digest) = 64),
    request_digest text not null check (length(request_digest) = 64),
    member_ids text not null,
    expires_at text not null,
    created_at text not null
);
create index if not exists idx_assignment_query_snapshots_expiry
    on assignment_query_snapshots (expires_at);

-- ── Control operation records ──
create table if not exists assignment_control_operations (
    operation_key    text not null check (length(operation_key) between 1 and 255),
    operation_type   text not null check (operation_type in (
        'PRIORITY_CAS', 'REQUEST_CANCEL', 'FORCE_CANCEL')),
    assignment_id    text not null check (length(assignment_id) between 1 and 255),
    actor            text not null check (length(actor) between 1 and 128),
    scope            text not null check (length(scope) between 1 and 64),
    action           text not null check (length(action) between 1 and 64),
    resource         text not null check (length(resource) between 1 and 255),
    expected_revision integer,
    new_priority     integer,
    reason           text check (reason is null or length(reason) <= 4096),
    request_digest   text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state  text not null check (operation_state in (
        'RESERVED', 'COMPLETED', 'CONFLICT', 'STALE')),
    result_detail    text check (result_detail is null or length(result_detail) <= 4096),
    created_at       text not null,
    completed_at     text,
    primary key (operation_type, operation_key)
);

create index if not exists idx_assignment_control_ops_assignment
    on assignment_control_operations (assignment_id, created_at desc);

-- Existing assignments retain unknown source/context attribution as NULL.
-- row_version begins at zero because lease fencing is a distinct capability.
