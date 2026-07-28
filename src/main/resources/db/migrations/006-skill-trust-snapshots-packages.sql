-- Migration 006: Immutable skill trust decisions, snapshots, activations,
-- executable authorizations, package ingestion, and resource-execution audit.

-- ── Immutable snapshots (created first, referenced by trust_decisions) ──
create table if not exists skill_snapshots (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    logical_key           text not null,
    provenance            text not null check (provenance in ('GLOBAL_PACKAGE', 'WORKSPACE_FLAT')),
    format_version        integer not null,
    snapshot_digest       text not null check (
        length(snapshot_digest) = 64 and snapshot_digest not glob '*[^0-9a-f]*'),
    skill_name            text not null check (length(skill_name) between 1 and 128),
    description           text not null check (length(description) between 1 and 1024),
    instructions          text not null check (length(instructions) <= 262144),
    hints                 text check (hints is null or length(hints) <= 4096),
    allowed_tools         text check (allowed_tools is null or length(allowed_tools) <= 4096),
    resource_manifest_json text not null check (length(resource_manifest_json) <= 65536),
    created_at            text not null,
    unique (snapshot_digest),
    unique (candidate_version_id),
    foreign key (candidate_version_id) references skill_candidate_versions(id)
);

create index if not exists idx_skill_snapshots_logical_key
    on skill_snapshots (logical_key, created_at desc);

-- ── Trust decisions (approve/reject/revoke per candidate) ──
create table if not exists skill_trust_decisions (
    id                    integer primary key autoincrement,
    candidate_version_id  integer not null,
    logical_key           text not null,
    decision              text not null check (decision in ('APPROVE', 'REJECT', 'REVOKE')),
    snapshot_id           integer,
    actor                 text check (actor is null or (length(actor) between 1 and 255
        and instr(actor, '/') = 0 and instr(actor, char(92)) = 0)),
    reason                text check (reason is null or length(reason) <= 4096),
    scope                 text not null check (scope in ('INSTRUCTION', 'EXECUTABLE_RESOURCE', 'PACKAGE_INGEST')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    decided_at            text not null,
    unique (operation_key),
    foreign key (candidate_version_id) references skill_candidate_versions(id),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_trust_decisions_candidate
    on skill_trust_decisions (candidate_version_id, decided_at desc);
create index if not exists idx_skill_trust_decisions_logical_key
    on skill_trust_decisions (logical_key, decision, decided_at desc);

-- ── Snapshot resource bytes (immutable, append-only) ──
create table if not exists skill_snapshot_resources (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    relative_path         text not null check (
        length(relative_path) between 1 and 1024
        and substr(relative_path, 1, 1) <> '/'
        and instr(relative_path, '\\') = 0
        and instr(relative_path, '..') = 0
        and instr(relative_path, char(10)) = 0),
    resource_type         text not null check (
        resource_type in ('SCRIPT', 'REFERENCE', 'ASSET', 'SKILL_MD')),
    content_length        integer not null check (content_length >= 0),
    content_bytes         blob not null,
    content_digest        text not null check (
        length(content_digest) = 64 and content_digest not glob '*[^0-9a-f]*'),
    unique (snapshot_id, relative_path),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_snapshot_resources_snapshot
    on skill_snapshot_resources (snapshot_id, relative_path);

-- ── Activation records ──
create table if not exists skill_activations (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    logical_key           text not null,
    task_run_id           text not null check (length(task_run_id) between 1 and 255
        and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    conversation_id       text check (conversation_id is null or length(conversation_id) between 1 and 255),
    agent_id              text not null check (length(agent_id) between 1 and 255
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    activation_context    text not null check (length(activation_context) between 1 and 512),
    activated_at          text not null,
    unique (snapshot_id, activation_context),
    foreign key (snapshot_id) references skill_snapshots(id)
);

create index if not exists idx_skill_activations_task_run
    on skill_activations (task_run_id, activated_at desc);
create index if not exists idx_skill_activations_agent
    on skill_activations (agent_id, logical_key, activated_at desc);

-- ── Executable authorizations (separate from instruction approval) ──
create table if not exists skill_executable_authorizations (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    snapshot_resource_id  integer not null,
    logical_key           text not null,
    decision              text not null check (decision in ('AUTHORIZED', 'DENIED', 'REVOKED')),
    actor                 text check (actor is null or (length(actor) between 1 and 255
        and instr(actor, '/') = 0 and instr(actor, char(92)) = 0)),
    reason                text check (reason is null or length(reason) <= 4096),
    confinement_capability text not null check (
        confinement_capability in ('PROVEN', 'UNAVAILABLE', 'DISABLED')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    decided_at            text not null,
    unique (operation_key),
    unique (snapshot_id, snapshot_resource_id),
    foreign key (snapshot_id) references skill_snapshots(id),
    foreign key (snapshot_resource_id) references skill_snapshot_resources(id)
);

create index if not exists idx_skill_executable_authorizations_resource
    on skill_executable_authorizations (snapshot_resource_id, decision);

-- ── Package ingestion / install operations ──
create table if not exists skill_package_operations (
    id                    integer primary key autoincrement,
    operation_type        text not null check (operation_type in ('INGEST', 'INSTALL', 'REJECT')),
    operation_key         text not null check (length(operation_key) between 1 and 255),
    request_digest        text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state       text not null check (operation_state in (
        'STAGING', 'STAGED', 'INSTALLING', 'INSTALLED', 'FAILED', 'REJECTED')),
    staging_path          text check (staging_path is null or (
        length(staging_path) > 0
        and substr(staging_path, 1, 1) <> '/'
        and instr(staging_path, '\\') = 0)),
    package_identity      text check (package_identity is null or length(package_identity) between 1 and 255),
    entry_count           integer check (entry_count is null or entry_count >= 0),
    total_bytes           integer check (total_bytes is null or total_bytes >= 0),
    validation_evidence   text check (validation_evidence is null or length(validation_evidence) <= 16384),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    created_at            text not null,
    updated_at            text not null,
    unique (operation_type, operation_key)
);

create index if not exists idx_skill_package_operations_state
    on skill_package_operations (operation_state, created_at);

-- ── Resource-execution audit ──
create table if not exists skill_resource_executions (
    id                    integer primary key autoincrement,
    snapshot_id           integer not null,
    snapshot_resource_id  integer not null,
    task_run_id           text not null check (length(task_run_id) between 1 and 255
        and instr(task_run_id, '/') = 0 and instr(task_run_id, char(92)) = 0),
    agent_id              text not null check (length(agent_id) between 1 and 255
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    writer_owner_id       text check (writer_owner_id is null or length(writer_owner_id) between 1 and 255),
    fence_generation      integer check (fence_generation is null or fence_generation > 0),
    attempt_number        integer check (attempt_number is null or attempt_number > 0),
    launch_state          text not null check (launch_state in (
        'PREFLIGHT', 'LAUNCHED', 'COMPLETED', 'FAILED', 'REJECTED')),
    process_policy        text check (process_policy is null or length(process_policy) <= 16384),
    args_json             text check (args_json is null or length(args_json) <= 8192),
    env_json              text check (env_json is null or length(env_json) <= 8192),
    start_result_state    text check (start_result_state is null or length(start_result_state) <= 1024),
    exit_code             integer,
    exit_facts_json       text check (exit_facts_json is null or length(exit_facts_json) <= 8192),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    started_at            text,
    completed_at          text,
    created_at            text not null,
    foreign key (snapshot_id) references skill_snapshots(id),
    foreign key (snapshot_resource_id) references skill_snapshot_resources(id)
);

create index if not exists idx_skill_resource_executions_task_run
    on skill_resource_executions (task_run_id, created_at desc);
create index if not exists idx_skill_resource_executions_resource
    on skill_resource_executions (snapshot_resource_id, created_at desc);
