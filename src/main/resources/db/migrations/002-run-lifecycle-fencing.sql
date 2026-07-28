-- Migration 002: scoped lifecycle operations, lineage, assignment fences, and writer fencing.

alter table task_run_locations add column lineage_parent_task_run_id text
    references task_run_locations(task_run_id);
alter table task_run_locations add column lineage_mode text
    check (lineage_mode in ('RETRY_OF', 'REPLAY_OF'));

-- Rebuild operation storage so keys are scoped by operation type. Rows written
-- before operation typing remain explicit legacy rows with an unavailable type.
alter table task_run_idempotent_ops rename to task_run_idempotent_ops_v1;
create table task_run_idempotent_ops (
    id                 integer primary key,
    operation_type     text check (operation_type is null or operation_type in ('RESERVE', 'RESUME', 'RETRY', 'REPLAY')),
    operation_key      text not null check (length(operation_key) > 0),
    request_digest     text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state    text not null check (operation_state in ('RESERVED', 'COMPLETED')),
    task_run_id        text,
    result_json        text,
    created_at         text not null,
    updated_at         text not null,
    unique (operation_type, operation_key),
    foreign key (task_run_id) references task_run_locations(task_run_id)
);
insert into task_run_idempotent_ops(
    operation_type, operation_key, request_digest, operation_state,
    task_run_id, result_json, created_at, updated_at)
select null, operation_key, request_digest,
       case when task_run_id is null then 'RESERVED' else 'COMPLETED' end,
       task_run_id, result_json, created_at, updated_at
from task_run_idempotent_ops_v1;
drop table task_run_idempotent_ops_v1;

-- Add the assignment fence to upgraded databases. The owning runtime table
-- already exists on every supported upgraded schema.
alter table work_assignments add column lease_fence integer not null default 0
    check (lease_fence >= 0);

create table assignment_fence_generations (
    assignment_id    text not null,
    generation       integer not null check (generation > 0),
    owner_id         text not null check (length(owner_id) > 0),
    claimed_at       text not null,
    primary key (assignment_id, generation),
    foreign key (assignment_id) references work_assignments(id)
) without rowid;

-- Carry forward old claim generations as non-current historical facts. The v1
-- table had no trustworthy current marker, so migration does not invent one.
create table writer_claim_generations (
    task_run_id      text not null,
    attempt_number   integer not null check (attempt_number > 0),
    generation       integer not null check (generation > 0),
    owner_id         text not null check (length(owner_id) > 0),
    fence_token      text not null unique check (length(fence_token) > 0),
    is_current       integer not null default 1 check (is_current in (0, 1)),
    superseded_at    text,
    created_at       text not null,
    primary key (task_run_id, attempt_number, generation),
    unique (task_run_id, generation),
    foreign key (task_run_id, attempt_number)
        references task_run_attempts(task_run_id, attempt_number) on delete cascade
) without rowid;
insert into writer_claim_generations(
    task_run_id, attempt_number, generation, owner_id, fence_token,
    is_current, superseded_at, created_at)
select task_run_id, attempt_number, claim_generation, owner_id, fence_token,
       0, created_at, created_at
from task_run_writer_claims;
drop table task_run_writer_claims;

create unique index idx_writer_claim_generations_current
    on writer_claim_generations (task_run_id, attempt_number)
    where is_current = 1;

-- Child executions keep their own native/task-run identity and sibling root.
-- This typed relation avoids overloading assignment or deterministic node/item ids.
create table task_run_links (
    parent_task_run_id text not null,
    child_task_run_id  text not null,
    link_type          text not null check (link_type in ('WORKFLOW_CHILD', 'JOB_WORK_ITEM')),
    link_key           text not null check (length(link_key) > 0),
    created_at         text not null,
    primary key (parent_task_run_id, link_type, link_key),
    unique (child_task_run_id, link_type),
    check (parent_task_run_id <> child_task_run_id),
    foreign key (parent_task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (child_task_run_id) references task_run_locations(task_run_id) on delete cascade
) without rowid;

create index idx_task_run_links_child on task_run_links(child_task_run_id);
