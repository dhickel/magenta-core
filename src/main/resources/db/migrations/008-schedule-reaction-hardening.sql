-- Migration 008: Fenced schedule and reaction audit, revision-qualified identity,
-- claims, attempts, dispatch, causation, suppression, assignment templates, and simulation.

-- ── Drop legacy tables (replaced by revisioned structures) ──
drop table if exists schedule_firings;
drop table if exists agent_schedules;
drop table if exists agent_event_reactions;

-- ── Revisioned schedule definitions ──
create table schedule_definitions (
    id                text primary key check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    cron_expression   text not null check (length(cron_expression) between 1 and 256),
    timezone          text not null check (length(timezone) between 1 and 128),
    dialect_version   text not null check (length(dialect_version) between 1 and 32),
    policy_version    text not null check (length(policy_version) between 1 and 32),
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    job_id            text,
    assignment_template_json text not null,
    created_at        text not null,
    updated_at        text not null,
    unique (id, revision)
);

create index idx_schedule_defs_agent on schedule_definitions (agent_id, deleted, enabled);
create index idx_schedule_defs_enabled on schedule_definitions (enabled, deleted);

-- ── Scheduler claim for a single deployed topology ──
create table scheduler_claims (
    id                text primary key check (length(id) between 1 and 128),
    owner_id          text not null check (length(owner_id) > 0),
    fence             integer not null check (fence >= 0),
    heartbeat_at      text not null,
    expires_at        text not null,
    claimed_at        text not null,
    created_at        text not null,
    updated_at        text not null
);

-- ── Revision-qualified schedule firings ──
create table schedule_firings (
    firing_id         text primary key check (length(firing_id) between 1 and 128),
    schedule_id       text not null,
    revision          integer not null check (revision >= 1),
    slot_instant      text not null,
    timezone          text not null,
    offset_policy     text not null check (offset_policy in ('GAP_SKIP', 'OVERLAP_FIRE_ONCE')),
    firing_state      text not null check (firing_state in (
        'CLAIMED', 'ENQUEUED', 'EXHAUSTED', 'RECOVERED', 'CONFLICT')),
    operation_key     text not null check (length(operation_key) between 1 and 255),
    request_digest    text not null check (length(request_digest) = 64),
    template_snapshot_json text not null,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    error_class       text,
    created_at        text not null,
    updated_at        text not null,
    foreign key (schedule_id, revision) references schedule_definitions(id, revision),
    unique (schedule_id, revision, slot_instant, timezone)
);

create index idx_schedule_firings_schedule on schedule_firings (schedule_id, revision, firing_state);
create index idx_schedule_firings_op_key on schedule_firings (operation_key, request_digest);
create index idx_schedule_firings_assignment on schedule_firings (assignment_id);

-- ── Append-only firing attempt audit ──
create table schedule_firing_attempts (
    attempt_id        text primary key check (length(attempt_id) between 1 and 128),
    firing_id         text not null,
    attempt_number    integer not null check (attempt_number between 1 and 3),
    eligible_at       text not null,
    attempted_at      text,
    claim_owner       text not null,
    claim_fence       integer not null check (claim_fence >= 0),
    outcome           text not null check (outcome in (
        'ENQUEUED', 'FAILED', 'RECOVERED', 'DUPLICATE')),
    error_class       text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    foreign key (firing_id) references schedule_firings(firing_id)
);

create index idx_schedule_firing_attempts_firing on schedule_firing_attempts (firing_id, attempt_number);
create index idx_schedule_firing_attempts_assignment on schedule_firing_attempts (assignment_id);

-- ── Revisioned reaction definitions ──
create table reaction_definitions (
    id                text primary key check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    event_type        text not null check (length(event_type) between 1 and 64),
    filter_json       text,
    action_type       text not null check (action_type in ('ENQUEUE_ASSIGNMENT')),
    assignment_template_json text not null,
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    created_at        text not null,
    updated_at        text not null,
    unique (id, revision)
);

create index idx_reaction_defs_agent on reaction_definitions (agent_id, deleted, enabled);
create index idx_reaction_defs_enabled_type on reaction_definitions (enabled, event_type, deleted);

-- ── Revision-qualified reaction dispatches ──
create table reaction_dispatches (
    dispatch_id       text primary key check (length(dispatch_id) between 1 and 128),
    reaction_id       text not null,
    revision          integer not null check (revision >= 1),
    source_event_id   text not null,
    operation_key     text not null check (length(operation_key) between 1 and 255),
    request_digest    text not null check (length(request_digest) = 64),
    template_snapshot_json text not null,
    root_event_id     text not null,
    parent_event_id   text,
    parent_dispatch_id text,
    depth             integer not null check (depth >= 1 and depth <= 8),
    dedupe_key        text not null,
    dispatch_state    text not null check (dispatch_state in (
        'ENQUEUED', 'SUPPRESSED_LOOP', 'SUPPRESSED_RATE', 'SUPPRESSED_DEPTH', 'CONFLICT')),
    suppression_reason text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    updated_at        text not null,
    foreign key (reaction_id, revision) references reaction_definitions(id, revision),
    unique (reaction_id, revision, source_event_id)
);

create index idx_reaction_dispatches_reaction on reaction_dispatches (reaction_id, revision, dispatch_state);
create index idx_reaction_dispatches_root_event on reaction_dispatches (root_event_id);
create index idx_reaction_dispatches_op_key on reaction_dispatches (operation_key, request_digest);
create index idx_reaction_dispatches_dedupe on reaction_dispatches (dedupe_key, created_at);
create index idx_reaction_dispatches_assignment on reaction_dispatches (assignment_id);

-- ── Append-only dispatch attempt audit ──
create table reaction_dispatch_attempts (
    attempt_id        text primary key check (length(attempt_id) between 1 and 128),
    dispatch_id       text not null,
    attempt_number    integer not null check (attempt_number between 1 and 3),
    eligible_at       text not null,
    attempted_at      text,
    claim_owner       text not null,
    claim_fence       integer not null check (claim_fence >= 0),
    outcome           text not null check (outcome in (
        'ENQUEUED', 'FAILED', 'RECOVERED', 'DUPLICATE')),
    error_class       text,
    assignment_id     text,
    task_run_id       text,
    native_run_id     text,
    correlation_id    text,
    created_at        text not null,
    foreign key (dispatch_id) references reaction_dispatches(dispatch_id)
);

create index idx_reaction_dispatch_attempts_dispatch on reaction_dispatch_attempts (dispatch_id, attempt_number);
create index idx_reaction_dispatch_attempts_assignment on reaction_dispatch_attempts (assignment_id);
