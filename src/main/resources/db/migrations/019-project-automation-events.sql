-- Migration 019: Revision-head repair, project attribution, CRON/ONE_TIME modes,
-- event trust, and project-scoped automation for schedules and reactions.
--
-- Repair: composite (id,revision) primary key replaces the incorrect single-column
-- id PK that prevented multiple revisions from sharing the same definition identity.
-- current_revision flag selects the sole active revision for polling/dispatch.
--
-- Project attribution: optional project_id on schedules, reactions, and events.
-- Project-scoped definitions require active project + current member executor.
--
-- Modes: CRON carries cron_expression + timezone. ONE_TIME carries fire_at instant.
-- Reject ONE_TIME with past fireAt. Terminal ONE_TIME schedules don't repeat.
--
-- Event trust: OrchestrationEvent.projectId is immutable server-derived attribution.
-- Reactions match only events bearing the same trusted project_id.

-- ── Drop dependent tables first (FK ordering) ──
drop table if exists schedule_firing_attempts;
drop table if exists schedule_firings;
drop table if exists reaction_dispatch_attempts;
drop table if exists reaction_dispatches;

-- ── Drop definition tables ──
drop table if exists schedule_definitions;
drop table if exists reaction_definitions;

-- ── Revisioned schedule definitions with composite PK ──
create table schedule_definitions (
    id                text not null check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    current_revision  integer not null default 1 check (current_revision in (0, 1)),
    mode              text not null default 'CRON' check (mode in ('CRON', 'ONE_TIME')),
    cron_expression   text check ((mode = 'CRON' and length(cron_expression) between 1 and 256)
        or (mode = 'ONE_TIME' and cron_expression is null)),
    timezone          text check ((mode = 'CRON' and length(timezone) between 1 and 128)
        or (mode = 'ONE_TIME' and timezone is null)),
    fire_at           text check ((mode = 'ONE_TIME' and fire_at is not null)
        or (mode = 'CRON' and fire_at is null)),
    dialect_version   text not null check (length(dialect_version) between 1 and 32),
    policy_version    text not null check (length(policy_version) between 1 and 32),
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    project_id        text,
    job_id            text,
    assignment_template_json text not null,
    created_at        text not null,
    updated_at        text not null,
    primary key (id, revision),
    foreign key (project_id) references projects(id) on delete set null
);

create index idx_schedule_defs_agent on schedule_definitions (agent_id, deleted, enabled);
create index idx_schedule_defs_enabled on schedule_definitions (enabled, deleted);
create index idx_schedule_defs_project on schedule_definitions (project_id, deleted, enabled)
    where project_id is not null;
create unique index idx_schedule_defs_current on schedule_definitions (id)
    where current_revision = 1;

-- ── Scheduler claim for a single deployed topology ──
create table if not exists scheduler_claims (
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

-- ── Revisioned reaction definitions with composite PK ──
create table reaction_definitions (
    id                text not null check (length(id) between 1 and 128),
    agent_id          text not null check (length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    revision          integer not null check (revision >= 1),
    current_revision  integer not null default 1 check (current_revision in (0, 1)),
    event_type        text not null check (length(event_type) between 1 and 64),
    filter_json       text,
    action_type       text not null check (action_type in ('ENQUEUE_ASSIGNMENT')),
    assignment_template_json text not null,
    enabled           integer not null check (enabled in (0, 1)),
    deleted           integer not null default 0 check (deleted in (0, 1)),
    project_id        text,
    created_at        text not null,
    updated_at        text not null,
    primary key (id, revision),
    foreign key (project_id) references projects(id) on delete set null
);

create index idx_reaction_defs_agent on reaction_definitions (agent_id, deleted, enabled);
create index idx_reaction_defs_enabled_type on reaction_definitions (enabled, event_type, deleted);
create index idx_reaction_defs_project on reaction_definitions (project_id, deleted, enabled)
    where project_id is not null;
create unique index idx_reaction_defs_current on reaction_definitions (id)
    where current_revision = 1;

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

-- ── Add project_id to orchestration_events ──
-- The runner installs this idempotent SQLite column repair before executing
-- this script so fresh-schema adoption and warm upgrades share one shape.

create index if not exists idx_orchestration_events_project
    on orchestration_events (project_id, created_at desc)
    where project_id is not null;
