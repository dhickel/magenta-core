-- Migration 001: canonical task-run companion state.
create table if not exists task_run_locations (
    task_run_id        text primary key
        check (length(task_run_id) between 1 and 255
            and task_run_id not in ('.', '..', 'tasks', 'outputs', 'staging', '.skills', 'workspace')
            and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    source_kind        text not null check (source_kind in ('task', 'workflow', 'job')),
    source_id          text not null check (length(source_id) > 0),
    workspace_type     text not null check (workspace_type in ('agent', 'user')),
    workspace_key      text not null check (length(workspace_key) > 0
        and workspace_key not in ('.', '..')
        and instr(workspace_key, '/') = 0 and instr(workspace_key, char(92)) = 0),
    workspace_identity text not null
        check (workspace_identity = workspace_type || '/' || workspace_key),
    task_path_key      text not null check (
        (source_kind = 'task' and substr(task_path_key, 1, 8) = 'v1-task-'
            and length(task_path_key) = 72 and substr(task_path_key, 9) not glob '*[^0-9a-f]*')
        or (source_kind = 'workflow' and substr(task_path_key, 1, 12) = 'v1-workflow-'
            and length(task_path_key) = 76 and substr(task_path_key, 13) not glob '*[^0-9a-f]*')
        or (source_kind = 'job' and substr(task_path_key, 1, 7) = 'v1-job-'
            and length(task_path_key) = 71 and substr(task_path_key, 8) not glob '*[^0-9a-f]*')),
    root_relative_path text not null check (
        root_relative_path = 'workspace/' || workspace_type || '/' || workspace_key
            || '/tasks/' || task_path_key || '/' || task_run_id),
    created_at         text not null,
    updated_at         text not null,
    unique (workspace_type, workspace_key, root_relative_path)
);

create index if not exists idx_task_run_locations_workspace
    on task_run_locations (workspace_identity, created_at desc);
create index if not exists idx_task_run_locations_source
    on task_run_locations (source_kind, source_id, created_at desc);

create table if not exists task_run_attempts (
    task_run_id        text not null,
    attempt_number     integer not null check (attempt_number > 0),
    status             text not null,
    started_at         text,
    completed_at       text,
    error_text         text,
    created_at         text not null,
    updated_at         text not null,
    primary key (task_run_id, attempt_number),
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade
) without rowid;

create index if not exists idx_task_run_attempts_status
    on task_run_attempts (status, created_at desc);

create table if not exists task_run_idempotent_ops (
    operation_key      text primary key check (length(operation_key) > 0),
    request_digest     text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    task_run_id        text,
    result_json        text,
    created_at         text not null,
    updated_at         text not null,
    foreign key (task_run_id) references task_run_locations(task_run_id)
) without rowid;

create table if not exists task_run_native_links (
    task_run_id        text primary key,
    native_kind        text not null check (native_kind in ('task', 'workflow', 'job')),
    native_run_id      text not null check (length(native_run_id) > 0),
    assignment_id      text,
    workflow_child_id  text,
    job_work_item_id   text,
    created_at         text not null,
    unique (native_kind, native_run_id),
    check (workflow_child_id is null or native_kind = 'workflow'),
    check (job_work_item_id is null or native_kind = 'job'),
    foreign key (task_run_id) references task_run_locations(task_run_id) on delete cascade,
    foreign key (assignment_id) references work_assignments(id)
) without rowid;

create index if not exists idx_task_run_native_links_assignment
    on task_run_native_links (assignment_id) where assignment_id is not null;
create index if not exists idx_task_run_native_links_workflow_child
    on task_run_native_links (workflow_child_id) where workflow_child_id is not null;
create index if not exists idx_task_run_native_links_job_work_item
    on task_run_native_links (job_work_item_id) where job_work_item_id is not null;

create table if not exists task_run_writer_claims (
    task_run_id        text not null,
    attempt_number     integer not null check (attempt_number > 0),
    claim_generation   integer not null check (claim_generation > 0),
    owner_id           text not null check (length(owner_id) > 0),
    fence_token        text not null unique check (length(fence_token) > 0),
    created_at         text not null,
    primary key (task_run_id, attempt_number, claim_generation),
    foreign key (task_run_id, attempt_number)
        references task_run_attempts(task_run_id, attempt_number) on delete cascade
) without rowid;
