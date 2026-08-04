-- Migration 007: Typed run summaries and repeated model/tool invocation measurements.

-- ── Rebuildable run summary cache ──
create table if not exists run_summaries (
    task_run_id          text primary key,
    normalized_status    text not null check (normalized_status in (
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT',
        'INTERRUPTED', 'PARTIAL', 'ABANDONED', 'UNAVAILABLE',
        'ACTIVE')),
    attempt_count        integer not null check (attempt_count >= 0),
    model_call_count     integer not null check (model_call_count >= 0),
    tool_call_count      integer not null check (tool_call_count >= 0),
    wall_elapsed_ms      integer,
    active_elapsed_ms    integer,
    completeness         text not null check (completeness in (
        'COMPLETE', 'PARTIAL', 'UNAVAILABLE')),
    workspace_type       text not null check (workspace_type in ('agent', 'user')),
    workspace_key        text not null check (length(workspace_key) > 0
        and workspace_key not in ('.', '..')
        and instr(workspace_key, '/') = 0 and instr(workspace_key, char(92)) = 0),
    source_kind          text not null check (source_kind in ('task', 'workflow', 'job')),
    lineage_parent_task_run_id text,
    lineage_mode         text check (lineage_mode in ('RETRY_OF', 'REPLAY_OF')),
    created_at           text not null,
    updated_at           text not null,
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade
);

create index if not exists idx_run_summaries_workspace
    on run_summaries (workspace_type, workspace_key, normalized_status, updated_at desc);
create index if not exists idx_run_summaries_task_run
    on run_summaries (task_run_id);

-- ── Typed model/tool invocation measurements ──
create table if not exists run_measurements (
    invocation_id        text primary key check (
        length(invocation_id) between 1 and 255
        and instr(invocation_id, '/') = 0 and instr(invocation_id, char(92)) = 0),
    task_run_id          text not null,
    attempt_number       integer not null check (attempt_number > 0),
    kind                 text not null check (kind in ('MODEL', 'TOOL')),
    call_id              text not null check (length(call_id) between 1 and 255),
    correlation_id       text check (correlation_id is null or length(correlation_id) between 1 and 255),
    provider             text,
    model_name           text,
    tool_name            text,
    outcome              text not null check (outcome in (
        'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'REJECTED',
        'INTERRUPTED', 'TRUNCATED', 'DISABLED', 'UNAVAILABLE')),
    started_at           text,
    completed_at         text,
    elapsed_ms           integer,
    input_token_count    integer,
    output_token_count   integer,
    total_token_count    integer,
    timeout_ms           integer,
    exit_code            integer,
    truncation_kind      text,
    interruption_kind    text,
    rejection_reason     text,
    failure_detail       text,
    completeness         text not null check (completeness in (
        'COMPLETE', 'INCOMPLETE', 'UNAVAILABLE')),
    created_at           text not null,
    updated_at           text not null,
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (task_run_id, attempt_number)
        references task_run_attempts(task_run_id, attempt_number) on delete cascade
);

create index if not exists idx_run_measurements_task_run
    on run_measurements (task_run_id, attempt_number, kind, created_at desc);
create index if not exists idx_run_measurements_call_id
    on run_measurements (task_run_id, call_id);
create index if not exists idx_run_measurements_correlation
    on run_measurements (correlation_id) where correlation_id is not null;
