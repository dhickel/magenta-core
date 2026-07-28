-- Migration 010: agent materialization and context persistence.
--
-- Persists generation-safe project materializations, versioned context
-- defaults, insert-only context binding snapshots, typed binding owner
-- links, and operation idempotency records.

create table if not exists agent_materializations (
    agent_id              text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    materialization_key   text not null check (
        length(materialization_key) between 15 and 255
        and substr(materialization_key, 1, 3) = 'v1-'),
    materialization_kind  text not null check (materialization_kind = 'PROJECT'),
    source_kind           text not null check (source_kind = 'project'),
    source_id             text not null check (length(source_id) > 0),
    generation            integer not null check (generation >= 1),
    state                 text not null check (
        state in ('PROVISIONING', 'READY', 'FAILED', 'REVOKED')),
    root_relative_path    text check (root_relative_path is null or (
        length(root_relative_path) > 0
        and substr(root_relative_path, 1, 1) <> '/'
        and instr(root_relative_path, char(92)) = 0
        and instr(root_relative_path, '..') = 0
        and instr(root_relative_path, char(10)) = 0)),
    failure_detail        text check (failure_detail is null or length(failure_detail) <= 4096),
    created_at            text not null,
    updated_at            text not null,
    unique (agent_id, materialization_key),
    check (root_relative_path is not null or state in ('PROVISIONING', 'FAILED', 'REVOKED'))
);

create index if not exists idx_agent_materializations_agent
    on agent_materializations (agent_id, state, updated_at desc);
create index if not exists idx_agent_materializations_key
    on agent_materializations (materialization_key);

create table if not exists context_defaults (
    agent_id                  text primary key check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    version                   integer not null check (version >= 1),
    selection_kind            text not null check (
        selection_kind in ('MATERIALIZATION', 'PERSONAL')),
    materialization_key       text check (materialization_key is null or (
        length(materialization_key) between 15 and 255
        and substr(materialization_key, 1, 3) = 'v1-')),
    materialization_generation integer check (
        materialization_generation is null or materialization_generation >= 1),
    personal_selection        text check (
        personal_selection is null or personal_selection in ('HOME', 'SCRIPTS', 'APPS')),
    reason                    text check (reason is null or length(reason) <= 4096),
    created_at                text not null,
    updated_at                text not null,
    check ((selection_kind = 'MATERIALIZATION'
            and materialization_key is not null
            and materialization_generation is not null
            and personal_selection is null)
        or (selection_kind = 'PERSONAL'
            and materialization_key is null
            and materialization_generation is null
            and personal_selection is not null))
);

create table if not exists context_bindings (
    id                        integer primary key autoincrement,
    agent_id                  text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    binding_key               text not null check (length(binding_key) between 1 and 255),
    selection_kind            text not null check (
        selection_kind in ('MATERIALIZATION', 'PERSONAL')),
    materialization_key       text check (materialization_key is null or (
        length(materialization_key) between 15 and 255
        and substr(materialization_key, 1, 3) = 'v1-')),
    materialization_generation integer check (
        materialization_generation is null or materialization_generation >= 1),
    personal_selection        text check (
        personal_selection is null or personal_selection in ('HOME', 'SCRIPTS', 'APPS')),
    captured_at               text not null,
    unique (binding_key),
    check ((selection_kind = 'MATERIALIZATION'
            and materialization_key is not null
            and materialization_generation is not null
            and personal_selection is null)
        or (selection_kind = 'PERSONAL'
            and materialization_key is null
            and materialization_generation is null
            and personal_selection is not null))
);

create index if not exists idx_context_bindings_agent
    on context_bindings (agent_id, captured_at desc);

create table if not exists binding_owner_links (
    owner_type   text not null check (
        length(owner_type) between 1 and 64
        and instr(owner_type, '/') = 0 and instr(owner_type, char(92)) = 0),
    owner_id     text not null check (
        length(owner_id) between 1 and 255
        and instr(owner_id, '/') = 0 and instr(owner_id, char(92)) = 0),
    binding_key  text not null,
    created_at   text not null,
    unique (owner_type, owner_id),
    foreign key (binding_key) references context_bindings(binding_key)
);

create index if not exists idx_binding_owner_links_binding
    on binding_owner_links (binding_key);

create table if not exists materialization_operation_results (
    operation_type       text not null check (
        operation_type in ('REGISTER', 'PROVISION', 'REVOKE', 'FAIL')),
    operation_key        text not null check (length(operation_key) between 1 and 255),
    request_digest       text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    result_state         text not null check (
        result_state in ('COMPLETED', 'CONFLICT')),
    materialization_key  text,
    result_json          text check (result_json is null or length(result_json) <= 4096),
    created_at           text not null,
    primary key (operation_type, operation_key)
);
