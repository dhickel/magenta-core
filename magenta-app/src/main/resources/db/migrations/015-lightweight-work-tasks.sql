-- Phase 03 (Lightweight Project Tasks): Definition and attempt persistence.
--
-- work_task_definitions    – immutable-after-first-attempt task definitions.
-- work_task_attempts       – append-only execution attempts linking tasks to assignments.
-- work_task_operations     – operation-key idempotency for task mutations.
--
-- Task display state is derived by joining through the designated attempt to
-- WorkAssignment.status, not stored on the task row.

create table if not exists work_task_definitions (
    id                    text primary key check (
        length(id) between 1 and 255
        and instr(id, '/') = 0 and instr(id, char(92)) = 0),
    title                 text not null check (length(title) between 1 and 512),
    description           text check (description is null or length(description) <= 8192),
    project_id            text,
    pin                   integer not null default 0 check (pin in (0, 1)),
    default_priority      integer not null default 0,
    preferred_agent_id    text check (
        preferred_agent_id is null or (
            length(preferred_agent_id) > 0
            and instr(preferred_agent_id, '/') = 0
            and instr(preferred_agent_id, char(92)) = 0)),
    promoted_plan_id      text,
    definition_version    integer not null default 1 check (definition_version >= 1),
    created_at            text not null,
    updated_at            text not null,
    foreign key (project_id) references projects(id) on delete set null,
    foreign key (preferred_agent_id) references agent_profiles(id) on delete set null,
    foreign key (promoted_plan_id) references plan_definitions(id) on delete set null
);

create index if not exists idx_work_task_defs_project
    on work_task_definitions (project_id, pin desc, updated_at desc)
    where project_id is not null;

create unique index if not exists idx_work_task_defs_promoted_plan
    on work_task_definitions (promoted_plan_id)
    where promoted_plan_id is not null;

create table if not exists work_task_attempts (
    id                     text primary key check (
        length(id) between 1 and 255
        and instr(id, '/') = 0 and instr(id, char(92)) = 0),
    task_id                text not null,
    assignment_id          text not null unique,
    attempt_order          integer not null check (attempt_order >= 1),
    predecessor_attempt_id text,
    created_at             text not null,
    unique (task_id, attempt_order),
    foreign key (task_id) references work_task_definitions(id) on delete cascade,
    foreign key (assignment_id) references work_assignments(id) on delete cascade,
    foreign key (predecessor_attempt_id) references work_task_attempts(id) on delete set null
);

create index if not exists idx_work_task_attempts_task
    on work_task_attempts (task_id, attempt_order desc);

create table if not exists work_task_operations (
    operation_type   text not null check (
        operation_type in ('CREATE', 'EDIT', 'PROMOTE', 'SUBMIT')),
    operation_key    text not null check (length(operation_key) between 1 and 255),
    request_digest   text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state  text not null check (operation_state in ('RESERVED', 'COMPLETED')),
    task_id           text,
    result_json       text,
    created_at        text not null,
    primary key (operation_type, operation_key),
    foreign key (task_id) references work_task_definitions(id) on delete set null
);

-- Active-origin uniqueness guard: a single task must not have more than one
-- non-terminal assignment at any time. This partial unique index on
-- work_assignments prevents concurrent active assignments for the same
-- work-task source definition.
create unique index if not exists idx_work_assignments_task_origin_active
    on work_assignments (source_definition_id)
    where source_definition_id is not null
      and status not in ('CANCELLED', 'FAILED', 'COMPLETED', 'NEEDS_REVIEW');
